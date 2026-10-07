/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.reliza.exceptions.RelizaException;

/**
 * Match a stored, user-authored regex without letting it run away.
 *
 * <p>A pattern like {@code (a+)+$} or {@code (.*a){20}} is perfectly valid and compiles fine, but
 * backtracks for minutes on a short input. Rules holding such patterns are evaluated on hot read
 * paths -- ownership of every component, the approval policy of every component -- so an
 * unbounded match would let one saved rule burn request threads indefinitely, a denial of service
 * an org admin could trigger for the whole instance. The step budget makes a pathological pattern
 * fail fast instead.
 *
 * <p>java.util.regex has no timeout, but it interrogates the input through {@code charAt()}, so
 * counting those reads bounds a match without a watchdog thread per evaluation. The budget does
 * not bound recursion: a repeated alternation such as {@code (?:[a-z0-9]|-)+} recurses once per
 * input character and overflows the stack on a name of a few thousand characters, long before it
 * spends the budget -- and where it overflows depends on the thread's stack size, so the same
 * name could match on one thread and not on another. Inputs are therefore capped at
 * {@link #MAX_INPUT_LENGTH}; a longer one is {@link MatchResult#BUDGET_EXCEEDED} wherever it is
 * matched. A stack overflow below the cap is caught and reported the same way, as a backstop.
 *
 * <p>One thing still depends on the thread: compiling. The JDK compiles nested groups
 * recursively and reports a stack overflow while compiling as a syntax error, so a deeply nested
 * pattern within {@link #MAX_PATTERN_LENGTH} (around 255 groups) can compile on a 1 MB stack,
 * pass {@link #validate} on a request thread, and come back {@link MatchResult#INVALID_PATTERN}
 * on a thread with a much smaller stack. Callers already treat that result per site -- no
 * match, or fail closed -- so it degrades the same way bad stored data does.
 *
 * <p>Compiled patterns are cached by their text, at most {@link #MAX_CACHED_PATTERNS} of them.
 */
public final class SafeRegex {

	/** Match-step budget: generous for any sane pattern on a name-sized input. */
	public static final int MATCH_STEP_BUDGET = 200_000;

	/**
	 * Longest pattern a rule may store. A cap alone does not stop catastrophic backtracking, but it
	 * bounds how much rope an operator gets and keeps the cache keys small. A longer pattern stored
	 * before the cap still works; it is compiled per call rather than cached.
	 */
	public static final int MAX_PATTERN_LENGTH = 512;

	/**
	 * Cache bound. The live set is every distinct stored pattern on the instance -- rule, guard,
	 * dependency and ignore-violation patterns across all orgs, a handful per org -- so a few
	 * thousand covers any real instance with room to spare. Without a bound, an org admin
	 * re-saving rules with fresh patterns grows the heap of the whole instance; at the cap the
	 * cache holds about 28 MB even if every pattern is the longest allowed (measured ~6.9 KB per
	 * 500-character pattern), and an evicted pattern only costs a recompile.
	 */
	static final int MAX_CACHED_PATTERNS = 4_096;

	/**
	 * Longest input matched. Real component names are far shorter; well below this, repeated
	 * alternations still match on any thread stack in use, so the verdict does not depend on which
	 * thread asks.
	 */
	public static final int MAX_INPUT_LENGTH = 1_024;

	/**
	 * How many budget trips one pattern may take in a single sweep over many inputs (the
	 * components of an org, the violations of a sync) before the sweep gives up on it. One trip
	 * can be an unlucky input; a pattern that keeps tripping is broken, and paying the budget for
	 * every remaining input would cost the sweep what the budget exists to prevent.
	 */
	public static final int MAX_TRIPS_PER_SWEEP = 3;

	/** How much of a matched input a log line may show: enough to find the record, never all of it. */
	static final int LOGGED_INPUT_PREFIX = 64;

	private static final Cache<String, Pattern> PATTERN_CACHE = Caffeine.newBuilder()
			.maximumSize(MAX_CACHED_PATTERNS)
			.build();

	/** Rules reported within the last {@link #REPORT_INTERVAL}; see {@link #shouldReport}. */
	private static final Duration REPORT_INTERVAL = Duration.ofHours(1);

	private static final Cache<List<Object>, Boolean> REPORTED = Caffeine.newBuilder()
			.maximumSize(MAX_CACHED_PATTERNS)
			.expireAfterWrite(REPORT_INTERVAL)
			.build();

	private SafeRegex() {}

	/** How a match attempt came out; the caller decides what each means and how to log it. */
	public enum MatchResult {
		MATCH,
		NO_MATCH,
		/**
		 * The pattern does not compile. Writes validate it, so this is bad stored data -- or a
		 * deeply nested pattern compiled on a thread with too small a stack; see the class doc.
		 */
		INVALID_PATTERN,
		/** The match ran out of steps: the pattern backtracks catastrophically on this input. */
		BUDGET_EXCEEDED
	}

	/** Whether the whole of {@code input} matches {@code pattern}, under the step budget. */
	public static MatchResult matches(String pattern, CharSequence input) {
		if (input.length() > MAX_INPUT_LENGTH) return MatchResult.BUDGET_EXCEEDED;
		Pattern p;
		try {
			p = pattern.length() > MAX_PATTERN_LENGTH
					? Pattern.compile(pattern)
					: PATTERN_CACHE.get(pattern, Pattern::compile);
		} catch (PatternSyntaxException e) {
			return MatchResult.INVALID_PATTERN;
		}
		try {
			return p.matcher(new BudgetedCharSequence(input, MATCH_STEP_BUDGET)).matches()
					? MatchResult.MATCH : MatchResult.NO_MATCH;
		} catch (MatchBudgetExceededException | StackOverflowError e) {
			// The stack overflow unwinds to here; the matcher is discarded with it.
			return MatchResult.BUDGET_EXCEEDED;
		}
	}

	/**
	 * Refuse a pattern a rule is about to store: blank, longer than {@link #MAX_PATTERN_LENGTH}, or
	 * not compiling. The one write-time check every stored user regex goes through, so the cap and
	 * the wording are the same on every form. Lists that have always accepted blank entries (the
	 * UI saves empty rows) skip those entries rather than calling this.
	 *
	 * @param what how the message names the pattern, e.g. {@code "Rule 'web' uriPattern"}
	 */
	public static void validate(String pattern, String what) throws RelizaException {
		if (StringUtils.isBlank(pattern)) throw new RelizaException(what + " is blank");
		if (pattern.length() > MAX_PATTERN_LENGTH) {
			throw new RelizaException(what + " exceeds " + MAX_PATTERN_LENGTH + " characters");
		}
		try {
			Pattern.compile(pattern);
		} catch (PatternSyntaxException e) {
			throw new RelizaException(what + " is not a valid regex: " + e.getMessage());
		}
	}

	/**
	 * An input as a log line may show it: its length and at most the first
	 * {@link #LOGGED_INPUT_PREFIX} characters. Names are not length-capped, and a runaway rule is
	 * hit on every lookup, so logging the whole input wrote lines of tens of thousands of
	 * characters, one per lookup.
	 */
	public static String describeInput(CharSequence input) {
		if (null == input) return "null";
		String shown = input.length() > LOGGED_INPUT_PREFIX
				? input.subSequence(0, LOGGED_INPUT_PREFIX) + "..."
				: input.toString();
		return input.length() + " chars '" + shown + "'";
	}

	/**
	 * Whether to log a failed match of the rule identified by {@code ruleKey} (its parts, e.g. the
	 * reporting class, org, rule name and pattern) now: once per rule per hour. A runaway rule fails on every lookup of every component -- an approval queue read
	 * alone walks every component in the org -- so logging each failure buried the logs, while one
	 * line an hour still raises the alert and keeps raising it until the rule is fixed.
	 */
	public static boolean shouldReport(Object... ruleKey) {
		// Arrays.asList, not List.of: a part may be null (a rule saved without a name) and must not throw.
		return null == REPORTED.asMap().putIfAbsent(Arrays.asList(ruleKey.clone()), Boolean.TRUE);
	}

	/** Number of compiled patterns currently cached, for tests. */
	static long cachedPatternCount() {
		PATTERN_CACHE.cleanUp();
		return PATTERN_CACHE.estimatedSize();
	}

	/** Raised when a match exceeds {@link #MATCH_STEP_BUDGET} steps. */
	private static final class MatchBudgetExceededException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	/** A CharSequence that counts reads and aborts once the budget is spent. */
	private static final class BudgetedCharSequence implements CharSequence {
		private final CharSequence delegate;
		private int budget;
		BudgetedCharSequence(CharSequence delegate, int budget) {
			this.delegate = delegate;
			this.budget = budget;
		}
		@Override public int length() { return delegate.length(); }
		@Override public char charAt(int index) {
			if (--budget < 0) throw new MatchBudgetExceededException();
			return delegate.charAt(index);
		}
		@Override public CharSequence subSequence(int start, int end) {
			return new BudgetedCharSequence(delegate.subSequence(start, end), budget);
		}
		@Override public String toString() { return delegate.toString(); }
	}
}

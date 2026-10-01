/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reliza.common.SafeRegex.MatchResult;

/**
 * A stored regex is matched under a step budget, so a catastrophically backtracking pattern fails
 * fast instead of holding a request thread.
 */
class SafeRegexTest {

	@Test
	@Timeout(10)
	void theClassicReDoSPayloadNeverMatchesOrHangs() {
		// (a+)+$ against a run of a's that does not end the way it wants: exponential on a naive
		// backtracker. Current JDKs finish it inside the budget; either way it must not match or hang.
		long start = System.nanoTime();
		MatchResult result = SafeRegex.matches("(a+)+$", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!");
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertNotEquals(MatchResult.MATCH, result);
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
	}

	@Test
	@Timeout(10)
	void aPatternThatBacktracksCatastrophicallyIsCutOffByTheBudget() {
		// (.*a){20} does run away on this JDK: without the budget this does not return in any
		// practical time.
		long start = System.nanoTime();
		MatchResult result = SafeRegex.matches("(.*a){20}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!");
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertEquals(MatchResult.BUDGET_EXCEEDED, result);
		assertTrue(elapsedMs < 5_000, "the budget must abort the match; took " + elapsedMs + "ms");
	}

	@Test
	void ordinaryPatternsMatchAsUsual() {
		assertEquals(MatchResult.MATCH, SafeRegex.matches("frontend-.*", "frontend-web"));
		assertEquals(MatchResult.NO_MATCH, SafeRegex.matches("frontend-.*", "backend-api"));
		// matches() is anchored: a partial hit is no match.
		assertEquals(MatchResult.NO_MATCH, SafeRegex.matches("web", "frontend-web"));
	}

	@Test
	void anInputOverTheCapIsBudgetExceededOnAnyThread() {
		// Where a repeated alternation overflows the stack depends on the thread, so a long name
		// matched on one thread and not on another. Over the cap it is the same everywhere.
		String name = "a".repeat(SafeRegex.MAX_INPUT_LENGTH + 1);
		assertEquals(MatchResult.BUDGET_EXCEEDED, SafeRegex.matches(".*", name));
		assertEquals(MatchResult.MATCH, SafeRegex.matches("(?:[a-z0-9]|-)+", "a".repeat(SafeRegex.MAX_INPUT_LENGTH)));
	}

	@Test
	@Timeout(30)
	void aStackOverflowIsReportedAsBudgetExceededNotThrown() throws Exception {
		// A benign repeated alternation recurses once per character and can overflow the stack
		// long before it spends the read budget; an Error passed every caller's catch (Exception).
		// Under the input cap that needs a small stack, which this thread is given.
		String pattern = "(?:[a-z0-9]|-)+";
		String name = "a".repeat(SafeRegex.MAX_INPUT_LENGTH);
		Object[] outcome = new Object[2];
		Thread t = new Thread(null, () -> {
			try {
				Pattern.compile(pattern).matcher(name).matches();
				outcome[0] = "no overflow";
			} catch (StackOverflowError e) {
				outcome[0] = e;
			}
			outcome[1] = SafeRegex.matches(pattern, name);
		}, "small-stack", 16 * 1024);
		t.start();
		t.join();
		assertInstanceOf(StackOverflowError.class, outcome[0], "the unguarded match must overflow here, or this "
				+ "test proves nothing");
		assertEquals(MatchResult.BUDGET_EXCEEDED, outcome[1]);
	}

	@Test
	void theCacheIsBounded() {
		for (int i = 0; i < SafeRegex.MAX_CACHED_PATTERNS + 500; i++) {
			assertEquals(MatchResult.NO_MATCH, SafeRegex.matches("cache-bound-" + i + "-.*", "x"));
		}
		assertTrue(SafeRegex.cachedPatternCount() <= SafeRegex.MAX_CACHED_PATTERNS,
				"cached " + SafeRegex.cachedPatternCount());
	}

	@Test
	void aLoggedInputShowsItsLengthAndOnlyItsStart() {
		String described = SafeRegex.describeInput("n".repeat(50_000));
		assertTrue(described.startsWith("50000 chars '"), described);
		assertTrue(described.length() <= SafeRegex.LOGGED_INPUT_PREFIX + 32,
				"a log line must not carry the whole input; got " + described.length() + " chars");
		assertEquals("12 chars 'frontend-web'", SafeRegex.describeInput("frontend-web"));
	}

	@Test
	void aFailingRuleIsReportedOncePerInterval() {
		String rule = "rule-" + System.nanoTime();
		assertTrue(SafeRegex.shouldReport(SafeRegexTest.class, rule, "p"));
		assertFalse(SafeRegex.shouldReport(SafeRegexTest.class, rule, "p"));
		assertTrue(SafeRegex.shouldReport(SafeRegexTest.class, rule, "p2"), "an edited pattern reports again");
		assertTrue(SafeRegex.shouldReport(SafeRegexTest.class, rule + "|p", ""),
				"key parts are kept apart, so a separator in a rule name cannot collide");
		assertTrue(SafeRegex.shouldReport(SafeRegexTest.class, null, rule), "a null part is a key, not an error");
	}

	@Test
	void aPatternThatDoesNotCompileIsReportedNotThrown() {
		assertEquals(MatchResult.INVALID_PATTERN, SafeRegex.matches("frontend-(", "frontend-web"));
	}
}

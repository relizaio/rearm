/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reliza.common.SafeRegex;
import io.reliza.common.SafeRegex.MatchResult;
import io.reliza.model.OrganizationData.IgnoreViolation;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;

/**
 * An org's ignoreViolation patterns applied to the violations of one Dependency-Track fetch.
 *
 * <p>Matched under the step budget (see {@link SafeRegex}): the patterns are org-admin input, the
 * purl comes from an uploaded SBOM, and this runs per violation per pattern on every sync, so one
 * {@code (.*a){20}} would hold the sync thread for minutes per violation. A pattern that does not
 * compile or runs out of budget ignores nothing -- fail closed, the violation is reported rather
 * than hidden. A pattern that does not compile is retired at once; one that runs out of budget on
 * more than {@link SafeRegex#MAX_TRIPS_PER_SWEEP} distinct purls is retired too; a retired pattern
 * is not tried again for the rest of the fetch, so a broken pattern costs a few budgets per sync,
 * not one per violation. A purl over {@link SafeRegex#MAX_INPUT_LENGTH} is matched by no pattern
 * and counts against none.
 *
 * <p>The outcome does not depend on the order Dependency-Track returns the violations in. A
 * pattern that retires ignores nothing for the whole fetch: whatever it ignored before it retired
 * is held back rather than dropped, and {@link #releaseRetired} gives it back after the last page
 * -- re-checked against the patterns still live, which may ignore it in its place. Without that,
 * an alternation that matches one purl cheaply and runs away on others hid that purl's violation
 * or not depending on whether it came before the trips, and the visible violation counts, which
 * rules read, changed from sync to sync.
 *
 * @param <T> the violation as the caller reports it
 */
final class ViolationIgnoreSweep<T> {

	private static final Logger log = LoggerFactory.getLogger(ViolationIgnoreSweep.class);

	/** One ignore pattern of one violation type: what a fetch counts trips of and retires. */
	record PatternKey(ViolationType violationType, String pattern) {}

	/** Why a purl was not matched at all; a report key apart from the match results. */
	private enum UnmatchedPurl { TOO_LONG }

	private record Held<V>(String purl, ViolationType violationType, V violation) {}

	/** One pattern against one purl; the result does not depend on the violation type. */
	private record Evaluation(String pattern, String purl) {}

	private final IgnoreViolation ignoreViolation;
	private final UUID orgUuid;
	/**
	 * Distinct purls each pattern ran out of budget on. A set, not a count: a purl that comes back
	 * when {@link #releaseRetired} re-checks it must not count twice, or retirement would again
	 * depend on the order.
	 */
	private final Map<PatternKey, Set<String>> trippedOn = new HashMap<>();
	private final Set<PatternKey> retired = new LinkedHashSet<>();
	private final Map<PatternKey, List<Held<T>>> ignoredBy = new LinkedHashMap<>();
	/**
	 * Every pattern-against-purl result of the sweep, so each pair is matched once: a purl with
	 * several violations, and the re-checks in {@link #releaseRetired}, reuse it. Bounded by the
	 * distinct purls of one fetch times the patterns they were tried against.
	 */
	private final Map<Evaluation, MatchResult> evaluated = new HashMap<>();
	/** How many times a pattern was actually run against a purl, for tests. */
	private int matchRuns;

	ViolationIgnoreSweep(IgnoreViolation ignoreViolation, UUID orgUuid) {
		this.ignoreViolation = ignoreViolation;
		this.orgUuid = orgUuid;
	}

	/**
	 * Whether to leave this violation out for now. A violation left out is held, not dropped:
	 * should the pattern that ignored it retire later in the fetch, {@link #releaseRetired} gives
	 * it back.
	 */
	boolean ignore(String purl, ViolationType violationType, T violation) {
		PatternKey by = matchingPattern(purl, violationType);
		if (by == null) return false;
		ignoredBy.computeIfAbsent(by, k -> new ArrayList<>()).add(new Held<>(purl, violationType, violation));
		return true;
	}

	/**
	 * After the last page: the violations held back by patterns that have since retired and that
	 * no live pattern ignores either. Re-checking can retire further patterns, so this runs until
	 * nothing it holds belongs to a retired one.
	 */
	List<T> releaseRetired() {
		List<T> released = new ArrayList<>();
		boolean changed = true;
		while (changed) {
			changed = false;
			for (PatternKey key : List.copyOf(retired)) {
				List<Held<T>> held = ignoredBy.remove(key);
				if (held == null) continue;
				changed = true;
				for (Held<T> h : held) {
					if (!ignore(h.purl(), h.violationType(), h.violation())) released.add(h.violation());
				}
			}
		}
		return released;
	}

	/** Whether the pattern has been given up on for this fetch, for tests. */
	boolean isRetired(ViolationType violationType, String pattern) {
		return retired.contains(new PatternKey(violationType, pattern));
	}

	/** Number of pattern-against-purl matches actually run, for tests. */
	int matchRuns() {
		return matchRuns;
	}

	private MatchResult run(String pattern, String purl) {
		matchRuns++;
		return SafeRegex.matches(pattern, purl);
	}

	/** The first live pattern for this violation's type that matches its purl, or null. */
	private PatternKey matchingPattern(String purl, ViolationType violationType) {
		if (ignoreViolation == null || purl == null) return null;
		List<String> patterns = null;
		if (violationType == ViolationType.LICENSE) {
			patterns = ignoreViolation.getLicenseViolationRegexIgnore();
		} else if (violationType == ViolationType.SECURITY) {
			patterns = ignoreViolation.getSecurityViolationRegexIgnore();
		} else if (violationType == ViolationType.OPERATIONAL) {
			patterns = ignoreViolation.getOperationalViolationRegexIgnore();
		}
		if (patterns == null || patterns.isEmpty()) return null;

		if (purl.length() > SafeRegex.MAX_INPUT_LENGTH) {
			// The purl, not the patterns: no pattern is matched against input this long.
			if (SafeRegex.shouldReport(ViolationIgnoreSweep.class, UnmatchedPurl.TOO_LONG, orgUuid, violationType)) {
				log.error("Purl of {} is too long to match the ignoreViolation patterns for {} violations (org {})"
						+ " -- over {} characters, so the violation is reported, not ignored (logged once an hour)",
						SafeRegex.describeInput(purl), violationType, orgUuid, SafeRegex.MAX_INPUT_LENGTH);
			}
			return null;
		}

		for (String pattern : patterns) {
			if (pattern == null) continue;
			PatternKey key = new PatternKey(violationType, pattern);
			if (retired.contains(key)) continue;
			switch (evaluated.computeIfAbsent(new Evaluation(pattern, purl), e -> run(pattern, purl))) {
				case MATCH -> {
					return key;
				}
				case NO_MATCH -> { }
				case INVALID_PATTERN -> {
					retired.add(key);
					// ERROR: the org asked for these violations to be hidden and they are not.
					if (SafeRegex.shouldReport(ViolationIgnoreSweep.class, MatchResult.INVALID_PATTERN, orgUuid,
							violationType, pattern)) {
						log.error("ignoreViolation pattern {} for {} violations (org {}) does not compile -- it"
								+ " ignores nothing; fix the pattern (logged once an hour per pattern)",
								SafeRegex.describeInput(pattern), violationType, orgUuid);
					}
				}
				case BUDGET_EXCEEDED -> {
					Set<String> tripped = trippedOn.computeIfAbsent(key, k -> new HashSet<>());
					tripped.add(purl);
					if (tripped.size() > SafeRegex.MAX_TRIPS_PER_SWEEP) retired.add(key);
					if (SafeRegex.shouldReport(ViolationIgnoreSweep.class, MatchResult.BUDGET_EXCEEDED, orgUuid,
							violationType, pattern)) {
						log.error("ignoreViolation pattern {} for {} violations (org {}) exceeded the match budget on"
								+ " purl of {} -- not ignored, so the violation is reported, and after more than {}"
								+ " such purls the pattern ignores nothing this sync; simplify the pattern"
								+ " (logged once an hour per pattern)", SafeRegex.describeInput(pattern),
								violationType, orgUuid, SafeRegex.describeInput(purl), SafeRegex.MAX_TRIPS_PER_SWEEP);
					}
				}
			}
		}
		return null;
	}
}

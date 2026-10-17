/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reliza.common.SafeRegex;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData.IgnoreViolation;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;

/**
 * An org's ignoreViolation patterns are matched under the step budget, and a runaway one fails
 * closed: the violation is reported, not hidden.
 *
 * <p>The patterns run against the purl of every violation of every Dependency-Track sync, and the
 * purl comes from an uploaded SBOM; unbounded, one saved {@code (.*a){20}} held the sync thread
 * for minutes per violation.
 */
class IgnoreViolationRegexBudgetTest {

	private static final String RUNAWAY = "(.*a){20}";
	private static final String SUBJECT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";
	/** Matches the good purl cheaply; runs away on {@link #tripping}. */
	private static final String SOMETIMES_RUNAWAY = "pkg:npm/good@.*|(.*a){20}";
	private static final String GOOD = "pkg:npm/good@1.0.0";

	private final UUID org = UUID.randomUUID();

	private static IgnoreViolation security(String... patterns) {
		IgnoreViolation iv = new IgnoreViolation();
		iv.setSecurityViolationRegexIgnore(List.of(patterns));
		return iv;
	}

	/** A distinct purl the runaway half of the pattern trips on. */
	private static String tripping(int i) {
		return "a".repeat(40 + i) + "!";
	}

	/** One fetch of security violations with these purls, in this order: what gets reported. */
	private List<String> reported(IgnoreViolation iv, List<String> purls) {
		ViolationIgnoreSweep<String> sweep = new ViolationIgnoreSweep<>(iv, org);
		List<String> reported = new ArrayList<>();
		for (String purl : purls) {
			if (!sweep.ignore(purl, ViolationType.SECURITY, purl)) reported.add(purl);
		}
		reported.addAll(sweep.releaseRetired());
		Collections.sort(reported);
		return reported;
	}

	@Test
	@Timeout(10)
	void aRunawayPatternFailsClosedFast() {
		long start = System.nanoTime();
		List<String> reported = reported(security(RUNAWAY), List.of(SUBJECT));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertEquals(List.of(SUBJECT), reported, "a pattern that cannot be evaluated must not hide the violation");
	}

	@Test
	@Timeout(10)
	void aLaterPatternThatMatchesStillIgnores() {
		long start = System.nanoTime();
		List<String> reported = reported(security(RUNAWAY, "a+!"), List.of(SUBJECT));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertEquals(List.of(), reported);
	}

	@Test
	@Timeout(30)
	void aPatternRetiresOnlyAfterTrippingOnMoreThanTheThresholdOfDistinctPurls() {
		ViolationIgnoreSweep<String> sweep = new ViolationIgnoreSweep<>(security(RUNAWAY), org);
		// The same purl again and again is one trip: a re-check must not count twice.
		for (int i = 0; i <= SafeRegex.MAX_TRIPS_PER_SWEEP + 1; i++) sweep.ignore(SUBJECT, ViolationType.SECURITY, SUBJECT);
		assertFalse(sweep.isRetired(ViolationType.SECURITY, RUNAWAY));
		for (int i = 1; i < SafeRegex.MAX_TRIPS_PER_SWEEP; i++) sweep.ignore(tripping(i), ViolationType.SECURITY, "v");
		assertFalse(sweep.isRetired(ViolationType.SECURITY, RUNAWAY), "at the threshold, still live");
		sweep.ignore(tripping(SafeRegex.MAX_TRIPS_PER_SWEEP), ViolationType.SECURITY, "v");
		assertTrue(sweep.isRetired(ViolationType.SECURITY, RUNAWAY), "past it, retired");
		assertFalse(sweep.isRetired(ViolationType.LICENSE, RUNAWAY), "retirement is per violation type");
	}

	@Test
	@Timeout(30)
	void theOutcomeDoesNotDependOnTheOrderOfTheViolations() {
		// One alternation that ignores the good purl cheaply and runs away on the others. Before,
		// the good purl was hidden when it came before the trips and reported when it came after.
		List<String> trips = new ArrayList<>();
		for (int i = 0; i <= SafeRegex.MAX_TRIPS_PER_SWEEP; i++) trips.add(tripping(i));
		List<String> goodFirst = new ArrayList<>(List.of(GOOD));
		goodFirst.addAll(trips);
		List<String> goodLast = new ArrayList<>(trips);
		goodLast.add(GOOD);

		List<String> everything = new ArrayList<>(goodFirst);
		Collections.sort(everything);
		assertEquals(everything, reported(security(SOMETIMES_RUNAWAY), goodFirst),
				"a retired pattern ignores nothing, including what it ignored before it retired");
		assertEquals(everything, reported(security(SOMETIMES_RUNAWAY), goodLast));

		// A live pattern further down the list still ignores what the retired one gave back.
		List<String> tripsOnly = new ArrayList<>(trips);
		Collections.sort(tripsOnly);
		assertEquals(tripsOnly, reported(security(SOMETIMES_RUNAWAY, "pkg:npm/good@1\\.0\\.0"), goodFirst));
		assertEquals(tripsOnly, reported(security(SOMETIMES_RUNAWAY, "pkg:npm/good@1\\.0\\.0"), goodLast));
	}

	@Test
	void anInvalidStoredPatternIsRetiredAtOnce() {
		ViolationIgnoreSweep<String> sweep = new ViolationIgnoreSweep<>(security("pkg:npm/("), org);
		assertFalse(sweep.ignore("pkg:npm/x@1", ViolationType.SECURITY, "v"));
		assertTrue(sweep.isRetired(ViolationType.SECURITY, "pkg:npm/("));
	}

	@Test
	void anOverlongPurlIsNotIgnoredAndCountsAgainstNoPattern() {
		// Over the input cap every pattern would trip, through no fault of its own; such purls in
		// an SBOM must not retire the org's patterns for the rest of the sync.
		ViolationIgnoreSweep<String> sweep = new ViolationIgnoreSweep<>(security("pkg:npm/.*"), org);
		for (int i = 0; i <= SafeRegex.MAX_TRIPS_PER_SWEEP; i++) {
			String longPurl = "pkg:npm/" + "a".repeat(SafeRegex.MAX_INPUT_LENGTH + i);
			assertFalse(sweep.ignore(longPurl, ViolationType.SECURITY, longPurl));
		}
		assertFalse(sweep.isRetired(ViolationType.SECURITY, "pkg:npm/.*"));
		assertTrue(sweep.ignore("pkg:npm/left-pad@1.0.0", ViolationType.SECURITY, "v"));
	}

	@Test
	void aBlankPatternMatchesOnlyAnEmptyPurl() {
		assertEquals(List.of("pkg:npm/x@1"), reported(security(""), List.of("pkg:npm/x@1")));
		assertEquals(List.of(), reported(security(""), List.of("")));
	}

	@Test
	void onlyTheListOfTheViolationsTypeApplies() {
		ViolationIgnoreSweep<String> sweep = new ViolationIgnoreSweep<>(security(".*"), org);
		assertFalse(sweep.ignore("pkg:npm/x@1", ViolationType.LICENSE, "v"));
		assertTrue(sweep.ignore("pkg:npm/x@1", ViolationType.SECURITY, "v"));
	}

	@Test
	void writesStillAcceptBlankEntries() throws RelizaException {
		// The settings form saves empty rows; refusing them would block every unrelated save.
		OrganizationService.validateIgnoreViolationPatterns(List.of("pkg:npm/.*", "", " "), null,
				"securityViolationRegexIgnore");
	}

	@Test
	void writesRefuseANewPatternOverTheCap() {
		RelizaException e = assertThrows(RelizaException.class, () -> OrganizationService.validateIgnoreViolationPatterns(
				List.of("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1)), null, "securityViolationRegexIgnore"));
		assertEquals("securityViolationRegexIgnore[0] exceeds " + SafeRegex.MAX_PATTERN_LENGTH + " characters",
				e.getMessage());
	}

	@Test
	void writesRefuseANewInvalidPattern() {
		RelizaException e = assertThrows(RelizaException.class, () -> OrganizationService.validateIgnoreViolationPatterns(
				List.of("", "pkg:npm/("), List.of(""), "licenseViolationRegexIgnore"));
		assertTrue(e.getMessage().startsWith("licenseViolationRegexIgnore[1] is not a valid regex: "), e.getMessage());
	}

	@Test
	void writesRefuseTooManyPatterns() throws RelizaException {
		OrganizationService.validateIgnoreViolationPatterns(
				Collections.nCopies(OrganizationService.MAX_IGNORE_VIOLATION_PATTERNS, "pkg:npm/.*"), null, "f");
		RelizaException e = assertThrows(RelizaException.class, () -> OrganizationService.validateIgnoreViolationPatterns(
				Collections.nCopies(OrganizationService.MAX_IGNORE_VIOLATION_PATTERNS + 1, "pkg:npm/.*"), null, "f"));
		assertEquals("f holds " + (OrganizationService.MAX_IGNORE_VIOLATION_PATTERNS + 1) + " entries; at most "
				+ OrganizationService.MAX_IGNORE_VIOLATION_PATTERNS + " are allowed", e.getMessage());
	}

	@Test
	void legacyStoredEntriesDoNotBlockAnUnrelatedSave() throws RelizaException {
		// The form sends all three lists in full. A legacy entry saved before the checks -- over
		// the length cap, or no longer compiling -- must not stop the org editing another list.
		String overCap = "pkg:npm/" + "a".repeat(SafeRegex.MAX_PATTERN_LENGTH);
		IgnoreViolation stored = security(overCap, "pkg:npm/(");
		OrganizationService.validateIgnoreViolation(List.of("pkg:maven/.*"), List.of(overCap, "pkg:npm/("), null, stored);
		// Removing one of them is fine too; adding a new bad entry is not.
		OrganizationService.validateIgnoreViolation(null, List.of(overCap), null, stored);
		assertThrows(RelizaException.class, () -> OrganizationService.validateIgnoreViolation(null,
				List.of(overCap, "pkg:npm/(", "pkg:pypi/("), null, stored));
	}

	@Test
	void aLegacyEntryCannotBeMultipliedByAnEdit() throws RelizaException {
		String overCap = "pkg:npm/" + "a".repeat(SafeRegex.MAX_PATTERN_LENGTH);
		OrganizationService.validateIgnoreViolationPatterns(List.of(overCap, "pkg:maven/.*"), List.of(overCap), "f");
		RelizaException e = assertThrows(RelizaException.class, () -> OrganizationService.validateIgnoreViolationPatterns(
				List.of(overCap, overCap), List.of(overCap), "f"));
		assertEquals("f[1] exceeds " + SafeRegex.MAX_PATTERN_LENGTH + " characters", e.getMessage());
	}

	@Test
	void aListAlreadyOverTheSizeCapMayOnlyKeepOrDropWhatItHas() throws RelizaException {
		int max = OrganizationService.MAX_IGNORE_VIOLATION_PATTERNS;
		List<String> stored = new ArrayList<>();
		for (int i = 0; i < max + 2; i++) stored.add("pkg:npm/p" + i + "@.*");
		// Kept as it is, or with entries dropped: fine.
		OrganizationService.validateIgnoreViolationPatterns(stored, stored, "f");
		OrganizationService.validateIgnoreViolationPatterns(stored.subList(1, stored.size()), stored, "f");
		// Still over the cap, so no new entry -- not even one swapped in for one dropped -- and
		// no growth.
		List<String> swapped = new ArrayList<>(stored.subList(1, stored.size()));
		swapped.add("pkg:npm/new@.*");
		RelizaException e = assertThrows(RelizaException.class,
				() -> OrganizationService.validateIgnoreViolationPatterns(swapped, stored, "f"));
		assertTrue(e.getMessage().startsWith("f holds " + (max + 2) + " entries; at most " + max + " are allowed"
				+ " -- it is already over that"), e.getMessage());
		List<String> grown = new ArrayList<>(stored);
		grown.add(stored.get(0));
		assertThrows(RelizaException.class, () -> OrganizationService.validateIgnoreViolationPatterns(grown, stored, "f"));
		// Back under the cap, new entries are fine again.
		List<String> under = new ArrayList<>(stored.subList(0, max - 1));
		under.add("pkg:npm/new@.*");
		OrganizationService.validateIgnoreViolationPatterns(under, stored, "f");
	}

	@Test
	@Timeout(30)
	void eachPatternIsRunOnceAgainstEachPurl() {
		// Many violations on one purl, and the re-checks when a pattern retires, reuse the result.
		ViolationIgnoreSweep<String> sweep = new ViolationIgnoreSweep<>(security(SOMETIMES_RUNAWAY, "pkg:npm/.*"), org);
		for (int i = 0; i < 10; i++) sweep.ignore(GOOD, ViolationType.SECURITY, "v" + i);
		assertEquals(1, sweep.matchRuns(), "ten violations on one purl, one match");
		for (int i = 0; i <= SafeRegex.MAX_TRIPS_PER_SWEEP; i++) {
			for (int j = 0; j < 3; j++) sweep.ignore(tripping(i), ViolationType.SECURITY, "t");
		}
		int runs = sweep.matchRuns();
		// The good purl's violations come back for a re-check against the second pattern: one more
		// run, not ten.
		sweep.releaseRetired();
		assertEquals(runs + 1, sweep.matchRuns());
	}
}

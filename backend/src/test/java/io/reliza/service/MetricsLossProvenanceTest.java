/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Pins the one thing the provenance probe exists to decide.
 *
 * <p>The customer instance has no SQL and no kubectl, so a log line is the entire investigation. The
 * [METRICS-LOSS] counts established that a release with one gathered, resolved, never-scanned BOM lost
 * its whole finding set -- but not whether that BOM is the artifact that used to carry them. The two
 * answers need opposite fixes: a SWAP is a release/SCE wiring problem, LOST_IN_PLACE is an
 * artifact-metrics problem. A probe that cannot tell them apart would send the next investigation to the
 * wrong subsystem, which is what the previous four rounds of work did.
 */
class MetricsLossProvenanceTest {

	private final ReleaseMetricsComputeService svc = new ReleaseMetricsComputeService();

	private static final UUID ART_OLD = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID ART_NEW = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

	/** Persisted findings credited to {@code sourceArtifact}, the way the fan-out stamps them. */
	private static ReleaseMetricsDto findingsFrom(UUID sourceArtifact) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(new VulnerabilityDto(
				"pkg:npm/left-pad@1.0.0", "CVE-1", VulnerabilitySeverity.HIGH,
				Set.of(), Set.of(new FindingSourceDto(sourceArtifact, null, null)), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, false))));
		return m;
	}

	@SuppressWarnings("unchecked")
	private Set<UUID> sourcesOf(ReleaseMetricsDto m) {
		Set<UUID> out = new LinkedHashSet<>();
		ReflectionTestUtils.invokeMethod(svc, "collectSourceArtifacts", m, out, new int[] { 0, 0 });
		return out;
	}

	@Test
	void findingsCarryTheArtifactThatProducedThem() {
		assertEquals(Set.of(ART_OLD), sourcesOf(findingsFrom(ART_OLD)),
				"the whole probe rests on sources[].artifact being populated: if it is empty the verdict "
				+ "degrades to NO_SOURCE_ATTRIBUTION and the log answers nothing");
	}

	/**
	 * The discriminator. Same lost findings, two different gathered sets, two opposite verdicts -- so the
	 * line cannot report the same thing regardless of what happened, which is the failure mode that made
	 * several earlier probes and tests useless.
	 */
	@Test
	void theVerdictDistinguishesASwapFromALossInPlace() {
		Set<UUID> lostFrom = sourcesOf(findingsFrom(ART_OLD));

		Set<UUID> gatheredAfterSwap = Set.of(ART_NEW);
		assertEquals(true, java.util.Collections.disjoint(lostFrom, gatheredAfterSwap),
				"the artifact that carried the findings is NOT attached any more -> ARTIFACTS_SWAPPED, "
				+ "and the line prints both uuids so the swap can be correlated");

		// Calls the SHIPPED ladder. An earlier version asserted only disjoint()/containsAll() on
		// hand-built sets and never computed a verdict at all, which left SAME_ARTIFACT_LOST_IN_PLACE --
		// the verdict the probe exists to produce -- pinned by nothing.
		assertEquals("SAME_ARTIFACT_LOST_IN_PLACE",
				verdictFor(lostFrom, Set.of(ART_OLD), Set.of(ART_OLD), false),
				"the same artifact is still attached, still resolves, and simply has no findings any "
				+ "more -> points at artifact metrics, not at wiring");
		assertEquals("ARTIFACTS_SWAPPED",
				verdictFor(lostFrom, Set.of(ART_NEW), Set.of(ART_NEW), false),
				"a different BOM is attached in its place -> points at wiring");
		assertEquals("PRODUCT_ROLLUP",
				verdictFor(lostFrom, Set.of(), Set.of(), true),
				"a product's findings come from its children and the rollup keeps the CHILD's artifact "
				+ "uuid, so without this rung every product loss would read as ALL_ARTIFACTS_GONE and "
				+ "accuse the artifact wiring of a release that never had artifacts");
		assertEquals("ARTIFACTS_GONE_NON_BOM_REMAIN",
				verdictFor(lostFrom, Set.of(ART_NEW), Set.of(), false),
				"only a non-finding-capable artifact (a VDR snapshot, an SCE signature) is left -- that "
				+ "is not a replacement, and calling it one sends the reader hunting one");
	}

	/**
	 * The verdict must NOT read as a swap when the release gathered nothing at all.
	 *
	 * <p>`Collections.disjoint(anything, EMPTY)` is trivially true, so an order-of-tests mistake reports
	 * ARTIFACTS_SWAPPED for a release that lost its entire artifact list -- sending the reader to hunt a
	 * replacement that never existed. This is not hypothetical: 7 of 59 losses in the production overnight
	 * window were gathered=0, and unlike the other 49 they carry scanIncomplete=false, i.e. a different
	 * defect that no scan-completeness guard would ever engage on. Mislabelling 12% of the evidence is how
	 * an investigation goes to the wrong subsystem.
	 */
	@Test
	void gatheringNothingIsNotASwap() {
		Set<UUID> lostFrom = sourcesOf(findingsFrom(ART_OLD));
		Set<UUID> gatheredNothing = Set.of();

		assertEquals(true, java.util.Collections.disjoint(lostFrom, gatheredNothing),
				"precondition, and the whole trap: disjointness alone CANNOT distinguish 'swapped' from "
				+ "'gathered nothing', so the empty case must be tested first");
		assertEquals("ALL_ARTIFACTS_GONE", verdictFor(lostFrom, gatheredNothing, Set.of(), false),
				"a release that gathered no artifacts at all must say so, not claim a swap");
		assertEquals("ARTIFACTS_SWAPPED", verdictFor(lostFrom, Set.of(ART_NEW), Set.of(ART_NEW), false),
				"and a genuine swap must still be reported as one");
	}

	/**
	 * Calls the SHIPPED ladder. An earlier version of this test re-implemented it here, which made it a
	 * tautology: it asserted its own copy and would have stayed green while production said anything at
	 * all.
	 */
	private static String verdictFor(Set<UUID> lostFrom, Set<UUID> gatheredNow,
			Set<UUID> resolvedFindingCapable, boolean rollupLoss) {
		return ReleaseMetricsComputeService.lossVerdict(lostFrom, gatheredNow, resolvedFindingCapable,
				rollupLoss);
	}

	/**
	 * The gauge must not emit a one-sample "summary" the first time anything is lost after a boot, and
	 * must not emit again until the window has actually elapsed. A gauge that fires on every restart, or
	 * more than hourly, lands in the only alerting channel this instance has -- which is the reason
	 * partial losses are not reported per release in the first place.
	 */
	@Test
	void theHourlyGaugeStartsItsWindowRatherThanEmittingImmediately() {
		ReleaseMetricsComputeService fresh = new ReleaseMetricsComputeService();
		Object marker = ReflectionTestUtils.getField(fresh, "lastLossGaugeReport");
		assertEquals(null, ((java.util.concurrent.atomic.AtomicReference<?>) marker).get(),
				"no window is open before the first loss");

		ReflectionTestUtils.invokeMethod(fresh, "recordFindingLoss", 10, 3, false, true, false);

		assertEquals(false, ((java.util.concurrent.atomic.AtomicReference<?>) marker).get() == null,
				"the FIRST loss opens the window instead of emitting -- otherwise every pod restart "
				+ "produces a summary describing a single release");
		assertEquals(1L, ((java.util.concurrent.atomic.AtomicLong)
				ReflectionTestUtils.getField(fresh, "lossGaugeReleases")).get(),
				"and it is still counted, so the first hour is not silently dropped");
		assertEquals(7L, ((java.util.concurrent.atomic.AtomicLong)
				ReflectionTestUtils.getField(fresh, "lossGaugeFindings")).get(),
				"findingsLost accumulates the size of the drop, not the number of releases");
	}

	/**
	 * subAssembledCollapses is a STRICT SUBSET of collapses: it increments only on a total collapse that
	 * is also sub-ASSEMBLED (PENDING/DRAFT), so the gauge can separate expected pre-release churn from an
	 * ASSEMBLED+ collapse worth paging on. A partial drop (not a collapse) must not touch it even when
	 * sub-ASSEMBLED, and an ASSEMBLED collapse must land in collapses but not in the subset.
	 */
	@Test
	void subAssembledCollapseCounterIsAStrictSubsetOfCollapses() {
		ReleaseMetricsComputeService fresh = new ReleaseMetricsComputeService();
		// sub-ASSEMBLED total collapse -> both counters.
		ReflectionTestUtils.invokeMethod(fresh, "recordFindingLoss", 6, 0, true, false, true);
		// ASSEMBLED total collapse -> collapses only, NOT the subset.
		ReflectionTestUtils.invokeMethod(fresh, "recordFindingLoss", 4, 0, true, false, false);
		// sub-ASSEMBLED PARTIAL drop (not a collapse) -> neither collapse counter.
		ReflectionTestUtils.invokeMethod(fresh, "recordFindingLoss", 5, 2, false, false, true);

		assertEquals(2L, ((java.util.concurrent.atomic.AtomicLong)
				ReflectionTestUtils.getField(fresh, "lossGaugeTotalCollapses")).get(),
				"both total collapses count; the partial drop does not");
		assertEquals(1L, ((java.util.concurrent.atomic.AtomicLong)
				ReflectionTestUtils.getField(fresh, "lossGaugeSubAssembledCollapses")).get(),
				"only the sub-ASSEMBLED collapse counts in the subset -- not the ASSEMBLED one, "
				+ "and not the sub-ASSEMBLED partial drop (which is not a collapse)");
	}

	/**
	 * The per-release [METRICS-LOSS] / [METRICS-LOSS-PROVENANCE] ERROR lines are gated on
	 * {@code isScannableLifecycle(rd.getLifecycle())}: a collapse on a sub-ASSEMBLED release
	 * (PENDING = CI-created, DRAFT = manual edit) is expected pre-release churn and must NOT ERROR-log
	 * (production showed 100% of collapses were lifecycle=PENDING), while an ASSEMBLED+ release still
	 * emits. This pins that boundary directly -- the emission itself is a log line, and log4j2 output
	 * capture is flaky here (see FindingChangeEventDiffTest). The gauge's subAssembledCollapses still
	 * counts the sub-ASSEMBLED collapses for aggregate visibility (see the subset test above).
	 */
	@Test
	void perReleaseLossErrorIsGatedToScannableLifecycles() {
		assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(
				ReleaseMetricsComputeService.class, "isScannableLifecycle", ReleaseLifecycle.PENDING),
				"PENDING (CI-created) collapse is pre-release churn -- must not ERROR-log");
		assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(
				ReleaseMetricsComputeService.class, "isScannableLifecycle", ReleaseLifecycle.DRAFT),
				"DRAFT (manual edit) collapse -- must not ERROR-log");
		assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(
				ReleaseMetricsComputeService.class, "isScannableLifecycle", ReleaseLifecycle.ASSEMBLED),
				"ASSEMBLED is a real release -- a genuine loss still ERROR-logs");
		assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(
				ReleaseMetricsComputeService.class, "isScannableLifecycle", ReleaseLifecycle.GENERAL_AVAILABILITY),
				"and every lifecycle past ASSEMBLED");
	}

	/**
	 * The stall gauge must be bounded whatever the stalled population turns out to be -- that is the
	 * whole reason it replaced a per-release WARN rather than that WARN being raised to ERROR. Pins the
	 * two properties that make it safe on an ERROR-only pipeline: the first observation opens the window
	 * instead of emitting, and the uuid sample never grows past SAMPLE_LIMIT no matter how many stall.
	 */
	@Test
	void theStallGaugeIsBoundedRegardlessOfPopulation() {
		ReleaseMetricsComputeService fresh = new ReleaseMetricsComputeService();
		java.util.concurrent.atomic.AtomicReference<?> window =
				(java.util.concurrent.atomic.AtomicReference<?>)
						ReflectionTestUtils.getField(fresh, "lastStallReport");
		assertEquals(null, window.get(), "no window before the first stalled compute");

		for (int i = 0; i < 500; i++) {
			ReflectionTestUtils.invokeMethod(fresh, "recordStalledCompute", UUID.randomUUID(), 24 + i);
		}

		assertEquals(false, window.get() == null,
				"the first observation opens the window instead of emitting a one-sample summary");
		assertEquals(5, ((java.util.Set<?>) ReflectionTestUtils.getField(fresh, "stallSample")).size(),
				"500 stalled releases must still yield a SAMPLE_LIMIT-sized sample -- the output cannot "
				+ "scale with the population, or raising this to the only alerting channel would be a bet "
				+ "on a number nobody has measured");
		assertEquals(500L, ((java.util.concurrent.atomic.AtomicLong)
				ReflectionTestUtils.getField(fresh, "stallObservations")).get(),
				"but every observation is still counted, so the aggregate reports the real size");
		assertEquals(523, ((java.util.concurrent.atomic.AtomicInteger)
				ReflectionTestUtils.getField(fresh, "stallMaxAttempts")).get(),
				"and maxAttempts tracks the worst offender, which is the 11-day shape");
	}

	@Test
	void findingsWithNoSourceDegradeHonestly() {
		ReleaseMetricsDto noSources = new ReleaseMetricsDto();
		noSources.setVulnerabilityDetails(new LinkedList<>(List.of(new VulnerabilityDto(
				"pkg:npm/x@1.0.0", "CVE-2", VulnerabilitySeverity.HIGH,
				Set.of(), null, Set.of(), null, null, ZonedDateTime.now(),
				null, null, null, null, null, false))));

		assertEquals(Set.of(), sourcesOf(noSources),
				"a null sources set must yield no attribution rather than throw -- the probe runs inside the "
				+ "compute it is describing and must never be able to fail it");
	}
}

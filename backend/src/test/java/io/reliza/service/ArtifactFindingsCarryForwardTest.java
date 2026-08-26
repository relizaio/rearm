/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;

/**
 * Pins the carry-forward decision itself.
 *
 * <p>A release re-derives its findings by MERGING its artifacts' metrics, so a replacement BOM that
 * has not been scanned yet contributes nothing and the release collapses to zero -- then recovers
 * when the scan lands, writing a phantom RESOLVED/APPEARED cycle into the findings timeline on the
 * way. Seeding the replacement from its predecessor means the merge always has something to merge.
 *
 * <p>The three properties below are the ones a wrong implementation would break, and each is
 * asserted against a fixture that fails if the property is dropped: findings travel, the scan stamps
 * do NOT (or the replacement reads as scanned and the release stops waiting for the real result),
 * and a real scan is never overwritten (or a remediation gets resurrected).
 */
class ArtifactFindingsCarryForwardTest {

	private static final UUID PREDECESSOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	@Test
	void theSummaryScalarsComputeMetricsFromFactsDoesNotDeriveAreCarriedToo() {
		DependencyTrackIntegration predecessor = scannedPredecessor(4);
		predecessor.setComponents(1420);
		predecessor.setVulnerableComponents(63);
		predecessor.setSuppressed(8);
		predecessor.setFindingsTotal(210);
		predecessor.setFindingsAudited(190);
		predecessor.setInheritedRiskScore(740);

		DependencyTrackIntegration inherited = SharedArtifactService
				.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), predecessor);

		assertEquals(1420, inherited.getComponents(),
				"computeMetricsFromFacts re-derives the severity counts and the policyViolations*Total "
				+ "fields, but NOT these. Without carrying them the successor reported a full detail "
				+ "list alongside components=0 / findingsTotal=0 / suppressed=0 -- the successor "
				+ "clone's zeros, which are neither the predecessor's numbers nor a derivation of the "
				+ "carried facts");
		assertEquals(63, inherited.getVulnerableComponents());
		assertEquals(8, inherited.getSuppressed());
		assertEquals(210, inherited.getFindingsTotal());
		assertEquals(190, inherited.getFindingsAudited());
		assertEquals(740, inherited.getInheritedRiskScore());
		assertEquals(4, inherited.getHigh(),
				"and the fields computeMetricsFromFacts DOES derive still win, so a carried scalar can "
				+ "never disagree with the detail list it summarises");
	}

	@Test
	void theTruncationProbeIsExempt_forAnyCarriedArtifactsOwnFirstScan() {
		// The carry-forward marker: findings present, never stamped. Its own first scan emptying the
		// carried set is the seam working -- exempt whether the emptiness is a genuine remediation or
		// an all-dereferenced ALL_ARTIFACTS_GONE. DECISION 2026-08-19 (design doc 13.1): the latter is
		// exempt here too, because it is already reported per release by the rate-limited
		// [METRICS-LOSS-PROVENANCE] probe, and keeping this artifact-level ERROR armed on that
		// population only produced a standing per-build alert nobody could clear. An earlier revision
		// threaded a hadRealCoverage flag to keep the probe armed on the degraded case; it was removed
		// with that flag.
		assertTrue(SharedArtifactService.isCarriedReplacedByItsOwnScan(null, 8),
				"a carried artifact (null firstScanned + findings) having its own first scan replace "
				+ "the carried set is exempt -- otherwise carry-forward makes every clean rebuild fire "
				+ "[METRICS-TRUNCATION]");

		assertFalse(SharedArtifactService.isCarriedReplacedByItsOwnScan(ZonedDateTime.now(), 8),
				"an already-scanned row is not carried, so a wipe there is the original 2026-08-13 "
				+ "incident and must always report");
		assertFalse(SharedArtifactService.isCarriedReplacedByItsOwnScan(null, 0),
				"and a row with no findings was never carrying anything");
	}

	private static DependencyTrackIntegration metricsWith(int findings, UUID sourceArtifact) {
		DependencyTrackIntegration m = new DependencyTrackIntegration();
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			vulns.add(new VulnerabilityDto("pkg:maven/org.example/lib" + i + "@1.0.0", "CVE-" + i,
					VulnerabilitySeverity.HIGH, Set.of(),
					Set.of(new FindingSourceDto(sourceArtifact, null, null)), Set.of(),
					null, null, ZonedDateTime.now(), null, null, null, null, null, false));
		}
		m.setVulnerabilityDetails(vulns);
		// A THIRD distinct count, so no single-list implementation can satisfy all three.
		LinkedList<WeaknessDto> weaknesses = new LinkedList<>();
		for (int i = 0; i < findings + 2; i++) {
			// LOW, not HIGH: the severity scalars aggregate vulnerabilities AND weaknesses, so giving
			// weaknesses the same severity would make scalarCountsAreDerivedFromTheDetails assert
			// against a blended number and stop isolating the vulnerability list.
			weaknesses.add(new WeaknessDto("CWE-" + i, "rule-1", "src/Foo.java:10", "fp-" + i,
					VulnerabilitySeverity.LOW, Set.of(new FindingSourceDto(sourceArtifact, null, null)),
					null, null, ZonedDateTime.now()));
		}
		m.setWeaknessDetails(weaknesses);
		// Violations and weaknesses too, at DIFFERENT counts, so an implementation that carried only
		// vulnerabilities -- or that copied one list into all three -- cannot pass.
		LinkedList<ViolationDto> violations = new LinkedList<>();
		for (int i = 0; i < findings + 1; i++) {
			violations.add(new ViolationDto("pkg:maven/org.example/lib" + i + "@1.0.0",
					ViolationType.LICENSE, "GPL-3.0-only", "License not allowed",
					Set.of(new FindingSourceDto(sourceArtifact, null, null)), null, null,
					ZonedDateTime.now()));
		}
		m.setViolationDetails(violations);
		m.computeMetricsFromFacts();
		return m;
	}

	/** A predecessor as the fan-out leaves it: findings AND both scan stamps set. */
	private static DependencyTrackIntegration scannedPredecessor(int findings) {
		DependencyTrackIntegration m = metricsWith(findings, PREDECESSOR);
		ZonedDateTime scanned = ZonedDateTime.now().minusHours(1);
		m.setFirstScanned(scanned);
		m.setLastScanned(scanned);
		return m;
	}

	@Test
	void theReplacementInheritsFindingsButNotTheScanStamps() {
		DependencyTrackIntegration inherited = SharedArtifactService
				.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), scannedPredecessor(8));

		assertNotNull(inherited, "a scanned predecessor with findings must produce a carry-forward");
		assertEquals(8, inherited.getVulnerabilityDetails().size(),
				"the whole point: the replacement is not empty, so the release merge cannot collapse");
		assertNull(inherited.getFirstScanned(),
				"firstScanned must stay null -- it is what keeps anyBomUnscanned true, so the release "
				+ "still reports scan-pending and still recomputes when the real scan lands");
		assertNull(inherited.getLastScanned(),
				"lastScanned must stay null too. computeMetricsFromFacts defaults it to now(), so this "
				+ "asserts it was nulled AFTER that call -- otherwise the replacement reads as scanned");
	}

	@Test
	void inheritedFindingsStillNameThePredecessorAsTheirSource() {
		DependencyTrackIntegration inherited = SharedArtifactService
				.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), scannedPredecessor(3));

		Set<UUID> sources = inherited.getVulnerabilityDetails().stream()
				.flatMap(v -> v.sources().stream()).map(FindingSourceDto::artifact)
				.collect(Collectors.toSet());
		assertEquals(Set.of(PREDECESSOR), sources,
				"attribution must NOT be re-pointed at the successor. Re-pointing would make the loss "
				+ "probe's lostFrom and gatheredNow identical and collapse its verdict from "
				+ "ARTIFACTS_SWAPPED to SAME_ARTIFACT_LOST_IN_PLACE -- sending the next investigation to "
				+ "artifact metrics when the fault is wiring. VEX rulings ride on these same records");
	}

	@Test
	void scalarCountsAreDerivedFromTheDetailsRatherThanCopied() {
		DependencyTrackIntegration predecessor = scannedPredecessor(5);
		// Corrupt the predecessor's scalar so a copy and a re-derivation give different answers.
		predecessor.setHigh(999);

		DependencyTrackIntegration inherited = SharedArtifactService
				.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), predecessor);

		assertEquals(5, inherited.getHigh(),
				"counts are re-derived from the detail lists, never copied, so the two cannot disagree");
	}

	@Test
	void aRealScanOnTheReplacementIsNeverOverwritten() {
		DependencyTrackIntegration alreadyScanned = metricsWith(0, null);
		alreadyScanned.setFirstScanned(ZonedDateTime.now());

		assertNull(SharedArtifactService.inheritFindingsFromPredecessor(alreadyScanned, scannedPredecessor(8)),
				"the upload and the fan-out are independent, so the replacement's own scan CAN land "
				+ "first. That result is authoritative -- inheriting over it would resurrect findings "
				+ "the scan had just cleared, which is the genuine-remediation case inverted");
	}

	@Test
	void thereIsNothingToInheritFromAnUnscannedOrAbsentPredecessor() {
		assertNull(SharedArtifactService.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), null),
				"no predecessor at all -- a first upload is a genuine first scan and must stay one");
		assertNull(SharedArtifactService.inheritFindingsFromPredecessor(new DependencyTrackIntegration(),
						new DependencyTrackIntegration()),
				"a predecessor that was itself never scanned carries nothing, so there is no write to "
				+ "make; returning empty metrics here would look like a completed scan of zero findings");
	}

	@Test
	void theReplacementKeepsItsOwnDtrackPlumbing() {
		DependencyTrackIntegration successor = new DependencyTrackIntegration();
		successor.setDtrackSubmissionAttempts(3);
		successor.setDependencyTrackProject("proj-successor");

		DependencyTrackIntegration inherited = SharedArtifactService
				.inheritFindingsFromPredecessor(successor, scannedPredecessor(2));

		assertEquals(3, inherited.getDtrackSubmissionAttempts(),
				"only the finding-bearing fields come from the predecessor; submission/fetch state "
				+ "belongs to the replacement and its own DTrack project");
		assertEquals("proj-successor", inherited.getDependencyTrackProject(),
				"and the predecessor's project must not be adopted -- the replacement is a different "
				+ "BOM and will be submitted on its own");
	}

	/**
	 * The IN-PLACE re-upload uses the same function with a fresh successor, because there is no new
	 * row: validateCycloneDxUpdate only mints a new uuid when the BOM's serialNumber CHANGES, so the
	 * ordinary "new version of this BOM" flow and all SPDX reuse the existing artifact and would
	 * otherwise have their findings overwritten with an empty metrics object.
	 */
	@Test
	void anInPlaceReUploadKeepsTheFindingsButNotThePredecessorsPlumbing() {
		DependencyTrackIntegration existingRow = scannedPredecessor(6);
		existingRow.setDependencyTrackProject("proj-previous-content");
		existingRow.setDtrackSubmissionAttempts(4);

		DependencyTrackIntegration carried = SharedArtifactService
				.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), existingRow);

		assertEquals(6, carried.getVulnerabilityDetails().size(),
				"the row keeps its findings across a same-serial re-upload -- without this the release "
				+ "collapses to zero exactly as it does on the replaced-uuid branch");
		assertNull(carried.getFirstScanned(),
				"but the NEW content has not been scanned, so the stamps clear and the release reports "
				+ "scan-pending. This part already happens today; only the findings surviving is new");
		assertNull(carried.getLastScanned());
		assertNull(carried.getDependencyTrackProject(),
				"the previous content's DTrack project must NOT be adopted -- the new bytes will be "
				+ "submitted on their own, and its attempt counters do not describe them");
		assertEquals(0, carried.getDtrackSubmissionAttempts(),
				"same for submission state: it belongs to the content that was replaced");
	}

	@Test
	void everyDetailListTravels() {
		DependencyTrackIntegration predecessor = scannedPredecessor(4);

		DependencyTrackIntegration inherited = SharedArtifactService
				.inheritFindingsFromPredecessor(new DependencyTrackIntegration(), predecessor);

		assertEquals(4, inherited.getVulnerabilityDetails().size(), "vulnerabilities carry");
		assertEquals(5, inherited.getViolationDetails().size(),
				"policy violations carry too, and at their OWN count -- an implementation that copied "
				+ "the vulnerability list into every field, or that carried only vulnerabilities, fails "
				+ "here. Deleting either setter from the implementation must turn this red");
		assertEquals(6, inherited.getWeaknessDetails().size(),
				"weaknesses carry too, at their OWN third count. This assertion previously read "
				+ "isEmpty() against a fixture that gave the predecessor no weaknesses, so it passed "
				+ "with setWeaknessDetails deleted -- it pinned nothing. Verified by deleting that "
				+ "setter and watching this go red.");
	}
}

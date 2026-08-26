/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.dto.CarryForwardArm;
import io.reliza.model.dto.CarryForwardTally;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.Deliverable;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the rebuild-seam PAIRING, which is a heuristic and therefore the part most likely to be
 * silently wrong.
 *
 * <p>A CI rebuild does not replace artifacts one at a time: it clears the variant's outbound
 * deliverables and builds a fresh set, so nothing records which new BOM replaces which old one. The
 * pairing infers it from the deliverable's {@code displayIdentifier}, corroborated by the purl
 * coordinate. Production data behind that choice: displayIdentifier is populated on 3332/3332
 * deliverables, unique within a release, and 240 distinct names cover 3332 deliverables (13.9x
 * reuse) -- a stable lineage name rather than a per-build value.
 *
 * <p>The negative cases matter as much as the positive one. A WRONG pairing attributes one
 * component's vulnerabilities to another, which is worse than the collapse being fixed, so
 * "declined" must stay the outcome whenever the evidence is not clean.
 */
@SpringBootTest(classes = {App.class})
public class RebuildCarryForwardPairingTest {

	@Autowired private DeliverableService deliverableService;
	@Autowired private ArtifactService artifactService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private DeliverableRepository deliverableRepository;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private TestInitializer testInitializer;

	@Test
	public void aRebuiltDeliverableInheritsFromTheOneItReplaces() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID oldBom = bom(org, 5, true);
		UUID newBom = bom(org, 0, false);
		UUID prior = deliverable(org, "backend", null, oldBom);
		UUID fresh = deliverable(org, "backend", null, newBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(1, tally.seeded(), "same displayIdentifier across the rebuild is the pairing signal");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(5, after.getMetrics().getVulnerabilityDetails().size(),
				"the replacement carries its predecessor's findings, so the release merge has "
				+ "something to merge and never reports zero while the new scan is pending");
		assertNull(after.getMetrics().getFirstScanned(),
				"and stays unscanned, so the release keeps reporting scan-pending and recomputes when "
				+ "the real scan lands");
	}

	@Test
	public void aDeliverableWithNoCounterpartIsLeftAlone() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID prior = deliverable(org, "backend", null, bom(org, 5, true));
		UUID unrelated = bom(org, 0, false);
		UUID fresh = deliverable(org, "frontend", null, unrelated);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.seeded(), "no name match means no pairing");
		assertEquals(1, tally.unpaired(),
				"and it is COUNTED as unpaired. These counters are the only way the heuristic's hit "
				+ "rate is visible in production -- a decline that is silent is indistinguishable from "
				+ "a carry-forward that never ran");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(unrelated).orElseThrow());
		assertEquals(List.of(), after.getMetrics().getVulnerabilityDetails(),
				"an unpaired deliverable must behave exactly as it does today -- inventing a pairing "
				+ "would credit the backend's vulnerabilities to the frontend, which is worse than the "
				+ "collapse this fixes");
	}

	@Test
	public void aNameMatchWithConflictingPurlsIsRefused() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID prior = deliverable(org, "app", "pkg:oci/team-a/app@1.0.0", bom(org, 5, true));
		UUID newBom = bom(org, 0, false);
		UUID fresh = deliverable(org, "app", "pkg:oci/team-b/app@2.0.0", newBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"same name but a different purl coordinate means two different components that happen "
				+ "to share a label. Carrying findings across that boundary is the one outcome worse "
				+ "than not carrying them at all");
		assertEquals(1, tally.purlConflict(),
				"and it is counted SEPARATELY from unpaired: a name collision between two different "
				+ "components is a different operational problem from a renamed deliverable, and the "
				+ "response differs");
		assertEquals(0, tally.unpaired(), "a purl conflict is not an unpaired deliverable");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(List.of(), after.getMetrics().getVulnerabilityDetails());
	}

	@Test
	public void matchingPurlsCorroborateTheNameRatherThanBlockingIt() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID newBom = bom(org, 0, false);
		// Same coordinate, different VERSION -- which is what a rebuild always produces.
		UUID prior = deliverable(org, "app", "pkg:oci/team-a/app@1.0.0", bom(org, 4, true));
		UUID fresh = deliverable(org, "app", "pkg:oci/team-a/app@2.0.0", newBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(1, tally.seeded(),
				"purlCoordinateBase strips the version, so a normal version bump still pairs -- if it "
				+ "did not, the corroboration would reject every real rebuild it was meant to confirm");
	}

	/**
	 * The RELEASE-DIRECT arm. A rebuild replaces all three owner arms from CI input, and this one has
	 * no container carrying a stable name to pair on -- artifact-level displayIdentifier is populated
	 * on ZERO of 5891 production BOMs -- so it pairs only the unambiguous single-BOM case.
	 *
	 * <p>Worth its own test because the first implementation covered the DELIVERABLE arm only, and
	 * the sandbox validation used a deliverable-attached BOM, so the gap was invisible to both.
	 */
	@Test
	public void releaseDirectArtifactsAreCarriedForwardToo() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID oldBom = bom(org, 5, true);
		UUID newBom = bom(org, 0, false);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(oldBom), List.of(newBom), WhoUpdated.getAutoWhoUpdated(),
				CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		assertEquals(1, tally.seeded(), "one BOM each side pairs unambiguously");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(5, after.getMetrics().getVulnerabilityDetails().size(),
				"a release-direct BOM must inherit exactly as a deliverable-attached one does");
		assertNull(after.getMetrics().getFirstScanned(), "and stay unscanned");
	}

	@Test
	public void twoBomsOnOneSideDeclineRatherThanGuess() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID newBom = bom(org, 0, false);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(bom(org, 5, true), bom(org, 3, true)), List.of(newBom),
				WhoUpdated.getAutoWhoUpdated(), CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"two candidate predecessors and no key to choose between them -- picking one would "
				+ "credit one component's vulnerabilities to another, which is worse than the collapse");
		assertEquals(1, tally.bomCountAmbiguous(), "and the ambiguity is COUNTED, not silent");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(List.of(), after.getMetrics().getVulnerabilityDetails());
	}

	@Test
	public void anUnchangedArtifactIsNotSeededFromItself() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID sameBom = bom(org, 4, true);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(sameBom), List.of(sameBom), WhoUpdated.getAutoWhoUpdated(),
				CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"nothing was swapped, so there is nothing to carry -- seeding a row from itself would "
				+ "null its own scan stamps and make a scanned artifact look unscanned");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(sameBom).orElseThrow());
		assertNotNull(after.getMetrics().getFirstScanned(),
				"and critically its scan stamp survives");
	}

	/**
	 * The SCE arm. Same single-BOM pairing as release-direct, reached when a rebuild supplies a new
	 * commit and therefore a new source-code entry.
	 *
	 * <p>Tested through the same entry point the rebuild uses, because the arm is otherwise reachable
	 * only via a full rebuild with a changed commit -- and an arm that is implemented but never
	 * exercised is exactly the shape that let the release-direct gap survive.
	 */
	@Test
	public void sceAttachedBomsAreCarriedForwardToo() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID oldBom = bom(org, 7, true);
		UUID newBom = bom(org, 0, false);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(oldBom), List.of(newBom), WhoUpdated.getAutoWhoUpdated(),
				CarryForwardArm.SCE, UUID.randomUUID());

		assertEquals(1, tally.seeded(), "the SCE arm pairs on the same single-BOM rule");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(7, after.getMetrics().getVulnerabilityDetails().size(),
				"an SCE-attached BOM inherits exactly as the other two arms do");
	}

	/**
	 * A rebuild that keeps the SAME commit produces the same SCE uuid on both sides, and the caller
	 * skips the arm entirely. Pinned because the alternative -- passing the same SCE through -- would
	 * hit the same-row guard and be a no-op anyway, so a regression here is invisible unless the
	 * no-op property itself is asserted.
	 */
	@Test
	public void anUnchangedSceIsANoOp() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID bom = bom(org, 3, true);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(bom), List.of(bom), WhoUpdated.getAutoWhoUpdated(),
				CarryForwardArm.SCE, UUID.randomUUID());

		assertEquals(0, tally.seeded(), "nothing swapped, nothing carried");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(bom).orElseThrow());
		assertNotNull(after.getMetrics().getFirstScanned(),
				"and the artifact's own scan stamp is untouched -- seeding a row from itself would null "
				+ "its stamps and make a scanned artifact read as unscanned");
	}

	/**
	 * The replacement BOM introducing a NEW finding. Not a regression risk -- additions could never
	 * appear before their scan, with or without carry-forward -- but pinned so the inherited set
	 * cannot start masking the scan result.
	 */
	@Test
	public void aReplacementWithDifferentFindingsTakesItsOwnOnceScanned() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID oldBom = bom(org, 2, true);
		UUID newBom = bom(org, 0, false);

		artifactService.carryFindingsAcrossArtifactSwap(List.of(oldBom), List.of(newBom),
				WhoUpdated.getAutoWhoUpdated(), CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());
		ArtifactData inherited = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(2, inherited.getMetrics().getVulnerabilityDetails().size(), "inherits 2 while pending");
		assertNull(inherited.getMetrics().getFirstScanned(),
				"and stays unscanned, so its own scan result -- however many findings that turns out to "
				+ "be -- still replaces this set wholesale when it lands");
	}

	// ---- fixtures ----

	@Test
	public void anUnscannedPredecessorCountsAsSeededZeroNotSeededOne() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// The predecessor exists and pairs by name, but carries NO findings -- it was itself never
		// scanned. Nothing can be carried, so the release still collapses.
		UUID prior = deliverable(org, "backend", null, bom(org, 0, false));
		UUID newBom = bom(org, 0, false);
		UUID fresh = deliverable(org, "backend", null, newBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"seeded counts FINDINGS WRITTEN, not pairings attempted. This previously read 1, because "
				+ "the underlying call returned 'both artifact rows were found' -- so the one counter "
				+ "meant to reveal the heuristic underperforming reported 100% success in exactly the "
				+ "case where nothing was carried and the release still drops to zero");
	}

	@Test
	public void twoPriorDeliverablesSharingANameDeclineRatherThanMispair() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// A multi-arch build: same displayIdentifier, different images. purlCoordinateBase strips the
		// arch qualifier, so the purl guard cannot separate them either.
		UUID amdBom = bom(org, 5, true);
		UUID armBom = bom(org, 9, true);
		UUID priorAmd = deliverable(org, "app", "pkg:oci/myorg/app@1.0.0?arch=amd64", amdBom);
		UUID priorArm = deliverable(org, "app", "pkg:oci/myorg/app@1.0.0?arch=arm64", armBom);
		UUID newBom = bom(org, 0, false);
		UUID fresh = deliverable(org, "app", "pkg:oci/myorg/app@1.1.0?arch=amd64", newBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(priorAmd, priorArm), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"an ambiguous name must DECLINE. Last-wins would have seeded the arm64 image's findings "
				+ "onto the amd64 BOM -- attributing one component's vulnerabilities to another, "
				+ "silently, with no probe that would ever show it. Mispairing is the worst outcome "
				+ "this seam can produce, so ambiguity is dropped rather than guessed");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(List.of(), after.getMetrics().getVulnerabilityDetails(),
				"and the successor is left exactly as it is today");
	}

	@Test
	public void aDeliverableWhoseBomRowIsReusedIsNotSeededFromItself() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// prepareListofDeliverables re-uses the artifact row when the digest is unchanged, so the same
		// BOM uuid appears on both sides of the rebuild.
		UUID sharedBom = bom(org, 5, true);
		UUID prior = deliverable(org, "backend", null, sharedBom);
		UUID fresh = deliverable(org, "backend", null, sharedBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"nothing was swapped, so nothing is carried");
		// THE assertion that pins the guard. seeded==0 does NOT: with the guard deleted the pair
		// (X, X) still reaches transferArtifactVersionHistoryInternal, whose FIRST action is
		// transferVersionHistory -- and only AFTER that does inheritFindingsFromPredecessor decline,
		// because the shared row is already scanned. So the findings and the stamp look untouched
		// while previousVersions has silently grown. Verified by deleting the guard.
		assertEquals(0, ArtifactData.dataFromRecord(artifactRepository.findById(sharedBom).orElseThrow())
						.getPreviousVersions().size(),
				"and crucially its previousVersions list is UNTOUCHED. transferVersionHistory runs "
				+ "before the findings guard, so a missing same-row guard appends a self-snapshot and "
				+ "re-appends the whole prior list on every single rebuild -- unbounded growth inside "
				+ "the artifact's record_data JSONB, invisible to every findings assertion");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(sharedBom).orElseThrow());
		assertEquals(5, after.getMetrics().getVulnerabilityDetails().size(),
				"and the untouched row keeps its own findings and its scan stamp");
		assertNotNull(after.getMetrics().getFirstScanned(),
				"crucially it stays SCANNED -- self-seeding would have nulled its stamps and made a "
				+ "scanned artifact read as scan-pending");
	}

	@Test
	public void twoNEWDeliverablesSharingANameDeclineToo() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// The mirror of twoPriorDeliverablesSharingANameDeclineRatherThanMispair: a SINGLE-arch prior
		// rebuilt as multi-arch. De-duplicating only the prior side leaves this direction open --
		// both successors match the one predecessor and both get seeded from it.
		UUID prior = deliverable(org, "app", "pkg:oci/myorg/app@1.0.0?arch=amd64", bom(org, 5, true));
		UUID newAmd = bom(org, 0, false);
		UUID newArm = bom(org, 0, false);
		UUID freshAmd = deliverable(org, "app", "pkg:oci/myorg/app@1.1.0?arch=amd64", newAmd);
		UUID freshArm = deliverable(org, "app", "pkg:oci/myorg/app@1.1.0?arch=arm64", newArm);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
List.of(prior), List.of(freshAmd, freshArm), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.seeded(),
				"ambiguity on the SUCCESSOR side must decline just as it does on the prior side. "
				+ "Otherwise both new deliverables pair against the single predecessor and both are "
				+ "seeded from it -- the amd64 image's vulnerabilities credited to the arm64 build -- "
				+ "and the tally reports a perfect run (seeded=2 of 2)");
		for (UUID b : List.of(newAmd, newArm)) {
			ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(b).orElseThrow());
			assertEquals(List.of(), after.getMetrics().getVulnerabilityDetails(),
					"neither successor may be seeded when the pairing is ambiguous");
		}
	}

	@Test
	public void anArmCarryingNoBomIsOrdinary_notAnAmbiguousPairing() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// Release-direct artifacts that are NOT BOMs -- an attestation-shaped release. Both sides are
		// non-empty, so the early return does not fire.
		UUID priorAtt = nonBomArtifact(org);
		UUID newAtt = nonBomArtifact(org);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(priorAtt), List.of(newAtt),
				WhoUpdated.getAutoWhoUpdated(), CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		assertEquals(1, tally.noBom(),
				"no BOM on this arm is an ORDINARY shape -- BOMs usually hang off deliverables -- and "
				+ "means there is simply nothing to carry");
		assertEquals(0, tally.bomCountAmbiguous(),
				"and it must NOT be counted as an ambiguous pairing. A single sole-BOM lookup returned "
				+ "empty for both 'no BOM' and 'several BOMs', so routine traffic landed in the counter "
				+ "the alert gate reads -- firing ERROR on healthy builds and making the counter "
				+ "untrustworthy for the case it exists to catch");
		assertEquals(0, tally.candidates(),
				"and candidates is ZERO, which is what silences the log entirely -- only candidates "
				+ "drives the no-op guard, so reporting noBom for the caller and staying quiet on the "
				+ "channel are not in conflict");
		assertEquals(0, tally.bomCountAmbiguous(),
				"and it stays out of the ambiguity counter, which is what an operator reads to judge "
				+ "whether the pairing heuristic is degrading");
	}

	@Test
	public void twoBomsOnTheReleaseDirectArmStillDecline_andDoCountAsAmbiguous() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID newBom = bom(org, 0, false);

		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(bom(org, 5, true), bom(org, 3, true)), List.of(newBom),
				WhoUpdated.getAutoWhoUpdated(), CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		assertEquals(0, tally.seeded(), "two predecessors and no key to choose: decline");
		assertEquals(1, tally.bomCountAmbiguous(),
				"and THIS one is a genuine declined pairing, so it does belong in the counter -- the "
				+ "distinction from the no-BOM case above is the whole point of splitting them");
		assertEquals(0, tally.noBom(),
				"and it is NOT the benign no-BOM shape -- the two must stay distinguishable, because "
				+ "one is a healthy build and the other is the heuristic failing");
	}

	@Test
	public void theSeedWriteIsREFUSEDByTheDatabaseOnceTheRowIsScanned() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// A row that a real scan has already stamped, carrying its own result.
		UUID scanned = bom(org, 2, true);
		DependencyTrackIntegration wouldOverwrite = new DependencyTrackIntegration();
		wouldOverwrite.setVulnerabilityDetails(new LinkedList<>());
		wouldOverwrite.computeMetricsFromFacts();

		SharedArtifactService.SeedWriteResult written = sharedArtifactService.saveArtifactMetricsIfStillUnscanned(
				artifactRepository.findById(scanned).orElseThrow(), wouldOverwrite);

		assertEquals(SharedArtifactService.SeedWriteResult.RACE_LOST, written,
				"the UPDATE carries its own 'WHERE firstScanned IS NULL', so the database refuses. "
				+ "The in-Java guard reads the row earlier in the transaction and the synthetic fan-out "
				+ "writes on an independent tick with no shared lock, so a scan landing in that window "
				+ "would otherwise be clobbered -- showing a fixed CVE as still open, or hiding a new "
				+ "one, until something happened to re-scan");
		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(scanned).orElseThrow());
		assertEquals(2, after.getMetrics().getVulnerabilityDetails().size(),
				"and the real scan result is intact -- the losing write touched nothing");
	}

	@Test
	public void aReusedBomRowIsNotACandidateAndSaysNothing() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// prepareListofDeliverables re-uses the artifact row when the digest is unchanged, so a
		// no-change rebuild lands here for EVERY deliverable.
		UUID sharedBom = bom(org, 5, true);
		UUID prior = deliverable(org, "backend", null, sharedBom);
		UUID fresh = deliverable(org, "backend", null, sharedBom);

		CarryForwardTally tally = deliverableService.carryFindingsAcrossRebuild(
				List.of(prior), List.of(fresh), WhoUpdated.getAutoWhoUpdated(), UUID.randomUUID());

		assertEquals(0, tally.candidates(),
				"nothing was SWAPPED, so there was never a candidate to carry. Counting it in the "
				+ "denominator made a no-change rebuild report '0 of 1 seeded' with every decline "
				+ "counter at zero -- an unreconcilable line indistinguishable from a total pairing "
				+ "failure, emitted on every build that changed no digests");
		assertEquals(0, tally.seeded() + tally.unpaired() + tally.purlConflict()
				+ tally.bomCountAmbiguous() + tally.noBom(),
				"and every bucket is empty, so seeded plus the declines reconcile against candidates "
				+ "-- the invariant this arm asserts and previously broke");
	}

	@Test
	public void aMultiBomFlatArmReportsHOWMANYItDeclined() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(bom(org, 5, true)),
				List.of(bom(org, 0, false), bom(org, 0, false), bom(org, 0, false)),
				WhoUpdated.getAutoWhoUpdated(), CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		assertEquals(3, tally.candidates(),
				"three SUCCESSOR BOMs went unpaired, so the denominator is three. A hard-coded 1 "
				+ "understated the "
				+ "loss and made this arm's numbers non-comparable with the deliverable arm's, even "
				+ "though both emit the same field names into the same log population");
		assertEquals(3, tally.bomCountAmbiguous(), "and the decline counter matches the denominator");
	}

	@Test
	public void aRebuildCarriesFindingsButNOTTheVersionLineageChain() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID oldBom = bom(org, 5, true);
		UUID newBom = bom(org, 0, false);

		artifactService.carryFindingsAcrossArtifactSwap(List.of(oldBom), List.of(newBom),
				WhoUpdated.getAutoWhoUpdated(), CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());

		ArtifactData after = ArtifactData.dataFromRecord(artifactRepository.findById(newBom).orElseThrow());
		assertEquals(5, after.getMetrics().getVulnerabilityDetails().size(),
				"findings still carry -- that is the entire feature");
		assertEquals(0, after.getPreviousVersions().size(),
				"but the version-lineage chain does NOT. transferVersionHistory inherits the "
				+ "predecessor's whole chain and appends a snapshot, inside record_data JSONB, so on "
				+ "the rebuild path an N-build component reaches N-1 snapshots in one row and O(N^2) "
				+ "across rows -- re-parsed by every dataFromRecord on the metrics hot path. It ran "
				+ "BEFORE the findings guard too, so a component with a clean BOM paid on every build "
				+ "forever while carry-forward did nothing for it. The MANUAL replace keeps it: that "
				+ "is what it was built for and it is a rare user action");
	}

	/** An artifact that is NOT a BOM -- an attestation-shaped release-direct artifact. */
	private UUID nonBomArtifact(UUID org) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> rec = new HashMap<>();
		rec.put("org", org.toString());
		rec.put("type", "ATTESTATION");
		a.setRecordData(rec);
		return artifactRepository.save(a).getUuid();
	}

	private UUID bom(UUID org, int findings, boolean scanned) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> rec = new HashMap<>();
		rec.put("org", org.toString());
		rec.put("type", "BOM");
		a.setRecordData(rec);

		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			vulns.add(new VulnerabilityDto("pkg:maven/org.example/lib" + i + "@1.0.0", "CVE-" + i,
					VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
					null, null, ZonedDateTime.now(), null, null, null, null, null, false));
		}
		dti.setVulnerabilityDetails(vulns);
		dti.computeMetricsFromFacts();
		ZonedDateTime stamp = scanned ? ZonedDateTime.now().minusHours(1) : null;
		dti.setFirstScanned(stamp);
		dti.setLastScanned(stamp);
		a.setMetrics(Utils.OM.convertValue(dti, LinkedHashMap.class));
		return artifactRepository.save(a).getUuid();
	}

	private UUID deliverable(UUID org, String displayIdentifier, String purl, UUID bomUuid) {
		Deliverable d = new Deliverable();
		d.setUuid(UUID.randomUUID());
		d.setCreatedDate(ZonedDateTime.now());
		d.setLastUpdatedDate(ZonedDateTime.now());
		d.setSchemaVersion(0);
		Map<String, Object> rec = new HashMap<>();
		rec.put("org", org.toString());
		rec.put("displayIdentifier", displayIdentifier);
		rec.put("type", "CONTAINER");
		List<String> arts = new ArrayList<>();
		arts.add(bomUuid.toString());
		rec.put("artifacts", arts);
		if (null != purl) {
			Map<String, Object> ident = new HashMap<>();
			ident.put("idType", "PURL");
			ident.put("idValue", purl);
			List<Map<String, Object>> idents = new ArrayList<>();
			idents.add(ident);
			rec.put("identifiers", idents);
		}
		d.setRecordData(rec);
		return deliverableRepository.save(d).getUuid();
	}

}

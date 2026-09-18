/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Reproduces the 2026-08 prod synthetic-stall pattern at 2-bucket scale, then
 * pins the self-heal.
 *
 * <p>Prod sequence: the V79 backfill turned every CPE-less pkg:generic
 * component enrichment-terminal at once; buckets that were 100% generic
 * dropped out of the matchable population without a resubmit; retirement
 * cleared their local state and parked them PENDING with their DTrack
 * project refs intact — 19 buckets counted by the stall reporter forever
 * ("have not reached INGESTED in 2h"), with 19 orphaned DTrack projects
 * still carrying the evicted components.
 *
 * <p>Phase A reproduces exactly that stuck shape (DTrack unreachable, so
 * project deletion fails and retirement stays PENDING). Phase B removes the
 * integration and re-ticks: retirement converges (refs dropped, INGESTED)
 * and the stall predicate goes to zero — no manual SQL. Phase C empties the
 * whole population and pins that retirement still runs when submitOrg has
 * zero matchable components (the early-return used to skip it entirely).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SyntheticBucketRetirementTest {

	@Autowired private SyntheticSbomService syntheticSbomService;
	@Autowired private IntegrationService integrationService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SyntheticDtrackBucketRepository bucketRepository;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private SbomComponent saveComponent(UUID org, String canonical, int bucketIndex, boolean terminal) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonical);
		sc.setIdentities(List.of(new ComponentIdentity("purl", canonical)));
		sc.setEnrichedAt(ZonedDateTime.now());
		sc.setSyntheticBucketIndex(bucketIndex);
		if (terminal) markTerminal(sc);
		return sbomComponentRepository.save(sc);
	}

	private void markTerminal(SbomComponent sc) {
		// Same shape the V79 backfill writes (deliberately no enriched_at
		// guard there: enriched generic rows must leave the population too).
		sc.setFlowControl(new SbomComponentFlowControl(
				ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				SbomComponentService.TERMINAL_REASON_UNMATCHABLE_PURL_TYPE));
	}

	/** A healthy pre-backfill bucket: INGESTED, populated, with a DTrack project. */
	private SyntheticDtrackBucket saveLiveBucket(UUID org, int index) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(org);
		b.setBucketIndex(index);
		b.setDtrackProjectUuid(UUID.randomUUID());
		b.setContentHash("hash-" + index);
		Map<String, Object> refMap = new LinkedHashMap<>();
		refMap.put("pkg:generic/seed-" + index, List.of());
		b.setRefMap(refMap);
		b.setFindings(new LinkedHashMap<>());
		b.setIngestState(IngestState.INGESTED);
		b.setLastUpdatedDate(ZonedDateTime.now());
		return bucketRepository.save(b);
	}

	/**
	 * The stall reporter's stuck-bucket predicate, scoped to the given bucket
	 * indexes. Scoped because the survivor's bucket legitimately sits FAILED in
	 * this rig (DTrack is unreachable, so its resubmit cannot succeed) — in
	 * prod that resubmit succeeded and the stall count was exactly the retired
	 * buckets, which is what these assertions pin.
	 */
	private long stallCount(UUID org, ZonedDateTime cutoff, java.util.Set<Integer> indexes) {
		return bucketRepository.findByOrg(org).stream()
				.filter(b -> indexes.contains(b.getBucketIndex()))
				.filter(b -> IngestState.INGESTED != b.getIngestState())
				.filter(b -> b.getLastUpdatedDate() != null && b.getLastUpdatedDate().isBefore(cutoff))
				.count();
	}

	private void backdate(UUID org, int hours) {
		for (SyntheticDtrackBucket b : bucketRepository.findByOrg(org)) {
			b.setLastUpdatedDate(ZonedDateTime.now().minusHours(hours));
			bucketRepository.save(b);
		}
	}

	@Test
	public void prodStallPatternReproducesThenSelfHeals() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();

		// Pre-backfill estate: two buckets wholly made of (enriched, CPE-less)
		// generic rows, plus a live survivor bucket; all INGESTED with
		// DTrack projects. DTrack "runs" at an unreachable address.
		integrationService.createIntegration(CommonVariables.BASE_INTEGRATION_IDENTIFIER,
				org, IntegrationType.DEPENDENCYTRACK,
				URI.create("http://127.0.0.1:9"), "dummy-token", null, null, WU);
		saveComponent(org, "pkg:generic/gen-a-" + UUID.randomUUID() + ".h", 0, true);
		saveComponent(org, "pkg:generic/gen-b-" + UUID.randomUUID() + ".h", 0, true);
		saveComponent(org, "pkg:generic/gen-c-" + UUID.randomUUID() + ".h", 1, true);
		saveComponent(org, "pkg:generic/gen-d-" + UUID.randomUUID() + ".h", 1, true);
		SbomComponent survivor = saveComponent(org,
				"pkg:npm/survivor-" + UUID.randomUUID().toString().substring(0, 8) + "@1.0.0", 2, false);
		saveLiveBucket(org, 0);
		saveLiveBucket(org, 1);
		saveLiveBucket(org, 2);

		// ---- Phase A: the backfill has run; next submit tick. -------------
		syntheticSbomService.submitOrg(org, false);

		SyntheticDtrackBucket b0 = bucketRepository.findByOrgAndBucketIndex(org, 0).orElseThrow();
		SyntheticDtrackBucket b1 = bucketRepository.findByOrgAndBucketIndex(org, 1).orElseThrow();
		for (SyntheticDtrackBucket b : List.of(b0, b1)) {
			assertTrue(b.getRefMap() == null || b.getRefMap().isEmpty(),
					"retired bucket's coverage must be cleared");
			assertNull(b.getContentHash());
			assertEquals(IngestState.PENDING, b.getIngestState(),
					"DTrack deletion failed (unreachable) -> retirement in progress");
			assertNotNull(b.getDtrackProjectUuid(),
					"the project ref must survive so the deletion can be retried");
		}

		// Two hours pass; the stall reporter counts exactly the prod pattern.
		backdate(org, 3);
		assertEquals(2, stallCount(org, ZonedDateTime.now().minusHours(2), java.util.Set.of(0, 1)),
				"the stuck-bucket predicate must reproduce the prod stall count");
		// A retirement-in-progress must keep the scheduler's idle-gate open:
		// the delete retry lives in submitOrg, so a PENDING bucket that the
		// gate ignores would never heal on an otherwise-idle org.
		assertTrue(syntheticSbomService.hasPendingSyntheticWork(org),
				"PENDING retirement must count as pending synthetic work");

		// ---- Phase B: DTrack integration removed; next tick self-heals. ---
		integrationService.getIntegrationDataByOrgTypeIdentifier(org,
				IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER)
				.ifPresent(id -> integrationService.deleteIntegration(id.getUuid()));
		syntheticSbomService.submitOrg(org, false);

		b0 = bucketRepository.findByOrgAndBucketIndex(org, 0).orElseThrow();
		b1 = bucketRepository.findByOrgAndBucketIndex(org, 1).orElseThrow();
		for (SyntheticDtrackBucket b : List.of(b0, b1)) {
			assertEquals(IngestState.INGESTED, b.getIngestState(),
					"retirement must converge without manual intervention");
			assertNull(b.getDtrackProjectUuid(), "converged bucket holds no project ref");
		}
		backdate(org, 3);
		assertEquals(0, stallCount(org, ZonedDateTime.now().minusHours(2), java.util.Set.of(0, 1)),
				"a converged retirement must leave the stall counter at zero");

		// ---- Phase C: the whole population goes terminal. ------------------
		// submitOrg now sees ZERO matchable components; retirement must still
		// run (the old early-return skipped it and stranded every bucket).
		markTerminal(survivor);
		sbomComponentRepository.save(survivor);
		syntheticSbomService.submitOrg(org, false);

		SyntheticDtrackBucket b2 = bucketRepository.findByOrgAndBucketIndex(org, 2).orElseThrow();
		assertEquals(IngestState.INGESTED, b2.getIngestState(),
				"a fully-terminal population must still retire its buckets");
		assertNull(b2.getDtrackProjectUuid());
		backdate(org, 3);
		assertEquals(0, stallCount(org, ZonedDateTime.now().minusHours(2), java.util.Set.of(0, 1, 2)));
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository.CanonicalPurlRow;
import io.reliza.repositories.SbomComponentRepository.DueBucketRow;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.service.ComponentLatestVersionService.StampRow;
import io.reliza.ws.App;

/**
 * The latest-version refresh's native queries against the real schema (V92): which
 * buckets and unbucketed components are due, and the stamp. The service test mocks
 * these; this pins what they select.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ComponentLatestVersionProbeTest {

	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SyntheticDtrackBucketRepository bucketRepository;

	private SbomComponent saveComponent(UUID org, String canonical, Integer bucketIndex, boolean root) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonical);
		sc.setIdentities(List.of(new ComponentIdentity("purl", canonical)));
		sc.setSyntheticBucketIndex(bucketIndex);
		if (root) sc.setRecordData(Map.of("isRoot", true));
		return sbomComponentRepository.save(sc);
	}

	private void saveBucket(UUID org, int index, IngestState state, ZonedDateTime lastIngested) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(org);
		b.setBucketIndex(index);
		b.setDtrackProjectUuid(UUID.randomUUID());
		b.setContentHash("hash-" + index);
		b.setRefMap(new LinkedHashMap<>());
		b.setFindings(new LinkedHashMap<>());
		b.setIngestState(state);
		b.setLastIngested(lastIngested);
		bucketRepository.save(b);
	}

	private int stamp(UUID org, List<StampRow> rows) {
		return sbomComponentRepository.stampLatestVersions(org.toString(), Utils.OM.writeValueAsString(rows));
	}

	@Test
	void theProbesSelectWhatIsDueAndTheStampKeepsAKnownVersion() {
		UUID org = UUID.randomUUID();
		ZonedDateTime now = ZonedDateTime.now();
		ZonedDateTime settled = now.minusDays(2);
		// 0: fresh; 1: due; 2: due but failed; 3: due but ingested just now; 4: holds only a root
		saveBucket(org, 0, IngestState.INGESTED, settled);
		saveBucket(org, 1, IngestState.INGESTED, settled);
		saveBucket(org, 2, IngestState.FAILED, settled);
		saveBucket(org, 3, IngestState.INGESTED, now);
		saveBucket(org, 4, IngestState.INGESTED, settled);
		SbomComponent fresh = saveComponent(org, "pkg:npm/fresh@1.0.0", 0, false);
		saveComponent(org, "pkg:npm/due@1.0.0", 1, false);
		saveComponent(org, "pkg:npm/failed@1.0.0", 2, false);
		saveComponent(org, "pkg:npm/unsettled@1.0.0", 3, false);
		saveComponent(org, "pkg:generic/bucketed-root@1.0", 4, true);
		SbomComponent unbucketedDue = saveComponent(org, "pkg:npm/loose@2.0.0", null, false);
		saveComponent(org, "pkg:generic/app@1.0.0", null, true);
		saveComponent(org, "cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*", null, false);
		assertEquals(1, stamp(org, List.of(new StampRow(fresh.getUuid(), "1.2.0"))));

		ZonedDateTime cutoff = now.minusHours(24);
		List<Integer> dueBuckets = sbomComponentRepository.findLatestVersionDueBuckets(org.toString(), cutoff,
				now.minusHours(1), 50).stream().map(DueBucketRow::getBucketIndex).toList();
		assertEquals(List.of(1), dueBuckets, "fresh, failed, unsettled and root-only buckets are not due");

		List<UUID> dueUnbucketed = sbomComponentRepository.findLatestVersionDueUnbucketed(org.toString(), cutoff, 1000)
				.stream().map(CanonicalPurlRow::getUuid).toList();
		assertEquals(List.of(unbucketedDue.getUuid()), dueUnbucketed, "a root and a non-purl component are not due");

		// an answer without a version keeps the one known and still counts as checked
		assertEquals(2, stamp(org, List.of(new StampRow(fresh.getUuid(), null), new StampRow(unbucketedDue.getUuid(), "3.1.0"))));
		SbomComponent kept = sbomComponentRepository.findById(fresh.getUuid()).orElseThrow();
		assertEquals("1.2.0", kept.getLatestVersion());
		assertNotNull(kept.getLatestVersionChecked());
		assertEquals("3.1.0", sbomComponentRepository.findById(unbucketedDue.getUuid()).orElseThrow().getLatestVersion());
		assertEquals(List.of(), sbomComponentRepository.findLatestVersionDueUnbucketed(org.toString(), cutoff, 1000));

		// a stamp names components of its own org only
		UUID otherOrg = UUID.randomUUID();
		assertEquals(0, stamp(otherOrg, List.of(new StampRow(unbucketedDue.getUuid(), "9.9.9"))));
		assertEquals("3.1.0", sbomComponentRepository.findById(unbucketedDue.getUuid()).orElseThrow().getLatestVersion());

		// once the freshness has passed, checked rows are due again, never-checked ones first
		SbomComponent never = saveComponent(org, "pkg:npm/never@1.0.0", null, false);
		ZonedDateTime later = now.plusDays(1);
		assertEquals(List.of(never.getUuid(), unbucketedDue.getUuid()), sbomComponentRepository
				.findLatestVersionDueUnbucketed(org.toString(), later, 1000).stream().map(CanonicalPurlRow::getUuid).toList());
		assertEquals(List.of(never.getUuid()), sbomComponentRepository
				.findLatestVersionDueUnbucketed(org.toString(), later, 1).stream().map(CanonicalPurlRow::getUuid).toList());
		assertEquals(List.of(0, 1), sbomComponentRepository.findLatestVersionDueBuckets(org.toString(), later,
				now.minusHours(1), 50).stream().map(DueBucketRow::getBucketIndex).toList());
	}
}

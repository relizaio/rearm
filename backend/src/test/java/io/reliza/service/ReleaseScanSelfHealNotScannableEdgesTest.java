/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.Artifact;
import io.reliza.model.Organization;
import io.reliza.model.Release;

/**
 * TPF-126 mutation round: what is and is not a scan input of the release metrics compute. A BOM that
 * has no metrics at all is the freshest unscanned BOM there is and still holds its release pending; an
 * artifact that is not a BOM is no scan input and so does not hold its release.
 */
public class ReleaseScanSelfHealNotScannableEdgesTest extends ReleaseScanSelfHealTestBase {

	private void compute(UUID uuid) {
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(releaseRepository.findById(uuid).orElseThrow().getUuid());
	}

	private UUID saveArtifactOfType(UUID orgUuid, String type) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", type);
		a.setRecordData(recordData);
		return artifactRepository.save(a).getUuid();
	}

	@Test
	public void aBomWithNoMetricsAtAllHoldsTheReleasePending() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		UUID bom = saveUnscannedBomArtifact(org.getUuid());
		assertNull(artifactRepository.findById(bom).orElseThrow().getMetrics(), "the fixture: no metrics at all");
		attachArtifact(r.getUuid(), bom);

		compute(r.getUuid());

		assertNull(firstScanned(releaseRepository.findById(r.getUuid()).orElseThrow()),
				"an unscanned BOM, however new, is a scan to wait for");
	}

	@Test
	public void anArtifactThatIsNotABomIsNoScanInputAndTheReleaseAnchorsLikeNoBom() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifact(r.getUuid(), saveArtifactOfType(org.getUuid(), "OTHER"));

		compute(r.getUuid());

		assertNotNull(firstScanned(releaseRepository.findById(r.getUuid()).orElseThrow()),
				"nothing to scan: the no-BOM anchor");
	}
}

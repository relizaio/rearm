/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.UUID;

import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.repositories.ArtifactRepository;

/**
 * FDAW-2: a BOM artifact's first scan, stamped the way the scan-data ingestion leaves it (as
 * {@code ReleaseTriggerBomScanTest.markBomScanned} does): artifact metrics with no findings and
 * firstScanned / lastScanned at the given instant. A test then runs the production metrics compute
 * ({@code ReleaseService.computeReleaseMetrics(release, true)}) for the release and its products; the
 * release's stored metrics are never written directly.
 */
final class BomScanFixtures {

	private BomScanFixtures() {}

	static void markBomScanned(ArtifactRepository artifactRepository, UUID bom, ZonedDateTime at) {
		Artifact a = artifactRepository.findById(bom).orElseThrow();
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		dti.setVulnerabilityDetails(new LinkedList<>());
		dti.computeMetricsFromFacts();
		dti.setFirstScanned(at);
		dti.setLastScanned(at);
		a.setMetrics(Utils.OM.convertValue(dti, LinkedHashMap.class));
		artifactRepository.save(a);
	}
}

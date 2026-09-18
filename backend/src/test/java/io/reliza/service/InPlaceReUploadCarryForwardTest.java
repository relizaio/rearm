/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.WhoUpdated;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Covers the WIRING of the in-place carry-forward, which no unit test can reach.
 *
 * <p>{@code ArtifactFindingsCarryForwardTest} pins the decision function; this pins that
 * {@code createArtifact} actually calls it. Deleting the call site compiles cleanly and leaves every
 * unit test green, so without this the in-place half of the fix is unprotected -- which is exactly
 * the "a test can pass because nothing was written" trap this work has hit repeatedly.
 *
 * <p>The path matters because it is the COMMON one:
 * {@code ArtifactService.validateCycloneDxUpdate} only mints a new artifact uuid when the BOM's
 * {@code serialNumber} changes. The ordinary "new version of this BOM" flow keeps the serial and
 * bumps the version, and SPDX never reassigns at all -- both reuse the existing row and land here,
 * where a fresh {@code artifactDataFactory} product would otherwise take its empty metrics straight
 * over the column.
 */
@SpringBootTest(classes = {App.class})
public class InPlaceReUploadCarryForwardTest {

	@Autowired private ArtifactService artifactService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private MetricsAuditRepository metricsAuditRepository;

	private static ArtifactDto dtoFor(UUID org, UUID uuid) {
		return ArtifactDto.builder()
				.uuid(uuid)
				.org(org)
				.type(ArtifactType.BOM)
				.displayIdentifier("carry-forward-in-place")
				.build();
	}

	private static DependencyTrackIntegration scannedMetrics(int findings, UUID artifactUuid) {
		DependencyTrackIntegration m = new DependencyTrackIntegration();
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			vulns.add(new VulnerabilityDto("pkg:maven/org.example/lib" + i + "@1.0.0", "CVE-" + i,
					VulnerabilitySeverity.HIGH, Set.of(),
					Set.of(new FindingSourceDto(artifactUuid, null, null)), Set.of(),
					null, null, ZonedDateTime.now(), null, null, null, null, null, false));
		}
		m.setVulnerabilityDetails(vulns);
		m.computeMetricsFromFacts();
		ZonedDateTime scanned = ZonedDateTime.now().minusHours(1);
		m.setFirstScanned(scanned);
		m.setLastScanned(scanned);
		return m;
	}

	@Test
	public void aSameSerialReUploadKeepsTheFindingsInsteadOfWipingThem() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		WhoUpdated wu = WhoUpdated.getAutoWhoUpdated();

		// 1. The artifact exists and has been scanned.
		Artifact created = artifactService.createArtifact(dtoFor(org, UUID.randomUUID()), wu);
		sharedArtifactService.saveArtifactMetrics(created, scannedMetrics(7, created.getUuid()));

		ArtifactData beforeReUpload = ArtifactData.dataFromRecord(
				sharedArtifactService.getArtifact(created.getUuid()).orElseThrow());
		assertEquals(7, beforeReUpload.getMetrics().getVulnerabilityDetails().size(),
				"precondition: the scan landed and the findings are on the row");

		// 2. Re-upload against the SAME uuid -- what a same-serial bump or any SPDX upload does.
		artifactService.createArtifact(dtoFor(org, created.getUuid()), wu);

		ArtifactData afterReUpload = ArtifactData.dataFromRecord(
				sharedArtifactService.getArtifact(created.getUuid()).orElseThrow());

		assertEquals(7, afterReUpload.getMetrics().getVulnerabilityDetails().size(),
				"the findings must survive the in-place re-upload. Before this fix a fresh "
				+ "artifactDataFactory product wrote its empty metrics straight over the column, the "
				+ "release had nothing to merge, and it reported ZERO vulnerabilities until the new "
				+ "scan landed -- writing a phantom RESOLVED-then-APPEARED cycle into the timeline");
		assertNull(afterReUpload.getMetrics().getFirstScanned(),
				"but the NEW content has not been scanned, so the stamps clear and the release keeps "
				+ "reporting scan-pending. Only the findings surviving is new behaviour");
		assertNull(afterReUpload.getMetrics().getLastScanned());

		// And the revision the NATIVE writes advanced must have survived the JPA flush that follows
		// them. saveArtifactMetrics bumps metrics_revision through a @Modifying native query, which
		// does not refresh the persistence context, so the managed entity still holds the pre-bump
		// number and createArtifact's saveArtifact flush would write it straight back -- Artifact has
		// no @DynamicUpdate, so the flush is a full-column UPDATE. The audit row was already stamped
		// at the old revision, so the row would come to rest with revision == maxAuditRevision, which
		// is exactly the condition that fires the monitored "Duplicate metrics audit revision
		// detected" ERROR on the NEXT write to this artifact -- on the highest-traffic seam there is
		// (all SPDX, plus every same-serialNumber CycloneDX bump).
		Artifact reloaded = sharedArtifactService.getArtifact(created.getUuid()).orElseThrow();
		int maxAudit = metricsAuditRepository.findMaxRevision(
				MetricsEntityType.ARTIFACT.name(), created.getUuid());
		assertTrue(reloaded.getMetricsRevision() > maxAudit,
				"metrics_revision (" + reloaded.getMetricsRevision() + ") must stay AHEAD of the "
				+ "highest audit revision (" + maxAudit + "). Equal means the flush reverted the "
				+ "native bump and the next write to this artifact reports a duplicate revision");
	}

	@Test
	public void aFirstUploadIsUnaffectedAndStaysAGenuineFirstScan() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		WhoUpdated wu = WhoUpdated.getAutoWhoUpdated();

		Artifact created = artifactService.createArtifact(dtoFor(org, UUID.randomUUID()), wu);
		ArtifactData ad = ArtifactData.dataFromRecord(
				sharedArtifactService.getArtifact(created.getUuid()).orElseThrow());

		assertEquals(List.of(), ad.getMetrics().getVulnerabilityDetails(),
				"nothing to carry on a first upload -- the carry-forward must not invent findings, and "
				+ "must leave a genuine first scan looking like one");
		assertNull(ad.getMetrics().getFirstScanned());
	}
}

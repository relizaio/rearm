/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.Deliverable;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.Variant;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.repositories.VariantRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Regression tests for the three artifact-driven metrics-finder queries on
 * {@link ReleaseRepository}:
 *
 * <ul>
 *   <li>{@code findReleasesForMetricsComputeByArtifactDirect}</li>
 *   <li>{@code findReleasesForMetricsComputeBySce}</li>
 *   <li>{@code findReleasesForMetricsComputeByOutboundDeliverables}</li>
 * </ul>
 *
 * Pin the per-release comparison: a release whose own {@code metrics.lastScanned} is older
 * than at least one of its (directly-attached, SCE-linked, or outbound-deliverable-linked)
 * artifacts MUST be picked up. Pre-fix versions used a global {@code MAX(release.lastScanned)}
 * cutoff that stranded releases as soon as any other release was recomputed (see SQL
 * comments in {@code VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_ARTIFACT_DIRECT}
 * for the full history).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseMetricsFinderQueryTest {

	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;
	@Autowired private DeliverableRepository deliverableRepository;
	@Autowired private VariantRepository variantRepository;
	@Autowired private TestInitializer testInitializer;

	// Sentinel epochs chosen well above any plausible real release.lastScanned so the
	// regression assertion holds even if the test DB has unrelated rows with high values.
	// 4_000_000_000.0 ≈ year 2096; 5_000_000_000.0 ≈ year 2128.
	private static final double OLD_EPOCH = 4_000_000_000.0;
	private static final double NEW_EPOCH = 5_000_000_000.0;

	@Test
	public void byArtifactDirect_picksUpReleaseWithFresherArtifact() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		Release stuckRelease = saveReleaseWithDirectArtifact(org.getUuid(), OLD_EPOCH, freshArt.getUuid());

		Set<UUID> picked = uuidsOf(releaseRepository.findReleasesForMetricsComputeByArtifactDirect());

		assertTrue(picked.contains(stuckRelease.getUuid()),
				"Release with art.lastScanned > rel.lastScanned must be picked up "
				+ "(per-release comparison; pre-fix global-cutoff filter would skip it whenever "
				+ "any other release had been recomputed since)");

		// Negative case: bump release.lastScanned past the artifact's; finder must drop it.
		updateReleaseLastScanned(stuckRelease.getUuid(), NEW_EPOCH + 1.0);
		Set<UUID> pickedAfter = uuidsOf(releaseRepository.findReleasesForMetricsComputeByArtifactDirect());
		assertFalse(pickedAfter.contains(stuckRelease.getUuid()),
				"Release with rel.lastScanned > art.lastScanned must not be picked up");
	}

	@Test
	public void bySce_picksUpReleaseLinkedViaSourceCodeEntry() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		SourceCodeEntry sce = saveSceWithArtifact(org.getUuid(), freshArt.getUuid());
		Release stuckRelease = saveReleaseWithSourceCodeEntry(org.getUuid(), OLD_EPOCH, sce.getUuid());

		Set<UUID> picked = uuidsOf(releaseRepository.findReleasesForMetricsComputeBySce());

		assertTrue(picked.contains(stuckRelease.getUuid()),
				"Release whose SCE-linked artifact is fresher than rel.lastScanned must be picked up");
	}

	@Test
	public void byOutboundDeliverables_picksUpReleaseLinkedViaVariant() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		Deliverable deliverable = saveDeliverableWithArtifact(org.getUuid(), freshArt.getUuid());
		Release stuckRelease = saveReleaseWithLastScanned(org.getUuid(), OLD_EPOCH);
		saveVariantLinkingReleaseToDeliverable(org.getUuid(), stuckRelease.getUuid(), deliverable.getUuid());

		Set<UUID> picked = uuidsOf(releaseRepository.findReleasesForMetricsComputeByOutboundDeliverables());

		assertTrue(picked.contains(stuckRelease.getUuid()),
				"Release linked via variant.outboundDeliverables → deliverable.artifacts to a "
				+ "fresher artifact must be picked up");
	}

	// ---- helpers (intentionally local; cross-cutting test fixtures aren't worth a refactor yet) ----

	private static Set<UUID> uuidsOf(List<Release> releases) {
		return releases.stream().map(Release::getUuid).collect(Collectors.toSet());
	}

	private Artifact saveArtifactWithLastScanned(UUID orgUuid, double lastScanned) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		a.setMetrics(metrics);
		return artifactRepository.save(a);
	}

	private Release saveReleaseWithLastScanned(UUID orgUuid, double lastScanned) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("artifacts", new ArrayList<String>());
		r.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		r.setMetrics(metrics);
		return releaseRepository.save(r);
	}

	private Release saveReleaseWithDirectArtifact(UUID orgUuid, double lastScanned, UUID artifactUuid) {
		Release r = saveReleaseWithLastScanned(orgUuid, lastScanned);
		Map<String, Object> recordData = r.getRecordData();
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}

	private Release saveReleaseWithSourceCodeEntry(UUID orgUuid, double lastScanned, UUID sceUuid) {
		Release r = saveReleaseWithLastScanned(orgUuid, lastScanned);
		Map<String, Object> recordData = r.getRecordData();
		recordData.put("sourceCodeEntry", sceUuid.toString());
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}

	private void updateReleaseLastScanned(UUID releaseUuid, double lastScanned) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> metrics = r.getMetrics() != null ? r.getMetrics() : new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		r.setMetrics(metrics);
		releaseRepository.save(r);
	}

	private SourceCodeEntry saveSceWithArtifact(UUID orgUuid, UUID artifactUuid) {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		List<Map<String, String>> artifacts = new ArrayList<>();
		Map<String, String> entry = new HashMap<>();
		entry.put("artifactUuid", artifactUuid.toString());
		artifacts.add(entry);
		recordData.put("artifacts", artifacts);
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce);
	}

	private Deliverable saveDeliverableWithArtifact(UUID orgUuid, UUID artifactUuid) {
		Deliverable d = new Deliverable();
		d.setUuid(UUID.randomUUID());
		d.setCreatedDate(ZonedDateTime.now());
		d.setLastUpdatedDate(ZonedDateTime.now());
		d.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		d.setRecordData(recordData);
		return deliverableRepository.save(d);
	}

	private Variant saveVariantLinkingReleaseToDeliverable(UUID orgUuid, UUID releaseUuid, UUID deliverableUuid) {
		Variant v = new Variant();
		v.setUuid(UUID.randomUUID());
		v.setCreatedDate(ZonedDateTime.now());
		v.setLastUpdatedDate(ZonedDateTime.now());
		v.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("release", releaseUuid.toString());
		List<String> outboundDeliverables = new ArrayList<>();
		outboundDeliverables.add(deliverableUuid.toString());
		recordData.put("outboundDeliverables", outboundDeliverables);
		v.setRecordData(recordData);
		return variantRepository.save(v);
	}
}

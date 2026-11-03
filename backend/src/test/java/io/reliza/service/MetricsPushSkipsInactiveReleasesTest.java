/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.Artifact;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Deliverable;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.Variant;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.MetricsVulnerabilityTestBase;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.repositories.VariantRepository;
import io.reliza.ws.App;

/**
 * The metrics pushes do not re-queue releases that never need a metrics refresh: a release
 * whose branch is ARCHIVED, whose component is ARCHIVED, or whose lifecycle is PENDING, CANCELLED
 * or REJECTED (VariableQueries.METRICS_PUSH_ELIGIBLE). In production one child change touched
 * about 17,600 product releases of one product, one per CI build, in five minutes.
 *
 * <p>Every case seeds the same six states plus an active control, settles them all (lastScanned in
 * the far future, no fence, so each push's no-op guard lets them through), runs one push and reads
 * which rows' last_updated_date moved. Only the active row may move. The states are written with
 * the enum names, so a literal in the SQL that drifted from its enum fails here.
 */
@SpringBootTest(classes = {App.class})
class MetricsPushSkipsInactiveReleasesTest extends MetricsVulnerabilityTestBase {

	/** Far-future lastScanned (year 2128): a settled row the no-op guard still lets a push touch. */
	private static final double SETTLED_EPOCH = 5_000_000_000.0;

	private static final String ACTIVE = "active ASSEMBLED";

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired private ReleaseMetricsTouchService releaseMetricsTouchService;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;
	@Autowired private DeliverableRepository deliverableRepository;
	@Autowired private VariantRepository variantRepository;

	/**
	 * Seven releases of {@code type} components in one org, keyed by state: an active ASSEMBLED
	 * one, one on an archived branch, one of an archived component, and one each REJECTED, PENDING
	 * and CANCELLED (on the active component's branch).
	 */
	private Map<String, UUID> releasesInEveryState(UUID org, ComponentType type) throws Exception {
		ComponentAndBranch active = component(org, type);
		ComponentAndBranch archivedBranch = component(org, type);
		ComponentAndBranch archivedComponent = component(org, type);
		jdbc.update("UPDATE rearm.branches SET record_data = record_data || jsonb_build_object('status', ?) WHERE uuid = ?",
				StatusEnum.ARCHIVED.name(), archivedBranch.branch().getUuid());
		jdbc.update("UPDATE rearm.components SET record_data = record_data || jsonb_build_object('status', ?) WHERE uuid = ?",
				StatusEnum.ARCHIVED.name(), archivedComponent.component().getUuid());
		Map<String, UUID> releases = new LinkedHashMap<>();
		releases.put(ACTIVE, release(org, active, List.of(), List.of()));
		releases.put("archived branch", release(org, archivedBranch, List.of(), List.of()));
		releases.put("archived component", release(org, archivedComponent, List.of(), List.of()));
		for (ReleaseLifecycle lc : List.of(ReleaseLifecycle.REJECTED, ReleaseLifecycle.PENDING, ReleaseLifecycle.CANCELLED)) {
			UUID r = release(org, active, List.of(), List.of());
			mergeReleaseRecord(r, "{\"lifecycle\": \"" + lc.name() + "\"}");
			releases.put(lc.name(), r);
		}
		return releases;
	}

	/** Out of the BY_UPDATE pool, unfenced: every push's no-op guard lets the row through. */
	private void settle(UUID release) {
		jdbc.update("UPDATE rearm.releases SET metrics = coalesce(metrics, '{}'::jsonb) || jsonb_build_object('lastScanned', ?::float),"
				+ " flow_control = NULL WHERE uuid = ?", SETTLED_EPOCH, release);
	}

	private Instant lastUpdated(UUID release) {
		return releaseRepository.findById(release).orElseThrow().getLastUpdatedDate().toInstant();
	}

	/** Settles every release, runs {@code push}, and asserts that it moved the active one only. */
	private void assertOnlyActiveIsTouched(Map<String, UUID> releases, Runnable push) {
		releases.values().forEach(this::settle);
		Map<String, Instant> before = new HashMap<>();
		releases.forEach((state, r) -> before.put(state, lastUpdated(r)));
		push.run();
		List<Executable> checks = new ArrayList<>();
		releases.forEach((state, r) -> checks.add(() -> {
			if (ACTIVE.equals(state)) {
				assertTrue(lastUpdated(r).isAfter(before.get(state)), "the " + state + " release is re-queued");
			} else {
				assertEquals(before.get(state), lastUpdated(r), "the " + state + " release is not re-queued");
			}
		}));
		assertAll(checks);
	}

	/** Writes each release's record_data through {@code wire}. */
	private void wireAll(Map<String, UUID> releases, Consumer<UUID> wire) {
		releases.values().forEach(wire);
	}

	// ---- markContainingProductsStale ----

	@Test
	void aChildMetricsChangeRequeuesOnlyTheActiveProduct() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID child = release(org, component(org, ComponentType.COMPONENT), List.of(), List.of());
		Map<String, UUID> products = releasesInEveryState(org, ComponentType.PRODUCT);
		wireAll(products, p -> mergeReleaseRecord(p, "{\"parentReleases\": [{\"release\": \"" + child + "\"}]}"));
		// A finding on a BOM attached to the child: its next compute changes its metrics, which is
		// what pushes the products that contain it.
		UUID bom = artifact(org, List.of(vuln("pkg:npm/push-skip@1.0.0", "CVE-2026-90001")));
		mergeReleaseRecord(child, "{\"artifacts\": [\"" + bom + "\"]}");

		assertOnlyActiveIsTouched(products, () -> assertTrue(
				releaseMetricsComputeService.computeReleaseMetricsOnRescan(child),
				"sanity: the child's metrics changed"));
	}

	@Test
	void theProductUuidReadSelectsOnlyTheActiveProduct() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID child = release(org, component(org, ComponentType.COMPONENT), List.of(), List.of());
		Map<String, UUID> products = releasesInEveryState(org, ComponentType.PRODUCT);
		wireAll(products, p -> mergeReleaseRecord(p, "{\"parentReleases\": [{\"release\": \"" + child + "\"}]}"));

		assertEquals(List.of(products.get(ACTIVE)),
				releaseRepository.findProductUuidsToRequeueByRelease(org.toString(), child.toString()));
		assertEquals(products.size(), releaseRepository.findProductsByRelease(org.toString(), child.toString()).size(),
				"the entity read is unfiltered: only the push read skips");
	}

	// ---- the scan pushes (SharedArtifactService.touchReleasesCarrying) ----

	@Test
	void theDirectArmRequeuesOnlyTheActiveRelease() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Map<String, UUID> releases = releasesInEveryState(org, ComponentType.COMPONENT);
		UUID art = bareArtifact(org);
		wireAll(releases, r -> mergeReleaseRecord(r, "{\"artifacts\": [\"" + art + "\"]}"));

		assertOnlyActiveIsTouched(releases,
				() -> releaseRepository.touchReleasesByScannedArtifactDirect(art.toString()));
	}

	@Test
	void theSceArmRequeuesOnlyTheActiveRelease() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Map<String, UUID> releases = releasesInEveryState(org, ComponentType.COMPONENT);
		UUID art = bareArtifact(org);
		UUID sce = sceCarrying(org, art);
		wireAll(releases, r -> mergeReleaseRecord(r, "{\"sourceCodeEntry\": \"" + sce + "\"}"));

		assertOnlyActiveIsTouched(releases,
				() -> releaseRepository.touchReleasesByScannedSceArtifact(art.toString()));
	}

	@Test
	void theDeliverableArmRequeuesOnlyTheActiveRelease() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Map<String, UUID> releases = releasesInEveryState(org, ComponentType.COMPONENT);
		UUID art = bareArtifact(org);
		UUID del = deliverableCarrying(org, art);
		wireAll(releases, r -> variantLinking(org, r, del));

		assertOnlyActiveIsTouched(releases,
				() -> releaseRepository.touchReleasesByScannedDeliverableArtifact(art.toString()));
	}

	// ---- the attach-time pushes (ReleaseMetricsTouchService) ----

	@Test
	void theAttachTimeSceTouchRequeuesOnlyTheActiveRelease() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Map<String, UUID> releases = releasesInEveryState(org, ComponentType.COMPONENT);
		UUID art = bareArtifact(org);
		UUID sce = sceCarrying(org, art);
		wireAll(releases, r -> mergeReleaseRecord(r, "{\"sourceCodeEntry\": \"" + sce + "\"}"));

		assertOnlyActiveIsTouched(releases, () -> releaseMetricsTouchService.touchBySceArtifact(art.toString()));
	}

	@Test
	void theAttachTimeDeliverableTouchRequeuesOnlyTheActiveRelease() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Map<String, UUID> releases = releasesInEveryState(org, ComponentType.COMPONENT);
		UUID art = bareArtifact(org);
		UUID del = deliverableCarrying(org, art);
		wireAll(releases, r -> variantLinking(org, r, del));

		assertOnlyActiveIsTouched(releases, () -> releaseMetricsTouchService.touchByDeliverableArtifact(art.toString()));
	}

	/** A release with no branch, component or lifecycle in its record (legacy rows) is pushed as before. */
	@Test
	void aReleaseWithoutBranchComponentOrLifecycleIsStillRequeued() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID art = bareArtifact(org);
		UUID r = release(org, component(org, ComponentType.COMPONENT), List.of(), List.of());
		jdbc.update("UPDATE rearm.releases SET record_data = record_data - 'branch' - 'component' - 'lifecycle' WHERE uuid = ?", r);
		mergeReleaseRecord(r, "{\"artifacts\": [\"" + art + "\"]}");

		assertOnlyActiveIsTouched(Map.of(ACTIVE, r),
				() -> releaseRepository.touchReleasesByScannedArtifactDirect(art.toString()));
	}

	// ---- fixtures ----

	/** An artifact row with no metrics: the pushes only match its uuid. */
	private UUID bareArtifact(UUID org) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		return artifactRepository.save(a).getUuid();
	}

	/** An SCE carrying {@code art} with no componentUuid tag, so every release on it matches. */
	private UUID sceCarrying(UUID org, UUID art) {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		recordData.put("artifacts", List.of(Map.of("artifactUuid", art.toString())));
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce).getUuid();
	}

	private UUID deliverableCarrying(UUID org, UUID art) {
		Deliverable d = new Deliverable();
		d.setUuid(UUID.randomUUID());
		d.setCreatedDate(ZonedDateTime.now());
		d.setLastUpdatedDate(ZonedDateTime.now());
		d.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		recordData.put("artifacts", List.of(art.toString()));
		d.setRecordData(recordData);
		return deliverableRepository.save(d).getUuid();
	}

	private void variantLinking(UUID org, UUID release, UUID deliverable) {
		Variant v = new Variant();
		v.setUuid(UUID.randomUUID());
		v.setCreatedDate(ZonedDateTime.now());
		v.setLastUpdatedDate(ZonedDateTime.now());
		v.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		recordData.put("release", release.toString());
		recordData.put("outboundDeliverables", List.of(deliverable.toString()));
		v.setRecordData(recordData);
		variantRepository.save(v);
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.repositories.ReleaseRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * TPF-125 / TPF-126 architecture 6.2: the not-scannable marker's writers on SharedArtifactService. The
 * in-place stamp and clear with their release touches, the audited wipe of findings under the marker,
 * the clears inside updateArtifactDti, the carry-forward decline, and the marker's JSON shape.
 */
class SharedArtifactServiceNotScannableTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final ZonedDateTime AT = ZonedDateTime.of(2026, 10, 29, 1, 2, 3, 250_000_000, ZoneOffset.UTC);

	private ArtifactRepository repo;
	private ReleaseRepository releaseRepository;
	private MetricsAuditRepository metricsAuditRepository;
	private SharedArtifactService svc;

	@BeforeEach
	void wire() {
		repo = mock(ArtifactRepository.class);
		releaseRepository = mock(ReleaseRepository.class);
		metricsAuditRepository = mock(MetricsAuditRepository.class);
		when(metricsAuditRepository.findMaxRevision(any(), any())).thenReturn(-1);
		svc = new SharedArtifactService(repo, "", "");
		ReflectionTestUtils.setField(svc, "releaseRepository", releaseRepository);
		ReflectionTestUtils.setField(svc, "metricsAuditRepository", metricsAuditRepository);
	}

	private static ArtifactNotScannable marker(NoMatchableComponentsReason reason) {
		return new ArtifactNotScannable(reason, AT, 2, 0, 0, 2);
	}

	private static Artifact artifactWithMetrics(DependencyTrackIntegration dti) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now(ZoneOffset.UTC).minusHours(1));
		Map<String, Object> rd = new HashMap<>();
		rd.put("uuid", a.getUuid().toString());
		rd.put("org", ORG.toString());
		rd.put("type", "BOM");
		a.setRecordData(rd);
		if (dti != null) a.setMetrics(Utils.OM.convertValue(dti, new TypeReference<Map<String, Object>>() {}));
		return a;
	}

	private static LinkedList<VulnerabilityDto> vulns(int n) {
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < n; i++) {
			vulns.add(new VulnerabilityDto("pkg:npm/lib" + i + "@1.0.0", "CVE-2026-" + i, VulnerabilitySeverity.HIGH,
					Set.of(), Set.of(), Set.of(), null, null, null, null, null, null, null, null, false));
		}
		return vulns;
	}

	private static LinkedList<ViolationDto> violations(int n) {
		LinkedList<ViolationDto> out = new LinkedList<>();
		for (int i = 0; i < n; i++) {
			out.add(new ViolationDto("pkg:npm/viol" + i + "@1.0.0", ViolationType.LICENSE, null, null, Set.of(),
					null, null, null));
		}
		return out;
	}

	private static LinkedList<WeaknessDto> weaknesses(int n) {
		LinkedList<WeaknessDto> out = new LinkedList<>();
		for (int i = 0; i < n; i++) {
			out.add(new WeaknessDto("CWE-79", "rule-" + i, "src/a.js", "fp-" + i, VulnerabilitySeverity.MEDIUM,
					Set.of(), null, null, null));
		}
		return out;
	}

	private void verifyTouchedOnce(UUID uuid) {
		verify(releaseRepository, times(1)).touchReleasesByScannedDeliverableArtifact(uuid.toString());
		verify(releaseRepository, times(1)).touchReleasesByScannedSceArtifact(uuid.toString());
		verify(releaseRepository, times(1)).touchReleasesByScannedArtifactDirect(uuid.toString());
	}

	private void verifyNoTouch() {
		verify(releaseRepository, never()).touchReleasesByScannedDeliverableArtifact(anyString());
		verify(releaseRepository, never()).touchReleasesByScannedSceArtifact(anyString());
		verify(releaseRepository, never()).touchReleasesByScannedArtifactDirect(anyString());
	}

	private JsonNode savedMetrics(UUID uuid) {
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(repo, times(1)).updateMetrics(eq(uuid), json.capture());
		return Utils.OM.readTree(json.getValue());
	}

	private static boolean absentOrNull(JsonNode node, String field) {
		return !node.has(field) || node.get(field).isNull();
	}

	// ---- rows 1-2: case (a), the in-place stamp ----

	@Test
	void caseA_stampsInPlaceAndTouchesTheCarryingReleases() {
		Artifact a = artifactWithMetrics(new DependencyTrackIntegration());
		when(repo.stampNotScannable(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyDouble()))
				.thenReturn(1);

		assertEquals(SharedArtifactService.NotScannableWrite.STAMPED,
				svc.markArtifactNotScannable(a, marker(NoMatchableComponentsReason.NO_PURL_OR_CPE)));

		verify(repo, times(1)).stampNotScannable(a.getUuid(), "NO_PURL_OR_CPE", 2, 0, 0, 2,
				Utils.toFractionalEpochSecond(AT));
		verifyTouchedOnce(a.getUuid());
		verify(repo, never()).updateMetrics(any(), any());
		verify(repo, never()).advanceLastScannedOnly(any(), anyDouble());
		verify(metricsAuditRepository, never()).save(any());
	}

	@Test
	void caseA_unchangedMarkerWritesNothingAndTouchesNothing() {
		Artifact a = artifactWithMetrics(new DependencyTrackIntegration());
		when(repo.stampNotScannable(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyDouble()))
				.thenReturn(0);

		assertEquals(SharedArtifactService.NotScannableWrite.UNCHANGED,
				svc.markArtifactNotScannable(a, marker(NoMatchableComponentsReason.NO_PURL_OR_CPE)));

		verifyNoTouch();
		verify(metricsAuditRepository, never()).save(any());
		verify(repo, never()).updateMetrics(any(), any());
	}

	// ---- row 3: case (b), carried findings wiped through the audited save ----

	@Test
	void caseB_wipesCarriedFindingsThroughTheAuditedSave() {
		DependencyTrackIntegration carried = new DependencyTrackIntegration();
		carried.setVulnerabilityDetails(vulns(2));
		carried.setViolationDetails(violations(1));
		carried.setWeaknessDetails(weaknesses(1));
		Artifact a = artifactWithMetrics(carried);

		assertEquals(SharedArtifactService.NotScannableWrite.FINDINGS_WIPED,
				svc.markArtifactNotScannable(a, marker(NoMatchableComponentsReason.NO_PURL_OR_CPE)));

		JsonNode saved = savedMetrics(a.getUuid());
		assertEquals("NO_PURL_OR_CPE", saved.get("notScannable").get("reason").asString());
		assertTrue(saved.get("notScannable").get("at").isNumber(), "at is written as epoch seconds");
		assertEquals(0, saved.get("vulnerabilityDetails").size());
		assertTrue(saved.get("vulnerabilityDetails").isArray());
		assertEquals(0, saved.get("violationDetails").size());
		assertTrue(saved.get("violationDetails").isArray());
		assertEquals(1, saved.get("weaknessDetails").size(), "weakness rows are SARIF, left alone");
		assertTrue(absentOrNull(saved, "firstScanned"), "this is not a scan: " + saved);
		assertTrue(absentOrNull(saved, "lastScanned"), "this is not a scan: " + saved);
		verify(metricsAuditRepository, times(1)).save(any());
		verifyTouchedOnce(a.getUuid());
		verify(repo, never()).stampNotScannable(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(),
				anyDouble());
	}

	@Test
	void caseB_afterARealScanKeepsBothStamps() {
		DependencyTrackIntegration scanned = new DependencyTrackIntegration();
		scanned.setVulnerabilityDetails(vulns(2));
		scanned.setViolationDetails(violations(1));
		scanned.setWeaknessDetails(weaknesses(1));
		ZonedDateTime stamp = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		scanned.setFirstScanned(stamp);
		scanned.setLastScanned(stamp);
		Artifact a = artifactWithMetrics(scanned);

		assertEquals(SharedArtifactService.NotScannableWrite.FINDINGS_WIPED,
				svc.markArtifactNotScannable(a, marker(NoMatchableComponentsReason.ONLY_ROOT)));

		JsonNode saved = savedMetrics(a.getUuid());
		assertEquals("ONLY_ROOT", saved.get("notScannable").get("reason").asString());
		assertEquals(0, saved.get("vulnerabilityDetails").size());
		assertEquals(0, saved.get("violationDetails").size());
		assertEquals(1, saved.get("weaknessDetails").size());
		assertEquals(Utils.toFractionalEpochSecond(stamp), saved.get("firstScanned").asDouble(), 1e-6);
		assertEquals(Utils.toFractionalEpochSecond(stamp), saved.get("lastScanned").asDouble(), 1e-6);
	}

	// ---- rows 4-5: updateArtifactDti clears the marker in both branches ----

	@Test
	void idempotentBranchClearsTheMarkerWithoutATouch() {
		DependencyTrackIntegration existing = new DependencyTrackIntegration();
		existing.setVulnerabilityDetails(vulns(1));
		ZonedDateTime stamp = ZonedDateTime.now(ZoneOffset.UTC).minusDays(1);
		existing.setFirstScanned(stamp);
		existing.setLastScanned(stamp);
		existing.setNotScannable(marker(NoMatchableComponentsReason.NO_PURL_OR_CPE));
		Artifact a = artifactWithMetrics(existing);
		DependencyTrackIntegration incoming = new DependencyTrackIntegration();
		incoming.setVulnerabilityDetails(vulns(1));
		incoming.setLastScanned(ZonedDateTime.now(ZoneOffset.UTC));

		svc.updateArtifactDti(a, incoming, WhoUpdated.getAutoWhoUpdated());

		InOrder order = inOrder(repo);
		order.verify(repo, times(1)).advanceLastScannedOnly(eq(a.getUuid()), anyDouble());
		order.verify(repo, times(1)).clearNotScannable(a.getUuid());
		verify(repo, never()).updateMetrics(any(), any());
		verifyNoTouch();
	}

	@Test
	void idempotentBranchWithoutAMarkerNeverClears() {
		DependencyTrackIntegration existing = new DependencyTrackIntegration();
		existing.setVulnerabilityDetails(vulns(1));
		ZonedDateTime stamp = ZonedDateTime.now(ZoneOffset.UTC).minusDays(1);
		existing.setFirstScanned(stamp);
		existing.setLastScanned(stamp);
		Artifact a = artifactWithMetrics(existing);
		DependencyTrackIntegration incoming = new DependencyTrackIntegration();
		incoming.setVulnerabilityDetails(vulns(1));
		incoming.setLastScanned(ZonedDateTime.now(ZoneOffset.UTC));

		svc.updateArtifactDti(a, incoming, WhoUpdated.getAutoWhoUpdated());

		verify(repo, times(1)).advanceLastScannedOnly(eq(a.getUuid()), anyDouble());
		verify(repo, never()).clearNotScannable(any());
	}

	@Test
	void mergeBranchDropsTheMarkerFromTheWholeSave() {
		DependencyTrackIntegration existing = new DependencyTrackIntegration();
		existing.setNotScannable(marker(NoMatchableComponentsReason.NO_PURL_OR_CPE));
		Artifact a = artifactWithMetrics(existing);
		assertNotNull(a.getMetrics().get("notScannable"), "fixture: the stored row carries the marker");
		DependencyTrackIntegration incoming = new DependencyTrackIntegration();
		incoming.setVulnerabilityDetails(vulns(1));
		ZonedDateTime scanned = ZonedDateTime.now(ZoneOffset.UTC);
		incoming.setLastScanned(scanned);

		svc.updateArtifactDti(a, incoming, WhoUpdated.getAutoWhoUpdated());

		JsonNode saved = savedMetrics(a.getUuid());
		assertFalse(saved.has("notScannable"), "the scan's whole save omits the key: " + saved);
		assertEquals(Utils.toFractionalEpochSecond(scanned), saved.get("firstScanned").asDouble(), 1e-6,
				"firstScanned is set from lastScanned");
	}

	// ---- rows 6-7: the carry-forward clone and fromReleaseMetricsDto never carry a marker ----

	@Test
	void inheritFindingsNeverCarriesOrSeedsUnderAMarker() {
		DependencyTrackIntegration predecessor = new DependencyTrackIntegration();
		predecessor.setVulnerabilityDetails(vulns(3));
		predecessor.setNotScannable(marker(NoMatchableComponentsReason.ALL_TERMINAL));

		DependencyTrackIntegration inherited = SharedArtifactService.inheritFindingsFromPredecessor(
				new DependencyTrackIntegration(), predecessor);
		assertNotNull(inherited);
		assertNull(inherited.getNotScannable(), "the clone is the successor's, never the predecessor's marker");
		assertEquals(3, inherited.getVulnerabilityDetails().size());

		DependencyTrackIntegration markedSuccessor = new DependencyTrackIntegration();
		markedSuccessor.setNotScannable(marker(NoMatchableComponentsReason.NO_PURL_OR_CPE));
		DependencyTrackIntegration findings = new DependencyTrackIntegration();
		findings.setVulnerabilityDetails(vulns(3));
		assertNull(SharedArtifactService.inheritFindingsFromPredecessor(markedSuccessor, findings),
				"a marked successor is never seeded");
	}

	@Test
	void fromReleaseMetricsDtoLeavesTheMarkerNull() {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(vulns(1));
		assertNull(DependencyTrackIntegration.fromReleaseMetricsDto(rmd).getNotScannable());
	}

	// ---- row 8: the JSON shape ----

	@Test
	void markerJsonShapeRoundTrips() throws Exception {
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		dti.setNotScannable(new ArtifactNotScannable(NoMatchableComponentsReason.ALL_TERMINAL, AT, 3, 1, 2, 0));
		String json = Utils.OM.writeValueAsString(dti);
		JsonNode node = Utils.OM.readTree(json).get("notScannable");
		assertTrue(node.get("at").isNumber());
		assertEquals(Utils.toFractionalEpochSecond(AT), node.get("at").asDouble(), 1e-6);
		assertEquals("ALL_TERMINAL", node.get("reason").asString());
		assertEquals(3, node.get("components").asInt());
		assertEquals(1, node.get("root").asInt());
		assertEquals(2, node.get("terminal").asInt());
		assertEquals(0, node.get("unmatchable").asInt());

		DependencyTrackIntegration back = Utils.OM.readValue(json, DependencyTrackIntegration.class);
		assertEquals(NoMatchableComponentsReason.ALL_TERMINAL, back.getNotScannable().reason());
		assertEquals(AT.toInstant(), back.getNotScannable().at().toInstant());
		assertEquals(3, back.getNotScannable().components());

		assertFalse(Utils.OM.readTree(Utils.OM.writeValueAsString(new DependencyTrackIntegration()))
				.has("notScannable"), "a scannable artifact's metrics carry no notScannable key");

		Map<String, Object> withJsonNull = new HashMap<>();
		withJsonNull.put("notScannable", null);
		assertNull(Utils.OM.convertValue(withJsonNull, DependencyTrackIntegration.class).getNotScannable());
	}

	// ---- row 9: the clear, and its release touch (F-1) ----

	@Test
	void clearThatLandsOnAnUnscannedArtifactTouchesTheCarryingReleases() {
		DependencyTrackIntegration marked = new DependencyTrackIntegration();
		marked.setNotScannable(marker(NoMatchableComponentsReason.NO_COMPONENTS));
		Artifact a = artifactWithMetrics(marked);
		when(repo.clearNotScannable(a.getUuid())).thenReturn(1);
		when(repo.findById(a.getUuid())).thenReturn(Optional.of(a));

		assertEquals(SharedArtifactService.NotScannableClear.CLEARED, svc.clearArtifactNotScannable(a.getUuid()));

		InOrder order = inOrder(repo, releaseRepository);
		order.verify(repo).clearNotScannable(a.getUuid());
		order.verify(releaseRepository).touchReleasesByScannedDeliverableArtifact(a.getUuid().toString());
		order.verify(releaseRepository).touchReleasesByScannedSceArtifact(a.getUuid().toString());
		order.verify(releaseRepository).touchReleasesByScannedArtifactDirect(a.getUuid().toString());
		verifyTouchedOnce(a.getUuid());
		verify(repo, times(1)).findById(a.getUuid());
		verifyNoOtherWrites();
	}

	@Test
	void clearThatMatchesNothingReadsAndTouchesNothing() {
		UUID uuid = UUID.randomUUID();
		when(repo.clearNotScannable(uuid)).thenReturn(0);

		assertEquals(SharedArtifactService.NotScannableClear.UNCHANGED, svc.clearArtifactNotScannable(uuid));

		verify(repo, never()).findById(any());
		verifyNoTouch();
		verifyNoOtherWrites();
	}

	@Test
	void clearOnAStampedArtifactSkipsTheTouch() {
		DependencyTrackIntegration legacy = new DependencyTrackIntegration();
		ZonedDateTime stamp = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		legacy.setFirstScanned(stamp);
		legacy.setLastScanned(stamp);
		legacy.setNotScannable(marker(NoMatchableComponentsReason.NO_PURL_OR_CPE));
		Artifact a = artifactWithMetrics(legacy);
		when(repo.clearNotScannable(a.getUuid())).thenReturn(1);
		when(repo.findById(a.getUuid())).thenReturn(Optional.of(a));

		assertEquals(SharedArtifactService.NotScannableClear.CLEARED_STAMPED,
				svc.clearArtifactNotScannable(a.getUuid()));

		verify(repo, times(1)).findById(a.getUuid());
		verifyNoTouch();
		verifyNoOtherWrites();
	}

	@Test
	void clearOnADeletedArtifactTouchesNothing() {
		UUID uuid = UUID.randomUUID();
		when(repo.clearNotScannable(uuid)).thenReturn(1);
		when(repo.findById(uuid)).thenReturn(Optional.empty());

		assertEquals(SharedArtifactService.NotScannableClear.UNCHANGED, svc.clearArtifactNotScannable(uuid));

		verifyNoTouch();
		verifyNoOtherWrites();
	}

	/** The clear writes nothing but its own statement: no whole save, no stamp, no audit row. */
	private void verifyNoOtherWrites() {
		verify(repo, never()).updateMetrics(any(), any());
		verify(repo, never()).advanceLastScannedOnly(any(), anyDouble());
		verify(repo, never()).stampNotScannable(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(),
				anyDouble());
		verify(metricsAuditRepository, never()).save(any());
	}

	@Test
	void caseB_aSingleKindOfCarriedFindingIsWipedToo() {
		for (int kind = 0; kind < 2; kind++) {
			DependencyTrackIntegration carried = new DependencyTrackIntegration();
			if (kind == 0) carried.setVulnerabilityDetails(vulns(2));
			else carried.setViolationDetails(violations(2));
			Artifact a = artifactWithMetrics(carried);

			assertEquals(SharedArtifactService.NotScannableWrite.FINDINGS_WIPED,
					svc.markArtifactNotScannable(a, marker(NoMatchableComponentsReason.NO_PURL_OR_CPE)),
					kind == 0 ? "vulnerability rows alone" : "violation rows alone");

			JsonNode saved = savedMetrics(a.getUuid());
			assertEquals(0, saved.get("vulnerabilityDetails").size());
			assertEquals(0, saved.get("violationDetails").size());
		}
		verify(repo, never()).stampNotScannable(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(),
				anyDouble());
	}
}

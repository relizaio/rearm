/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.ParsedBom;
import io.reliza.model.Rebom.ParsedBomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;

/**
 * TPF-114 W1/W2 (architecture 3.14, test 4.11 (b)): when the enrichment puller marks a
 * component enrichment-terminal, the canonicals holding it are requeued for fan-out, after
 * the mark and only for the rows the mark wrote. Same rig as SbomComponentEnrichmentPullerTest.
 */
@ExtendWith(MockitoExtension.class)
class SbomComponentEnrichmentRequeueTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SbomComponentSupportRepository sbomComponentSupportRepository;
	@Mock private SbomComponentSupportAuditRepository sbomComponentSupportAuditRepository;
	@Mock private ReleaseRepository releaseRepository;
	@Mock private RebomService rebomService;
	@Mock private ArtifactService artifactService;

	private SbomComponentService service;

	private final UUID ORG = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new SbomComponentService(
				sbomComponentRepository, artifactSbomComponentRepository,
				releaseArtifactIndexRepository, artifactCanonicalMapRepository,
				sbomComponentSupportRepository, sbomComponentSupportAuditRepository);
		ReflectionTestUtils.setField(service, "releaseRepository", releaseRepository);
		ReflectionTestUtils.setField(service, "rebomService", rebomService);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		ReflectionTestUtils.setField(service, "self", service);
	}

	private SbomComponent comp(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		return sc;
	}

	private void wireComponentToBom(SbomComponent sc, UUID bomId) {
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(ORG);
		asc.setCanonicalArtifactUuid(canonicalArtifact);
		asc.setSbomComponentUuid(sc.getUuid());
		lenient().when(artifactSbomComponentRepository
				.findFirstByOrgAndSbomComponentUuid(ORG, sc.getUuid()))
				.thenReturn(Optional.of(asc));
		ArtifactData ad = mock(ArtifactData.class);
		lenient().when(ad.getInternalBom()).thenReturn(new InternalBom(bomId, null));
		lenient().when(artifactService.getArtifactData(canonicalArtifact)).thenReturn(Optional.of(ad));
	}

	private static RebomService.BomMeta meta(RebomService.EnrichmentStatus status) {
		return new RebomService.BomMeta(null, null, null, null, null, null, null, null, null,
				null, null, null, status, null, null, null);
	}

	private static ParsedBomComponent pc(String canonicalPurl) {
		return new ParsedBomComponent(canonicalPurl, canonicalPurl, "library", null,
				canonicalPurl, "1.0", false, null, List.of(Map.of("license", Map.of("id", "MIT"))));
	}

	@SuppressWarnings("unchecked")
	private void clearPullBackoff() {
		((Map<UUID, Instant>) ReflectionTestUtils.getField(service, "recentlyPulledBoms")).clear();
	}

	@Test
	void aCandidateTheOwnPullLeavesUnenrichedIsRequeuedAfterItsMark() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent candidate = comp("pkg:npm/left-unenriched@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(candidate));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(candidate, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/something-else@2.0")), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		when(sbomComponentRepository.findById(candidate.getUuid())).thenReturn(Optional.of(candidate));
		when(sbomComponentRepository.markEnrichmentTerminal(candidate.getUuid(), "OWN_BOM_PULLED_UNMATCHED"))
				.thenReturn(1);

		service.pullEnrichmentForOrg(ORG);

		InOrder order = inOrder(sbomComponentRepository, artifactCanonicalMapRepository);
		order.verify(sbomComponentRepository).markEnrichmentTerminal(candidate.getUuid(), "OWN_BOM_PULLED_UNMATCHED");
		order.verify(artifactCanonicalMapRepository, times(1))
				.requeueFanOutForComponents(ORG, List.of(candidate.getUuid()));
	}

	@Test
	void aMarkThatWroteNothingRequeuesNothing() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent candidate = comp("pkg:npm/raced@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(candidate));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(candidate, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/something-else@2.0")), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		when(sbomComponentRepository.findById(candidate.getUuid())).thenReturn(Optional.of(candidate));
		// Enriched concurrently: the guarded UPDATE matched no row.
		when(sbomComponentRepository.markEnrichmentTerminal(candidate.getUuid(), "OWN_BOM_PULLED_UNMATCHED"))
				.thenReturn(0);

		service.pullEnrichmentForOrg(ORG);

		verify(artifactCanonicalMapRepository, never()).requeueFanOutForComponents(any(), any());
	}

	@Test
	void theUnpullableStrikeRequeuesAfterTheThirdFailureOnly() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent stuck = comp("pkg:npm/unpullable@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(stuck));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(stuck, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenThrow(new RuntimeException("rebom down"));
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		when(sbomComponentRepository.findById(stuck.getUuid())).thenReturn(Optional.of(stuck));
		when(sbomComponentRepository.markEnrichmentTerminal(stuck.getUuid(), "OWN_BOM_UNPULLABLE")).thenReturn(1);

		service.pullEnrichmentForOrg(ORG);
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForComponents(any(), any());
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForComponents(any(), any());
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);

		InOrder order = inOrder(sbomComponentRepository, artifactCanonicalMapRepository);
		order.verify(sbomComponentRepository).markEnrichmentTerminal(stuck.getUuid(), "OWN_BOM_UNPULLABLE");
		order.verify(artifactCanonicalMapRepository, times(1))
				.requeueFanOutForComponents(ORG, List.of(stuck.getUuid()));
	}

	// ---------------- mutation round ----------------

	/** Two candidates behind one own BOM that the pull leaves unmatched. */
	private List<SbomComponent> twoUnmatchedCandidates() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent first = comp("pkg:npm/first@1.0");
		SbomComponent second = comp("pkg:npm/second@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(first, second));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(first, bomId);
		wireComponentToBom(second, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/something-else@2.0")), List.of()));
		lenient().when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any())).thenReturn(List.of());
		when(sbomComponentRepository.findById(first.getUuid())).thenReturn(Optional.of(first));
		when(sbomComponentRepository.findById(second.getUuid())).thenReturn(Optional.of(second));
		return List.of(first, second);
	}

	@Test
	void onlyTheComponentsTheMarkWroteAreRequeuedWhenTheOwnPullLeavesTwoUnmatched() {
		List<SbomComponent> c = twoUnmatchedCandidates();
		when(sbomComponentRepository.markEnrichmentTerminal(c.get(0).getUuid(), "OWN_BOM_PULLED_UNMATCHED")).thenReturn(1);
		when(sbomComponentRepository.markEnrichmentTerminal(c.get(1).getUuid(), "OWN_BOM_PULLED_UNMATCHED")).thenReturn(0);

		service.pullEnrichmentForOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForComponents(ORG, List.of(c.get(0).getUuid()));
	}

	@Test
	void onlyTheComponentsTheMarkWroteAreRequeuedOnTheThirdUnpullableFailure() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent first = comp("pkg:npm/first-stuck@1.0");
		SbomComponent second = comp("pkg:npm/second-stuck@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(first, second));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(first, bomId);
		wireComponentToBom(second, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenThrow(new RuntimeException("rebom down"));
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any())).thenReturn(List.of());
		when(sbomComponentRepository.findById(first.getUuid())).thenReturn(Optional.of(first));
		when(sbomComponentRepository.findById(second.getUuid())).thenReturn(Optional.of(second));
		when(sbomComponentRepository.markEnrichmentTerminal(first.getUuid(), "OWN_BOM_UNPULLABLE")).thenReturn(1);
		when(sbomComponentRepository.markEnrichmentTerminal(second.getUuid(), "OWN_BOM_UNPULLABLE")).thenReturn(0);

		service.pullEnrichmentForOrg(ORG);
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForComponents(ORG, List.of(first.getUuid()));
	}

	@Test
	void aRequeueThatFailsAfterTheOwnPullMarkDoesNotFailThePassAndTheMarkStands() {
		List<SbomComponent> c = twoUnmatchedCandidates();
		when(sbomComponentRepository.markEnrichmentTerminal(c.get(0).getUuid(), "OWN_BOM_PULLED_UNMATCHED")).thenReturn(1);
		when(sbomComponentRepository.markEnrichmentTerminal(c.get(1).getUuid(), "OWN_BOM_PULLED_UNMATCHED")).thenReturn(1);
		doThrow(new RuntimeException("map table down")).when(artifactCanonicalMapRepository)
				.requeueFanOutForComponents(any(), any());

		assertDoesNotThrow(() -> service.pullEnrichmentForOrg(ORG));

		verify(sbomComponentRepository).markEnrichmentTerminal(c.get(0).getUuid(), "OWN_BOM_PULLED_UNMATCHED");
		verify(sbomComponentRepository).markEnrichmentTerminal(c.get(1).getUuid(), "OWN_BOM_PULLED_UNMATCHED");
		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForComponents(ORG, List.of(c.get(0).getUuid(), c.get(1).getUuid()));
	}

	@Test
	void aRequeueThatFailsAfterTheUnpullableStrikeDoesNotFailThePassEither() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent stuck = comp("pkg:npm/unpullable-requeue-fails@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(stuck));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(stuck, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenThrow(new RuntimeException("rebom down"));
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any())).thenReturn(List.of());
		when(sbomComponentRepository.findById(stuck.getUuid())).thenReturn(Optional.of(stuck));
		when(sbomComponentRepository.markEnrichmentTerminal(stuck.getUuid(), "OWN_BOM_UNPULLABLE")).thenReturn(1);
		doThrow(new RuntimeException("map table down")).when(artifactCanonicalMapRepository)
				.requeueFanOutForComponents(any(), any());

		service.pullEnrichmentForOrg(ORG);
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);
		clearPullBackoff();
		assertDoesNotThrow(() -> service.pullEnrichmentForOrg(ORG));

		verify(sbomComponentRepository).markEnrichmentTerminal(stuck.getUuid(), "OWN_BOM_UNPULLABLE");
	}
}

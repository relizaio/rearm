/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.ParsedBomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;

/** Unit tests for the BEAR-enrichment puller in {@link SbomComponentService}. */
@ExtendWith(MockitoExtension.class)
class SbomComponentEnrichmentPullerTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private ReleaseRepository releaseRepository;
	@Mock private RebomService rebomService;
	@Mock private ArtifactService artifactService;

	private SbomComponentService service;

	private final UUID ORG = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new SbomComponentService(
				sbomComponentRepository, artifactSbomComponentRepository,
				releaseArtifactIndexRepository, artifactCanonicalMapRepository);
		ReflectionTestUtils.setField(service, "releaseRepository", releaseRepository);
		ReflectionTestUtils.setField(service, "rebomService", rebomService);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		// stampEnrichedLicenses is invoked through the self-proxy; point it at the
		// real instance so the @Transactional method body actually runs in the test.
		ReflectionTestUtils.setField(service, "self", service);
	}

	private SbomComponent comp(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		return sc;
	}

	private List<Map<String, Object>> licenses(String id) {
		return List.of(Map.of("license", Map.of("id", id)));
	}

	/** Wire a candidate component to a BOM via its representative artifact. */
	private void wireComponentToBom(SbomComponent sc, UUID bomId) {
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(ORG);
		asc.setCanonicalArtifactUuid(canonicalArtifact);
		asc.setSbomComponentUuid(sc.getUuid());
		lenient().when(artifactSbomComponentRepository
				.findFirstByOrgAndSbomComponentUuid(ORG, sc.getUuid()))
				.thenReturn(Optional.of(asc));
		ArtifactData ad = org.mockito.Mockito.mock(ArtifactData.class);
		lenient().when(ad.getInternalBom()).thenReturn(new InternalBom(bomId, null));
		lenient().when(artifactService.getArtifactData(canonicalArtifact)).thenReturn(Optional.of(ad));
	}

	@Test
	void skipsEntirelyWhenBearNotConfigured() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);

		service.pullEnrichmentForOrg(ORG);

		verify(sbomComponentRepository, never()).findUnenrichedMatchableByOrgOrdered(any(), anyInt());
		verify(rebomService, never()).parseBom(any(), any());
	}

	@Test
	void stampsCompletedBomComponentsFillNullsOnly() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent candidate = comp("pkg:npm/foo@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(candidate));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(candidate, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));

		// BOM carries two components: the un-enriched candidate (gets stamped) and a
		// co-resident already enriched by a prior pull (must be left untouched).
		SbomComponent alreadyEnriched = comp("pkg:npm/bar@2.0");
		alreadyEnriched.setEnrichedAt(java.time.ZonedDateTime.now().minusDays(1));
		alreadyEnriched.setLicenses(licenses("Apache-2.0"));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/foo@1.0", licenses("MIT")),
						pc("pkg:npm/bar@2.0", licenses("GPL-3.0"))),
				List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of(candidate, alreadyEnriched));

		service.pullEnrichmentForOrg(ORG);

		// Candidate stamped with enriched licenses + enriched_at.
		assertNotNull(candidate.getEnrichedAt());
		org.junit.jupiter.api.Assertions.assertEquals(licenses("MIT"), candidate.getLicenses());
		verify(sbomComponentRepository).save(candidate);
		// Already-enriched co-resident untouched (no save, original licenses kept).
		org.junit.jupiter.api.Assertions.assertEquals(licenses("Apache-2.0"), alreadyEnriched.getLicenses());
		verify(sbomComponentRepository, never()).save(alreadyEnriched);
	}

	@Test
	void skipsPendingBomAndPullsNextCompleted() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent pendingComp = comp("pkg:npm/pending@1.0");
		SbomComponent readyComp = comp("pkg:npm/ready@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(pendingComp, readyComp));
		UUID pendingBom = UUID.randomUUID();
		UUID readyBom = UUID.randomUUID();
		wireComponentToBom(pendingComp, pendingBom);
		wireComponentToBom(readyComp, readyBom);
		when(rebomService.getBomMetadataById(pendingBom, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.PENDING));
		when(rebomService.getBomMetadataById(readyBom, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(readyBom, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/ready@1.0", licenses("MIT"))), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of(readyComp));

		service.pullEnrichmentForOrg(ORG);

		// PENDING bom not parsed; the next (COMPLETED) bom is pulled — "add one more".
		verify(rebomService, never()).parseBom(eq(pendingBom), any());
		verify(rebomService, times(1)).parseBom(readyBom, ORG);
		assertNotNull(readyComp.getEnrichedAt());
		assertNull(pendingComp.getEnrichedAt());
	}

	private static RebomService.BomMeta meta(RebomService.EnrichmentStatus status) {
		return new RebomService.BomMeta(null, null, null, null, null, null, null, null, null,
				null, null, null, status, null, null, null);
	}

	private static ParsedBomComponent pc(String canonicalPurl, List<Map<String, Object>> licenses) {
		return new ParsedBomComponent(canonicalPurl, canonicalPurl, "library", null,
				canonicalPurl, "1.0", false, null, licenses);
	}
}

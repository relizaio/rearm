/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.ParsedBom;
import io.reliza.model.Rebom.ParsedBomComponent;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import jakarta.persistence.EntityManager;

/**
 * TPF-114 W4 (architecture 3.14, test 4.13): the release reconcile inserts a canonical's map
 * row in its own transaction before the component parse commits, so a fan-out pass in between
 * can stamp the canonical against an empty inventory. The reconcile therefore requeues the
 * canonical after the parse returns true, and only then. The service runs for real, through
 * its own self reference, built as SbomComponentEnrichmentPullerTest builds it.
 */
class SbomComponentParseRequeueTest {

	private static final UUID ORG = UUID.randomUUID();

	private SbomComponentService service;
	private SbomComponentRepository sbomComponentRepository;
	private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	private RebomService rebomService;

	private ReleaseData rd;
	private ArtifactData bom;
	private final UUID canonical = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		sbomComponentRepository = mock(SbomComponentRepository.class);
		artifactSbomComponentRepository = mock(ArtifactSbomComponentRepository.class);
		artifactCanonicalMapRepository = mock(ArtifactCanonicalMapRepository.class);
		service = new SbomComponentService(sbomComponentRepository, artifactSbomComponentRepository,
				mock(ReleaseArtifactIndexRepository.class), artifactCanonicalMapRepository,
				mock(SbomComponentSupportRepository.class), mock(SbomComponentSupportAuditRepository.class));
		rebomService = mock(RebomService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
		VariantService variantService = mock(VariantService.class);
		GetDeliverableService getDeliverableService = mock(GetDeliverableService.class);
		when(variantService.findBaseVariantForRelease(any())).thenReturn(Optional.empty());
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of());
		ReflectionTestUtils.setField(service, "self", service);
		ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		ReflectionTestUtils.setField(service, "rebomService", rebomService);
		ReflectionTestUtils.setField(service, "variantService", variantService);
		ReflectionTestUtils.setField(service, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(service, "getSourceCodeEntryService", mock(GetSourceCodeEntryService.class));
		ReflectionTestUtils.setField(service, "releaseRepository", mock(ReleaseRepository.class));

		bom = new ArtifactData();
		ReflectionTestUtils.setField(bom, "uuid", UUID.randomUUID());
		bom.setOrg(ORG);
		bom.setType(ArtifactType.BOM);
		bom.setStoredIn(StoredIn.REARM);
		bom.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		when(artifactService.getArtifactData(bom.getUuid())).thenReturn(Optional.of(bom));

		ArtifactCanonicalMap map = new ArtifactCanonicalMap();
		map.setOrg(ORG);
		map.setArtifactUuid(bom.getUuid());
		map.setCanonicalArtifactUuid(canonical);
		when(artifactCanonicalMapRepository.findByArtifactUuid(bom.getUuid())).thenReturn(Optional.of(map));
		lenient().when(sbomComponentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setArtifacts(new LinkedList<>(List.of(bom.getUuid())));
		when(sharedReleaseService.getReleaseData(rd.getUuid())).thenReturn(Optional.of(rd));
	}

	private static ParsedBomComponent pc(String canonicalPurl) {
		return new ParsedBomComponent(canonicalPurl, canonicalPurl, "library", null,
				canonicalPurl, "1.0", false, null, null);
	}

	@Test
	void aParsedNewCanonicalIsRequeuedAfterItsRowsAreSaved() {
		when(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonical)).thenReturn(false);
		when(rebomService.parseBom(bom.getInternalBom().id(), ORG))
				.thenReturn(new ParsedBom(List.of(pc("pkg:npm/parsed-" + UUID.randomUUID() + "@1.0")), List.of()));

		assertEquals(0, service.reconcileReleaseSbomComponents(rd.getUuid()));

		InOrder order = inOrder(artifactSbomComponentRepository, artifactCanonicalMapRepository);
		order.verify(artifactSbomComponentRepository).saveAll(any());
		order.verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForCanonical(ORG, canonical);
		verify(sbomComponentRepository).save(any(SbomComponent.class));
	}

	@Test
	void aCanonicalWhoseRowsExistIsNotParsedAndNotRequeued() {
		when(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonical)).thenReturn(true);

		service.reconcileReleaseSbomComponents(rd.getUuid());

		verify(rebomService, never()).parseBom(any(), any());
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForCanonical(any(), any());
	}

	@Test
	void anEmptyParseStillRequeues() {
		when(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonical)).thenReturn(false);
		when(rebomService.parseBom(bom.getInternalBom().id(), ORG)).thenReturn(new ParsedBom(List.of(), List.of()));

		assertEquals(0, service.reconcileReleaseSbomComponents(rd.getUuid()));

		verify(artifactSbomComponentRepository, never()).saveAll(any());
		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForCanonical(ORG, canonical);
	}

	@Test
	void aParseRebomCouldNotServeIsASkipAndIsNotRequeued() {
		when(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonical)).thenReturn(false);
		when(rebomService.parseBom(bom.getInternalBom().id(), ORG)).thenReturn(null);

		assertEquals(1, service.reconcileReleaseSbomComponents(rd.getUuid()));

		verify(artifactCanonicalMapRepository, never()).requeueFanOutForCanonical(any(), any());
	}

	@Test
	void aRequeueThatFailsAfterTheParseDoesNotFailTheReconcile() {
		when(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonical)).thenReturn(false);
		when(rebomService.parseBom(bom.getInternalBom().id(), ORG))
				.thenReturn(new ParsedBom(List.of(pc("pkg:npm/parsed-" + UUID.randomUUID() + "@1.0")), List.of()));
		doThrow(new RuntimeException("map table down")).when(artifactCanonicalMapRepository)
				.requeueFanOutForCanonical(ORG, canonical);

		assertEquals(0, service.reconcileReleaseSbomComponents(rd.getUuid()));

		verify(artifactSbomComponentRepository).saveAll(any());
		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForCanonical(ORG, canonical);
	}
}

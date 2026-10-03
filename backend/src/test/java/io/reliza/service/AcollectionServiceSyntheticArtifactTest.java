/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.Acollection;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.repositories.AcollectionRepository;

/**
 * TEA-4: the automatic acollection snapshot of a release holds its inventory and never a
 * generated document. Wired like AcollectionServiceMissingInternalBomTest, but with a real
 * ArtifactGatherService (its own collaborators mocked), so the exclusion is the gather's, not a
 * stub's.
 */
public class AcollectionServiceSyntheticArtifactTest {

	private AcollectionService acollectionService;
	private AcollectionRepository repository;
	private ArtifactService artifactService;
	private RebomService rebomService;
	private SharedReleaseService sharedReleaseService;
	private final UUID org = UUID.randomUUID();
	private final UUID releaseUuid = UUID.randomUUID();
	private ReleaseData rd;
	private ArtifactData inventory;
	private ArtifactData synthetic;

	@BeforeEach
	void setUp() {
		repository = mock(AcollectionRepository.class);
		when(repository.save(any(Acollection.class))).thenAnswer(inv -> inv.getArgument(0));
		when(repository.findAcollectionsByRelease(releaseUuid.toString())).thenReturn(List.of());
		acollectionService = new AcollectionService(repository);
		artifactService = mock(ArtifactService.class);
		rebomService = mock(RebomService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		VariantService variantService = mock(VariantService.class);
		when(variantService.getVariantsOfRelease(any())).thenReturn(List.of());
		ArtifactGatherService gather = new ArtifactGatherService();
		ReflectionTestUtils.setField(gather, "getSourceCodeEntryService", mock(GetSourceCodeEntryService.class));
		ReflectionTestUtils.setField(gather, "variantService", variantService);
		ReflectionTestUtils.setField(gather, "getDeliverableService", mock(GetDeliverableService.class));
		ReflectionTestUtils.setField(acollectionService, "artifactService", artifactService);
		ReflectionTestUtils.setField(acollectionService, "rebomService", rebomService);
		ReflectionTestUtils.setField(acollectionService, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(acollectionService, "artifactGatherService", gather);
		when(artifactService.isRebomStoreable(any())).thenCallRealMethod();

		inventory = storedBom(ArtifactBelongsTo.RELEASE);
		synthetic = storedBom(ArtifactBelongsTo.SYNTHETIC);
		rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", releaseUuid);
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setArtifacts(new LinkedList<>(List.of(inventory.getUuid())));
		rd.addSyntheticArtifact(synthetic.getUuid());
		when(sharedReleaseService.getReleaseData(releaseUuid)).thenReturn(Optional.of(rd));
		when(sharedReleaseService.getReleaseDataLight(releaseUuid)).thenReturn(Optional.of(rd));
	}

	private ArtifactData storedBom(ArtifactBelongsTo belongsTo) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setOrg(org);
		ad.setType(ArtifactType.BOM);
		ad.setBomFormat(BomFormat.CYCLONEDX);
		ad.setStoredIn(StoredIn.REARM);
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), belongsTo));
		when(artifactService.getArtifactData(ad.getUuid())).thenReturn(Optional.of(ad));
		when(rebomService.resolveBomMetas(ad.getInternalBom().id(), org)).thenReturn(List.of(new RebomService.BomMeta(
				null, null, null, "2", null, null, null, null, null, null, null, null, null, null, null, null)));
		return ad;
	}

	@Test
	void theSnapshotVersionsTheInventoryOnly() {
		AcollectionData collection = acollectionService.resolveReleaseCollection(releaseUuid, WhoUpdated.getTestWhoUpdated());

		assertEquals(Set.of(new VersionedArtifact(inventory.getUuid(), 2L, ArtifactType.BOM)), collection.getArtifacts());
		verify(rebomService).resolveBomMetas(inventory.getInternalBom().id(), org);
		verify(rebomService, never()).resolveBomMetas(synthetic.getInternalBom().id(), org);
		verify(artifactService, never()).getArtifactData(synthetic.getUuid());
	}

	@Test
	void bindingAGeneratedArtifactIsNotAnArtifactChange() {
		AcollectionData latest = AcollectionData.acollectionDataFactory(org, releaseUuid, 1L,
				Set.of(new VersionedArtifact(inventory.getUuid(), 2L, ArtifactType.BOM)), null);
		Acollection row = new Acollection();
		row.setUuid(latest.getUuid());
		row.setRecordData(Utils.dataToRecord(latest));
		when(repository.findAcollectionsByRelease(releaseUuid.toString())).thenReturn(List.of(row));

		AcollectionData answered = acollectionService.resolveReleaseCollectionIfArtifactsChanged(releaseUuid,
				WhoUpdated.getTestWhoUpdated());

		assertEquals(latest.getUuid(), answered.getUuid(), "the latest snapshot is answered as is");
		assertEquals(latest.getArtifacts(), answered.getArtifacts());
		verify(rebomService, never()).resolveBomMetas(any(), any());
		verify(repository, never()).save(any(Acollection.class));
	}
}

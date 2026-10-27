/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ArtifactRepository;

/**
 * TEA-5 round 4, case 91 (T-10 point 4) on the artifact funnels: {@code saveArtifact} and
 * {@code saveArtifactWithoutSnapshot} write the row's stored TEA id back, read under the row lock
 * before the audit row, whatever the caller's record carried; a new row takes no locked read.
 */
class SharedArtifactServiceTeaUuidCarryTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private ArtifactRepository repository;
	private AuditService auditService;
	private SharedArtifactService service;
	private Artifact row;

	@BeforeEach
	void setUp() {
		repository = mock(ArtifactRepository.class);
		auditService = mock(AuditService.class);
		service = new SharedArtifactService(repository, "reliza", "http://localhost");
		ReflectionTestUtils.setField(service, "auditService", auditService);
		row = artifact(null, "1");
		when(repository.findById(row.getUuid())).thenReturn(Optional.of(row));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(repository.currentTeaUuidForUpdate(any())).thenReturn(Optional.empty());
	}

	private static Artifact artifact(UUID teaUuid, String version) {
		Artifact a = new Artifact();
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", a.getUuid());
		ad.setType(ArtifactType.BOM);
		ad.setDisplayIdentifier("sbom");
		ad.setVersion(version);
		ad.setTeaUuid(teaUuid);
		a.setRecordData(Utils.dataToRecord(ad));
		return a;
	}

	/** The caller's copy of the row: read before a mint committed, so without the id. */
	private ArtifactData staleCopy(UUID teaUuid, String version) {
		ArtifactData ad = ArtifactData.dataFromRecord(row);
		ad.setTeaUuid(teaUuid);
		ad.setVersion(version);
		return ad;
	}

	private void assertLockedReadBeforeAuditAndSave() {
		InOrder order = inOrder(repository, auditService);
		order.verify(repository).currentTeaUuidForUpdate(row.getUuid());
		order.verify(auditService).createAndSaveAuditRecord(TableName.ARTIFACTS, row);
		order.verify(repository).save(row);
	}

	@Test
	void saveArtifactWritesTheStoredIdBack() {
		UUID x = UUID.randomUUID();
		when(repository.currentTeaUuidForUpdate(row.getUuid())).thenReturn(Optional.of(x.toString()));

		service.saveArtifact(row, staleCopy(null, "1"), WU);

		assertEquals(x, ArtifactData.dataFromRecord(row).getTeaUuid());
		assertLockedReadBeforeAuditAndSave();
	}

	@Test
	void saveArtifactWithANewVersionKeepsTheSnapshotAndTheStoredId() {
		UUID x = UUID.randomUUID();
		when(repository.currentTeaUuidForUpdate(row.getUuid())).thenReturn(Optional.of(x.toString()));

		service.saveArtifact(row, staleCopy(null, "2"), WU);

		ArtifactData saved = ArtifactData.dataFromRecord(row);
		assertEquals(x, saved.getTeaUuid());
		assertEquals("2", saved.getVersion());
		assertEquals(1, saved.getPreviousVersions().size());
		assertLockedReadBeforeAuditAndSave();
	}

	@Test
	void saveArtifactCarryingAnIdTheRowDoesNotHaveWritesNone() {
		service.saveArtifact(row, staleCopy(UUID.randomUUID(), "1"), WU);

		assertNull(ArtifactData.dataFromRecord(row).getTeaUuid());
		assertFalse(row.getRecordData().containsKey("teaUuid"));
	}

	@Test
	void saveArtifactWithoutSnapshotWritesTheStoredIdBack() {
		UUID x = UUID.randomUUID();
		when(repository.currentTeaUuidForUpdate(row.getUuid())).thenReturn(Optional.of(x.toString()));

		service.saveArtifactWithoutSnapshot(row, staleCopy(null, "1"), WU);

		assertEquals(x, ArtifactData.dataFromRecord(row).getTeaUuid());
		assertLockedReadBeforeAuditAndSave();
	}

	@Test
	void saveArtifactWithoutSnapshotCarryingAnIdTheRowDoesNotHaveWritesNone() {
		service.saveArtifactWithoutSnapshot(row, staleCopy(UUID.randomUUID(), "1"), WU);

		assertNull(ArtifactData.dataFromRecord(row).getTeaUuid());
	}

	@Test
	void aNewRowTakesNoLockedRead() {
		Artifact fresh = artifact(null, "1");
		when(repository.findById(fresh.getUuid())).thenReturn(Optional.empty());

		service.saveArtifact(fresh, ArtifactData.dataFromRecord(fresh), WU);
		service.saveArtifactWithoutSnapshot(fresh, ArtifactData.dataFromRecord(fresh), WU);

		verify(repository, never()).currentTeaUuidForUpdate(any());
	}
}

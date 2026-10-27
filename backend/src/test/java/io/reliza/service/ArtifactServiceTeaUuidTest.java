/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.repositories.ArtifactRepository;
import jakarta.persistence.EntityManager;

/**
 * TEA-5: an artifact's TEA id (architecture 3.2, cases 29 and 30 of 4.4; case 29 amended by round
 * 4's case 90). ensureTeaUuid mints once under the advisory lock, re-reading the row only after
 * the lock is held, by one conditional statement that bumps neither the revision nor the version
 * and never goes through a funnel; persistArtifact's in-place branch carries the id forward
 * because the dto never carries one.
 */
class ArtifactServiceTeaUuidTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private ArtifactService svc;
	private ArtifactRepository repository;
	private SharedArtifactService shared;
	private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		repository = mock(ArtifactRepository.class);
		shared = mock(SharedArtifactService.class);
		entityManager = mock(EntityManager.class);
		svc = new ArtifactService(repository, null, "reliza", "http://localhost");
		ReflectionTestUtils.setField(svc, "sharedArtifactService", shared);
		ReflectionTestUtils.setField(svc, "entityManager", entityManager);
		// The save writes the record onto the managed row, as the real one does.
		doAnswer(inv -> {
			Artifact a = inv.getArgument(0);
			a.setRecordData(Utils.dataToRecord(inv.<ArtifactData>getArgument(1)));
			return a;
		}).when(shared).saveArtifactWithoutSnapshot(any(Artifact.class), any(ArtifactData.class), any());
		doAnswer(inv -> {
			Artifact a = inv.getArgument(0);
			a.setRecordData(Utils.dataToRecord(inv.<ArtifactData>getArgument(1)));
			return a;
		}).when(shared).saveArtifact(any(Artifact.class), any(ArtifactData.class), any());
	}

	/** A persisted BOM row, with or without a TEA id. */
	private static Artifact row(UUID teaUuid) {
		UUID uuid = UUID.randomUUID();
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setType(ArtifactType.BOM);
		ad.setBomFormat(BomFormat.CYCLONEDX);
		ad.setDisplayIdentifier("sbom");
		ad.setVersion("1");
		ad.setTeaUuid(teaUuid);
		Artifact a = new Artifact();
		a.setUuid(uuid);
		a.setRecordData(Utils.dataToRecord(ad));
		return a;
	}

	private static UUID storedTeaUuid(Artifact a) {
		return ArtifactData.dataFromRecord(a).getTeaUuid();
	}

	/** The statement's effect on the row, which the refresh after it reads back. */
	private UUID[] mintWrites(Artifact a, int answer) {
		UUID[] stored = new UUID[1];
		when(repository.mintTeaUuid(eq(a.getUuid()), anyString())).thenAnswer(inv -> {
			if (1 == answer) stored[0] = UUID.fromString(inv.getArgument(1));
			return answer;
		});
		doAnswer(inv -> {
			if (null != stored[0]) {
				ArtifactData fresh = ArtifactData.dataFromRecord(a);
				fresh.setTeaUuid(stored[0]);
				a.setRecordData(Utils.dataToRecord(fresh));
			}
			return null;
		}).when(entityManager).refresh(a);
		return stored;
	}

	// ---- 29, amended by round 4 case 90: one statement, never the funnel ----

	@Test
	void mintsOnceUnderTheLockByOneStatementThenAnswersTheStoredId() throws Exception {
		Artifact a = row(null);
		UUID uuid = a.getUuid();
		when(repository.findById(uuid)).thenReturn(Optional.of(a));
		UUID[] stored = mintWrites(a, 1);

		UUID minted = svc.ensureTeaUuid(uuid, WU);

		assertNotNull(minted);
		assertEquals(stored[0], minted, "the id returned is the one the statement wrote");
		assertEquals(minted, storedTeaUuid(a), "the managed instance reads the id after the refresh");
		InOrder order = inOrder(repository, entityManager);
		order.verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), uuid.toString());
		order.verify(repository).findById(uuid);
		order.verify(entityManager).refresh(a);
		order.verify(repository).mintTeaUuid(uuid, minted.toString());
		order.verify(entityManager).refresh(a);

		UUID again = svc.ensureTeaUuid(uuid, WU);

		assertEquals(minted, again, "the second call answers the stored id");
		verify(repository, times(2)).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), uuid.toString());
		verify(repository, times(1)).mintTeaUuid(any(), any());
		assertTrue(mockingDetails(shared).getInvocations().isEmpty(), "the mint never goes through a funnel");
		verify(repository, never()).save(any());
	}

	@Test
	void theMintChangesNeitherTheRevisionNorTheVersion() throws Exception {
		Artifact a = row(null);
		a.setRevision(7);
		when(repository.findById(a.getUuid())).thenReturn(Optional.of(a));
		mintWrites(a, 1);

		svc.ensureTeaUuid(a.getUuid(), WU);

		assertEquals(7, a.getRevision(), "no @Version bump");
		assertEquals("1", ArtifactData.dataFromRecord(a).getVersion(), "the TEA revision is untouched");
	}

	/** The refresh after the lock is what reads an id the previous lock holder committed. */
	@Test
	void anIdCommittedByTheLockHolderIsReadAfterTheRefreshAndNotMintedAgain() throws Exception {
		Artifact a = row(null);
		UUID committedByOther = UUID.randomUUID();
		when(repository.findById(a.getUuid())).thenReturn(Optional.of(a));
		doAnswer(inv -> {
			Artifact managed = inv.getArgument(0);
			ArtifactData fresh = ArtifactData.dataFromRecord(managed);
			fresh.setTeaUuid(committedByOther);
			managed.setRecordData(Utils.dataToRecord(fresh));
			return null;
		}).when(entityManager).refresh(a);

		assertEquals(committedByOther, svc.ensureTeaUuid(a.getUuid(), WU));
		verify(repository, never()).mintTeaUuid(any(), any());
	}

	@Test
	void aStatementThatWritesNothingOnARowWithoutAnIdIsNotFound() {
		Artifact a = row(null);
		when(repository.findById(a.getUuid())).thenReturn(Optional.of(a));
		mintWrites(a, 0);

		RelizaException e = assertThrows(RelizaException.class, () -> svc.ensureTeaUuid(a.getUuid(), WU));
		assertTrue(e.getMessage().contains("not found"), e.getMessage());
	}

	@Test
	void aConstraintErrorOfTheStatementIsARelizaExceptionNamingTheKindTheUuidAndTheCause() {
		Artifact a = row(null);
		when(repository.findById(a.getUuid())).thenReturn(Optional.of(a));
		when(repository.mintTeaUuid(eq(a.getUuid()), anyString()))
				.thenThrow(new DataIntegrityViolationException("duplicate key value violates artifacts_tea_uuid"));

		RelizaException e = assertThrows(RelizaException.class, () -> svc.ensureTeaUuid(a.getUuid(), WU));

		assertTrue(e.getMessage().startsWith("the TEA id of artifact " + a.getUuid() + " could not be assigned: ")
				&& e.getMessage().contains("artifacts_tea_uuid"), e.getMessage());
	}

	@Test
	void anExistingIdIsAnsweredWithoutAWrite() throws Exception {
		UUID existing = UUID.randomUUID();
		Artifact a = row(existing);
		when(repository.findById(a.getUuid())).thenReturn(Optional.of(a));

		assertEquals(existing, svc.ensureTeaUuid(a.getUuid(), WU));
		assertTrue(mockingDetails(shared).getInvocations().isEmpty(), "nothing saved");
		verify(repository, never()).mintTeaUuid(any(), any());
	}

	@Test
	void aMissingArtifactIsRefusedAfterTheLock() {
		UUID uuid = UUID.randomUUID();
		when(repository.findById(uuid)).thenReturn(Optional.empty());

		RelizaException e = assertThrows(RelizaException.class, () -> svc.ensureTeaUuid(uuid, WU));

		assertTrue(e.getMessage().contains(uuid.toString()), e.getMessage());
		verify(repository).lockTeaIdMint(anyInt(), anyString());
		verify(repository, never()).mintTeaUuid(any(), any());
		assertTrue(mockingDetails(shared).getInvocations().isEmpty());
	}

	// ---- 30 ----

	private static ArtifactDto dto(UUID uuid) {
		return ArtifactDto.builder()
				.uuid(uuid)
				.org(UUID.randomUUID())
				.type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX)
				.displayIdentifier("sbom")
				.version("2")
				.build();
	}

	private ArtifactData savedThroughSaveArtifact() {
		ArgumentCaptor<ArtifactData> saved = ArgumentCaptor.forClass(ArtifactData.class);
		verify(shared).saveArtifact(any(Artifact.class), saved.capture(), any());
		return saved.getValue();
	}

	@Test
	void anInPlaceReuploadKeepsTheRowsTeaId() throws Exception {
		UUID teaUuid = UUID.randomUUID();
		Artifact existing = row(teaUuid);
		when(shared.getArtifact(existing.getUuid())).thenReturn(Optional.of(existing));

		Artifact written = svc.persistArtifact(dto(existing.getUuid()), WU);

		ArtifactData saved = savedThroughSaveArtifact();
		assertEquals(teaUuid, saved.getTeaUuid(), "the dto carries no TEA id; the row's is carried forward");
		assertEquals("2", saved.getVersion());
		assertEquals(teaUuid, storedTeaUuid(written));
	}

	@Test
	void anInPlaceReuploadOfARowWithoutATeaIdStaysWithout() throws Exception {
		Artifact existing = row(null);
		when(shared.getArtifact(existing.getUuid())).thenReturn(Optional.of(existing));

		svc.persistArtifact(dto(existing.getUuid()), WU);

		assertNull(savedThroughSaveArtifact().getTeaUuid());
	}

	@Test
	void aNewRowHasNoTeaId() throws Exception {
		svc.persistArtifact(dto(null), WU);

		assertNull(savedThroughSaveArtifact().getTeaUuid());
		verify(shared, never()).getArtifact(any());
	}

	@Test
	void aNewRowUnderAGivenUuidHasNoTeaId() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(shared.getArtifact(uuid)).thenReturn(Optional.empty());

		Artifact written = svc.persistArtifact(dto(uuid), WU);

		assertNull(savedThroughSaveArtifact().getTeaUuid());
		assertEquals(uuid, written.getUuid());
		assertEquals(List.of(), mockingDetails(shared).getInvocations().stream()
				.map(i -> i.getMethod().getName())
				.filter(n -> n.equals("saveArtifactWithoutSnapshot")).toList());
	}
}

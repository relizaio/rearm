/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.exceptions.ArtifactRetainedException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactPin;
import io.reliza.model.PackageKind;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ArtifactRepository;

/**
 * FDAW-5, design T12 (C2): archiveArtifact is the floor of the retention lock. A row a package version
 * pins is refused before the status is set, so its status is unchanged and no row or audit write
 * happens; any other row is archived as before.
 */
class ArtifactServiceArchiveFloorTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	private static final UUID ORG = UUID.randomUUID();

	private ArtifactService svc;
	private SharedArtifactService shared;
	private ArtifactRetentionHook hook;

	@BeforeEach
	void setUp() {
		shared = mock(SharedArtifactService.class);
		hook = mock(ArtifactRetentionHook.class);
		svc = new ArtifactService(mock(ArtifactRepository.class), null, "reliza", "http://localhost");
		ArtifactRetentionLock lock = new ArtifactRetentionLock();
		ReflectionTestUtils.setField(lock, "hook", hook);
		ReflectionTestUtils.setField(svc, "sharedArtifactService", shared);
		ReflectionTestUtils.setField(svc, "artifactRetentionLock", lock);
	}

	private Artifact row() {
		UUID uuid = UUID.randomUUID();
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setOrg(ORG);
		ad.setType(ArtifactType.BOM);
		ad.setDisplayIdentifier("sbom");
		Artifact a = new Artifact();
		a.setUuid(uuid);
		a.setRecordData(Utils.dataToRecord(ad));
		when(shared.getArtifact(uuid)).thenReturn(Optional.of(a));
		return a;
	}

	@Test
	void aPinnedRowIsRefusedAndNothingIsWritten() {
		Artifact a = row();
		UUID release = UUID.randomUUID();
		when(hook.pinsOf(eq(ORG), any())).thenReturn(List.of(new ArtifactPin(a.getUuid(), UUID.randomUUID(),
				PackageKind.FDA_PREMARKET, 1, release, "pump 1.0.0 (" + release + ")")));

		ArtifactRetainedException e = assertThrows(ArtifactRetainedException.class,
				() -> svc.archiveArtifact(a.getUuid(), WU));

		assertTrue(e.getMessage().contains(" cannot be archived: "), e.getMessage());
		verify(shared, never()).saveArtifact(any(Artifact.class), any(ArtifactData.class), any());
		verify(shared, never()).saveArtifact(any(Artifact.class), anyMap(), any());
		assertNull(ArtifactData.dataFromRecord(a).getStatus(), "the row's status is unchanged");
	}

	@Test
	void aRowNoVersionPinsIsArchivedAsBefore() {
		Artifact a = row();
		when(hook.pinsOf(eq(ORG), any())).thenReturn(List.of());

		assertTrue(svc.archiveArtifact(a.getUuid(), WU));

		ArgumentCaptor<ArtifactData> saved = ArgumentCaptor.forClass(ArtifactData.class);
		verify(shared).saveArtifact(eq(a), saved.capture(), eq(WU));
		assertEquals(StatusEnum.ARCHIVED, saved.getValue().getStatus());
	}
}

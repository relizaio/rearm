/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.oss.OssReleaseService;

/**
 * TEA-4: a generated artifact of a release is owned by the generator that wrote it. The release
 * artifact attach and replace flows refuse it, before anything is saved or reconciled.
 */
class ReleaseServiceArtifactGuardTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private ReleaseService svc;
	private OssReleaseService oss;
	private SbomComponentService sbomComponentService;
	private UUID releaseUuid;
	private final UUID inventory = UUID.randomUUID();
	private final UUID synthetic = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		svc = new ReleaseService(null);
		SharedReleaseService shared = mock(SharedReleaseService.class);
		oss = mock(OssReleaseService.class);
		sbomComponentService = mock(SbomComponentService.class);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(svc, "ossReleaseService", oss);
		ReflectionTestUtils.setField(svc, "sbomComponentService", sbomComponentService);
		ReflectionTestUtils.setField(svc, "componentLockService", mock(ComponentLockService.class));

		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setArtifacts(new LinkedList<>(List.of(inventory)));
		rd.addSyntheticArtifact(synthetic);
		Release r = new Release();
		releaseUuid = UUID.randomUUID();
		r.setUuid(releaseUuid);
		r.setRecordData(Utils.dataToRecord(rd));
		when(shared.getRelease(releaseUuid)).thenReturn(Optional.of(r));
		when(shared.getReleaseData(releaseUuid)).thenReturn(Optional.of(ReleaseData.dataFromRecord(r)));
	}

	private void assertRefused(RelizaException e) {
		assertEquals("artifact " + synthetic + " is a generated artifact of release " + releaseUuid
				+ "; it cannot be attached as inventory", e.getMessage());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any());
		verify(sbomComponentService, never()).requestReconcile(any());
	}

	@Test
	void addArtifactRefusesAGeneratedArtifact() {
		assertRefused(assertThrows(RelizaException.class, () -> svc.addArtifact(synthetic, releaseUuid, WU)));
	}

	@Test
	void replaceArtifactRefusesAGeneratedArtifactAsTheReplacement() {
		assertRefused(assertThrows(RelizaException.class,
				() -> svc.replaceArtifact(inventory, synthetic, releaseUuid, WU)));
	}

	@Test
	void replaceArtifactRefusesToReplaceAGeneratedArtifact() {
		assertRefused(assertThrows(RelizaException.class,
				() -> svc.replaceArtifact(synthetic, UUID.randomUUID(), releaseUuid, WU)));
	}

	@Test
	void anOrdinaryAttachStillSaves() throws Exception {
		assertTrue(svc.addArtifact(UUID.randomUUID(), releaseUuid, WU));
		verify(oss).saveRelease(any(Release.class), any(ReleaseData.class), any());
	}
}

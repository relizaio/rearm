/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.repositories.ReleaseLiteRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.oss.OssReleaseService;

/**
 * TEA-4: a generated artifact of a release is owned by the generator that wrote it. The release
 * artifact attach and replace flows refuse it, before anything is saved or reconciled, also when
 * the call names another release than the one that lists it (test run 1, T-2). The real
 * {@link SharedReleaseService} guard runs over a mocked repository.
 */
class ReleaseServiceArtifactGuardTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private ReleaseService svc;
	private OssReleaseService oss;
	private SbomComponentService sbomComponentService;
	private ReleaseRepository repository;
	private UUID org;
	private UUID releaseUuid;
	private final UUID inventory = UUID.randomUUID();
	private final UUID synthetic = UUID.randomUUID();
	private final UUID otherReleasesGenerated = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		repository = mock(ReleaseRepository.class);
		// main's TPF-36: addArtifact and replaceArtifact lock and refresh the release right before the append
		svc = new ReleaseService(repository);
		ReflectionTestUtils.setField(svc, "entityManager", mock(jakarta.persistence.EntityManager.class));
		SharedReleaseService shared = spy(new SharedReleaseService(repository, mock(ReleaseLiteRepository.class),
				mock(MetricsAuditRepository.class)));
		oss = mock(OssReleaseService.class);
		sbomComponentService = mock(SbomComponentService.class);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(svc, "ossReleaseService", oss);
		ReflectionTestUtils.setField(svc, "sbomComponentService", sbomComponentService);
		ReflectionTestUtils.setField(svc, "componentLockService", mock(ComponentLockService.class));
		ReflectionTestUtils.setField(svc, "artifactService", mock(ArtifactService.class));
		org = UUID.randomUUID();

		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setArtifacts(new LinkedList<>(List.of(inventory)));
		rd.addSyntheticArtifact(synthetic);
		Release r = new Release();
		releaseUuid = UUID.randomUUID();
		ReflectionTestUtils.setField(rd, "uuid", releaseUuid);
		r.setUuid(releaseUuid);
		r.setRecordData(Utils.dataToRecord(rd));
		doReturn(Optional.of(r)).when(shared).getRelease(releaseUuid);
		when(repository.findByIdWriteLocked(releaseUuid)).thenReturn(Optional.of(r));
		doReturn(Optional.of(ReleaseData.dataFromRecord(r))).when(shared).getReleaseData(releaseUuid);
		// otherReleasesGenerated is listed by another release of the org only
		Release holder = new Release();
		holder.setUuid(UUID.randomUUID());
		when(repository.findReleasesBySyntheticArtifact(otherReleasesGenerated.toString(), org.toString()))
				.thenReturn(List.of(holder));
	}

	private void assertRefusedAsAnotherReleasesGenerated(RelizaException e) {
		assertEquals("artifact " + otherReleasesGenerated + " is a generated artifact of another release; it cannot "
				+ "be attached as inventory, replaced or versioned through a release", e.getMessage());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any());
		verify(sbomComponentService, never()).requestReconcile(any());
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
	void addArtifactRefusesAGeneratedArtifactOfAnotherRelease() {
		assertRefusedAsAnotherReleasesGenerated(assertThrows(RelizaException.class,
				() -> svc.addArtifact(otherReleasesGenerated, releaseUuid, WU)));
	}

	@Test
	void replaceArtifactRefusesAGeneratedArtifactOfAnotherReleaseAsTheReplacement() {
		assertRefusedAsAnotherReleasesGenerated(assertThrows(RelizaException.class,
				() -> svc.replaceArtifact(inventory, otherReleasesGenerated, releaseUuid, WU)));
	}

	@Test
	void replaceArtifactRefusesToReplaceAGeneratedArtifactOfAnotherRelease() {
		assertRefusedAsAnotherReleasesGenerated(assertThrows(RelizaException.class,
				() -> svc.replaceArtifact(otherReleasesGenerated, UUID.randomUUID(), releaseUuid, WU)));
	}

	@Test
	void anOrdinaryAttachStillSaves() throws Exception {
		assertTrue(svc.addArtifact(UUID.randomUUID(), releaseUuid, WU));
		verify(oss).saveRelease(any(Release.class), any(ReleaseData.class), any());
	}
}

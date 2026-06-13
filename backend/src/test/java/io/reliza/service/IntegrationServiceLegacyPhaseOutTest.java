/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.repositories.ArtifactRepository;

/**
 * Guards the legacy per-artifact DTrack project phase-out: projects are deleted
 * grouped per org (each org's own token), the artifact reference is cleared only
 * after a successful delete, and a failed delete is counted (and retried next tick
 * because its ref is left intact).
 */
class IntegrationServiceLegacyPhaseOutTest {

	@Test
	void deletesPerOrgAndClearsRefsOnlyOnSuccess() {
		UUID org1 = UUID.randomUUID();
		UUID org2 = UUID.randomUUID();
		String p1 = "proj-1", p2 = "proj-2", p3 = "proj-3";

		ArtifactRepository repo = mock(ArtifactRepository.class);
		when(repo.listLegacyDtrackProjectsForPhaseOut(anyInt())).thenReturn(List.of(
				new Object[]{p1, org1.toString()},
				new Object[]{p2, org1.toString()},
				new Object[]{p3, org2.toString()}));
		lenient().when(repo.clearDtrackProjectRef(eq(org1.toString()), anyString())).thenReturn(2);
		lenient().when(repo.clearDtrackProjectRef(eq(org2.toString()), anyString())).thenReturn(1);

		EncryptionService enc = mock(EncryptionService.class);
		when(enc.decrypt(any())).thenReturn("token");
		IntegrationData fakeInteg = mock(IntegrationData.class);
		when(fakeInteg.getSecret()).thenReturn("secret");

		// p2's delete fails -> it must not be cleared and must be counted as failed.
		Set<String> failing = Set.of(p2);
		List<String> deleteAttempts = new ArrayList<>();

		IntegrationService svc = new IntegrationService(null) {
			@Override
			public Optional<IntegrationData> getIntegrationDataByOrgTypeIdentifier(
					UUID orgUuid, IntegrationType type, String identifier) {
				return Optional.of(fakeInteg);
			}
			@Override
			boolean deleteDtrackProject(IntegrationData integ, String token, String projectId) {
				deleteAttempts.add(projectId);
				return !failing.contains(projectId);
			}
		};
		ReflectionTestUtils.setField(svc, "artifactRepository", repo);
		ReflectionTestUtils.setField(svc, "encryptionService", enc);

		IntegrationService.LegacyDtrackPhaseOutResult result = svc.phaseOutLegacyDtrackProjects(50);

		assertEquals(3, deleteAttempts.size(), "every candidate project should be attempted");
		assertEquals(2, result.projectsDeleted());
		assertEquals(1, result.projectsFailed());
		assertEquals(3, result.artifactsCleared(), "cleared = 2 (p1) + 1 (p3); p2 failed so not cleared");
		verify(repo).clearDtrackProjectRef(org1.toString(), p1);
		verify(repo, never()).clearDtrackProjectRef(org1.toString(), p2);
		verify(repo).clearDtrackProjectRef(org2.toString(), p3);
	}

	@Test
	void noCandidatesIsANoOp() {
		ArtifactRepository repo = mock(ArtifactRepository.class);
		when(repo.listLegacyDtrackProjectsForPhaseOut(anyInt())).thenReturn(List.of());
		IntegrationService svc = new IntegrationService(null);
		ReflectionTestUtils.setField(svc, "artifactRepository", repo);

		IntegrationService.LegacyDtrackPhaseOutResult result = svc.phaseOutLegacyDtrackProjects(50);

		assertEquals(0, result.projectsDeleted());
		assertEquals(0, result.projectsFailed());
		assertEquals(0, result.artifactsCleared());
		verify(repo, never()).clearDtrackProjectRef(anyString(), anyString());
	}
}

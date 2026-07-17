/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.SourceCodeEntryRepository;

/**
 * Mockito unit tests for {@link SourceCodeEntryService#createSourceCodeEntry}.
 *
 * <p>The method runs under {@code Propagation.REQUIRES_NEW}. Phase 2 (the
 * structural follow-up to PR #217) moved VCS provisioning into
 * {@link VcsRepositoryService#provisionVcsRepository}, a {@code REQUIRES_NEW}
 * transaction that commits the VCS row before this method runs. That makes it
 * safe for {@code createSourceCodeEntry} to validate {@code sceDto.vcs} again --
 * the read the self-heal had to drop. These tests pin the inverse of the old
 * contract: the VCS row IS read, a missing row throws a clean
 * {@link RelizaException}, and a cross-org VCS is rejected before any persist.
 */
class SourceCodeEntryServiceTest {

	// SourceCodeEntryService has a constructor for SourceCodeEntryRepository and
	// field-autowires the rest; Mockito's @InjectMocks picks one strategy, so we
	// construct manually and inject the field collaborators via reflection (same
	// approach as ReleaseChangeHookImplTest).
	private SourceCodeEntryRepository repository;
	private BranchService branchService;
	private GetSourceCodeEntryService getSourceCodeEntryService;
	private SharedReleaseService sharedReleaseService;
	private VcsRepositoryService vcsRepositoryService;
	private SourceCodeEntryService service;

	private static final String COMMIT = "a4ec2448bf9fd5b6981914781b015b426a134c98";

	@BeforeEach
	void wireMocks () throws Exception {
		repository = mock(SourceCodeEntryRepository.class);
		branchService = mock(BranchService.class);
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		vcsRepositoryService = mock(VcsRepositoryService.class);

		service = new SourceCodeEntryService(repository);
		inject("branchService", branchService);
		inject("getComponentService", mock(GetComponentService.class));
		inject("getSourceCodeEntryService", getSourceCodeEntryService);
		inject("sharedReleaseService", sharedReleaseService);
		inject("vcsRepositoryService", vcsRepositoryService);
		inject("agentService", mock(AgentService.class));
		inject("agentSessionService", mock(AgentSessionService.class));
		inject("acollectionService", mock(AcollectionService.class));
		inject("auditService", mock(AuditService.class));
	}

	private void inject (String fieldName, Object value) throws Exception {
		Field f = SourceCodeEntryService.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(service, value);
	}

	private SceDto sceDto (UUID vcsUuid, UUID orgUuid) {
		return SceDto.builder()
				.uuid(UUID.randomUUID())
				.branch(UUID.randomUUID())
				.vcs(vcsUuid)
				.commit(COMMIT)
				.organizationUuid(orgUuid)
				.build();
	}

	@Test
	void createSourceCodeEntryReadsAndValidatesTheVcsRow () throws Exception {
		// Phase 2: the VCS row referenced by sceDto.vcs is committed (by
		// provisionVcsRepository on the auto-VCS path, or pre-existing on the
		// direct mutation path) before this REQUIRES_NEW tx, so the validation
		// read here returns rather than NoSuchElement-ing.
		UUID vcsUuid = UUID.randomUUID();
		UUID orgUuid = UUID.randomUUID();
		SceDto sceDto = sceDto(vcsUuid, orgUuid);

		VcsRepositoryData vrd = mock(VcsRepositoryData.class);
		when(vrd.getOrg()).thenReturn(orgUuid);
		when(vcsRepositoryService.getVcsRepositoryData(vcsUuid)).thenReturn(Optional.of(vrd));

		// Skip the org-from-branch lookup (orthogonal to this contract).
		when(branchService.getBranchData(any())).thenReturn(Optional.empty());
		// saveSourceCodeEntry collaborators: fresh row (no audit), no affected releases.
		when(getSourceCodeEntryService.getSourceCodeEntry(any())).thenReturn(Optional.empty());
		when(repository.save(any(SourceCodeEntry.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		lenient().when(sharedReleaseService.findReleasesBySce(any(), any())).thenReturn(List.of());

		SourceCodeEntry saved = service.createSourceCodeEntry(sceDto, WhoUpdated.getAutoWhoUpdated());

		// Primary contract: the VCS row is read for validation.
		verify(vcsRepositoryService).getVcsRepositoryData(vcsUuid);

		// Secondary: the dto's vcs UUID flows through to the persisted record.
		ArgumentCaptor<SourceCodeEntry> captor = ArgumentCaptor.forClass(SourceCodeEntry.class);
		verify(repository).save(captor.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = (Map<String, Object>) captor.getValue().getRecordData();
		assertEquals(vcsUuid.toString(), String.valueOf(recordData.get("vcs")));
		assertEquals(saved, captor.getValue());
	}

	@Test
	void createSourceCodeEntryThrowsWhenVcsRowMissing () {
		UUID vcsUuid = UUID.randomUUID();
		SceDto sceDto = sceDto(vcsUuid, UUID.randomUUID());
		when(vcsRepositoryService.getVcsRepositoryData(vcsUuid)).thenReturn(Optional.empty());

		RelizaException ex = assertThrows(RelizaException.class,
				() -> service.createSourceCodeEntry(sceDto, WhoUpdated.getAutoWhoUpdated()));
		assertTrue(ex.getMessage().contains(vcsUuid.toString()),
				"error should name the missing VCS uuid");
		verify(repository, never()).save(any());
	}

	@Test
	void createSourceCodeEntryRejectsCrossOrgVcs () {
		UUID vcsUuid = UUID.randomUUID();
		UUID sceOrg = UUID.randomUUID();
		UUID vcsOrg = UUID.randomUUID();
		SceDto sceDto = sceDto(vcsUuid, sceOrg);

		VcsRepositoryData vrd = mock(VcsRepositoryData.class);
		when(vrd.getOrg()).thenReturn(vcsOrg);
		when(vcsRepositoryService.getVcsRepositoryData(vcsUuid)).thenReturn(Optional.of(vrd));
		// Skip the org-from-branch lookup so the dto's org (sceOrg) is the one compared.
		when(branchService.getBranchData(any())).thenReturn(Optional.empty());

		RelizaException ex = assertThrows(RelizaException.class,
				() -> service.createSourceCodeEntry(sceDto, WhoUpdated.getAutoWhoUpdated()));
		assertTrue(ex.getMessage().toLowerCase().contains("different organization"),
				"error should flag the cross-org mismatch");
		verify(repository, never()).save(any());
	}
}

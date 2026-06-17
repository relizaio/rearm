/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import io.reliza.model.SourceCodeEntry;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.SourceCodeEntryRepository;

/**
 * Mockito unit tests for {@link SourceCodeEntryService#createSourceCodeEntry}.
 *
 * <p>The method runs under {@code Propagation.REQUIRES_NEW} so the inner tx
 * cannot see uncommitted writes from the caller's outer tx. Concretely: on the
 * auto-VCS path through {@link SourceCodeEntryService#populateSourceCodeEntryByVcsAndCommit}
 * the VCS row referenced by {@code sceDto.vcs} is inserted in the outer tx and
 * is not yet visible here. The contract this test pins is therefore that
 * {@code createSourceCodeEntry} must not perform a read on
 * {@link VcsRepositoryService#getVcsRepositoryData(UUID)} — re-introducing such
 * a read would resurrect the {@code NoSuchElementException} the auto-VCS callers
 * surfaced as opaque {@code SERVICE_ERROR} until that lookup was dropped.
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

	@Test
	void createSourceCodeEntryDoesNotReadTheVcsRow () {
		// Caller-supplied VCS UUID — on the auto-VCS path this row is committed
		// only by the outer tx, so any read attempt from this REQUIRES_NEW tx
		// would NoSuchElementException.
		UUID vcsUuid = UUID.randomUUID();
		SceDto sceDto = SceDto.builder()
				.uuid(UUID.randomUUID())
				.branch(UUID.randomUUID())
				.vcs(vcsUuid)
				.commit("a4ec2448bf9fd5b6981914781b015b426a134c98")
				.organizationUuid(UUID.randomUUID())
				.build();

		// Skip the org-from-branch lookup (orthogonal to this contract).
		when(branchService.getBranchData(any())).thenReturn(Optional.empty());
		// saveSourceCodeEntry collaborators: fresh row (no audit), no affected releases.
		when(getSourceCodeEntryService.getSourceCodeEntry(any())).thenReturn(Optional.empty());
		when(repository.save(any(SourceCodeEntry.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		lenient().when(sharedReleaseService.findReleasesBySce(any(), any())).thenReturn(List.of());

		SourceCodeEntry saved = service.createSourceCodeEntry(sceDto, WhoUpdated.getAutoWhoUpdated());

		// Primary contract: the VCS row is never read inside createSourceCodeEntry.
		verify(vcsRepositoryService, never()).getVcsRepositoryData(any());
		verify(vcsRepositoryService, never()).getVcsRepository(any());

		// Secondary: the dto's vcs UUID flows through to the persisted record.
		ArgumentCaptor<SourceCodeEntry> captor = ArgumentCaptor.forClass(SourceCodeEntry.class);
		verify(repository).save(captor.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = (Map<String, Object>) captor.getValue().getRecordData();
		assertEquals(vcsUuid.toString(), String.valueOf(recordData.get("vcs")));
		assertEquals(saved, captor.getValue());
	}
}

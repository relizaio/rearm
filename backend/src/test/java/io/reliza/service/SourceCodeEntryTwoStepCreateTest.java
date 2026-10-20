/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.SourceCodeEntryRepository;

/**
 * SCORE-47 T-23 (ADR-17, F-8): a new source code entry that carries artifacts is committed without
 * them in its own transaction, and its artifacts are merged in under the row lock in the caller's
 * transaction, so a refusal later in the request rolls the attachment back with the artifact rows.
 * An entry without artifacts keeps the single write it had.
 */
class SourceCodeEntryTwoStepCreateTest {

	private static final String COMMIT = "a4ec2448bf9fd5b6981914781b015b426a134c98";

	private SourceCodeEntryRepository repository;
	private SourceCodeEntryService service;
	private SourceCodeEntryService self;
	/** What createSourceCodeEntryTx was handed, copied at the call. */
	private final List<List<SCEArtifact>> createdWith = new ArrayList<>();

	@BeforeEach
	void wireMocks() throws Exception {
		repository = mock(SourceCodeEntryRepository.class);
		GetSourceCodeEntryService getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		service = new SourceCodeEntryService(repository);
		ReflectionTestUtils.setField(service, "getSourceCodeEntryService", getSourceCodeEntryService);
		ReflectionTestUtils.setField(service, "sharedReleaseService", mock(SharedReleaseService.class));
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "acollectionService", mock(AcollectionService.class));
		ReflectionTestUtils.setField(service, "agentService", mock(AgentService.class));
		ReflectionTestUtils.setField(service, "agentSessionService", mock(AgentSessionService.class));
		self = spy(service);
		ReflectionTestUtils.setField(service, "self", self);
		when(getSourceCodeEntryService.belongsTo(any(), any())).thenReturn(true);
		when(getSourceCodeEntryService.getSourceCodeEntry(any())).thenReturn(Optional.empty());
		when(repository.save(any(SourceCodeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	private SceDto dto(UUID org, List<SCEArtifact> artifacts) {
		return SceDto.builder().uuid(UUID.randomUUID()).branch(UUID.randomUUID()).vcs(UUID.randomUUID())
				.commit(COMMIT).organizationUuid(org).artifacts(artifacts).build();
	}

	/** createSourceCodeEntryTx records what it was handed and answers the committed row. */
	private SourceCodeEntry committedRowFor(SceDto dto) throws Exception {
		SourceCodeEntry row = new SourceCodeEntry();
		doAnswer(inv -> {
			SceDto handed = inv.getArgument(0);
			createdWith.add(null == handed.getArtifacts() ? null : List.copyOf(handed.getArtifacts()));
			row.setRecordData(Utils.dataToRecord(SourceCodeEntryData.scEntryDataFactory(handed)));
			return row;
		}).when(self).createSourceCodeEntryTx(any(), any());
		when(repository.findByCommitAndVcs(COMMIT, dto.getVcs().toString())).thenReturn(Optional.empty());
		when(repository.findByIdWriteLocked(row.getUuid())).thenReturn(Optional.of(row));
		return row;
	}

	private void populate(SceDto dto) {
		ReflectionTestUtils.invokeMethod(service, "populateSourceCodeEntryByVcsAndCommitRoutine", dto, true,
				WhoUpdated.getAutoWhoUpdated());
	}

	@Test
	void aNewEntryWithArtifactsIsCreatedEmptyAndMergedUnderTheLock() throws Exception {
		UUID org = UUID.randomUUID();
		List<SCEArtifact> artifacts = List.of(new SCEArtifact(UUID.randomUUID(), UUID.randomUUID()),
				new SCEArtifact(UUID.randomUUID(), UUID.randomUUID()));
		SceDto dto = dto(org, new ArrayList<>(artifacts));
		SourceCodeEntry row = committedRowFor(dto);

		populate(dto);

		assertEquals(List.of(List.of()), createdWith, "the entry is committed without the artifacts");
		verify(repository).findByIdWriteLocked(row.getUuid());
		ArgumentCaptor<SourceCodeEntry> saved = ArgumentCaptor.forClass(SourceCodeEntry.class);
		verify(repository, times(1)).save(saved.capture());
		assertEquals(artifacts, SourceCodeEntryData.dataFromRecord(saved.getValue()).getArtifacts(),
				"the merge in the caller's transaction writes both artifacts");
		assertEquals(artifacts, dto.getArtifacts(), "the caller's dto keeps its list");
	}

	@Test
	void aNewEntryWithoutArtifactsIsOneWriteAndNoLock() throws Exception {
		SceDto dto = dto(UUID.randomUUID(), null);
		committedRowFor(dto);

		populate(dto);

		assertEquals(1, createdWith.size());
		verify(repository, never()).findByIdWriteLocked(any());
		verify(repository, never()).save(any(SourceCodeEntry.class));
	}
}

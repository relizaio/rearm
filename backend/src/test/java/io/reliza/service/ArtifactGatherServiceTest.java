/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;

/**
 * A release's source code entry reference that no longer resolves must not
 * fail the gather: the release metrics compute and every release save call
 * it, and an exception there fenced the release's metrics and failed every
 * update of the release.
 */
class ArtifactGatherServiceTest {

	private GetSourceCodeEntryService sceService;
	private ArtifactGatherService service;
	private final UUID component = UUID.randomUUID();
	private final UUID releaseArtifact = UUID.randomUUID();

	@BeforeEach
	void wire() {
		sceService = mock(GetSourceCodeEntryService.class);
		VariantService variantService = mock(VariantService.class);
		when(variantService.getVariantsOfRelease(any())).thenReturn(List.of());
		service = new ArtifactGatherService();
		ReflectionTestUtils.setField(service, "getSourceCodeEntryService", sceService);
		ReflectionTestUtils.setField(service, "variantService", variantService);
		ReflectionTestUtils.setField(service, "getDeliverableService", mock(GetDeliverableService.class));
	}

	private ReleaseData release(UUID sce) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "component", component);
		ReflectionTestUtils.setField(rd, "sourceCodeEntry", sce);
		ReflectionTestUtils.setField(rd, "artifacts", new LinkedList<>(List.of(releaseArtifact)));
		return rd;
	}

	@Test
	void aDanglingSourceCodeEntryContributesNothing() {
		UUID sce = UUID.randomUUID();
		when(sceService.getSourceCodeEntryData(sce)).thenReturn(Optional.empty());
		ReleaseData rd = release(sce);

		assertEquals(Set.of(releaseArtifact), service.gatherReleaseArtifacts(rd));
		// A second gather (the next compute or save) behaves the same.
		assertEquals(Set.of(releaseArtifact), service.gatherReleaseArtifacts(rd));
	}

	@Test
	void eachDanglingReferenceIsReportedOnce() {
		UUID sce = UUID.randomUUID();
		when(sceService.getSourceCodeEntryData(sce)).thenReturn(Optional.empty());
		ReleaseData rd = release(sce);

		service.gatherReleaseArtifacts(rd);
		service.gatherReleaseArtifacts(rd);
		service.gatherReleaseArtifacts(release(sce));

		// One per (release, entry): the same release twice is one, another release is another.
		assertEquals(2, ((Set<?>) ReflectionTestUtils.getField(service, "reportedDanglingSce")).size());
	}

	@Test
	void anExistingSourceCodeEntryAddsItsCommitScopedAndComponentArtifacts() {
		UUID sce = UUID.randomUUID();
		UUID commitScoped = UUID.randomUUID();
		UUID ours = UUID.randomUUID();
		UUID otherComponents = UUID.randomUUID();
		SourceCodeEntryData sced = mock(SourceCodeEntryData.class);
		when(sced.getArtifacts()).thenReturn(List.of(new SCEArtifact(commitScoped, null),
				new SCEArtifact(ours, component), new SCEArtifact(otherComponents, UUID.randomUUID())));
		when(sceService.getSourceCodeEntryData(sce)).thenReturn(Optional.of(sced));

		assertEquals(Set.of(releaseArtifact, commitScoped, ours), service.gatherReleaseArtifacts(release(sce)));
	}
}

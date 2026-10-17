/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.model.ArtifactData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.dto.ArtifactWebDto;
import io.reliza.service.ArtifactService;
import io.reliza.service.GetSourceCodeEntryService;

/**
 * BUG 6 regression guard for {@link
 * SourceCodeEntryDataFetcher#artifactsOfSourceCodeEntryWithDep}
 * ({@code SourceCodeEntry.artifactDetails}). A dangling artifact reference
 * is skipped rather than {@code .get()}-throwing {@link
 * java.util.NoSuchElementException} on the missing row, which pre-fix
 * surfaced as a SERVICE_ERROR that failed the whole enclosing release
 * query.
 */
class SourceCodeEntryDataFetcherDanglingRefTest {

	private static final UUID ORG = UUID.randomUUID();

	private ArtifactService artifactService;
	private GetSourceCodeEntryService getSourceCodeEntryService;
	private SourceCodeEntryDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		artifactService = mock(ArtifactService.class);
		fetcher = new SourceCodeEntryDataFetcher();
		inject("artifactService", artifactService);
		// Ownership as GetSourceCodeEntryService decides it for an entry that carries its org
		// (no branch fallback needed here): the entry's own org, or null when it has none.
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		when(getSourceCodeEntryService.ownerOrg(any()))
				.thenAnswer(inv -> ((SourceCodeEntryData) inv.getArgument(0)).getOrg());
		inject("getSourceCodeEntryService", getSourceCodeEntryService);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = SourceCodeEntryDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	private static DgsDataFetchingEnvironment dfeFor(Object source) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(source);
		return dfe;
	}

	private static SourceCodeEntryData sced(UUID uuid, List<SCEArtifact> artifacts) throws Exception {
		// SourceCodeEntryData has a private no-arg constructor and private
		// setters; instantiate reflectively and stamp the fields directly.
		var ctor = SourceCodeEntryData.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		SourceCodeEntryData sced = ctor.newInstance();
		ReflectionTestUtils.setField(sced, "uuid", uuid);
		ReflectionTestUtils.setField(sced, "org", ORG);
		if (artifacts != null) {
			ReflectionTestUtils.setField(sced, "artifacts", artifacts);
		}
		return sced;
	}

	private static ArtifactData artifactData(UUID uuid) {
		return artifactData(uuid, ORG);
	}

	private static ArtifactData artifactData(UUID uuid, UUID org) {
		// A fresh ArtifactData default-initializes the collections that
		// ArtifactWebDto.fromData copies, so it maps cleanly.
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ReflectionTestUtils.setField(ad, "org", org);
		return ad;
	}

	@Test
	void artifactsReturnsEmptyListWhenNoArtifacts() throws Exception {
		SourceCodeEntryData sced = sced(UUID.randomUUID(), null);
		assertTrue(fetcher.artifactsOfSourceCodeEntryWithDep(dfeFor(sced)).isEmpty());
		verify(artifactService, never()).getArtifactData(any());
	}

	@Test
	void artifactsSkipsMissingArtifactAndReturnsOnlyResolvedOne() throws Exception {
		// One resolvable artifact + one dangling artifact reference. Pre-fix
		// the dangling one .get()-threw on the missing row and failed the
		// whole query; now it is skipped and the present one survives.
		UUID presentUuid = UUID.randomUUID();
		UUID missingUuid = UUID.randomUUID();
		UUID componentUuid = UUID.randomUUID();
		SourceCodeEntryData sced = sced(UUID.randomUUID(), List.of(
				new SCEArtifact(presentUuid, componentUuid),
				new SCEArtifact(missingUuid, componentUuid)));
		ArtifactData present = artifactData(presentUuid);
		when(artifactService.getArtifactData(presentUuid)).thenReturn(Optional.of(present));
		when(artifactService.getArtifactData(missingUuid)).thenReturn(Optional.empty());

		List<ArtifactWebDto>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.artifactsOfSourceCodeEntryWithDep(dfeFor(sced)));
		assertEquals(1, holder[0].size(), "Only the resolvable artifact should be returned");
		assertEquals(presentUuid, holder[0].get(0).getUuid(),
				"The surviving ArtifactWebDto must be the resolvable artifact");
	}

	@Test
	void artifactsOmitsArtifactOfAnotherOrg() throws Exception {
		// An artifact merged into this entry from another org (before the SCE merge
		// checked ownership) is not this entry's to show; its own org's artifact is.
		UUID ownUuid = UUID.randomUUID();
		UUID foreignUuid = UUID.randomUUID();
		UUID componentUuid = UUID.randomUUID();
		SourceCodeEntryData sced = sced(UUID.randomUUID(), List.of(
				new SCEArtifact(ownUuid, componentUuid),
				new SCEArtifact(foreignUuid, componentUuid)));
		when(artifactService.getArtifactData(ownUuid)).thenReturn(Optional.of(artifactData(ownUuid)));
		when(artifactService.getArtifactData(foreignUuid))
				.thenReturn(Optional.of(artifactData(foreignUuid, UUID.randomUUID())));

		List<ArtifactWebDto> arts = fetcher.artifactsOfSourceCodeEntryWithDep(dfeFor(sced));
		assertEquals(1, arts.size(), "Only the entry's own org's artifact should be returned");
		assertEquals(ownUuid, arts.get(0).getUuid());
	}

	@Test
	void noArtifactsWhenOwnerCannotBeTold() throws Exception {
		// An entry with no org and no branch belongs to no org (GetSourceCodeEntryService.ownerOrg
		// is null), so, like SourceCodeEntry.releases, it shows none of its artifacts.
		UUID artUuid = UUID.randomUUID();
		UUID componentUuid = UUID.randomUUID();
		SourceCodeEntryData sced = sced(UUID.randomUUID(), List.of(new SCEArtifact(artUuid, componentUuid)));
		ReflectionTestUtils.setField(sced, "org", null);
		when(artifactService.getArtifactData(artUuid))
				.thenReturn(Optional.of(artifactData(artUuid, UUID.randomUUID())));

		assertTrue(fetcher.artifactsOfSourceCodeEntryWithDep(dfeFor(sced)).isEmpty());
	}
}

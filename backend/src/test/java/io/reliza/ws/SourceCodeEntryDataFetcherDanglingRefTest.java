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

	private ArtifactService artifactService;
	private SourceCodeEntryDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		artifactService = mock(ArtifactService.class);
		fetcher = new SourceCodeEntryDataFetcher();
		inject("artifactService", artifactService);
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
		if (artifacts != null) {
			ReflectionTestUtils.setField(sced, "artifacts", artifacts);
		}
		return sced;
	}

	private static ArtifactData artifactData(UUID uuid) {
		// A fresh ArtifactData default-initializes the collections that
		// ArtifactWebDto.fromData copies, so it maps cleanly.
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
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
}

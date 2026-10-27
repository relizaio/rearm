/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.repositories.ReleaseLiteRepository;
import io.reliza.repositories.ReleaseRepository;

/**
 * TEA-4 (test run 1, T-2): the generated-artifact guard holds whichever release the call names.
 * An artifact listed in another release's synthetic list, or carrying the generator's marks, is
 * refused as well as one in the named release's own list, and the refusal does not name a
 * release the caller may not read.
 */
class SharedReleaseServiceGeneratedArtifactGuardTest {

	private ReleaseRepository repository;
	private SharedReleaseService shared;
	private ReleaseData named;
	private final UUID org = UUID.randomUUID();
	private final UUID generated = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		repository = mock(ReleaseRepository.class);
		shared = new SharedReleaseService(repository, mock(ReleaseLiteRepository.class),
				mock(MetricsAuditRepository.class));
		named = new ReleaseData();
		ReflectionTestUtils.setField(named, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(named, "org", org);
		named.setArtifacts(new LinkedList<>());
	}

	private static ArtifactData artifact(UUID uuid, ArtifactBelongsTo belongsTo, List<TagRecord> tags) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		if (null != belongsTo) ad.setInternalBom(new InternalBom(UUID.randomUUID(), belongsTo));
		ad.setTags(new ArrayList<>(tags));
		return ad;
	}

	private void heldBy(UUID artifactUuid, UUID holderUuid) {
		Release holder = new Release();
		holder.setUuid(holderUuid);
		when(repository.findReleasesBySyntheticArtifact(artifactUuid.toString(), org.toString()))
				.thenReturn(List.of(holder));
	}

	private static void assertOtherRelease(UUID artifactUuid, RelizaException e) {
		assertEquals("artifact " + artifactUuid + " is a generated artifact of another release; it cannot be "
				+ "attached as inventory, replaced or versioned through a release", e.getMessage());
	}

	@Test
	void anArtifactOfTheNamedReleasesOwnListIsRefusedNamingIt() {
		named.addSyntheticArtifact(generated);

		RelizaException e = assertThrows(RelizaException.class,
				() -> shared.assertNotGeneratedArtifact(named, generated, null));

		assertEquals("artifact " + generated + " is a generated artifact of release " + named.getUuid()
				+ "; it cannot be attached as inventory", e.getMessage());
	}

	@Test
	void anArtifactListedByAnotherReleaseIsRefusedWithoutNamingThatRelease() {
		UUID holder = UUID.randomUUID();
		heldBy(generated, holder);

		RelizaException e = assertThrows(RelizaException.class, () -> shared.assertNotGeneratedArtifact(named,
				generated, artifact(generated, ArtifactBelongsTo.RELEASE, List.of())));

		assertOtherRelease(generated, e);
		assertEquals(-1, e.getMessage().indexOf(holder.toString()));
	}

	@Test
	void anUnlistedArtifactWithASyntheticInternalBomIsRefused() {
		assertOtherRelease(generated, assertThrows(RelizaException.class, () -> shared.assertNotGeneratedArtifact(
				named, generated, artifact(generated, ArtifactBelongsTo.SYNTHETIC, List.of()))));
	}

	@Test
	void anUnlistedArtifactWithTheSyntheticTagIsRefused() {
		ArtifactData ad = artifact(generated, ArtifactBelongsTo.RELEASE, List.of(
				new TagRecord(CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY, "true", Removable.NO)));

		assertOtherRelease(generated, assertThrows(RelizaException.class,
				() -> shared.assertNotGeneratedArtifact(named, generated, ad)));
	}

	@Test
	void anOrdinaryArtifactPasses() {
		UUID inventory = UUID.randomUUID();
		ArtifactData ad = artifact(inventory, ArtifactBelongsTo.RELEASE, List.of(
				new TagRecord(CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY, "false", Removable.YES)));

		assertDoesNotThrow(() -> shared.assertNotGeneratedArtifact(named, inventory, ad));
		assertDoesNotThrow(() -> shared.assertNotGeneratedArtifact(named, inventory, null));
		assertDoesNotThrow(() -> shared.assertNotGeneratedArtifact(named, null, null));
	}
}

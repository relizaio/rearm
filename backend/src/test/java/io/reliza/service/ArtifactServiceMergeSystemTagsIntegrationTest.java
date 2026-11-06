/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.WhoUpdated;

/**
 * FDAW-7 row 21: {@code ArtifactService.mergeSystemTags} replaces the stored tags of the keys it is given,
 * keeps every other tag, cuts no version snapshot, writes one audit row, and merges system tags only.
 */
class ArtifactServiceMergeSystemTagsIntegrationTest extends ReleaseSbomIntegrationTestBase {

	private long audits(UUID artifact) {
		return jdbc.queryForObject("SELECT count(*) FROM rearm.audit WHERE entity_name = 'artifacts' AND entity_uuid = ?",
				Long.class, artifact);
	}

	private static Set<String> asText(List<TagRecord> tags) {
		Set<String> out = new HashSet<>();
		for (TagRecord t : tags) out.add(t.key() + "=" + t.value() + ":" + t.removable());
		return out;
	}

	@Test
	void theNamedKeysAreReplacedEveryOtherTagIsKeptAndNoVersionIsCut() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID row = legacyAggregate(w.release(), List.of(new TagRecord("k1", "a", Removable.NO),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, "p1", Removable.NO),
				new TagRecord("u", "1", Removable.YES)), UUID.randomUUID(), 1, "4d".repeat(32));
		ArtifactData before = artifactService.getArtifactData(row).orElseThrow();
		long auditsBefore = audits(row);

		artifactService.mergeSystemTags(row, List.of(new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, "p2",
				Removable.NO), new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO)), WU);

		ArtifactData after = artifactService.getArtifactData(row).orElseThrow();
		Set<String> tags = asText(after.getTags());
		assertTrue(tags.contains("k1=a:NO"), tags.toString());
		assertTrue(tags.contains("u=1:YES"), tags.toString());
		assertTrue(tags.contains(CommonVariables.TEA_PUBLICATION_TAG_KEY + "=p2:NO"), tags.toString());
		assertTrue(tags.contains(CommonVariables.TEA_AGGREGATE_TAG_KEY + "=true:NO"), tags.toString());
		assertFalse(tags.contains(CommonVariables.TEA_PUBLICATION_TAG_KEY + "=p1:NO"), tags.toString());
		assertEquals(before.getTags().size() + 1, after.getTags().size(), "one replaced, one added");
		assertEquals(before.getVersion(), after.getVersion());
		assertEquals(before.getPreviousVersions().size(), after.getPreviousVersions().size(), "no snapshot cut");
		assertEquals(auditsBefore + 1, audits(row), "one audit row");
	}

	@Test
	void aUserTagIsRefusedAndAMissingRowIsARelizaException() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID row = legacyAggregate(w.release(), List.of(new TagRecord("k1", "a", Removable.NO)), UUID.randomUUID(), 1,
				"4d".repeat(32));
		ArtifactData before = artifactService.getArtifactData(row).orElseThrow();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> artifactService
				.mergeSystemTags(row, List.of(new TagRecord("k2", "b", Removable.NO), new TagRecord("u", "1",
						Removable.YES)), WU));
		assertEquals("mergeSystemTags of artifact " + row + ": tag u is Removable.YES; only system tags are merged here",
				e.getMessage());
		assertEquals(asText(before.getTags()), asText(artifactService.getArtifactData(row).orElseThrow().getTags()),
				"the row is unchanged");

		UUID missing = UUID.randomUUID();
		RelizaException r = assertThrows(RelizaException.class, () -> artifactService.mergeSystemTags(missing,
				List.of(new TagRecord("k", "v", Removable.NO)), WU));
		assertEquals("Artifact not found: " + missing, r.getMessage());
	}

	@Test
	void theMethodIsPackagePrivate() throws Exception {
		Method m = ArtifactService.class.getDeclaredMethod("mergeSystemTags", UUID.class, List.class, WhoUpdated.class);
		int mod = m.getModifiers();
		assertFalse(Modifier.isPublic(mod) || Modifier.isProtected(mod) || Modifier.isPrivate(mod));
	}
}

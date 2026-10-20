/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.Component;
import io.reliza.repositories.SourceCodeEntryRepository;

/**
 * SCORE-52 T-22 (ADR-8): the source code entry holder query answers only the element naming the
 * artifact, with its componentUuid tag; an untagged element resolves to the components of the
 * organization's releases on that commit, through {@code sourceCodeEntry} and through
 * {@code commits}, and to none when no release is on it. An entry of another organization naming
 * the artifact makes its holders unresolved.
 */
class SbomUpdateGuardSceHolderTest extends SbomUpdateGuardTestBase {

	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;

	private UUID sce(UUID org, String elements) {
		UUID sce = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO rearm.source_code_entries (uuid, record_data) VALUES (?, cast(? as jsonb))",
				sce, "{\"org\":\"" + org + "\",\"commit\":\"" + UUID.randomUUID().toString().replace("-", "")
						+ "\",\"artifacts\":" + elements + "}");
		return sce;
	}

	private void onRelease(UUID release, String field, UUID sce) {
		String value = "commits".equals(field) ? "jsonb_build_array(?::text)" : "to_jsonb(?::text)";
		jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{" + field + "}', " + value
				+ ") WHERE uuid = ?", sce.toString(), release);
	}

	@Test
	void anEntryWithTwoTaggedElementsAnswersOnlyTheMatchingOne() throws Exception {
		World w = world();
		UUID x = UUID.randomUUID();
		UUID z = UUID.randomUUID();
		UUID sce = sce(w.org().getUuid(), "[{\"artifactUuid\":\"" + x + "\",\"componentUuid\":\"" + w.a().getUuid()
				+ "\"},{\"artifactUuid\":\"" + z + "\",\"componentUuid\":\"" + w.b().getUuid() + "\"}]");

		List<Object[]> rows = sourceCodeEntryRepository.findSceArtifactHolders(x.toString());

		assertEquals(1, rows.size());
		assertEquals(sce.toString(), rows.get(0)[0]);
		assertEquals(w.a().getUuid().toString(), rows.get(0)[1]);
	}

	@Test
	void anUntaggedElementResolvesToTheReleasesOnTheCommit() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UUID viaSce = bomArtifact(org, UUID.randomUUID(), "1");
		UUID sceA = sce(org, "[{\"artifactUuid\":\"" + viaSce + "\"}]");
		onRelease(w.releaseA(), "sourceCodeEntry", sceA);
		assertEquals(Set.of(w.a().getUuid()), guard.holdersOfArtifact(org, viaSce).components());

		UUID viaCommits = bomArtifact(org, UUID.randomUUID(), "1");
		UUID sceB = sce(org, "[{\"artifactUuid\":\"" + viaCommits + "\"}]");
		onRelease(w.releaseB(), "commits", sceB);
		assertEquals(Set.of(w.b().getUuid()), guard.holdersOfArtifact(org, viaCommits).components());

		UUID onNoRelease = bomArtifact(org, UUID.randomUUID(), "1");
		sce(org, "[{\"artifactUuid\":\"" + onNoRelease + "\"}]");
		assertEquals(Set.of(), guard.holdersOfArtifact(org, onNoRelease).components());

		Component c = component(w.org());
		UUID tagged = bomArtifact(org, UUID.randomUUID(), "1");
		sce(org, "[{\"artifactUuid\":\"" + tagged + "\",\"componentUuid\":\"" + c.getUuid() + "\"}]");
		assertEquals(Set.of(c.getUuid()), guard.holdersOfArtifact(org, tagged).components());
	}

	@Test
	void anEntryOfAnotherOrganizationNamingTheArtifactIsUnresolved() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UUID row = bomArtifact(org, UUID.randomUUID(), "1");
		listDirectly(w.releaseA(), row);
		sce(testInitializer.obtainOrganization().getUuid(), "[{\"artifactUuid\":\"" + row + "\"}]");

		assertTrue(guard.holdersOfArtifact(org, row).unresolved());
	}
}

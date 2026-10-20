/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.model.ApiKey;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ArtifactAttachmentGuard.ArtifactHolders;

/**
 * SCORE-52 T-26 (ADR-8, F-1): an SBOM nested in another artifact is held by the holders of that
 * artifact, transitively. Nested on A's release, deliverable or commit, S answers {A}: B's key is
 * refused its next version and A's rebuild passes with S nested again. Two levels resolve; a
 * container also on B's release adds B; a cycle ends; a chain deeper or wider than the bounds, or a
 * container of another organization, is unresolved and needs organization-wide write. The user rule
 * reads the same holders, and a new nested serialNumber reads no container.
 */
class SbomUpdateGuardContainerTest extends SbomUpdateGuardProgrammaticTestBase {

	private static final String UPDATE = "mutation($r: ReleaseInput!) { updateRelease(release: $r) { uuid } }";

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	private ArtifactHolders holders(World w, UUID artifact) {
		return guard.holdersOfArtifact(w.org().getUuid(), artifact);
	}

	/** The newest row of {@code serial}. */
	private UUID newestRow(World w, UUID serial) {
		List<UUID> rows = rowsOf(w.org().getUuid(), serial);
		return rows.get(rows.size() - 1);
	}

	// (a)
	@Test
	void sNestedInAnArtifactOfAsReleaseIsHeldByA() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		Map<String, Object> input = releaseInputOf(w.a(), "52.26.a");
		input.put("artifacts", new ArrayList<>(List.of(container(fresh(), entry(cdx(urn(w.serial()), 2))))));
		progReleases(keyA, input);
		UUID nested = newestRow(w, w.serial());
		assertEquals(new ArtifactHolders(Set.of(w.a().getUuid()), false), holders(w, nested));

		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 3)));

		Map<String, Object> rebuild = releaseInputOf(w.a(), "52.26.a");
		rebuild.put("rebuildRelease", true);
		rebuild.put("artifacts", new ArrayList<>(List.of(container(fresh(), entry(cdx(urn(w.serial()), 3))))));
		progReleases(keyA, rebuild);
		assertEquals(Set.of(w.a().getUuid()), holders(w, newestRow(w, w.serial())).components());
		assertEquals(2, rebom.versionsStored(w.serial()));
	}

	// (b)
	@Test
	void sNestedInAnArtifactOfAsDeliverableOrCommitIsHeldByA() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		Map<String, Object> viaDeliverable = releaseInputOf(w.a(), "52.26.b1");
		viaDeliverable.put("outboundDeliverables", new ArrayList<>(List.of(map("displayIdentifier",
				"score52-odel-" + UUID.randomUUID(), "type", CdxType.CONTAINER.name(), "artifacts",
				new ArrayList<>(List.of(container(fresh(), entry(cdx(urn(w.serial()), 2)))))))));
		progReleases(keyA, viaDeliverable);
		assertEquals(Set.of(w.a().getUuid()), holders(w, newestRow(w, w.serial())).components());
		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 3)));

		Map<String, Object> commit = sce(uri(), commit());
		commit.put("artifacts", new ArrayList<>(List.of(container(fresh(), entry(cdx(urn(w.serial()), 3))))));
		Map<String, Object> viaCommit = releaseInputOf(w.a(), "52.26.b2");
		viaCommit.put(CommonVariables.COMMITS_FIELD, new ArrayList<>(List.of(commit)));
		progReleases(keyA, viaCommit);
		assertEquals(Set.of(w.a().getUuid()), holders(w, newestRow(w, w.serial())).components());
		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 4)));
	}

	// (c), (d)
	@Test
	void twoLevelsResolveAndAContainerOnBsReleaseAddsB() throws Exception {
		World w = world();
		UUID s = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID serial = artifactService.getArtifactData(s).orElseThrow().getInternalBom().id();
		UUID inner = containerArtifact(w.org().getUuid(), s);
		UUID outer = containerArtifact(w.org().getUuid(), inner);
		listDirectly(w.releaseA(), outer);
		assertEquals(new ArtifactHolders(Set.of(w.a().getUuid()), false), holders(w, s));
		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(serial), 2)));

		listDirectly(w.releaseB(), inner);
		assertEquals(Set.of(w.a().getUuid(), w.b().getUuid()), holders(w, s).components());
		assertRefusedUpload(() -> progUpload(freeformKey(w, null, w.a().getUuid()), w.releaseA(), cdx(urn(serial), 2)));
		progUpload(freeformKey(w, null, w.a().getUuid(), w.b().getUuid()), w.releaseA(), cdx(urn(serial), 2));
		assertEquals(1, rebom.versionsStored(serial));
	}

	// (e)
	@Test
	void aCycleEndsAndAnswersItsHolder() throws Exception {
		World w = world();
		UUID x = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID y = containerArtifact(w.org().getUuid(), x);
		jdbcTemplate.update("UPDATE rearm.artifacts SET record_data = jsonb_set(record_data, '{artifacts}',"
				+ " jsonb_build_array(?::text)) WHERE uuid = ?", y.toString(), x);
		listDirectly(w.releaseA(), y);

		assertEquals(new ArtifactHolders(Set.of(w.a().getUuid()), false), holders(w, x));
		assertEquals(new ArtifactHolders(Set.of(w.a().getUuid()), false), holders(w, y));
	}

	// (f)
	@Test
	void aChainDeeperOrWiderThanTheBoundsIsUnresolved() throws Exception {
		World w = world();
		UUID deepRow = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID deepSerial = artifactService.getArtifactData(deepRow).orElseThrow().getInternalBom().id();
		UUID top = deepRow;
		for (int i = 0; i < 9; i++) {
			top = containerArtifact(w.org().getUuid(), top);
		}
		listDirectly(w.releaseA(), top);
		assertTrue(holders(w, deepRow).unresolved());

		UUID wideRow = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		for (int i = 0; i < 64; i++) {
			containerArtifact(w.org().getUuid(), wideRow);
		}
		assertTrue(holders(w, wideRow).unresolved());

		ApiKey everyComponent = freeformKey(w, null, w.a().getUuid(), w.b().getUuid());
		assertRefusedUpload(() -> progUpload(everyComponent, w.releaseA(), cdx(urn(deepSerial), 2)));
		progUpload(orgRwKey(w), w.releaseA(), cdx(urn(deepSerial), 2));
		assertEquals(1, rebom.versionsStored(deepSerial));
	}

	// (g)
	@Test
	void aContainerOfAnotherOrganizationIsUnresolved() throws Exception {
		World w = world();
		UUID row = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID serial = artifactService.getArtifactData(row).orElseThrow().getInternalBom().id();
		listDirectly(w.releaseA(), row);
		Organization other = testInitializer.obtainOrganization();
		containerArtifact(other.getUuid(), row);
		assertTrue(holders(w, row).unresolved());

		assertRefusedUpload(() -> progUpload(freeformKey(w, null, w.a().getUuid(), w.b().getUuid()), w.releaseA(),
				cdx(urn(serial), 2)));
		progUpload(orgRwKey(w), w.releaseA(), cdx(urn(serial), 2));
	}

	// (h)
	@Test
	void theUserRuleReadsTheHoldersOfTheContainer() throws Exception {
		World w = world();
		UUID s = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		listDirectly(w.releaseA(), containerArtifact(w.org().getUuid(), s));

		UserData writerB = writerOn(w.org(), w.b().getUuid());
		assertTrue(onlyError(listOnB(writerB, w, s)).getMessage().contains("cannot be attached by reference"));
		ExecutionResult both = listOnB(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()), w, s);
		assertTrue(both.getErrors().isEmpty(), both.getErrors().toString());
		UUID draft = release(w.org(), w.b(), List.of());
		ExecutionResult admin = as(member(w.org(), PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.ADMIN),
				UPDATE, Map.of("r", releaseRef(w, draft, s)));
		assertTrue(admin.getErrors().isEmpty(), admin.getErrors().toString());
	}

	private ExecutionResult listOnB(UserData user, World w, UUID artifact) {
		return as(user, UPDATE, Map.of("r", releaseRef(w, w.releaseB(), w.y(), artifact)));
	}

	private static Map<String, Object> releaseRef(World w, UUID release, UUID... artifacts) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("uuid", release.toString());
		r.put("org", w.org().getUuid().toString());
		r.put("artifacts", Arrays.stream(artifacts).map(UUID::toString).toList());
		return r;
	}

	// (i)
	@Test
	void aNewNestedSerialNumberReadsNoContainer() throws Exception {
		World w = world();
		UUID nestedSerial = UUID.randomUUID();
		clearInvocations(guard);
		progUploadEntry(componentKey(w, w.b()), w.releaseB(), container(fresh(), entry(cdx(urn(nestedSerial), 1))));
		verify(guard, never()).holdersOfArtifact(any(), any());
		assertFalse(isCopy(newestRow(w, nestedSerial)));
	}
}

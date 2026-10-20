/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ApiKey;

/**
 * SCORE-52 T-11 (ADR-3), rule B through the programmatic routes, CycloneDX. A new serialNumber
 * passes any key; an identical re-upload of A's document by B's key passes as a copy, recording the
 * document's version whatever the input says; A's own key re-uploading it records an authorizing
 * row, and an organization-wide key reads nothing for the rule. A's key bumps S held by A alone on
 * every route; B's key is refused a higher version with nothing sent or retained, also after its
 * copy; the same or a lower version is not the rule's to refuse. A version rearm-saas cannot read
 * as an int (above {@code Integer.MAX_VALUE}, or not an integer) is an update, so B's key is refused
 * it before rebom.
 */
class SbomUpdateGuardProgrammaticCycloneDxTest extends SbomUpdateGuardProgrammaticTestBase {

	@Autowired private ComponentDataFetcher componentDataFetcher;

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	private void refusedBeforeRebom(World w) {
		assertEquals(0, rebom.addBomCalls(w.serial()), "never sent to rebom");
	}

	private void assertRefused(World w, ThrowingCall call) throws Exception {
		int sent = rebom.addBomCalls(w.serial());
		SbomUpdateNotPermittedException e = assertRefusedUpload(call);
		assertEquals(SbomUpdateNotPermittedException.messageFor(urn(w.serial())), e.getMessage());
		assertFalse(e.getMessage().contains(w.a().getUuid().toString()), e.getMessage());
		assertEquals(sent, rebom.addBomCalls(w.serial()));
	}

	// (a)
	@Test
	void aNewSerialNumberPassesAnyKey() throws Exception {
		World w = world();
		UUID fresh = UUID.randomUUID();
		progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(fresh), 1));
		UUID row = addedRow(w.releaseB(), List.of(w.y()));
		assertFalse(isCopy(row));
		assertEquals(1, rebom.versionsStored(fresh));
	}

	// (b), (e)
	@Test
	void bsIdenticalReUploadIsACopyAtTheDocumentsVersionAndItsBumpIsRefused() throws Exception {
		World w = world();
		ApiKey keyB = componentKey(w, w.b());
		Map<String, Object> entry = entry(cdx(urn(w.serial()), 1));
		entry.put(CommonVariables.VERSION_FIELD, "9999");

		progUploadEntry(keyB, w.releaseB(), entry);

		UUID copy = addedRow(w.releaseB(), List.of(w.y()));
		assertTrue(isCopy(copy));
		assertEquals("1", versionOf(copy));
		assertEquals(1, rebom.addBomCalls(w.serial()));
		assertEquals(0, rebom.versionsStored(w.serial()), "the dedup answer, nothing stored");

		assertRefused(w, () -> progUpload(keyB, w.releaseB(), cdx(urn(w.serial()), 2)));
	}

	// (b), round 4
	@Test
	void asOwnKeysAndAnOrganizationKeysIdenticalReUploadsAuthorize() throws Exception {
		World w = world();
		UUID secondA = release(w.org(), w.a(), List.of());
		progUpload(componentKey(w, w.a()), secondA, cdx(urn(w.serial()), 1));
		assertFalse(isCopy(addedRow(secondA, List.of())), "A writes A, the authorizing holder");

		clearInvocations(guard, rebomService);
		progUpload(orgRwKey(w), w.releaseB(), cdx(urn(w.serial()), 1));
		assertFalse(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		verify(guard, never()).holdersOfArtifact(any(), any());
		verify(rebomService, never()).latestStoredBomVersion(any(), any());
	}

	// (c)
	@Test
	void asKeyBumpsSHeldByAAloneOnEveryRoute() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		progUpload(keyA, w.releaseA(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));

		Map<String, Object> viaRelease = releaseInputOf(w.a(), "52.11.c1");
		viaRelease.put("artifacts", entries(cdx(urn(w.serial()), 3)));
		progReleases(keyA, viaRelease);
		assertEquals(2, rebom.versionsStored(w.serial()));

		Map<String, Object> viaCommit = releaseInputOf(w.a(), "52.11.c2");
		viaCommit.put(CommonVariables.COMMITS_FIELD, new ArrayList<>(List.of(sce(uri(), commit(),
				cdx(urn(w.serial()), 4)))));
		progReleases(keyA, viaCommit);
		assertEquals(3, rebom.versionsStored(w.serial()));

		Map<String, Object> viaDeliverable = releaseInputOf(w.a(), "52.11.c3");
		viaDeliverable.put("outboundDeliverables", new ArrayList<>(List.of(map("displayIdentifier",
				"score52-odel-" + UUID.randomUUID(), "type", CdxType.CONTAINER.name(), "artifacts",
				entries(cdx(urn(w.serial()), 5))))));
		progReleases(keyA, viaDeliverable);
		assertEquals(4, rebom.versionsStored(w.serial()));

		String branchName = branchService.getBranchData(branchService.getBaseBranchOfComponent(w.a().getUuid())
				.orElseThrow().getUuid()).orElseThrow().getName();
		componentDataFetcher.getNewVersionProgrammatic(env(keyA, "newVersionInput", map(
				CommonVariables.COMPONENT_FIELD, w.a().getUuid().toString(), CommonVariables.BRANCH_FIELD, branchName,
				CommonVariables.SOURCE_CODE_ENTRY_FIELD, sce(uri(), commit(), cdx(urn(w.serial()), 6)))));
		assertEquals(5, rebom.versionsStored(w.serial()));
		assertTrue(rowsOf(w.org().getUuid(), w.serial()).stream().noneMatch(this::isCopy));
	}

	// (d)
	@Test
	void bsKeyIsRefusedAHigherVersionOfAsSerialNumber() throws Exception {
		World w = world();
		ApiKey keyB = componentKey(w, w.b());
		assertRefused(w, () -> progUpload(keyB, w.releaseB(), cdx(urn(w.serial()), 2)));
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));
		assertEquals(1, guardEvents(w.releaseB()).size());
		refusedBeforeRebom(w);
	}

	// (f)
	@Test
	void theSameOrALowerVersionIsNotTheRulesToRefuse() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		ApiKey keyB = componentKey(w, w.b());
		progUpload(keyA, w.releaseA(), cdx(urn(w.serial()), 3));

		progUpload(keyB, w.releaseB(), cdx(urn(w.serial()), 3));
		assertTrue(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		int sent = rebom.addBomCalls(w.serial());
		String lower = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"" + urn(w.serial())
				+ "\",\"version\":2,\"components\":[{\"type\":\"library\",\"name\":\"other\"}]}";
		RelizaException e = assertThrows(RelizaException.class, () -> progUpload(keyB, w.releaseB(), lower));
		assertTrue(e.getMessage().contains(RebomStore.VERSION_CONFLICT), e.getMessage());
		assertEquals(sent + 1, rebom.addBomCalls(w.serial()), "it reached rebom, which refused it");
	}

	// security review round 6: the version rearm-saas cannot read as an int
	@Test
	void aVersionAboveIntOrNotAnIntegerFromBsKeyNeverReachesRebom() throws Exception {
		World w = world();
		ApiKey keyB = componentKey(w, w.b());
		for (String version : List.of("2147483648", "2.0", "1.0", "1e1")) {
			String doc = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"" + urn(w.serial())
					+ "\",\"version\":" + version + ",\"components\":[]}";
			assertRefused(w, () -> progUpload(keyB, w.releaseB(), doc));
		}
		refusedBeforeRebom(w);
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ApiKey;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.UserData;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.InternalBom;

/**
 * SCORE-52 T-30 (ADR-3, F-5, design 3.0 item 11): copies never authorize. A component that copied
 * S becomes a holder every update needs, but a holder reached only through copies never authorizes
 * one. A re-upload is a copy only when its principal could not write some authorizing holder, or
 * none was left: A's own re-upload, and one by a key that writes A, authorize; one whose authorizing
 * row does not resolve is a copy, never a refusal. An authorized new version of a copy row clears
 * its mark.
 */
class SbomUpdateGuardCopyTest extends SbomUpdateGuardProgrammaticTestBase {

	private static final String UPDATE = "mutation($r: ReleaseInput!) { updateRelease(release: $r) { uuid } }";

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	/** B's component key copies A's S verbatim to B's release; answers the copy row. */
	private UUID bCopies(World w) throws Exception {
		List<UUID> before = artifactsOf(w.releaseB());
		progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 1));
		UUID copy = addedRow(w.releaseB(), before);
		assertTrue(isCopy(copy));
		return copy;
	}

	// (a)
	@Test
	void aCopierIsAHolderEveryUpdateNeeds() throws Exception {
		World w = world();
		bCopies(w);
		assertRefusedUpload(() -> progUpload(freeformKey(w, null, w.a().getUuid()), w.releaseA(),
				cdx(urn(w.serial()), 2)));
		progUpload(freeformKey(w, null, w.a().getUuid(), w.b().getUuid()), w.releaseA(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	// (b)
	@Test
	void holdersReachedOnlyThroughCopiesNeverAuthorize() throws Exception {
		World w = world();
		bCopies(w);
		Component c = component(w.org());
		UUID releaseC = release(w.org(), c, List.of());
		UUID componentC = c.getUuid();
		progUpload(componentKey(w, c), releaseC, cdx(urn(w.serial()), 1));
		assertTrue(isCopy(addedRow(releaseC, List.of())));
		replaceOnRelease(w.releaseA(), w.x());

		assertRefusedUpload(() -> progUpload(freeformKey(w, null, w.b().getUuid(), componentC), w.releaseB(),
				cdx(urn(w.serial()), 2)));
	}

	// (c)
	@Test
	void aNestedCopyIsACopyAndItsNextVersionIsRefused() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		UUID sessionRow = artifactService.createArtifact(ArtifactDto.builder().org(w.org().getUuid())
				.type(ArtifactType.BOM).bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score52-session-" + UUID.randomUUID())
				.internalBom(new InternalBom(s, ArtifactBelongsTo.AGENT_SESSION)).version("1").build(), WU).getUuid();
		rebom.storeWithoutArtifact(s, 1, cdx(urn(s), 1));
		ApiKey keyB = componentKey(w, w.b());

		progUploadEntry(keyB, w.releaseB(), container(fresh(), entry(cdx(urn(s), 1))));
		List<UUID> rows = rowsOf(w.org().getUuid(), s);
		assertEquals(2, rows.size(), rows.toString());
		UUID child = rows.stream().filter(r -> !r.equals(sessionRow)).findFirst().orElseThrow();
		assertTrue(isCopy(child));

		assertRefusedUpload(() -> progUploadEntry(keyB, w.releaseB(), container(fresh(), entry(cdx(urn(s), 2)))));
	}

	// (d)
	@Test
	void aListedAuthorizingRowAuthorizesItsNewHolder() throws Exception {
		World w = world();
		UserData writerAB = writerOn(w.org(), w.a().getUuid(), w.b().getUuid());
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("uuid", w.releaseB().toString());
		r.put("org", w.org().getUuid().toString());
		r.put("artifacts", new ArrayList<>(List.of(w.y().toString(), w.x().toString())));
		ExecutionResult listed = as(writerAB, UPDATE, Map.of("r", r));
		assertTrue(listed.getErrors().isEmpty(), listed.getErrors().toString());
		replaceOnRelease(w.releaseA(), w.x());

		progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	// (e)
	@Test
	void anAuthorizedInPlaceNewVersionOfACopyRowClearsItsMark() throws Exception {
		World w = world();
		UUID copy = bCopies(w);
		UserData writerAB = writerOn(w.org(), w.a().getUuid(), w.b().getUuid());

		ExecutionResult r = manualUpdate(writerAB, w.releaseB(), copy, BomFormat.CYCLONEDX, cdx(urn(w.serial()), 2));

		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		assertTrue(artifactsOf(w.releaseB()).contains(copy));
		assertFalse(isCopy(copy), "an authorized new version of a copy row clears its mark");
		assertEquals("2", versionOf(copy));
	}

	// (f)
	@Test
	void aReUploadByAKeyThatWritesTheAuthorizingHolderIsNotACopy() throws Exception {
		World w = world();
		progUpload(freeformKey(w, null, w.a().getUuid(), w.b().getUuid()), w.releaseB(), cdx(urn(w.serial()), 1));
		assertFalse(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		replaceOnRelease(w.releaseA(), w.x());

		progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	// (g)
	@Test
	void copyRowsAreNotAskedWhenClassifying() throws Exception {
		World w = world();
		bCopies(w);
		ApiKey keyA = componentKey(w, w.a());
		UUID secondA = release(w.org(), w.a(), List.of());

		progUpload(keyA, secondA, cdx(urn(w.serial()), 1));
		assertFalse(isCopy(addedRow(secondA, List.of())), "A's key cannot write B, and still authorizes");
		assertRefusedUpload(() -> progUpload(keyA, w.releaseA(), cdx(urn(w.serial()), 2)));
		progUpload(freeformKey(w, null, w.a().getUuid(), w.b().getUuid()), w.releaseA(), cdx(urn(w.serial()), 2));
	}

	// (h)
	@Test
	void anUnresolvedAuthorizingRowMakesAReUploadACopyNeverARefusal() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		UUID deep = bomArtifact(w.org().getUuid(), s, "1");
		rebom.storeWithoutArtifact(s, 1, cdx(urn(s), 1));
		UUID top = deep;
		for (int i = 0; i < 9; i++) {
			top = containerArtifact(w.org().getUuid(), top);
		}
		listDirectly(w.releaseA(), top);
		assertTrue(guard.holdersOfArtifact(w.org().getUuid(), deep).unresolved());
		UUID secondA = release(w.org(), w.a(), List.of());

		progUpload(componentKey(w, w.a()), secondA, cdx(urn(s), 1));
		assertTrue(isCopy(addedRow(secondA, List.of())));
	}
}

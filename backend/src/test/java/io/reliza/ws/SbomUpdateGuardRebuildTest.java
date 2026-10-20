/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ApiKey;
import io.reliza.model.ArtifactData;

/**
 * SCORE-52 T-32 (design 3.12, F-6): the rebuild flow through addReleasesProgrammatic with A's
 * component key and {@code rebuildRelease} true. A rebuild's uploads are checked while the release
 * still lists its previous rows, so an identical rebuild's row authors and a later new version
 * passes, release-direct, nested, on deliverables and on the commit. Sequence (10), B's copy of A's
 * S after A's rows were replaced, stays refused. Sequence (2) fails against round 3's step 3.
 */
class SbomUpdateGuardRebuildTest extends SbomUpdateGuardProgrammaticTestBase {

	private static final String VERSION = "52.32.0";

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	/** One arm of a release input: where S's document goes. */
	private interface Arm extends Function<String, Map<String, Object>> {
	}

	private Arm direct() {
		return doc -> map("artifacts", entries(doc));
	}

	private Arm nested() {
		return doc -> map("artifacts", new ArrayList<>(List.of(container(fresh(), entry(doc)))));
	}

	private Arm outbound() {
		return doc -> map("outboundDeliverables", deliverables(doc));
	}

	private Arm inbound() {
		return doc -> map("inboundDeliverables", new ArrayList<>(List.of(map("displayIdentifier",
				"score52-idel-" + UUID.randomUUID(), "type", CdxType.CONTAINER.name(), "artifacts", entries(doc)))));
	}

	private Arm onCommit() {
		String uri = uri();
		String commit = commit();
		return doc -> map(CommonVariables.SOURCE_CODE_ENTRY_FIELD, sce(uri, commit, doc));
	}

	private void build(ApiKey key, World w, String version, boolean rebuild, Arm arm, String doc) throws Exception {
		Map<String, Object> input = releaseInputOf(w.a(), version);
		input.putAll(arm.apply(doc));
		if (rebuild) input.put("rebuildRelease", true);
		progReleases(key, input);
	}

	private void noRowIsACopy(World w, UUID s) {
		for (UUID row : rowsOf(w.org().getUuid(), s)) {
			assertFalse(isCopy(row), row.toString());
		}
	}

	/** Sequence (2) on {@code arm}: v1, an identical rebuild, then a rebuild with v2. */
	private void identicalRebuildThenBump(Arm arm) throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, VERSION, false, arm, cdx(urn(s), 1));
		build(keyA, w, VERSION, true, arm, cdx(urn(s), 1));
		noRowIsACopy(w, s);

		build(keyA, w, VERSION, true, arm, cdx(urn(s), 2));
		assertEquals(2, rebom.versionsStored(s), "v1 and v2 were stored");
		noRowIsACopy(w, s);
	}

	// (1)
	@Test
	void aBumpOnRebuildPasses() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, VERSION, false, direct(), cdx(urn(s), 1));
		build(keyA, w, VERSION, true, direct(), cdx(urn(s), 2));
		assertEquals(2, rebom.versionsStored(s));
		noRowIsACopy(w, s);
	}

	// (2)
	@Test
	void anIdenticalRebuildAuthorsAndTheNextBumpPasses() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, VERSION, false, direct(), cdx(urn(s), 1));
		UUID r = releaseOf(w.a(), VERSION);
		UUID r1 = artifactsOf(r).get(0);

		build(keyA, w, VERSION, true, direct(), cdx(urn(s), 1));
		List<UUID> listed = artifactsOf(r);
		assertEquals(1, listed.size(), listed.toString());
		UUID r1b = listed.get(0);
		assertFalse(r1b.equals(r1));
		assertFalse(isCopy(r1b), "the identical rebuild's row authors");
		ArtifactData old = artifactService.getArtifactData(r1).orElseThrow();
		assertEquals(StatusEnum.ARCHIVED, old.getStatus());

		build(keyA, w, VERSION, true, direct(), cdx(urn(s), 2));
		assertEquals(2, rebom.versionsStored(s), "v1 and v2 reached addBom and were stored");
		noRowIsACopy(w, s);
	}

	// (2), a new release instead of the final rebuild
	@Test
	void anIdenticalRebuildThenABumpOnANewReleasePasses() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, VERSION, false, direct(), cdx(urn(s), 1));
		build(keyA, w, VERSION, true, direct(), cdx(urn(s), 1));
		build(keyA, w, "52.32.1", false, direct(), cdx(urn(s), 2));
		assertEquals(2, rebom.versionsStored(s));
		noRowIsACopy(w, s);
	}

	// (3)
	@Test
	void repeatedIdenticalRebuildsThenABumpPass() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, VERSION, false, direct(), cdx(urn(s), 1));
		for (int i = 0; i < 3; i++) {
			build(keyA, w, VERSION, true, direct(), cdx(urn(s), 1));
		}
		build(keyA, w, VERSION, true, direct(), cdx(urn(s), 2));
		assertEquals(2, rebom.versionsStored(s));
		assertEquals(5, rowsOf(w.org().getUuid(), s).size());
		noRowIsACopy(w, s);
	}

	// (4)
	@Test
	void anIdenticalUploadOnANewReleaseAuthorsAndOutlivesTheFirstRow() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, "52.32.r1", false, direct(), cdx(urn(s), 1));
		build(keyA, w, "52.32.r2", false, direct(), cdx(urn(s), 1));
		UUID r1 = releaseOf(w.a(), "52.32.r1");
		UUID r2Row = artifactsOf(releaseOf(w.a(), "52.32.r2")).get(0);
		assertFalse(isCopy(r2Row));
		replaceOnRelease(r1, artifactsOf(r1).get(0));

		build(keyA, w, "52.32.r3", false, direct(), cdx(urn(s), 2));
		assertEquals(2, rebom.versionsStored(s));
	}

	// (5)
	@Test
	void nestedInAReleaseArtifact() throws Exception {
		identicalRebuildThenBump(nested());
	}

	// (6)
	@Test
	void onAnOutboundDeliverable() throws Exception {
		identicalRebuildThenBump(outbound());
	}

	// (6)
	@Test
	void onAnInboundDeliverable() throws Exception {
		identicalRebuildThenBump(inbound());
	}

	// (7)
	@Test
	void onTheCommit() throws Exception {
		identicalRebuildThenBump(onCommit());
	}

	// (10)
	@Test
	void bsCopyAfterAsRowsWereReplacedIsRefusedItsNextVersion() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		UUID s = UUID.randomUUID();
		build(keyA, w, VERSION, false, direct(), cdx(urn(s), 1));
		UUID r = releaseOf(w.a(), VERSION);
		ApiKey keyB = componentKey(w, w.b());
		progUpload(keyB, w.releaseB(), cdx(urn(s), 1));
		assertTrue(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		replaceOnRelease(r, artifactsOf(r).get(0));

		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(s), 2)));
		assertEquals(1, rebom.versionsStored(s));
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ApiKey;

/**
 * SCORE-52 T-15 (ADR-3 step 5, design 3.0 item 7): a serialNumber no component holds needs
 * organization-wide write. Every row of S unlisted, or nested only in an unlisted artifact: a key
 * with write on the target only is refused, an organization-wide key passes. One unlisted row
 * beside one held by A: A's key passes, B's is refused. On an orphan, B's verbatim copy passes as a
 * copy and its next version is refused; and when A's authorizing row is replaced after B copied S,
 * B's next version is refused until an organization-wide upload leaves B an authorizing row.
 */
class SbomUpdateGuardOrphanTest extends SbomUpdateGuardProgrammaticTestBase {

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	@Test
	void sWhoseEveryRowIsUnlistedNeedsOrganizationWideWrite() throws Exception {
		World w = world();
		replaceOnRelease(w.releaseA(), w.x());
		ApiKey keyB = componentKey(w, w.b());
		ApiKey keyA = componentKey(w, w.a());

		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(w.serial()), 2)));
		assertRefusedUpload(() -> progUpload(keyA, w.releaseA(), cdx(urn(w.serial()), 2)));
		progUpload(orgRwKey(w), w.releaseB(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	@Test
	void oneUnlistedRowBesideARowHeldByAPassesAOnly() throws Exception {
		World w = world();
		bomArtifact(w.org().getUuid(), w.serial(), "1");
		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(w.serial()), 2)));
		progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	@Test
	void sNestedOnlyInAnUnlistedArtifactIsAnOrphan() throws Exception {
		World w = world();
		UUID nested = bomArtifact(w.org().getUuid(), w.serial(), "1");
		containerArtifact(w.org().getUuid(), nested);
		replaceOnRelease(w.releaseA(), w.x());
		assertEquals(List.of(), List.copyOf(guard.holdersOfArtifact(w.org().getUuid(), nested).components()));

		assertRefusedUpload(() -> progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(w.serial()), 2)));
	}

	@Test
	void onAnOrphanBsVerbatimCopyPassesAsACopyAndItsNextVersionIsRefused() throws Exception {
		World w = world();
		replaceOnRelease(w.releaseA(), w.x());
		ApiKey keyB = componentKey(w, w.b());

		progUpload(keyB, w.releaseB(), cdx(urn(w.serial()), 1));
		assertTrue(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(w.serial()), 2)));
	}

	// the delayed case
	@Test
	void afterAsRowIsReplacedBsCopyAuthorizesNothingUntilAnOrganizationWideUpload() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		ApiKey keyB = componentKey(w, w.b());
		UUID s = UUID.randomUUID();
		progUpload(keyA, w.releaseA(), cdx(urn(s), 1));
		UUID r1 = addedRow(w.releaseA(), List.of(w.x()));
		assertFalse(isCopy(r1));
		progUpload(keyB, w.releaseB(), cdx(urn(s), 1));
		List<UUID> bBefore = artifactsOf(w.releaseB());
		assertTrue(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		replaceOnRelease(w.releaseA(), r1);

		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(s), 2)));

		progUpload(orgRwKey(w), w.releaseB(), cdx(urn(s), 2));
		List<UUID> afterOrg = artifactsOf(w.releaseB());
		assertFalse(isCopy(addedRow(w.releaseB(), bBefore)));
		progUpload(keyB, w.releaseB(), cdx(urn(s), 3));
		assertFalse(isCopy(addedRow(w.releaseB(), afterOrg)), "B now holds an authorizing row");
		assertEquals(3, rebom.versionsStored(s));
	}
}

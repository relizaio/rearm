/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.JustifiedUnknownReason;
import io.reliza.service.ReleaseSbomJustificationService.Overwrite;

/** FDAW-14 row 11 (REQ-7): A3's fingerprint line and count term are callable readers. */
class ReleaseSbomJustificationFingerprintTest extends ReleaseSbomJustificationTestBase {

	@Test
	void theLineMovesWithTheRevisionAndTheCountWithTheAuditAfterTheBuild() throws Exception {
		String purl = "pkg:deb/debian/zlib@1.3";
		World w = componentRelease(null);
		UUID serial = headOf(w.release(), PROFILE, List.of(hashless(purl), hashless("pkg:deb/debian/xz@5")));
		World other = componentRelease(null);
		UUID otherSerial = headOf(other.release(), PROFILE, List.of(hashless(purl)));
		UUID caller = admin().getUuid();

		assertEquals("justifications:" + serial + "=0", justificationService.fingerprintLine(serial));
		ZonedDateTime beforeAll = ZonedDateTime.now().minusSeconds(1);
		justificationService.set(head(serial), List.of(keyOf(purl)), JustifiedUnknownReason.OTHER, "vendored",
				null, caller, null, Overwrite.REPLACE);
		assertEquals("justifications:" + serial + "=1", justificationService.fingerprintLine(serial));
		ZonedDateTime builtAt = ZonedDateTime.now().plusSeconds(1);
		assertEquals(1, justificationService.countAuditSince(serial, beforeAll));
		assertEquals(0, justificationService.countAuditSince(serial, builtAt), "a set made before the build");

		Thread.sleep(1100);
		justificationService.clear(head(serial), List.of(keyOf(purl)), "upstream now hashes it", caller);
		assertEquals("justifications:" + serial + "=2", justificationService.fingerprintLine(serial));
		assertEquals(1, justificationService.countAuditSince(serial, builtAt), "a write after the build");

		justificationService.set(head(otherSerial), List.of(keyOf(purl)), JustifiedUnknownReason.OTHER, "x", null,
				caller, null, Overwrite.REPLACE);
		assertEquals("justifications:" + serial + "=2", justificationService.fingerprintLine(serial));
		assertEquals(1, justificationService.countAuditSince(serial, builtAt), "another head moves neither");
	}
}

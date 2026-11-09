/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Utils#sha256Hex}.
 *
 * <p>VEX import dedupe keys proposals on the stored hash of each statement, and
 * CLI session codes are stored hashed, so the rendering must stay byte-identical
 * to what the former private helpers produced: lowercase hex of SHA-256 over the
 * UTF-8 bytes. A change here would make every existing row look new.
 */
class UtilsSha256HexTest {

	@Test
	void matchesKnownVectorAsLowercaseHex() {
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
				Utils.sha256Hex("abc"));
	}

	@Test
	void hashesUtf8Bytes() {
		String value = "caf\u00e9";
		assertEquals(Utils.bytesToHexSha256(value.getBytes(StandardCharsets.UTF_8)),
				Utils.sha256Hex(value));
	}

	@Test
	void cliSessionCodesDelegates() {
		assertEquals(Utils.sha256Hex("code"), CliSessionCodes.sha256Hex("code"));
	}
}

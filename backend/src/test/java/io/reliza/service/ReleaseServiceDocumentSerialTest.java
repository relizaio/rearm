/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * FDAW-2 (B1 interim): a package build's VDR/VEX identity is a fresh random v4 urn:uuid that matches
 * the CycloneDX serialNumber pattern. Design T5.
 */
class ReleaseServiceDocumentSerialTest {

	private static final String CDX_SERIAL_PATTERN =
			"^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

	@Test
	void aNewSerialIsAV4UrnUuidMatchingTheCdxPattern() {
		String serial = ReleaseService.newDocumentSerial();
		assertTrue(serial.matches(CDX_SERIAL_PATTERN), serial);
		assertEquals(4, UUID.fromString(serial.substring("urn:uuid:".length())).version());
	}

	@Test
	void aThousandSerialsAreDistinct() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) seen.add(ReleaseService.newDocumentSerial());
		assertEquals(1000, seen.size());
	}
}

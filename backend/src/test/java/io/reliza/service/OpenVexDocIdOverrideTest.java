/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import org.cyclonedx.model.Bom;
import org.junit.jupiter.api.Test;

/**
 * FDAW-2 (B1 interim): a package build gives the OpenVEX document its own {@code @id}; without one the
 * deterministic {@link OpenVexService#buildOpenVexDocId} stays. Design T6.
 */
class OpenVexDocIdOverrideTest {

	private static final UUID RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000042");

	@Test
	void aGivenDocIdIsTheDocumentsId() {
		String given = ReleaseService.newDocumentSerial();
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(new Bom(), RELEASE, null, null, null, null,
				Boolean.FALSE, Boolean.FALSE, given);
		assertEquals(given, doc.get("@id"));
	}

	@Test
	void withoutADocIdTheDeterministicIdStays() {
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(new Bom(), RELEASE, null, null, null, null,
				Boolean.FALSE, Boolean.FALSE, null);
		assertEquals(OpenVexService.buildOpenVexDocId(RELEASE, null, null, null, Boolean.FALSE, Boolean.FALSE),
				doc.get("@id"));
	}

	@Test
	void theTimestampIsAnInstant() {
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(new Bom(), RELEASE, null,
				ZonedDateTime.parse("2024-01-02T03:04:05+01:00[Europe/Paris]"), null, null,
				Boolean.FALSE, Boolean.FALSE, ReleaseService.newDocumentSerial());
		assertEquals("2024-01-02T02:04:05Z", doc.get("timestamp"));
		assertDoesNotThrow(() -> Instant.parse((String) doc.get("timestamp")));
	}
}

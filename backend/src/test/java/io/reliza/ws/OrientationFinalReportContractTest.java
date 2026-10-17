/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code final-report} section.
 *
 * <p>One file per orientation section: add your assertion to the file for the section you changed, never at the end
 * of a shared class (RD4-15).
 */
class OrientationFinalReportContractTest {

	/** Task RD5-9: the FINAL report ships with {@code session close --final} (RD5-4), not an upload and a close. */
	@Test
	void theFinalReportShipsWithCloseFinal() throws IOException {
		String raw = section("final-report");
		assertTrue(raw.contains("rearm agent session close --final /tmp/final.json\n"), "the ship command");
		String doc = raw.replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("Ship it and close the session in one call:"), "one call");
		assertTrue(doc.contains("`--final` files the report as an `AGENTIC_REPORT` artifact (display id `final`, tag"
				+ " `agenticPhase=FINAL`), then closes the session (§2.8); a failed upload stops before the close and"
				+ " leaves the session open."), "what --final does");
		assertFalse(doc.contains("Then proceed to `rearm agent session close` (§2.8)."), "the separate close is replaced");
		assertFalse(doc.contains("--display-id final"), "the add-artifact upload is replaced");
	}
}

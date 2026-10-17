/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

/**
 * Reads the orientation for its contract tests, one class per section (task RD4-15). Helpers only; no tests go
 * here.
 */
final class OrientationText {

	private OrientationText() {}

	/** The whole served document: the core, then every section in the core's order (task RD3-10). */
	static String orientation() throws IOException {
		return AgentOrientation.full();
	}

	/** One section alone, as it is served: each rule is asserted where an agent reads it (task RD3-10). */
	static String section(String key) throws IOException {
		return AgentOrientation.section(key).orElseThrow(() -> new AssertionError("no orientation section " + key));
	}

	/** The text from {@code from} up to the next {@code to}, or to the end. */
	static String between(String doc, String from, String to) {
		int start = doc.indexOf(from);
		assertTrue(start >= 0, "orientation has no " + from);
		int end = doc.indexOf(to, start + from.length());
		return end < 0 ? doc.substring(start) : doc.substring(start, end);
	}
}

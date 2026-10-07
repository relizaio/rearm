/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code install-cli} section.
 *
 * <p>One file per orientation section: add your assertion to the file for the section you changed, never at the end
 * of a shared class (RD4-15).
 */
class OrientationInstallCliContractTest {

	/** The first CLI release that carries the RD5 verbs; change it here and in install-cli.md together. */
	static final String RD5_CLI_MIN = "26.10.1";

	/**
	 * Task RD5-9: the board sections name the RD5 verbs, so install-cli names the CLI they need, and how to tell an
	 * older one: an older CLI answers {@code task verify --help} with the parent's help, not an error, so the probe is
	 * whether {@code task --help} lists it.
	 */
	@Test
	void theBoardVerbsNameTheirMinimumCli() throws IOException {
		String doc = section("install-cli").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("**The board verbs need CLI `" + RD5_CLI_MIN + "` or later.** `task verify`, `task push`,"
				+ " `agent git commit` and `agent git merge`, `session open`, `session close --final` and `session current`,"
				+ " `task signoff --pr`, the brief's `next <TYPE>: <path>` lines and the checks in `doc publish --json`,"
				+ " which the board sections name, are not in `26.09.1`."), "the minimum version and the verbs");
		assertTrue(doc.contains("If `rearm agent task --help` does not list `verify`, your CLI predates them: tell the"
				+ " operator what `rearm version` prints."), "how to tell an older CLI");
	}
}

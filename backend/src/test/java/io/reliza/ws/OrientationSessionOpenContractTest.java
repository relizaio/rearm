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
 * The session lifecycle in the orientation's core, and the init fields in {@code session-details}, as the RD5 CLI
 * verbs run them (task RD5-9): {@code session open} records the session as the repository's current one and files
 * the ORIENTATION report, every verb's {@code --session} falls back to it (RD5-4), and {@code session close --final}
 * files the FINAL report and closes. The sentences that told an agent to copy the ids are replaced, not kept beside.
 *
 * <p>A class of its own, named after the rule: the core's {@link OrientationContractTest} is shared, and new tests
 * never go at the end of a shared class (RD4-15).
 */
class OrientationSessionOpenContractTest {

	private static String core() throws IOException {
		return section("core").replaceAll("[ \\t]*\\n[ \\t]*", " ");
	}

	@Test
	void theCoreOpensTheSessionWithSessionOpen() throws IOException {
		String core = section("core");
		assertTrue(core.contains("rearm agent session open \\\n"), "the core's command is session open");
		assertTrue(core.contains("  --orientation <orientation-report-file>\n```"), "the command files the ORIENTATION report");
		String doc = core();
		assertTrue(doc.contains("`session open` runs `session init` (every init flag applies), records the session as the"
				+ " current one for this repository on the instance your credentials point at, and files the ORIENTATION"
				+ " report given with `--orientation`."), "what open does");
		assertFalse(doc.contains("Record the response's `uuid` (every later session command takes it)"),
				"the copy-the-ids sentence is replaced");
	}

	@Test
	void aVerbWithoutSessionFallsBackToTheCurrentSession() throws IOException {
		String doc = core();
		assertTrue(doc.contains("A verb run without `--session` falls back to that current session, so you carry no ids;"
				+ " `rearm agent session current` prints it, and `session current --set <session-uuid>` makes an open"
				+ " session of yours current in another worktree."), "the fallback and session current");
		assertTrue(doc.contains("The `REARM_` lines `open` prints are for reading: never export them."),
				"the printed ids are not exported");
	}

	@Test
	void policyEventsAreReadWhenOpenSaysThereAreSome() throws IOException {
		String doc = core();
		assertTrue(doc.contains("When the session has policy events, `open` says so; read them with `rearm agent session"
				+ " show <session-uuid>`: they say what the organization expects of this session now."), "policy events");
		assertFalse(doc.contains("Read `policyEvents[]` on every init"), "the init-response sentence is replaced");
	}

	@Test
	void theCoreClosesWithFinal() throws IOException {
		assertTrue(section("core").contains("rearm agent session close --final <final-report-file>\n"),
				"the core's close command files the FINAL report");
		String doc = core();
		assertTrue(doc.contains("When the work is complete, file the FINAL report (§2.6) and close in one call:"), "one call");
		assertTrue(doc.contains("A closed session is no repository's current session any more."), "the close clears it");
		assertTrue(doc.contains("`--final` files the report first; a failed upload stops before the close and leaves the"
				+ " session open. With `--final` the session uuid may be left out: the current session for this repository"
				+ " on that instance is closed."), "what --final does");
		assertFalse(doc.contains("**ship the FINAL report first (§2.6)**"), "the two-step close is replaced");
		assertFalse(doc.contains("rearm agent session close <session-uuid>"), "the uuid-only close is replaced");
	}

	@Test
	void sessionDetailsSaysOpenKeepsTheFields() throws IOException {
		String doc = section("session-details").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("`session open` keeps them as this repository's current session (§2.3), so the verbs read"
				+ " them from there; after a bare `init`, record them yourself:"), "session-details");
		assertFalse(doc.contains("Three fields you must record for the rest of the session"), "the record-them sentence is replaced");
	}
}

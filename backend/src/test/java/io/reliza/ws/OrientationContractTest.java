/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.between;
import static io.reliza.ws.OrientationText.orientation;
import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The orientation as a whole and its core: the split into sections (task RD3-10), what every agent reads
 * first, and the URLs that serve it.
 *
 * <p>The orientation document is the agent-facing API contract, and for document handoff it is the
 * ONLY thing an agent reads: role prompts are operator material kept in the repository, and agents
 * receive their role's prompt with the task rather than fetching it. So anything the server refuses a
 * document for has to be stated here. A rule enforced in code and undocumented in the one place agents
 * look is a rule agents will break, and the refusal arrives after the work is done.
 *
 * <p>One file per orientation section: add your assertion to the file for the section you changed, or to a new
 * {@code Orientation<Section>ContractTest} for a section that has none, never at the end of a shared class. Task
 * RD4-15 split the old {@code AgentOrientationContractTest} this way, because every task that appended to its end made
 * the next PR conflict at the closing brace; the methods kept their names.
 */
class OrientationContractTest {

	@Test
	void theOrientationDoesNotPointAgentsAtEndpointsThatDoNotExist() throws IOException {
		// The role prompts were briefly served at /api/agents/roles/. They are operator material
		// and are not shipped in the image, so a citation would 404 -- and an agent cannot tell a
		// missing document from a misconfigured credential.
		String doc = orientation();
		assertFalse(doc.contains("/api/agents/roles/"),
				"orientation cites a role-prompt endpoint that is no longer served");
		assertFalse(new ClassPathResource("static/agents/roles/reviewer.md").exists(),
				"role prompts must not ship in the image; they are repository reference text");
	}

	@Test
	void theRateLimitAndTheBulkReadAreStated() throws IOException {
		// task cc14f4cb: a coordinator read tasks one by one, ~40 calls a poll, and was answered 429.
		String doc = section("core");
		String limit = "at most " + RateLimitingFilter.REQUESTS_PER_WINDOW + " requests per "
				+ RateLimitingFilter.WINDOW_SECONDS + " seconds";
		assertTrue(doc.replaceAll("\\s+", " ").contains(limit), "orientation states the limit the filter enforces: " + limit);
		// T-2 of round 1: a CLI command with a key is a token exchange and a call, both counted.
		String commands = "about " + RateLimitingFilter.REQUESTS_PER_WINDOW / 2 + " commands per "
				+ RateLimitingFilter.WINDOW_SECONDS + " seconds";
		assertTrue(doc.replaceAll("\\s+", " ").contains(commands), "orientation counts in commands: " + commands);
		assertTrue(doc.replaceAll("\\s+", " ").contains("spends two of them"), "and says why");
		assertTrue(doc.contains("HTTP 429 with a `Retry-After` header"));
		String bulk = between(section("taking-a-task"), "**Read the board in bulk.**", "### 2.5a");
		String flat = bulk.replaceAll("\\s+", " ");
		assertTrue(flat.contains("rearm agent task list --board <board-uuid> --status <status>"), bulk);
		assertTrue(flat.contains("task show <uuid> <uuid>"), "a named set in one request");
		assertTrue(flat.contains("board events --after <last seq>"), bulk);
	}

	@Test
	void theOrientationStatesNoPriorityThreshold() throws IOException {
		// task de91c937: nothing agents read may suggest a threshold the board did not set
		String doc = orientation().replaceAll("\\s+", " ");
		assertFalse(doc.contains("priority threshold"), "no priority threshold in the orientation");
		assertFalse(doc.contains("threshold you applied"), "no threshold to apply");
	}

	@Test
	void pollingCountsAsActivity() throws IOException {
		// task 6e7fe6fe: a working board agent was told to touch "when quiet", which it never is, and was
		// auto-closed mid-work; any call now counts, and the window, warning and holder rule are stated
		String doc = section("core").replaceAll("\\s+", " ");
		assertTrue(doc.contains("Any call you make with your session id counts as activity"), "polling counts");
		assertTrue(doc.contains("It counts when the call is made with the key that opened the session"), "own key only (round 2, T-1)");
		assertTrue(doc.contains("Touch only when you will make no calls for a long stretch"), "when to touch");
		assertTrue(doc.contains("agentSessionIdleCloseHours"), "the window is the org's");
		assertTrue(doc.contains("warned two hours before"), "the warning");
		assertTrue(doc.contains("given twice the window"), "a holder's window");
	}

	/** Task RD3-8: the round-trip rules are stated once; since RD3-10 in the core, which every agent reads. */
	@Test
	void theRoundTripRulesAreStated() throws IOException {
		String doc = section("core").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("### Round trips and output"), "in the core");
		assertFalse(orientation().replace(section("core"), "").contains("### Round trips and output"), "and only there");
		for (String rule : new String[] {
				"- Run independent commands in one turn.",
				"- Never re-poll in a short loop: `rearm agent wait` waits for you.",
				"- Read only the fields you need from a CLI result. The board mutations print compact lines by default; add `--json` when you need the shape.",
				"- Cap logs with `tail` or `grep` rather than reading them whole.",
				"- Keep report bodies in files, never in a command line.",
				"- Do not print a whole task or board after a mutation."}) {
			assertTrue(doc.contains(rule), rule);
		}
	}

	// ---------- task RD3-10: the split by action ----------

	private static final Path DIR = Path.of("src/main/resources/static/agents/orientation");

	/** Every row of the core's table names a section that exists, and every section file is in the table. */
	@Test
	void theIndexNamesEverySectionAndEverySectionIsInTheIndex() throws IOException {
		List<String> keys = AgentOrientation.sections().stream().map(AgentOrientation.Section::key).toList();
		assertEquals(keys.size(), new java.util.HashSet<>(keys).size(), "no section listed twice: " + keys);
		java.util.Set<String> files;
		try (var list = Files.list(DIR)) {
			files = list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md"))
					.map(n -> n.substring(0, n.length() - 3)).filter(n -> !AgentOrientation.CORE.equals(n))
					.collect(java.util.stream.Collectors.toSet());
		}
		assertEquals(files, new java.util.HashSet<>(keys), "the table and the section files are the same set");
		for (String k : List.of("taking-a-task", "publishing", "asking", "waiting", "commit-trailers", "signing-key", "artifacts",
				"deployments", "inbox", "install-cli", "session-details", "final-report", "stopping-and-asking")) {
			assertTrue(keys.contains(k), "the design's action " + k);
		}
		String date = section("core").lines().filter(l -> l.startsWith("last_updated: ")).findFirst().orElseThrow()
				.substring("last_updated: ".length());
		for (AgentOrientation.Section sec : AgentOrientation.sections()) {
			assertEquals("<!-- orientation section: " + sec.key() + " · core " + date + " -->",
					section(sec.key()).lines().findFirst().orElse(""), "a section's first line names its key and the core's version");
			assertFalse(sec.when().isBlank(), sec.key() + " says when to read it");
		}
	}

	/**
	 * The core is what every agent loads, at every task's start since RD3-8: about 3k tokens, held under a
	 * ceiling chosen before the text was cut (architecture round 2).
	 */
	@Test
	void theCoreStaysUnderItsBudget() throws IOException {
		int ceiling = 12 * 1024;
		int bytes = section("core").getBytes(StandardCharsets.UTF_8).length;
		assertTrue(bytes <= ceiling, "the core is " + bytes + " bytes; its ceiling is " + ceiling);
		assertTrue(bytes * 5 < orientation().getBytes(StandardCharsets.UTF_8).length, "the core is well under a fifth of the document");
		String core = section("core");
		for (String once : List.of("### 1.2 ", "### 2.6 ", "## 7. ", "#### Your tool's own session id")) {
			assertFalse(core.contains(once), "once per host or per session end, not in the core: " + once);
		}
		assertTrue(core.contains("rearm agent claude hooks install --project"), "the hooks' install command stays");
		assertTrue(core.contains("rearm agent session open"), "the session's opening command stays (session open since RD5-9)");
	}

	/** Nothing is lost: the whole document is the core and every section in order, and every old heading still resolves once. */
	@Test
	void theWholeDocumentKeepsEveryHeadingAndIsTheCoreThenTheSections() throws IOException {
		String full = orientation();
		StringBuilder expected = new StringBuilder(section("core"));
		for (AgentOrientation.Section sec : AgentOrientation.sections()) expected.append('\n').append(section(sec.key()));
		assertEquals(expected.toString(), full);
		List<String> headings = full.lines().filter(l -> l.startsWith("## ") || l.startsWith("### ")).toList();
		for (String old : OLD_HEADINGS) {
			assertEquals(1, headings.stream().filter(old::equals).count(), "the heading, and so its anchor, resolves once: " + old);
		}
		assertTrue(full.startsWith("---\n"), "the front matter still leads the document");
	}

	/** Every ## and ### heading of the single document as it was before the split. */
	private static final List<String> OLD_HEADINGS = List.of(
			"## 1. Prerequisites",
			"### 1.1 Environment variables",
			"### 1.2 Install the CLI — `26.09.1` exactly",
			"## 2. Session lifecycle",
			"### 2.1 When to open a session",
			"### 2.2 Picking a `clientSessionId`",
			"### 2.3 Initializing",
			"### 2.4 Generate and enrol your signing key (first run, once per agent host)",
			"### 2.5 Artifacts on the session (when policies require them)",
			"### 2.5 Taking a task (task boards)",
			"### 2.5a Publishing documents (task boards)",
			"### 2.5b Asking a question (task boards)",
			"### 2.5c Waiting for work (task boards)",
			"### 2.5d Working a task in a fresh context (task boards)",
			"### 2.6 Final session report",
			"### 2.7 Commit trailers",
			"### 2.8 Heartbeat and close",
			"## 3. Polling the inbox",
			"### Round trips and output",
			"### Event kinds and what to do with them",
			"### What's NOT in the inbox",
			"## 4. Sample \"agent fix loop\" pattern",
			"## 5. Read-side helpers",
			"### Reading a release's vulnerabilities and violations",
			"## 6. CEL surface (what policies can check)",
			"## 7. When to stop and ask the operator",
			"### 7.1 `session.status = BLOCKED`",
			"### 7.2 Repeated `attribution.state = REJECTED`",
			"### 7.3 Lifecycle stuck at REJECTED on multiple retries",
			"## 8. Self-check on startup",
			"## 9. Quick command index",
			"## 10. Deployment operations (ReARM Pro only)",
			"### 10.1 Discovery — `listfeaturesets`",
			"### 10.2 Versioning — `versionfeatureset`",
			"### 10.3 Switching — `switchfeatureset`",
			"### 10.4 Pre-flight checklist"
	);

	/** The URLs: the whole document as before, the core and each section alone, an unknown name lists the names. */
	@Test
	void theWholeDocumentTheCoreAndEachSectionAreServed() throws IOException {
		AgentDocsController c = new AgentDocsController();
		assertEquals(orientation(), new String(c.orientation().getBody(), StandardCharsets.UTF_8));
		assertEquals(section("core"), new String(c.orientationSection("core").getBody(), StandardCharsets.UTF_8));
		assertEquals(section("waiting"), new String(c.orientationSection("waiting").getBody(), StandardCharsets.UTF_8));
		var missing = c.orientationSection("fishing");
		assertEquals(404, missing.getStatusCode().value());
		String body = new String(missing.getBody(), StandardCharsets.UTF_8);
		assertTrue(body.contains("No orientation section named 'fishing'") && body.contains("taking-a-task") && body.contains("core"), body);
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.between;
import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict;

/**
 * The orientation's {@code publishing} section: the review item index the validator enforces, the carry-forward
 * rule, parallel tasks and the sign-off acknowledgement.
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
class OrientationPublishingContractTest {

	/**
	 * Task RD5-9: a round is written at the brief's next-round line (RD5-5), a republish in the same hop is a new
	 * version with no {@code --file} (RD5-6), and {@code --json} carries the checks (RD5-8). The sentence that sent
	 * agents to resolve the path from the board's documentPath read is replaced.
	 */
	@Test
	void aRoundIsWrittenWhereTheBriefSays() throws IOException {
		String doc = section("publishing").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("Write each round at the path the brief's `next <TYPE>: <path>` line names (`rearm agent"
				+ " task brief`): the round a publish would cut,"), "the next-round line");
		assertTrue(doc.contains("`doc publish` without `--file` publishes that path."), "the default path");
		assertFalse(doc.contains("the board's `documentPath` read returns"), "the documentPath sentence is replaced");
	}

	@Test
	void aRepublishInTheSameHopIsANewVersion() throws IOException {
		String doc = section("publishing").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("A republish in the same hop needs no `--file` either: when this hop already published the"
				+ " newest round of the type, the brief's line ends `(republish = new version of round <n>)`, and the"
				+ " publish defaults to that round's path and lands as a new version of it. `--file` always wins."),
				"the version case");
	}

	@Test
	void publishJsonCarriesTheChecks() throws IOException {
		String doc = section("publishing").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("With `--json`, `doc publish` prints one JSON value on stdout and nothing on stderr on"
				+ " success: the release, with `checks` (`verdict`, `counts`, `blocking`, `lines`; null for a document"
				+ " without elements) and any `notices`. With `--check` it prints `{check: true, checks}`. A refusal still"
				+ " goes to stderr, with exit 1 and nothing on stdout."), "--json");
	}

	@Test
	void everyStatusTheValidatorAcceptsIsDocumented() throws IOException {
		// Drift here is one-directional and silent: adding a status in code without documenting it
		// leaves agents unable to use it, while documenting one the validator rejects gets their
		// document refused.
		String doc = section("publishing");
		// Read off the ENUMS, so adding a member without documenting it fails here. This used to
		// read two string sets on the validator; the vocabulary now lives in one place and the
		// test follows it automatically.
		for (BoardReviewItemStatus status : BoardReviewItemStatus.values()) {
			assertTrue(doc.contains(status.name()),
					"orientation does not mention the review item status " + status);
		}
		for (BoardReviewVerdict verdict : BoardReviewVerdict.values()) {
			assertTrue(doc.contains(verdict.name()),
					"orientation does not mention the verdict " + verdict);
		}
	}

	@Test
	void theCarryForwardRuleIsStated() throws IOException {
		// The rule whose breach is silent -- only the newest round is read, so a dropped open
		// review item vanishes from the record while the problem stands.
		String doc = section("publishing");
		assertTrue(doc.contains("left open"), "the carry-forward rule should be stated");
		assertTrue(doc.contains("same id"), "ids carrying across rounds should be stated");
	}

	@Test
	void aPartlyFixedReviewItemIsReRaisedUnderANewId() throws IOException {
		// The no-progress stop compares open ids, so a review item carried under its id after a
		// partial fix reads as no movement and parks the task (seen on d0ee3624). Both places an
		// agent meets the rule -- the carry-forward section and the stop paragraph -- have to say
		// what to do instead.
		String carry = section("publishing");
		assertTrue(carry.contains("same id"), "the carry-forward section should keep the same-id rule");
		assertTrue(carry.contains("re-raised under a new id"),
				"the carry-forward section should state the partly fixed exception");
		String stops = between(section("asking"), "**Two things stop a loop**", "### ");
		assertTrue(stops.contains("re-raised under a new id"),
				"the stop paragraph should say why a partly fixed item gets a new id");
		assertTrue(stops.contains("routing rules"),
				"the stop paragraph should point at the served routing-rules block");
	}

	@Test
	void theIndexFieldsTheValidatorRequiresAreDocumented() throws IOException {
		String doc = section("publishing");
		for (String field : List.of("\"kind\"", "\"verdict\"", "\"reviewItems\"", "\"priority\"",
				"\"status\"", "\"title\"", "\"id\"")) {
			assertTrue(doc.contains(field), "orientation does not show the index field " + field);
		}
	}

	@Test
	void parallelTasksAreExplainedToEveryRole() throws IOException {
		// task f3e6f756
		String doc = section("publishing").replaceAll("\\s+", " ");
		assertTrue(doc.contains("**Parallel tasks.**"), "the paragraph");
		for (String rule : List.of("merges the current integration base into each PR branch before every sign-off",
				"merged with the current base tip", "re-checks every other DELIVERING task's PRs after each merge",
				"moves only forward")) {
			assertTrue(doc.contains(rule), rule);
		}
		assertFalse(doc.contains("Your code branches are yours to rebase until they merge"),
				"a tested branch is merged into, never rebased");
	}

	@Test
	void theMergeProcedureIsNamedInParallelTasks() throws IOException {
		// task 71a3dd22
		String doc = section("publishing").replaceAll("\\s+", " ");
		assertTrue(doc.contains("Whoever the board's `delivery.merge` names merges, by its method and in its order"), "who merges");
		assertTrue(doc.contains("The routing-rules block of every served prompt states the procedure."), "where it is stated");
	}

	@Test
	void aCorrectionIsOpenWorkThatNeverBlocks() throws IOException {
		// task cac71351: an item a person filed at an acceptance; the producer addresses it, the
		// server carries the flag.
		String doc = section("publishing").replaceAll("\\s+", " ");
		assertTrue(doc.contains("`\"correction\": true` marks an item a person filed while **accepting** a gate"), "the flag");
		assertTrue(doc.contains("It never blocks routing or completion, but it is open work"), "never blocks");
		assertTrue(doc.contains("**The server writes the flag.**"), "the server's, like decidedBy");
	}

	@Test
	void testedHeadsAreRequiredOnATestersPassOnly() throws IOException {
		// task 5ec48b02: a reviewer's heads are informative; the board holds the PRs to the tester's
		String doc = section("publishing").replaceAll("\\s+", " ");
		assertTrue(doc.contains("It is required on a tester's pass: a `BOARD_TEST_REPORT` that passes must name the head it tested"
				+ " for every PR the task links"), "required on the tester's pass");
		assertTrue(doc.contains("A reviewer may name the heads it looked at; they are informative"), "optional for a reviewer");
	}

	/** Task RD2-34: the orientation names the sign-off check on documents published since the assignment. */
	@Test
	void theSignOffAcknowledgesDocumentsPublishedSinceTheAssignment() throws IOException {
		String doc = section("publishing").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("The server refuses a sign-off that does not acknowledge a document published on the task since your assignment"), doc.length() + "");
		assertTrue(doc.contains("`task show --session <session>` is the acknowledgement"));
		assertTrue(doc.contains("A show without `--session` acknowledges nothing."));
	}

	/**
	 * Task RD3-14: a sign-off from a host without the session's state is refused once, and the show clears it; in the
	 * publishing section's acknowledgement paragraph.
	 */
	@Test
	void aHostWithoutTheSessionsStateIsRefusedOnce() throws IOException {
		String doc = section("publishing").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		int at = doc.indexOf("On a host where the session was not opened");
		assertTrue(at > doc.indexOf("**Documents published while you work.**"), "in the sign-off acknowledgement paragraph");
		assertTrue(doc.contains("a sign-off there after a document lands is refused once; the show records it, and the next sign-off passes."));
	}
}

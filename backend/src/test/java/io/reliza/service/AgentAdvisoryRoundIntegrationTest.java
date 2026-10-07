/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Advisory rounds (task e97fde56, gap analysis §1.38): a role publishes its own prose type on a task
 * another session holds -- assembled at publish, marked, announced -- and nothing else moves.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentAdvisoryRoundIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/advisory";
	private static final String DOCS_SOURCE = "github:acme/advisory-docs";
	private static final String DOCS = "https://github.com/acme/advisory-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(5300);
	private static final RearmSpecificationType ARCH = RearmSpecificationType.ARCHITECTURE;
	private static final RearmSpecificationType DD = RearmSpecificationType.DETAILED_DESIGN;
	private static final String NOT_HOLDER = "only the working session may publish its documents";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private record Worker(AgentData agent, AgentSessionData session) {}

	/** An architect producing ARCHITECTURE per task, a coder taking it and producing DETAILED_DESIGN. */
	private record Rig(Organization org, AgentBoardData board, Worker architect, Worker coder) {}

	private AgentBoardData newBoard(Organization org) throws RelizaException {
		Component target = componentService.createComponent("adv_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "advisory-" + UUID.randomUUID(),
				"advisory", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(ARCH, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.TASK, null, null, null)),
				List.of(new ProducedOutput(DD, InputScope.TASK, false)), null, null), true, WU);
		return agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
	}

	private Worker worker(Organization org, String name) throws RelizaException {
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				name + "-" + UUID.randomUUID(), null, null, null, WU);
		return new Worker(a, agentSessionService.initialize(org.getUuid(), a.getUuid(), null,
				"s-" + UUID.randomUUID(), name, null, null, WU));
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		return new Rig(org, newBoard(org), worker(org, "architect"), worker(org, "coder"));
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private ReleaseData release(ReleaseData rd) {
		return sharedReleaseService.getReleaseData(rd.getUuid()).orElseThrow();
	}

	private ReleaseData publish(Worker w, AgentTaskData t, RearmSpecificationType type, String path, boolean advisory)
			throws RelizaException {
		return agentDocumentService.publish(w.session(), new PublishRequest(t.getUuid(), type, null, path,
				"digest-of-" + path + "-" + UUID.randomUUID(), "text/markdown", null, null, null,
				"c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "doc", ZonedDateTime.now(), null, null, null,
				advisory), WU);
	}

	/** An index round: BOARD_REVIEW_ITEMS with the given review items open. */
	private ReleaseData publishReviewItems(Worker w, AgentTaskData t, List<String> open) throws RelizaException {
		List<Map<String, Object>> reviewItems = open.stream().map(id -> {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("id", id);
			f.put("priority", 1);
			f.put("status", "OPEN");
			f.put("title", "issue " + id);
			return f;
		}).toList();
		Map<String, Object> index = new LinkedHashMap<>();
		index.put("kind", "BOARD_REVIEW_ITEMS");
		index.put("verdict", "REJECTED");
		index.put("reviewItems", reviewItems);
		String path = "review-items/" + UUID.randomUUID() + ".md";
		return agentDocumentService.publish(w.session(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, path, "d-" + UUID.randomUUID(), "text/markdown",
				path + ".json", "i-" + UUID.randomUUID(), index, "c" + UUID.randomUUID().toString().substring(0, 8),
				DOCS, "reviewItems", ZonedDateTime.now(), null), WU);
	}

	/**
	 * A task the architect designed and signed off (so the architect role is in its agent's history on
	 * the board), now held by the coder.
	 */
	private AgentTaskData heldByCoderAfterArchitect(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.architect().agent().getUuid(), r.architect().session().getUuid(), WU);
		ReleaseData design = publish(r.architect(), reload(t), ARCH, "design/one.md", false);
		agentTaskService.signOff(t.getUuid(), r.architect().session().getUuid(), SignOffOutcome.PASSED, "designed",
				List.of(design.getUuid()), WU);
		assertEquals("coder", reload(t).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.coder().agent().getUuid(), r.coder().session().getUuid(), WU);
		return reload(t);
	}

	private RequiredInput architectureInput() {
		return new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.TASK, null, null, null);
	}

	// ---------- 1. an advisory round ----------

	@Test
	public void theArchitectAmendsATaskTheCoderHoldsAndTheCoderReadsItFirst() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldByCoderAfterArchitect(r);
		int eventsBefore = agentBoardService.recentEvents(board(r).getUuid()).size();

		ReleaseData amendment = release(publish(r.architect(), t, ARCH, "design/two.md", true));

		assertEquals(2, amendment.getDocument().round(), "the next round of the type: n + 1");
		assertEquals(ReleaseLifecycle.ASSEMBLED, amendment.getLifecycle(), "assembled at publish");
		assertTrue(amendment.getDocument().advisoryRound());
		assertEquals("architect", amendment.getDocument().publishedByRole());
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.ASSIGNED, after.getStatus(), "routing does not move");
		assertEquals(r.coder().session().getUuid(), after.getAssignment().session());
		List<AgentBoardData.BoardEvent> events = agentBoardService.recentEvents(board(r).getUuid());
		assertEquals(eventsBefore + 1, events.size(), events.toString());
		AgentBoardData.BoardEvent ev = events.get(events.size() - 1);
		assertEquals(AgentBoardData.BoardEventKind.INFO, ev.kind());
		assertTrue(ev.message().contains("architect published ARCHITECTURE round 2 on " + after.label()
				+ " (advisory; coder holds it)"), ev.message());

		// The holder's inputs lead with it: the latest round of the type at its floor.
		after.setRequiredInputs(List.of(architectureInput()));
		AgentTaskInputService.InputVerdict v = agentTaskInputService.evaluate(board(r), null, after).get(0);
		assertTrue(v.met(), String.valueOf(v.unmetReason()));
		assertEquals(amendment.getUuid(), v.resolved().release());

		// The coder's own round is untouched by it.
		ReleaseData note = release(publish(r.coder(), after, DD, "impl/one.md", false));
		assertEquals(1, note.getDocument().round());
		assertEquals(ReleaseLifecycle.DRAFT, note.getLifecycle());
		assertFalse(note.getDocument().advisoryRound());
		assertEquals("coder", note.getDocument().publishedByRole(), "every round names its role now");

		// After a return, the architect's own next round continues the series: n + 2.
		agentTaskService.returnTask(t.getUuid(), r.coder().session().getUuid(), TaskReturnReason.OTHER, "design question", WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.architect().agent().getUuid(), r.architect().session().getUuid(), WU);
		// Never an output of a sign-off: not even its author's, once it holds the task.
		RelizaException claimed = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.architect().session().getUuid(), SignOffOutcome.PASSED, "claims the amendment",
				List.of(amendment.getUuid()), WU));
		assertTrue(claimed.getMessage().contains("published before this hop began"), claimed.getMessage());
		ReleaseData third = release(publish(r.architect(), reload(t), ARCH, "design/three.md", false));
		assertEquals(3, third.getDocument().round());
		assertEquals(ReleaseLifecycle.DRAFT, third.getLifecycle());
		assertFalse(third.getDocument().advisoryRound());
	}

	// ---------- 2. refusals ----------

	@Test
	public void everyOtherCaseIsRefusedAndWritesNothing() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldByCoderAfterArchitect(r);
		int releases = reload(t).getReleases().size();

		RelizaException plain = assertThrows(RelizaException.class, () -> publish(r.architect(), t, ARCH, "design/x.md", false));
		assertTrue(plain.getMessage().contains(NOT_HOLDER), "without the flag, the refusal as before: " + plain.getMessage());

		Worker stranger = worker(r.org(), "stranger");
		RelizaException role = assertThrows(RelizaException.class, () -> publish(stranger, t, ARCH, "design/x.md", true));
		assertTrue(role.getMessage().contains("No role you have held on this board produces ARCHITECTURE"), role.getMessage());

		for (RearmSpecificationType index : List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
				RearmSpecificationType.BOARD_TEST_REPORT, RearmSpecificationType.BOARD_QUESTIONS)) {
			RelizaException e = assertThrows(RelizaException.class, () -> publish(r.architect(), t, index, "x/" + index + ".md", true));
			assertTrue(e.getMessage().contains("An advisory round cannot be an index type"), index + ": " + e.getMessage());
		}

		AgentTaskData intake = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "not yet",
				null, null, null, null, null, null, WU);
		assertEquals(TaskStatus.PENDING_INTAKE, intake.getStatus());
		RelizaException pending = assertThrows(RelizaException.class, () -> publish(r.architect(), intake, ARCH, "design/p.md", true));
		assertTrue(pending.getMessage().contains("Advisory rounds go on active tasks"), pending.getMessage());

		AgentTaskData done = heldByCoderAfterArchitect(r);
		AgentTaskData raw = reload(done);
		raw.setStatus(TaskStatus.COMPLETED);
		agentTaskService.saveData(raw, WU);
		RelizaException completed = assertThrows(RelizaException.class, () -> publish(r.architect(), done, ARCH, "design/c.md", true));
		assertTrue(completed.getMessage().contains("Advisory rounds go on active tasks"), completed.getMessage());

		// A task on another board of the organization, which the architect's agent never worked.
		AgentBoardData other = newBoard(r.org());
		AgentTaskData elsewhere = agentTaskService.register(other, SRC + "#" + ISSUE.incrementAndGet(), "elsewhere",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(elsewhere.getUuid(), other, "architect", 10, null, null, null, null, COORD, WU);
		RelizaException foreign = assertThrows(RelizaException.class, () -> publish(r.architect(), elsewhere, ARCH, "design/o.md", true));
		assertTrue(foreign.getMessage().contains("No role you have held on this board produces ARCHITECTURE"), foreign.getMessage());

		assertEquals(releases, reload(t).getReleases().size(), "no refused publish linked a round");
	}

	// ---------- 3. the holder's flag ----------

	@Test
	public void theHolderSendingTheFlagGetsANormalRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldByCoderAfterArchitect(r);
		int eventsBefore = agentBoardService.recentEvents(board(r).getUuid()).size();
		ReleaseData note = release(publish(r.coder(), t, DD, "impl/flag.md", true));
		assertEquals(ReleaseLifecycle.DRAFT, note.getLifecycle());
		assertFalse(note.getDocument().advisoryRound());
		assertEquals("coder", note.getDocument().publishedByRole());
		assertEquals(eventsBefore, agentBoardService.recentEvents(board(r).getUuid()).size(), "no advisory event");

		agentTaskService.signOff(t.getUuid(), r.coder().session().getUuid(), SignOffOutcome.PASSED, "built",
				List.of(note.getUuid()), WU);
		assertEquals(ReleaseLifecycle.ASSEMBLED, release(note).getLifecycle(), "its sign-off assembles it as today");
	}

	// ---------- 4. carry-forward ----------

	@Test
	public void anAdvisoryProseRoundDoesNotBreakTheIndexCarryForward() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldByCoderAfterArchitect(r);
		ReleaseData first = publishReviewItems(r.coder(), t, List.of("F-1"));
		publish(r.architect(), t, ARCH, "design/between.md", true);

		assertEquals(first.getUuid(),
				agentDocumentService.latestRoundRelease(reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS).orElseThrow().getUuid(),
				"the previous review item round is still the one before the advisory round");
		RelizaException dropped = assertThrows(RelizaException.class, () -> publishReviewItems(r.coder(), reload(t), List.of("F-2")));
		assertTrue(dropped.getMessage().contains("F-1"), "an open item of round 1 cannot be dropped: " + dropped.getMessage());
		ReleaseData second = publishReviewItems(r.coder(), reload(t), List.of("F-1", "F-2"));
		assertEquals(2, second.getDocument().round());
		assertFalse(second.getDocument().advisoryRound());
		assertEquals("coder", second.getDocument().publishedByRole());
	}

	// ---------- documents published since the assignment (task RD2-34) ----------

	/**
	 * On RD2-24 the architect's advisory round landed seconds after the coder picked the task up, and the coder's
	 * note answered the round before it. A sign-off that says what it read is refused while a document published
	 * on the task since the assignment, by anyone but this hop, is not among what it read.
	 */
	@Test
	public void aSignOffAcknowledgesWhatWasPublishedSinceTheAssignment() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldByCoderAfterArchitect(r);
		ReleaseData amendment = release(publish(r.architect(), t, ARCH, "design/amended.md", true));
		ReleaseData note = release(publish(r.coder(), reload(t), DD, "impl/after-amendment.md", false));
		UUID coder = r.coder().session().getUuid();

		RelizaException unread = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(), coder,
				SignOffOutcome.PASSED, "built it", List.of(note.getUuid()), List.of(), WU));
		assertEquals("Task " + reload(t).label() + " has documents published since your assignment that this sign-off"
				+ " does not acknowledge: ARCHITECTURE round 2 (advisory) v" + amendment.getVersion()
				+ ". Run task show --session " + coder + ", read it, then sign off again.", unread.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus(), "refused with no state change");
		assertEquals(coder, reload(t).getAssignment().session());

		// Read, it passes: the architect's round 1 was there at the assignment, the note is the hop's own.
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), coder, SignOffOutcome.PASSED, "built it, amended",
				List.of(note.getUuid()), List.of(amendment.getUuid()), WU);
		assertTrue(passed.getSignOffs().stream().anyMatch(so -> "coder".equals(so.role())), passed.getSignOffs().toString());
	}

	@Test
	public void aSignOffThatSaysNothingOfWhatItReadIsNotChecked() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldByCoderAfterArchitect(r);
		release(publish(r.architect(), t, ARCH, "design/amended-quietly.md", true));
		ReleaseData note = release(publish(r.coder(), reload(t), DD, "impl/older-client.md", false));
		// seenInputs null: an older client, or a programmatic caller that does not track what it read.
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.coder().session().getUuid(), SignOffOutcome.PASSED,
				"older client", List.of(note.getUuid()), null, WU);
		assertTrue(passed.getSignOffs().stream().anyMatch(so -> "coder".equals(so.role())));
	}
}


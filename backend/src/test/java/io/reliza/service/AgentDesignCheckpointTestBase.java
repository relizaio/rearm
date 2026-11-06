/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService.EventPage;
import io.reliza.service.AgentBoardService.LoggedEvent;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.oss.TestInitializer;

/**
 * The boards the design checkpoint tests share (task SPLIT-5, carried from SPLIT-2's
 * {@code AgentSplitRecommendationTestBase}): helpers only, no tests. Two boards, as the design's T-1 and T-2:
 * <ul>
 * <li>plain: architect (10) -> coder (40) -> tester (50);</li>
 * <li>reviewed: architect (10) -> functional_reviewer (20) -> security_reviewer (30) -> coder (40) -> tester (50).</li>
 * </ul>
 * Task SPLIT-9's tests add {@link #unflagged()}: the plain board without the flag, the coder holding CODE_PUSH.
 * The architect produces ARCHITECTURE and a second design document, the reviewers and the coder read both, the coder
 * produces DETAILED_DESIGN and the tester reads it. The coder carries {@code coordinatorCheckpoint} unless the rig is
 * built without it. One agent and session per role; the coordinator is a session actor, as the seat's verbs pass it.
 */
abstract class AgentDesignCheckpointTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	static final String SRC = "github:acme/checkpoint";
	static final String DOCS_SOURCE = "github:acme/checkpoint-docs";
	static final String DOCS = "https://github.com/acme/checkpoint-docs";
	static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
	static final RearmSpecificationType ARCH = RearmSpecificationType.ARCHITECTURE;
	/** The second design document, as the design's boards produce it beside ARCHITECTURE. */
	static final RearmSpecificationType TM = RearmSpecificationType.THREAT_MODEL;
	static final RearmSpecificationType DD = RearmSpecificationType.DETAILED_DESIGN;
	static final String CHECKPOINT = "design checkpoint before coder";
	static final String SENT_ON = "at the design checkpoint";
	static final long ARCHITECT_HOP = 1_000L;
	static final long CODER_HOP = 10_000L;
	static final AtomicInteger ISSUE = new AtomicInteger(8100);

	@Autowired TestInitializer testInitializer;
	@Autowired AgentBoardService agentBoardService;
	@Autowired AgentTaskService agentTaskService;
	@Autowired AgentDocumentService agentDocumentService;
	@Autowired SharedReleaseService sharedReleaseService;
	@Autowired ComponentService componentService;
	@Autowired AgentService agentService;
	@Autowired AgentSessionService agentSessionService;

	record Worker(AgentData agent, AgentSessionData session) {
		UUID sessionUuid() { return session.getUuid(); }
		UUID agentUuid() { return agent.getUuid(); }
	}

	record Rig(Organization org, AgentBoardData board, Worker architect, Worker functional, Worker security,
			Worker coder, Worker tester) {}

	/** T-1's board: architect -> coder -> tester, the coder flagged. */
	Rig plain() throws RelizaException {
		return rig(false, true, HumanGate.NONE);
	}

	/** T-2's board: architect -> functional_reviewer -> security_reviewer -> coder -> tester, the coder flagged. */
	Rig reviewed() throws RelizaException {
		return rig(true, true, HumanGate.NONE);
	}

	/**
	 * The unflagged board of task SPLIT-9's tests: architect -> coder -> tester with no checkpoint, the coder holding
	 * {@code CODE_PUSH}, so the coder is the build boundary (D2's second arm) and the design is the architect's.
	 */
	Rig unflagged() throws RelizaException {
		return rig(false, false, HumanGate.NONE, List.of(AgentCapability.CODE_PUSH));
	}

	/**
	 * @param reviewers whether the two design reviewers sit between the architect and the coder
	 * @param checkpoint whether the coder carries {@code coordinatorCheckpoint}
	 * @param architectGate the architect role's human gate
	 */
	Rig rig(boolean reviewers, boolean checkpoint, HumanGate architectGate) throws RelizaException {
		return rig(reviewers, checkpoint, architectGate, null);
	}

	/** As above, the coder holding {@code coderCapabilities} (null for none). */
	Rig rig(boolean reviewers, boolean checkpoint, HumanGate architectGate, List<AgentCapability> coderCapabilities)
			throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("checkpoint_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "checkpoint-" + UUID.randomUUID(),
				"design checkpoint", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, role("architect", 10, architectGate, List.of(),
				List.of(produces(ARCH), produces(TM)), ARCHITECT_HOP, null, null), true, WU);
		if (reviewers) {
			for (String name : List.of("functional_reviewer", "security_reviewer")) {
				agentBoardService.upsertRoleConfig(board, role(name, "functional_reviewer".equals(name) ? 20 : 30,
						HumanGate.NONE, List.of(input(ARCH), input(TM)),
						List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null, null), true, WU);
			}
		}
		agentBoardService.upsertRoleConfig(board, role("coder", 40, HumanGate.NONE, List.of(input(ARCH), input(TM)),
				List.of(produces(DD)), CODER_HOP, checkpoint ? Boolean.TRUE : null, coderCapabilities), true, WU);
		agentBoardService.upsertRoleConfig(board, role("tester", 50, HumanGate.NONE, List.of(input(DD)),
				List.of(produces(RearmSpecificationType.BOARD_TEST_REPORT)), null, null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		return new Rig(org, board, worker(org, "architect"), worker(org, "functional_reviewer"),
				worker(org, "security_reviewer"), worker(org, "coder"), worker(org, "tester"));
	}

	static AgentBoardService.RoleConfigSpec role(String name, int order, HumanGate gate, List<RequiredInput> inputs,
			List<ProducedOutput> outputs, Long hopBudget, Boolean checkpoint) {
		return role(name, order, gate, inputs, outputs, hopBudget, checkpoint, null);
	}

	static AgentBoardService.RoleConfigSpec role(String name, int order, HumanGate gate, List<RequiredInput> inputs,
			List<ProducedOutput> outputs, Long hopBudget, Boolean checkpoint, List<AgentCapability> capabilities) {
		return new AgentBoardService.RoleConfigSpec(name, name + " prompt", order, null, false, true, capabilities, null,
				null, gate, inputs, outputs, null, hopBudget, null, null, checkpoint);
	}

	static RequiredInput input(RearmSpecificationType spec) {
		return new RequiredInput(InputKind.DOCUMENT, spec, InputScope.TASK, null, null, null);
	}

	static ProducedOutput produces(RearmSpecificationType spec) {
		return new ProducedOutput(spec, InputScope.TASK, false);
	}

	Worker worker(Organization org, String name) throws RelizaException {
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				name + "-" + UUID.randomUUID(), null, null, null, WU);
		return new Worker(a, agentSessionService.initialize(org.getUuid(), a.getUuid(), null,
				"s-" + UUID.randomUUID(), name, null, null, WU));
	}

	AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	ReleaseData release(UUID uuid) {
		return sharedReleaseService.getReleaseData(uuid).orElseThrow();
	}

	/** The board's whole event log, oldest first: every event it kept, not the feed's newest 50. */
	List<LoggedEvent> events(Rig r) {
		try {
			EventPage page = agentBoardService.eventsOf(board(r).getUuid(), null, null, AgentBoardService.EVENTS_PAGE_MAX);
			assertFalse(page.hasMore(), "the board's whole log is one page");
			return page.events();
		} catch (RelizaException e) {
			throw new IllegalStateException(e);
		}
	}

	List<String> messages(Rig r, BoardEventKind kind, String containing) {
		return events(r).stream().filter(e -> kind == e.kind()).map(LoggedEvent::message)
				.filter(m -> m.contains(containing)).toList();
	}

	/** Every event on the board, any kind, as "KIND message". */
	List<String> boardEvents(Rig r) {
		return events(r).stream().map(e -> e.kind() + " " + e.message()).toList();
	}

	/** The checkpoint ALERTs on the board naming the task. */
	List<String> checkpointAlerts(Rig r, AgentTaskData t) {
		return messages(r, BoardEventKind.ALERT, CHECKPOINT).stream().filter(m -> m.contains(t.label())).toList();
	}

	/**
	 * Every ALERT on the board, the checkpoint's and any other, whether or not it names a task. Each rig is a board
	 * of its own, so this is the design's "no other ALERT" (T-33).
	 */
	List<String> boardAlerts(Rig r) {
		return messages(r, BoardEventKind.ALERT, "");
	}

	/** The "sent it on" INFOs on the board naming the task. */
	List<String> sentOnInfos(Rig r, AgentTaskData t) {
		return messages(r, BoardEventKind.INFO, SENT_ON).stream().filter(m -> m.contains(t.label())).toList();
	}

	DesignCheckpoint checkpoint(AgentTaskData t) {
		return reload(t).getDesignCheckpoint();
	}

	/** A task registered and authorized for the architect. */
	AgentTaskData task(Rig r, String title) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), title, null, null,
				null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		return reload(t);
	}

	void assign(Rig r, AgentTaskData t, Worker w) throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), w.agentUuid(), w.sessionUuid(), WU);
	}

	ReleaseData publish(Worker w, AgentTaskData t, RearmSpecificationType type) throws RelizaException {
		return publish(w, t, type, false);
	}

	ReleaseData publish(Worker w, AgentTaskData t, RearmSpecificationType type, boolean advisory) throws RelizaException {
		String path = type.name().toLowerCase() + "/" + UUID.randomUUID() + ".md";
		return agentDocumentService.publish(w.session(), new PublishRequest(reload(t).getUuid(), type, null, path,
				"digest-" + UUID.randomUUID(), "text/markdown", null, null, null,
				"c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "doc", ZonedDateTime.now(), null, null, null,
				advisory), WU);
	}

	/** An index round: one item at the given priority and status, with an {@code about} when given. */
	ReleaseData index(Worker w, AgentTaskData t, RearmSpecificationType kind, String verdict,
			RearmSpecificationType about, String id, int priority, String status) throws RelizaException {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", id);
		item.put("priority", priority);
		item.put("status", status);
		item.put("title", "issue " + id);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", kind.name());
		idx.put("verdict", verdict);
		idx.put("reviewItems", new ArrayList<>(List.of(item)));
		if (null != about) idx.put("about", Map.of("specification", about.name()));
		return agentDocumentService.publish(w.session(), new PublishRequest(t.getUuid(), kind, null, null, null, null,
				null, null, idx, null, null, null, null, null), WU);
	}

	AgentTaskData signOff(Worker w, AgentTaskData t, SignOffOutcome outcome, String note, List<UUID> outputs)
			throws RelizaException {
		return agentTaskService.signOff(t.getUuid(), w.sessionUuid(), outcome, note, outputs, WU);
	}

	/** The architect takes the task, publishes both design documents and passes with {@code note}. */
	List<ReleaseData> design(Rig r, AgentTaskData t, String note) throws RelizaException {
		assign(r, t, r.architect());
		ReleaseData arch = publish(r.architect(), t, ARCH);
		ReleaseData tm = publish(r.architect(), t, TM);
		signOff(r.architect(), t, SignOffOutcome.PASSED, note, List.of(arch.getUuid(), tm.getUuid()));
		return List.of(arch, tm);
	}

	/** The architect takes the task, publishes a new round of {@code spec} and passes. */
	ReleaseData redesign(Rig r, AgentTaskData t, RearmSpecificationType spec) throws RelizaException {
		assign(r, t, r.architect());
		ReleaseData round = publish(r.architect(), t, spec);
		signOff(r.architect(), t, SignOffOutcome.PASSED, "round " + spec, List.of(round.getUuid()));
		return round;
	}

	/** A worker takes the task and passes with no outputs (a reviewer's pass). */
	AgentTaskData pass(Rig r, AgentTaskData t, Worker w) throws RelizaException {
		assign(r, t, w);
		return signOff(w, t, SignOffOutcome.PASSED, "fine", List.of());
	}

	/** Both design reviewers pass. */
	AgentTaskData reviewsPass(Rig r, AgentTaskData t) throws RelizaException {
		pass(r, t, r.functional());
		return pass(r, t, r.security());
	}

	/** The coder takes the task, publishes DETAILED_DESIGN and passes. */
	AgentTaskData code(Rig r, AgentTaskData t) throws RelizaException {
		assign(r, t, r.coder());
		ReleaseData dd = publish(r.coder(), t, DD);
		return signOff(r.coder(), t, SignOffOutcome.PASSED, "built", List.of(dd.getUuid()));
	}

	/** The tester takes the task and rejects with one P1 about {@code about}. */
	AgentTaskData testRejects(Rig r, AgentTaskData t, RearmSpecificationType about, String id) throws RelizaException {
		assign(r, t, r.tester());
		ReleaseData run = index(r.tester(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT, "REJECTED", about, id, 1,
				"OPEN");
		return signOff(r.tester(), t, SignOffOutcome.REJECTED, "failed", List.of(run.getUuid()));
	}

	/** The design passes its stage and the task stops at the checkpoint: PENDING, AWAITING_COORDINATOR. */
	AgentTaskData atCheckpoint(Rig r, AgentTaskData t) throws RelizaException {
		design(r, t, "fits one coder pass");
		if (null != agentBoardService.getRoleConfig(r.board().getUuid(), "functional_reviewer").orElse(null)) {
			reviewsPass(r, t);
		}
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), after.getStatusHistory().toString());
		assertTrue(after.checkpointPending(), String.valueOf(after.getDesignCheckpoint()));
		return after;
	}

	/** At the checkpoint, then sent on by the seat: QUEUED for the coder, SENT_ON. */
	AgentTaskData sentOn(Rig r, AgentTaskData t) throws RelizaException {
		atCheckpoint(r, t);
		AgentTaskData queued = agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		return queued;
	}

	AgentTaskData authorize(Rig r, AgentTaskData t, String role, AgentActor by) throws RelizaException {
		return agentTaskService.authorize(t.getUuid(), board(r), role, 10, null, null, null, null, by, WU);
	}

	/** The ALERT the checkpoint posts, as T-1 reads it, for the given rounds and note. */
	static String alertFor(AgentTaskData t, String rounds, String note) {
		return "Task " + t.label() + " needs the coordinator: design checkpoint before coder \u2014 " + rounds
				+ (null == note ? "" : "; architect: \"" + note + "\"")
				+ "; read the design, then split it or send it on (rearm agent task authorize " + t.keyOrUuid()
				+ " --role coder --session <seat>)";
	}

	static RelizaException refused(Executable call) {
		return assertThrows(RelizaException.class, call);
	}
}

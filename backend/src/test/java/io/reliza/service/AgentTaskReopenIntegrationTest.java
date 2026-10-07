/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A completed task can be reopened to a role, with a reason (operator-actions D4 as amended; task
 * 56116a77, architecture-1 §4 tests 1-6).
 *
 * <p>Three REQUIRED roles: the architect produces nothing the others read, the coder produces a
 * DETAILED_DESIGN note per task, and the tester reads it. So redoing the coder's hop re-runs the
 * tester through D30 staleness and leaves the architect alone.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTaskReopenIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/reopen";
	private static final String DOCS_SOURCE = "github:acme/reopen-docs";
	private static final String DOCS = "https://github.com/acme/reopen-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
	private static final AtomicInteger ISSUE = new AtomicInteger(9700);

	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "reopen-" + UUID.randomUUID(),
				"reopen", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		role(board, "architect", 10, List.of(), List.of());
		role(board, "coder", 20, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, false)));
		role(board, "tester", 30, List.of(new RequiredInput(InputKind.DOCUMENT,
				RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, null, null, null)), List.of());
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		return new Rig(board, w, agentSessionService.initialize(org.getUuid(), w.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU));
	}

	private void role(AgentBoardData board, String name, int order, List<RequiredInput> reads,
			List<ProducedOutput> produces) throws RelizaException {
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec(name, "do " + name, order,
				null, false, true, null, null, RoleNecessity.REQUIRED, null, reads, produces, null, null), true, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	/** Assign the queued task to the rig's session, publish the coder's note if it is the coder, pass. */
	private AgentTaskData hop(Rig r, AgentTaskData t) throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		List<UUID> outputs = List.of();
		if ("coder".equals(reload(t).getRole())) {
			String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
			outputs = List.of(agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(),
					RearmSpecificationType.DETAILED_DESIGN, null, "impl/" + digest + ".md", digest, "text/markdown",
					null, null, null, UUID.randomUUID().toString().replace("-", ""), DOCS, "note",
					ZonedDateTime.now(), null), WU).getUuid());
		}
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", outputs, WU);
	}

	/** Architect, coder and tester each pass once: the board completes the task. */
	private AgentTaskData completed(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		t = agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		for (String expected : List.of("architect", "coder", "tester")) {
			assertEquals(TaskStatus.QUEUED, t.getStatus(), "queued for " + expected);
			assertEquals(expected, t.getRole());
			t = hop(r, t);
		}
		assertEquals(TaskStatus.COMPLETED, t.getStatus());
		return t;
	}

	private List<String> events(Rig r, AgentBoardData.BoardEventKind kind, String containing) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream().filter(ev -> ev.kind() == kind)
				.map(AgentBoardData.BoardEvent::message).filter(m -> m.contains(containing)).toList();
	}

	@Test
	public void reopenQueuesTheRoleWithTheReason() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = completed(r);
		AgentTaskData reopened = agentTaskService.reopen(t.getUuid(), "coder", "PR conflicts with the integration branch",
				COORD, WU);

		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		assertEquals("coder", reopened.getRole());
		StatusChange last = reopened.getStatusHistory().get(reopened.getStatusHistory().size() - 1);
		assertEquals(StatusTrigger.REOPEN, last.trigger());
		assertEquals("PR conflicts with the integration branch", last.note());
		assertEquals(COORD, last.actor());
		assertNotNull(reopened.getReopenedAt());
		assertNull(reopened.getCompletedAt());
		assertEquals(1, reopened.getReopenCount());
		assertEquals("coder", reopened.getReopens().get(0).role());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "reopened to coder").size());
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, "reopened to coder").get(0)
				.contains("PR conflicts with the integration branch"));
	}

	@Test
	public void refusals() throws RelizaException {
		Rig r = rig();
		AgentTaskData done = completed(r);

		RelizaException blank = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(done.getUuid(), "coder", "  ", COORD, WU));
		assertTrue(blank.getMessage().contains("needs a reason"), blank.getMessage());

		RelizaException noRole = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(done.getUuid(), "designer", "why", COORD, WU));
		assertTrue(noRole.getMessage().contains("not configured"), noRole.getMessage());

		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("retired", "gone", 40,
				null, false, false, null, null, null, null, List.of(), List.of(), null, null), true, WU);
		RelizaException inactive = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(done.getUuid(), "retired", "why", COORD, WU));
		assertTrue(inactive.getMessage().contains("inactive"), inactive.getMessage());

		agentBoardService.setPause(r.board().getUuid(), BoardPauseLevel.OPERATOR, true, "freeze", PERSON, WU);
		RelizaException locked = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(done.getUuid(), "coder", "why", COORD, WU));
		assertTrue(locked.getMessage().contains("is paused"), locked.getMessage());
		agentBoardService.setPause(r.board().getUuid(), BoardPauseLevel.OPERATOR, false, null, PERSON, WU);
		assertEquals(TaskStatus.COMPLETED, reload(done).getStatus(), "every refusal left it completed");
		assertEquals(0, reload(done).getReopenCount());

		AgentTaskData queued = agentTaskService.authorize(agentTaskService.register(board(r),
				SRC + "#" + ISSUE.incrementAndGet(), "open", null, null, null, null, null, null, WU).getUuid(),
				board(r), "coder", 10, null, null, null, null, COORD, WU);
		RelizaException notDone = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(queued.getUuid(), "coder", "why", COORD, WU));
		assertTrue(notDone.getMessage().contains("only a COMPLETED or DELIVERING task"), notDone.getMessage());

		agentTaskService.cancel(queued.getUuid(), "dropped", COORD, WU);
		RelizaException cancelled = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(queued.getUuid(), "coder", "why", COORD, WU));
		assertTrue(cancelled.getMessage().contains("register a new one"), cancelled.getMessage());
	}

	@Test
	public void theReopenedRoleMustPassAgain() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.reopen(completed(r).getUuid(), "coder", "conflict", COORD, WU);
		assertEquals(List.of("coder"), agentTaskService.missingRequiredRoles(t),
				"the coder's old pass is ignored; the architect's and tester's stand");
		RelizaException refused = assertThrows(RelizaException.class,
				() -> agentTaskService.complete(t.getUuid(), "done?", PERSON, false, WU));
		assertTrue(refused.getMessage().contains("[coder]"), refused.getMessage());
	}

	@Test
	public void downstreamPassesGoStaleWhenTheReopenedHopRepublishes() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.reopen(completed(r).getUuid(), "coder", "conflict", COORD, WU);

		t = hop(r, t);      // the coder republishes its note for the new head and passes
		assertEquals(TaskStatus.QUEUED, t.getStatus());
		assertEquals("tester", t.getRole(), "the tester read the note that moved, so it re-verifies");

		t = hop(r, t);
		assertEquals(TaskStatus.COMPLETED, t.getStatus(), "the architect's pass stood throughout");
		assertEquals(1, t.getSignOffs().stream().filter(so -> "architect".equals(so.role())).count(),
				"the architect was never re-run");
	}

	@Test
	public void aReopenRespectsTheBudget() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = completed(r);
		// The coder's allowance is its round's estimate, so a board budget of 500 cannot cover it.
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("coder", "do coder", 20,
				null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, false)), null,
				1_000L), true, WU);
		agentBoardService.updateSettings(r.board().getUuid(), 500L, null, null, null, null, null, WU);

		AgentTaskData held = agentTaskService.reopen(t.getUuid(), "coder", "conflict", COORD, WU);

		assertEquals(TaskStatus.ON_HOLD, held.getStatus(), "held, not queued");
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, held.getHold().level());
		assertTrue(held.getHold().reason().contains("budget: reopening to coder does not fit"), held.getHold().reason());
		assertEquals(1, held.getReopenCount(), "the reopen itself is recorded");
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, "reopening to coder does not fit").size());

		// With the budget raised and the hold lifted, routing takes the task on: to the coder,
		// whose pass the reopen took away -- not to a completion on the three old passes.
		agentBoardService.updateSettings(r.board().getUuid(), 100_000L, null, null, null, null, null, WU);
		AgentTaskData released = agentTaskService.liftHold(t.getUuid(), AgentTaskData.HoldLevel.OPERATOR, PERSON, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus(), "status after release: " + released.getStatus());
		assertEquals("coder", released.getRole());
	}

	@Test
	public void reopenCountAccumulates() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.reopen(completed(r).getUuid(), "coder", "first conflict", COORD, WU);
		t = hop(r, t);
		t = hop(r, t);
		assertEquals(TaskStatus.COMPLETED, t.getStatus());
		t = agentTaskService.reopen(t.getUuid(), "coder", "second conflict", PERSON, WU);
		assertEquals(2, t.getReopenCount());
		assertEquals(List.of("first conflict", "second conflict"),
				t.getReopens().stream().map(AgentTaskData.Reopen::reason).toList());
		assertFalse(t.getReopens().get(1).at().isBefore(t.getReopens().get(0).at()));
		assertEquals(PERSON, t.getReopens().get(1).by());
	}
}

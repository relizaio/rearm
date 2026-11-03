/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.AgentTaskRoleConfigData.Commissions;
import io.reliza.model.AgentTaskRoleConfigData.CommissionIntake;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;

/**
 * The design checkpoint (task SPLIT-5, design section 3.3-3.4, section 4): where it stops a task, what the stop says and records,
 * and how the coordinator's authorize and split settle it. T-1 to T-4, T-7, T-8 and T-12.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointIntegrationTest extends AgentDesignCheckpointTestBase {

	/** T-1: on architect -> coder the architect's pass stops at the coordinator, with one ALERT and a PENDING record. */
	@Test
	public void theArchitectsPassStopsAtTheCoordinatorBeforeTheCoder() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "stops before the coder");
		List<ReleaseData> design = design(r, t, "fits one coder pass");

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertEquals("architect", after.getRole(), "the role is unchanged");
		assertEquals(List.of(alertFor(after, "ARCHITECTURE round 1 (ASSEMBLED), " + TM + " round 1 (ASSEMBLED)",
				"fits one coder pass")), checkpointAlerts(r, after));
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertEquals("architect", cp.afterRole());
		assertEquals("coder", cp.beforeRole());
		assertEquals(List.of(design.get(0).getUuid(), design.get(1).getUuid()), cp.design());
		assertNull(cp.settledBy());
		assertNull(cp.settledAt());
		List<StatusChange> history = after.getStatusHistory();
		StatusChange last = history.get(history.size() - 1);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, last.to());
		assertEquals(StatusTrigger.SIGNOFF, last.trigger(), "the sign-off's own row, and no further row");
		assertEquals(TaskStatus.ASSIGNED, last.from());
	}

	/** T-2: on the reviewed board the architect's pass goes to the reviewers; their passes stop it at READY_TO_SHIP. */
	@Test
	public void theDesignReviewsRunFirstAndTheStopNamesTheReviewedRounds() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "reviewed design");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		AgentTaskData toReview = reload(t);
		assertEquals(TaskStatus.QUEUED, toReview.getStatus());
		assertEquals("functional_reviewer", toReview.getRole());
		assertNull(toReview.getDesignCheckpoint());
		assertTrue(boardAlerts(r).isEmpty(), "no stop, no event: no ALERT on the board");
		assertEquals(List.of(), boardEvents(r), "no event of any kind on the board");

		pass(r, t, r.functional());
		assertEquals("security_reviewer", reload(t).getRole());
		assertNull(checkpoint(t));
		pass(r, t, r.security());

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertEquals(List.of(alertFor(after, "ARCHITECTURE round 1 (READY_TO_SHIP), " + TM + " round 1 (READY_TO_SHIP)",
				"fits one coder pass")), checkpointAlerts(r, after));
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals("security_reviewer", cp.afterRole());
		assertEquals(List.of(design.get(0).getUuid(), design.get(1).getUuid()), cp.design());
	}

	/** T-3: the same boards without the flag queue the coder, post no checkpoint event and record nothing. */
	@Test
	public void withoutTheFlagTheCoderIsQueuedAsBefore() throws RelizaException {
		for (boolean reviewers : new boolean[] {false, true}) {
			Rig r = rig(reviewers, false, HumanGate.NONE);
			AgentTaskData t = task(r, "unflagged");
			design(r, t, "fits one coder pass");
			if (reviewers) reviewsPass(r, t);
			AgentTaskData after = reload(t);
			assertEquals(TaskStatus.QUEUED, after.getStatus(), "reviewers " + reviewers);
			assertEquals("coder", after.getRole());
			assertNull(after.getDesignCheckpoint());
			assertTrue(messages(r, BoardEventKind.ALERT, "").isEmpty(), messages(r, BoardEventKind.ALERT, "").toString());
		}
	}

	/** T-4: the seat's and a person's authorize send it on; authorizing the architect leaves it PENDING (F-7). */
	@Test
	public void theCoordinatorsAuthorizeSendsItOn() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "sent on by the seat");
		atCheckpoint(r, t);
		AgentTaskData queued = authorize(r, t, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		DesignCheckpoint cp = checkpoint(t);
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(COORD, cp.settledBy());
		assertEquals("authorized for coder", cp.note());
		assertTrue(null != cp.settledAt());
		assertEquals(List.of("Task " + queued.label() + ": " + COORD.display() + " sent it on to coder at the design"
				+ " checkpoint"), sentOnInfos(r, queued));

		AgentTaskData byPerson = task(r, "sent on by a person");
		atCheckpoint(r, byPerson);
		authorize(r, byPerson, "coder", PERSON);
		assertEquals(PERSON, checkpoint(byPerson).settledBy());
		assertEquals(DesignCheckpointState.SENT_ON, checkpoint(byPerson).state());
	}

	@Test
	public void authorizingTheArchitectFromTheCheckpointLeavesItPendingAndTheNextArrivalNamesTheNewRound()
			throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "redesigned at the checkpoint");
		AgentTaskData first = atCheckpoint(r, t);
		DesignCheckpoint before = first.getDesignCheckpoint();
		UUID round1 = before.design().get(0);
		UUID tm = before.design().get(1);

		authorize(r, t, "architect", COORD);
		DesignCheckpoint still = checkpoint(t);
		assertEquals(DesignCheckpointState.PENDING, still.state(), "a role before the checkpoint settles nothing");
		assertTrue(sentOnInfos(r, t).isEmpty());

		ReleaseData round2 = redesign(r, t, ARCH);
		AgentTaskData again = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, again.getStatus());
		List<String> alerts = checkpointAlerts(r, again);
		assertEquals(2, alerts.size(), alerts.toString());
		assertTrue(alerts.get(1).contains("ARCHITECTURE round 2 (ASSEMBLED), " + TM + " round 1 (ASSEMBLED)"),
				alerts.get(1));
		DesignCheckpoint cp = again.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertTrue(before.reachedAt().isEqual(cp.reachedAt()), "a PENDING record keeps its reachedAt");
		assertEquals(List.of(round2.getUuid(), tm), cp.design(), "F-7: the current design, not round 1");
		assertEquals("architect", cp.afterRole());

		List<AgentTaskData> children = agentTaskService.split(t.getUuid(), board(r),
				List.of(new SplitChild("A", null, null, null, null, null), new SplitChild("B", null, null, null, null, null)),
				COORD, WU);
		for (AgentTaskData c : children) {
			List<UUID> pinned = reload(c).getRequiredInputs().stream().map(RequiredInput::release).toList();
			assertEquals(List.of(round2.getUuid(), tm), pinned);
			assertFalse(pinned.contains(round1));
		}
	}

	/**
	 * T-7, carried from SPLIT-2's {@code aSplitOnTheRecommendationPinsTheDesignOnEveryChild}: a split from PENDING
	 * settles SPLIT and pins the design on every child, which an architect hop reads and a coder hop cannot stand in
	 * for.
	 */
	@Test
	public void aSplitAtTheCheckpointPinsTheDesignOnEveryChild() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "split at the checkpoint");
		AgentTaskData pending = atCheckpoint(r, t);
		List<UUID> design = pending.getDesignCheckpoint().design();

		List<AgentTaskData> children = agentTaskService.split(t.getUuid(), board(r), List.of(
				new SplitChild("A", null, null, null, null, null), new SplitChild("B", null, null, null, null, List.of(0))),
				COORD, WU);

		DesignCheckpoint cp = checkpoint(t);
		assertEquals(DesignCheckpointState.SPLIT, cp.state());
		assertEquals(COORD, cp.settledBy());
		assertEquals("2 children: " + children.get(0).keyOrUuid() + ", " + children.get(1).keyOrUuid(), cp.note());
		for (AgentTaskData c : children) {
			List<RequiredInput> pinned = reload(c).getRequiredInputs();
			assertEquals(2, pinned.size(), pinned.toString());
			for (int i = 0; i < 2; i++) {
				RequiredInput in = pinned.get(i);
				assertEquals(InputKind.DOCUMENT, in.kind());
				assertEquals(i == 0 ? ARCH : TM, in.specification());
				assertEquals(design.get(i), in.release());
				assertEquals(ReleaseLifecycle.ASSEMBLED, in.minLifecycle());
			}
		}

		// An architect hop on the child reads the parent's design.
		AgentTaskData a = children.get(0);
		authorize(r, a, "architect", COORD);
		WorkerAssignment hop = agentTaskService.assign(a.getUuid(), board(r), r.architect().agentUuid(),
				r.architect().sessionUuid(), WU);
		for (UUID d : design) {
			assertTrue(hop.resolvedInputs().stream().anyMatch(ri -> d.equals(ri.release())), hop.resolvedInputs().toString());
		}

		// A coder cannot take it on the parent's design alone: its TASK-scope ARCHITECTURE resolves among the
		// child's own releases.
		agentTaskService.returnTask(a.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "for the coder", WU);
		authorize(r, a, "coder", COORD);
		RelizaException coder = refused(() -> agentTaskService.assign(a.getUuid(), board(r), r.coder().agentUuid(),
				r.coder().sessionUuid(), WU));
		assertTrue(coder.getMessage().contains("has published no ARCHITECTURE yet"), coder.getMessage());
	}

	/** T-7, F-2: a child stops at the checkpoint on its own reviewed rounds, not on the parent's pinned ones. */
	@Test
	public void aChildStopsAtTheCheckpointOnItsOwnDesign() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "parent");
		AgentTaskData pending = atCheckpoint(r, t);
		List<UUID> parentDesign = pending.getDesignCheckpoint().design();
		List<AgentTaskData> children = agentTaskService.split(t.getUuid(), board(r),
				List.of(new SplitChild("child A", null, null, null, null, null),
						new SplitChild("child B", null, null, null, null, null)), COORD, WU);
		AgentTaskData child = children.get(0);
		authorize(r, child, "architect", COORD);

		List<ReleaseData> own = design(r, child, "delta round: parent 3.2 only");
		reviewsPass(r, child);

		AgentTaskData after = reload(child);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertEquals(List.of(own.get(0).getUuid(), own.get(1).getUuid()), cp.design());
		assertTrue(cp.design().stream().noneMatch(parentDesign::contains), "not the parent's pinned rounds");
		assertEquals("security_reviewer", cp.afterRole());
		assertEquals("coder", cp.beforeRole());
		assertEquals(1, checkpointAlerts(r, after).size(), checkpointAlerts(r, after).toString());

		AgentTaskData queued = authorize(r, child, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
	}

	/** T-8: a gate on the architect: acceptance reaches the checkpoint at READY_TO_SHIP; rejection records nothing. */
	@Test
	public void aGateOnTheArchitectReachesTheCheckpointOnAcceptanceOnly() throws RelizaException {
		Rig r = rig(false, true, HumanGate.ON_PASS);
		AgentTaskData accepted = task(r, "gate accepted");
		design(r, accepted, "fits one coder pass");
		assertEquals(TaskStatus.ON_HOLD, reload(accepted).getStatus(), "the gate holds the pass");
		assertNull(checkpoint(accepted));
		agentTaskService.humanReview(accepted.getUuid(), true, "good", PERSON, WU);
		AgentTaskData after = reload(accepted);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertTrue(after.checkpointPending());
		List<String> alerts = checkpointAlerts(r, after);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("ARCHITECTURE round 1 (READY_TO_SHIP), " + TM + " round 1 (READY_TO_SHIP)"),
				alerts.get(0));

		AgentTaskData rejected = task(r, "gate rejected");
		design(r, rejected, "fits one coder pass");
		agentTaskService.humanReview(rejected.getUuid(), false, "not yet", PERSON, WU);
		AgentTaskData back = reload(rejected);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertNull(back.getDesignCheckpoint());
		assertTrue(checkpointAlerts(r, back).isEmpty());
	}

	/** T-12: an investigation on a board with the flag is never stopped, even when the flagged role reviews it. */
	@Test
	public void anInvestigationNeverStops() throws RelizaException {
		Rig r = plain();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("researcher", "look", 5, null,
				false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.BOARD_INVESTIGATION_REPORT, InputScope.TASK, true)),
				null, null, null, null), true, WU);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("architect", null, null, null,
				null, null, null, null, null, null, null, null, null, null, null,
				new Commissions(List.of("researcher"), CommissionIntake.AUTO, null, null)), true, WU);
		Worker researcher = worker(r.org(), "researcher");
		AgentTaskData from = task(r, "commissioning");
		assign(r, from, r.architect());
		AgentTaskData inv = agentTaskService.commission(board(r), new AgentTaskService.CommissionRequest("researcher",
				"look into it", "What does it do?", from.getUuid(), List.of(), null, null, "coder", null, null, null, null),
				r.architect().sessionUuid(), AgentActor.ofSession(r.architect().sessionUuid()), WU);
		assign(r, inv, researcher);
		ReleaseData report = publish(researcher, inv, RearmSpecificationType.BOARD_INVESTIGATION_REPORT);
		signOff(researcher, inv, SignOffOutcome.PASSED, "found it", List.of(report.getUuid()));

		AgentTaskData after = reload(inv);
		assertEquals(TaskStatus.QUEUED, after.getStatus(), "the flagged coder reviews it without a stop");
		assertEquals("coder", after.getRole());
		assertNull(after.getDesignCheckpoint());
		assertTrue(checkpointAlerts(r, after).isEmpty());
	}
}

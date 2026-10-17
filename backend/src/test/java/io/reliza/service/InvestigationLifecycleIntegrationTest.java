/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * An investigation from the investigating hop to its return (task RD4-12, design §3.1 and §3.4): the pass needs
 * the report; completing pins it on the commissioning task with an INFO naming both, and offers that task back to
 * its role, or tells the hop still holding it; returnTo NONE stays put; a hop waits on its investigation by
 * returning BLOCKED_ON_DEPENDENCY; and an investigation links no PRs.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class InvestigationLifecycleIntegrationTest extends InvestigationTestBase {

	@Test
	public void theInvestigatingPassNeedsTheReport() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		agentTaskService.assign(inv.getUuid(), board(r), r.tester().agentUuid(), r.tester().sessionUuid(), WU);
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(inv.getUuid(),
				r.tester().sessionUuid(), SignOffOutcome.PASSED, "done", List.of(), WU));
		assertTrue(e.getMessage().contains("completes with its report"), e.getMessage());
		// Its BOARD_TEST_REPORT, required of the tester on a work task, is not owed here: the report alone passes.
		ReleaseData report = publish(r.tester(), reload(inv), REPORT);
		AgentTaskData done = agentTaskService.signOff(inv.getUuid(), r.tester().sessionUuid(), SignOffOutcome.PASSED,
				"done", List.of(report.getUuid()), WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), "no delivery step");
		assertEquals(report.getUuid(), done.getInvestigation().report());
		assertTrue(null != done.getCompletedAt());
	}

	@Test
	public void theReportIsPinnedOnTheCommissioningTaskWhoseHolderHasItAtOnce() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		ReleaseData report = investigate(r, r.tester(), inv);

		AgentTaskData back = reload(from);
		assertEquals(TaskStatus.ASSIGNED, back.getStatus(), "the architect still holds it");
		assertTrue(back.getRequiredInputs().stream().anyMatch(ri -> report.getUuid().equals(ri.release())),
				back.getRequiredInputs().toString());
		assertTrue(back.getAssignment().resolvedInputs().stream().map(ResolvedInput::release)
				.anyMatch(report.getUuid()::equals), "the input appears on the running hop");
		assertEquals(1, back.getReportsReturned().size());
		assertFalse(back.getReportsReturned().get(0).reoffered());
		List<String> said = infos(r, "Investigation " + inv.getKey() + " ");
		assertTrue(said.stream().anyMatch(m -> m.contains("pinned on " + from.getKey()) && m.contains("among its inputs")),
				said.toString());
	}

	/**
	 * Design round 2 §1 (tester run 1, T-1): the holder is the role, not the session. The commissioning hop ends
	 * before the report is in, and the architect role takes the task again in a new session: the report is bound on
	 * that running hop.
	 */
	@Test
	public void aReportReturningToAnotherSessionOfTheCommissioningRoleIsBoundOnItsHop() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "session ends", WU);
		agentTaskService.authorize(from.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		Worker architect2 = worker(r.org(), "architect");
		agentTaskService.assign(from.getUuid(), board(r), architect2.agentUuid(), architect2.sessionUuid(), WU);
		ReleaseData report = investigate(r, r.tester(), inv);

		AgentTaskData back = reload(from);
		assertEquals(TaskStatus.ASSIGNED, back.getStatus());
		assertEquals(architect2.sessionUuid(), back.getAssignment().session(), "the second architect session holds it");
		assertTrue(back.getAssignment().resolvedInputs().stream().map(ResolvedInput::release)
				.anyMatch(report.getUuid()::equals), "the input appears on the running hop of the commissioning role");
		assertFalse(back.getReportsReturned().get(0).reoffered());
		assertEquals(r.architect().sessionUuid(), back.getReportsReturned().get(0).session(),
				"the commissioning session stays on record for the poll's preference");
		List<String> said = infos(r, "Investigation " + inv.getKey() + " ");
		assertTrue(said.stream().anyMatch(m -> m.contains("whose architect hop (session "
				+ architect2.sessionUuid().toString().substring(0, 8) + ") has it among its inputs now")), said.toString());
	}

	/** Another role holding the commissioning task gets the pin and the INFO, not the binding (as round 1 built). */
	@Test
	public void aReportReturningToATaskAnotherRoleHoldsIsPinnedButNotBound() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "to the coder", WU);
		agentTaskService.authorize(from.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(from.getUuid(), board(r), r.coder().agentUuid(), r.coder().sessionUuid(), WU);
		ReleaseData report = investigate(r, r.tester(), inv);

		AgentTaskData back = reload(from);
		assertEquals("coder", back.getAssignment().role());
		assertTrue(back.getRequiredInputs().stream().anyMatch(ri -> report.getUuid().equals(ri.release())));
		assertFalse(back.getAssignment().resolvedInputs().stream().map(ResolvedInput::release)
				.anyMatch(report.getUuid()::equals), "the coder's hop is not the commissioning role's");
		assertTrue(infos(r, "Investigation " + inv.getKey() + " ").stream()
				.anyMatch(m -> m.contains("which the coder role is working; not offered back")));
	}

	@Test
	public void aCommissioningTaskWaitingOnTheCoordinatorIsOfferedBackToItsRole() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "later", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(from).getStatus());
		investigate(r, r.tester(), inv);
		AgentTaskData back = reload(from);
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("architect", back.getRole());
		assertTrue(back.getReportsReturned().get(0).reoffered());
		assertTrue(infos(r, "back to architect").stream().anyMatch(m -> m.contains(inv.getKey()) && m.contains(from.getKey())));
	}

	@Test
	public void aHopReturnsToWaitOnItsInvestigationAndGetsTheTaskBackWithTheReport() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		AgentTaskData waiting = agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(),
				TaskReturnReason.BLOCKED_ON_DEPENDENCY, "waiting on the report", WU);
		assertEquals(TaskStatus.QUEUED, waiting.getStatus(), "queued for its role, not sent to the coordinator");
		assertEquals("architect", waiting.getRole());
		assertTrue(waiting.getDependsOn().contains(inv.getUuid()));
		// Not offered while the investigation is open.
		Optional<AgentTaskService.WorkerAssignment> before = agentTaskService.next(List.of(board(r)),
				r.architect().agentUuid(), r.architect().sessionUuid(), List.of("architect"));
		assertTrue(before.isEmpty() || !before.get().task().getUuid().equals(from.getUuid()));

		ReleaseData report = investigate(r, r.tester(), inv);
		Optional<AgentTaskService.WorkerAssignment> after = agentTaskService.next(List.of(board(r)),
				r.architect().agentUuid(), r.architect().sessionUuid(), List.of("architect"));
		assertTrue(after.isPresent() && after.get().task().getUuid().equals(from.getUuid()), "offered back once the report is in");
		assertTrue(after.get().resolvedInputs().stream().map(ResolvedInput::release).anyMatch(report.getUuid()::equals),
				"with the report bound");
	}

	@Test
	public void aStandaloneInvestigationKeepsItsReport() throws RelizaException {
		Rig r = rig();
		AgentTaskData inv = agentTaskService.commission(board(r), ask("researcher", null), null, PERSON, WU);
		ReleaseData report = investigate(r, r.researcher(), inv);
		AgentTaskData done = reload(inv);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(report.getUuid(), done.getInvestigation().report());
		assertTrue(infos(r, "Investigation " + inv.getKey()).stream().anyMatch(m -> m.contains(report.getUuid().toString())));
	}

	@Test
	public void returnToNoneFromATaskPinsWithoutOfferingItBack() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, new AgentTaskService.CommissionRequest("tester", "t", null,
				from.getUuid(), List.of(), null, null, null, AgentTaskData.ReturnTo.NONE, null, null, null));
		agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "later", WU);
		ReleaseData report = investigate(r, r.tester(), inv);
		AgentTaskData back = reload(from);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus(), "NONE offers nothing back");
		assertTrue(back.getRequiredInputs().stream().anyMatch(ri -> report.getUuid().equals(ri.release())));
	}

	@Test
	public void anInvestigationLinksNoPullRequestsAndItsReportIsItsAlone() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		RelizaException pr = assertThrows(RelizaException.class,
				() -> agentTaskService.linkPr(inv.getUuid(), "https://github.com/acme/app/pull/1", WU));
		assertTrue(pr.getMessage().contains("links no PRs"), pr.getMessage());
		RelizaException onWork = assertThrows(RelizaException.class, () -> publish(r.architect(), from, REPORT));
		assertTrue(onWork.getMessage().contains("is a work task"), onWork.getMessage());
	}

	@Test
	public void aPersonCompletingAnInvestigationReturnsItsReport() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		agentTaskService.assign(inv.getUuid(), board(r), r.tester().agentUuid(), r.tester().sessionUuid(), WU);
		ReleaseData report = publish(r.tester(), reload(inv), REPORT);
		agentTaskService.returnTask(inv.getUuid(), r.tester().sessionUuid(), TaskReturnReason.OTHER, "out of time", WU);
		RelizaException needsPass = assertThrows(RelizaException.class,
				() -> agentTaskService.complete(inv.getUuid(), null, PERSON, WU));
		assertTrue(needsPass.getMessage().contains("tester"), "the investigating role, not the board's REQUIRED roles");
		AgentTaskData done = agentTaskService.complete(inv.getUuid(), "enough", PERSON, true, WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(report.getUuid(), done.getInvestigation().report(), "the newest report round, though a draft");
		assertTrue(reload(from).getRequiredInputs().stream().anyMatch(ri -> report.getUuid().equals(ri.release())));
		assertEquals(r.architect().sessionUuid(), reload(from).getReportsReturned().get(0).session());
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.ReturnedReport;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.CommissionIntake;
import io.reliza.ws.App;

/**
 * A cancelled investigation returns with no report (task RD4-12, design round 2 §2; tester run 1, T-2): the
 * commissioning task stops waiting on it, a reportsReturned row records the cancel with its note, one INFO names both
 * tasks, and the task moves as a completed investigation moves it. A returnTo NONE investigation touches no task,
 * and a dependency the coordinator set on the cancelled task is left for the coordinator.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class InvestigationCancelReturnIntegrationTest extends InvestigationTestBase {

	private static String cancelledInfo(AgentTaskData inv, AgentTaskData from) {
		return "Investigation " + inv.label() + " cancelled: no report; " + from.label() + " no longer waits on it";
	}

	@Test
	public void aTaskWaitingOnACancelledInvestigationLosesTheDependencyAndIsOfferedBack() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		AgentTaskData waiting = agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(),
				TaskReturnReason.BLOCKED_ON_DEPENDENCY, "waiting on the report", WU);
		assertTrue(waiting.getDependsOn().contains(inv.getUuid()));

		agentTaskService.cancel(inv.getUuid(), "not needed after all", PERSON, WU);

		AgentTaskData back = reload(from);
		assertFalse(back.getDependsOn().contains(inv.getUuid()), "the board drops the dependency it added");
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("architect", back.getRole());
		assertEquals(1, back.getReportsReturned().size());
		ReturnedReport row = back.getReportsReturned().get(0);
		assertTrue(row.cancelled());
		assertNull(row.report(), "no report came back");
		assertEquals("not needed after all", row.note());
		assertTrue(row.reoffered());
		List<String> said = infos(r, "Investigation " + inv.getKey() + " ");
		assertEquals(1, said.stream().filter(m -> m.contains("cancelled: no report")).count(), said.toString());
		assertTrue(said.stream().anyMatch(m -> m.startsWith(cancelledInfo(inv, from))), said.toString());
		Optional<AgentTaskService.WorkerAssignment> offer = agentTaskService.next(List.of(board(r)),
				r.architect().agentUuid(), r.architect().sessionUuid(), List.of("architect"));
		assertTrue(offer.isPresent() && offer.get().task().getUuid().equals(from.getUuid()),
				"offered to its role again, not stranded");
	}

	@Test
	public void aWithdrawnInvestigationReturnsToTheTaskWaitingOnIt() throws RelizaException {
		Rig r = rig(CommissionIntake.COORDINATOR);
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		assertEquals(TaskStatus.PENDING_INTAKE, reload(inv).getStatus());
		agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(), TaskReturnReason.BLOCKED_ON_DEPENDENCY,
				"waiting on the report", WU);

		agentTaskService.withdraw(inv.getUuid(), r.architect().sessionUuid(), "asked the wrong question", WU);

		AgentTaskData back = reload(from);
		assertFalse(back.getDependsOn().contains(inv.getUuid()));
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("withdrawn by its registrant: asked the wrong question", back.getReportsReturned().get(0).note());
	}

	@Test
	public void aCancelledInvestigationTellsTheHopStillHoldingItsTask() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));

		agentTaskService.cancel(inv.getUuid(), "out of scope", COORD, WU);

		AgentTaskData back = reload(from);
		assertEquals(TaskStatus.ASSIGNED, back.getStatus(), "the hop keeps the task");
		assertEquals(r.architect().sessionUuid(), back.getAssignment().session());
		assertTrue(back.getReportsReturned().get(0).cancelled());
		assertFalse(back.getReportsReturned().get(0).reoffered());
		assertTrue(infos(r, cancelledInfo(inv, from)).stream().anyMatch(m -> m.contains("its architect hop (session "
				+ r.architect().sessionUuid().toString().substring(0, 8) + ") decides how to go on")));
	}

	@Test
	public void aCancelledReturnToNoneInvestigationTouchesNoTask() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, new AgentTaskService.CommissionRequest("tester", "t", null,
				from.getUuid(), List.of(), null, null, null, AgentTaskData.ReturnTo.NONE, null, null, null));
		agentTaskService.returnTask(from.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "later", WU);
		AgentTaskData before = reload(from);

		agentTaskService.cancel(inv.getUuid(), "not needed", PERSON, WU);

		AgentTaskData after = reload(from);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertTrue(after.getReportsReturned().isEmpty());
		assertEquals(before.getStatusHistory().size(), after.getStatusHistory().size());
		assertTrue(infos(r, "cancelled: no report").stream().noneMatch(m -> m.contains(inv.getKey() + " ")));
	}

	@Test
	public void aCoordinatorSetDependencyOnACancelledInvestigationIsUnchanged() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		AgentTaskData other = agentTaskService.register(board(r), null, "other " + java.util.UUID.randomUUID(), null, null,
				null, null, null, null, PERSON, false, WU);
		agentTaskService.authorize(other.getUuid(), board(r), "coder", 10, List.of(inv.getUuid()), null, null, null,
				COORD, WU);

		agentTaskService.cancel(inv.getUuid(), "not needed", PERSON, WU);

		AgentTaskData planned = reload(other);
		assertEquals(List.of(inv.getUuid()), planned.getDependsOn(), "the coordinator re-plans its own dependency");
		assertFalse(agentTaskService.dependenciesMet(planned), "CANCELLED still does not satisfy that gate");
		assertTrue(planned.getReportsReturned().isEmpty());
		assertEquals(1, reload(from).getReportsReturned().size(), "only the commissioning task hears of it");
	}
}

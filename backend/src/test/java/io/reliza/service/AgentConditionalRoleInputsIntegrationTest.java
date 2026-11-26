/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.AgentTaskInputService.InputVerdict;
import io.reliza.ws.App;

/**
 * Inputs produced only by a role routing passed over by its runWhen (task TPF-155, C8, 6.1): a later role starts
 * without them and binds nothing for them; a second producer is waited for as today; the skip is recorded before the
 * next role's inputs are read; the task's own inputs are never waived. T-84, T-100.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentConditionalRoleInputsIntegrationTest extends AgentConditionalRoleTestBase {

	@Autowired AgentTaskInputService agentTaskInputService;

	/** T-84 (a)'s board: the mutant is the only producer of a BOARD_TEST_REPORT the tester requires. */
	private CRig waiverBoard() throws RelizaException {
		return conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(new ProducedOutput(BTR,
				InputScope.TASK, true))), testerDef(List.of(input(DD), assembled(BTR)), List.of()));
	}

	private InputVerdict verdictFor(CRig c, AgentTaskData t, String role) {
		return agentTaskInputService.evaluate(board(c.rig()), roleOf(c, role), reload(t)).stream()
				.filter(v -> BTR == v.requirement().specification()).findFirst().orElseThrow();
	}

	/** T-84 (a): the tester starts on the waiver, binds only DETAILED_DESIGN, publishes and passes. */
	@Test
	public void inputsProducedOnlyByASkippedRoleDoNotBlockTheNextRole() throws RelizaException {
		CRig c = waiverBoard();
		AgentTaskData t = designAndCode(c, task(c, "waived"));
		assertQueuedFor(t, "tester");
		assertEquals(List.of(BTR), skips(t).get(0).waives());

		InputVerdict v = verdictFor(c, t, "tester");
		assertTrue(v.met());
		assertNull(v.resolved());
		assertTrue(v.waived());
		assertTrue(v.unmetReason().startsWith("this task has published no BOARD_TEST_REPORT yet; waived: only mutant"
				+ " produces it"), v.unmetReason());

		assign(c.rig(), t, c.rig().tester());
		List<ResolvedInput> bound = reload(t).getAssignment().resolvedInputs();
		assertEquals(1, bound.size(), bound.toString());
		assertTrue(bound.stream().noneMatch(Objects::isNull));
		assertEquals(DD, bound.get(0).specification());

		ReleaseData report = passingReport(c.rig().tester(), t);
		signOff(c.rig().tester(), t, SignOffOutcome.PASSED, "tested", List.of(report.getUuid()));
		AgentTaskData done = reload(t);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), done.getStatusHistory().toString());
	}

	/** T-84 (b): with a second producer nothing is waived, and the REQUIRED reader waits for the tester's round. */
	@Test
	public void aSecondProducerIsWaitedFor() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(produces(BTR))),
				testerDef(List.of(input(DD)), List.of(produces(BTR))),
				new RoleDef("qa_lead", 60, RoleNecessity.REQUIRED, List.of(input(BTR)), List.of(), null));
		AgentTaskData t = designAndCode(c, task(c, "two producers"));
		assertQueuedFor(t, "tester");
		assertEquals(List.of(), skips(t).get(0).waives());

		assign(c.rig(), t, c.rig().tester());
		ReleaseData report = passingReport(c.rig().tester(), t);
		signOff(c.rig().tester(), t, SignOffOutcome.PASSED, "tested", List.of(report.getUuid()));
		assertQueuedFor(t, "qa_lead");
		assign(c.rig(), t, c.w("qa_lead"));
		List<ResolvedInput> bound = reload(t).getAssignment().resolvedInputs();
		assertEquals(List.of(report.getUuid()), bound.stream().map(ResolvedInput::release).toList());
	}

	/** T-84 (c): the skip is recorded before the next role's inputs are read, whatever the skipped role's own inputs. */
	@Test
	public void theConditionIsReadBeforeTheRolesOwnInputs() throws RelizaException {
		RequiredInput neverReached = new RequiredInput(InputKind.DOCUMENT, TM, InputScope.TASK, null,
				ReleaseLifecycle.READY_TO_SHIP, null);
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(neverReached), List.of(new ProducedOutput(BTR,
				InputScope.TASK, true))), testerDef(List.of(input(DD), assembled(BTR)), List.of()));
		AgentTaskData t = designAndCode(c, task(c, "unreachable mutant"));
		assertEquals(1, skips(t).size());
		assertQueuedFor(t, "tester");
	}

	/** T-100: a requirement the seat added to the task is never waived, even when its type is on a live entry. */
	@Test
	public void theTasksOwnInputsAreNeverWaived() throws RelizaException {
		CRig c = waiverBoard();
		AgentTaskData t = designAndCode(c, task(c, "the seat asks for the report"));
		assign(c.rig(), t, c.rig().tester());
		agentTaskService.returnTask(t.getUuid(), c.rig().tester().sessionUuid(), TaskReturnReason.OTHER,
				"wants the report", WU);
		AgentTaskData queued = agentTaskService.authorize(t.getUuid(), board(c.rig()), "tester", 10, null, null, null,
				List.of(assembled(BTR)), COORD, WU);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("tester", queued.getRole());

		InputVerdict v = verdictFor(c, t, "tester");
		assertFalse(v.met());
		assertFalse(v.waived());
		RelizaException refused = refused(() -> assign(c.rig(), t, c.rig().tester()));
		assertTrue(refused.getMessage().startsWith("Task " + t.getUuid() + " is waiting on its inputs: this task has"
				+ " published no BOARD_TEST_REPORT yet"), refused.getMessage());
	}
}

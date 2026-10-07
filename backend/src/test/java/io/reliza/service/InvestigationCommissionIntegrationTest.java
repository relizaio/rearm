/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.ReturnTo;
import io.reliza.model.AgentTaskData.TaskKind;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.AgentTaskRoleConfigData.CommissionIntake;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * Commissioning an investigation (task RD4-12, design §3.2-3.3): by a role its commissions allow, refused for one
 * they do not, by a person; AUTO and COORDINATOR intake; the brief, the pinned inputs, the budget and its cap, the
 * deadline; and the investigating role's work-task inputs not applying to it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class InvestigationCommissionIntegrationTest extends InvestigationTestBase {

	@Test
	public void anAllowedRoleCommissionsFromItsTaskAndTheInvestigationIsQueued() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));

		assertEquals(TaskKind.INVESTIGATION, inv.getKind());
		assertEquals(TaskStatus.QUEUED, inv.getStatus(), "AUTO intake queues it at once");
		assertEquals("tester", inv.getRole());
		assertEquals("What does the UI do today?", inv.getDescription(), "the brief is the description");
		AgentTaskData.Investigation block = inv.getInvestigation();
		assertEquals(RearmSpecificationType.BOARD_INVESTIGATION_REPORT, block.deliverable());
		assertEquals("tester", block.role());
		assertEquals("architect", block.commissionedBy().role());
		assertEquals(r.architect().sessionUuid(), block.commissionedBy().session());
		assertEquals(from.getUuid(), block.commissionedBy().task());
		assertEquals(ReturnTo.TASK, block.returnTo(), "the default with a from-task");
		assertNull(block.review());
		assertEquals(2_000_000L, inv.getBudgetMicros(), "the commissioning role's default budget");
		assertTrue(null != inv.getKey() && inv.getKey().contains("-"), "keys apply as to any task");
		assertEquals(1, infos(r, "Investigation " + inv.getKey()).size(), "one INFO names the commission");
		assertTrue(infos(r, "Investigation " + inv.getKey()).get(0).contains("from " + from.getKey()));
	}

	@Test
	public void aRoleItsCommissionsDoNotNameIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		RelizaException e = assertThrows(RelizaException.class, () -> commissionedFrom(r, from, ask("lead", from.getUuid())));
		assertTrue(e.getMessage().contains("may not commission lead"), e.getMessage());

		AgentTaskData coded = held(r, "coder", r.coder());
		RelizaException none = assertThrows(RelizaException.class, () -> agentTaskService.commission(board(r),
				ask("tester", coded.getUuid()), r.coder().sessionUuid(), AgentActor.ofSession(r.coder().sessionUuid()), WU));
		assertTrue(none.getMessage().contains("commissions nobody"), none.getMessage());
	}

	@Test
	public void aSessionCommissionsOnlyFromTheTaskItHolds() throws RelizaException {
		Rig r = rig();
		AgentTaskData other = held(r, "coder", r.coder());
		RelizaException e = assertThrows(RelizaException.class,
				() -> commissionedFrom(r, other, ask("tester", other.getUuid())));
		assertTrue(e.getMessage().contains("does not hold"), e.getMessage());
		RelizaException noFrom = assertThrows(RelizaException.class, () -> agentTaskService.commission(board(r),
				ask("tester", null), r.architect().sessionUuid(), AgentActor.ofSession(r.architect().sessionUuid()), WU));
		assertTrue(noFrom.getMessage().contains("--from-task"), noFrom.getMessage());
	}

	@Test
	public void onlyARoleThatProducesTheReportCanBeAskedForOne() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentTaskService.commission(board(r), ask("coder", null), null, PERSON, WU));
		assertTrue(e.getMessage().contains("does not produce BOARD_INVESTIGATION_REPORT"), e.getMessage());
	}

	@Test
	public void aPersonCommissionsAnyRoleThatProducesTheReportWithOrWithoutATask() throws RelizaException {
		Rig r = rig();
		AgentTaskData alone = agentTaskService.commission(board(r), ask("researcher", null), null, PERSON, WU);
		assertEquals(TaskStatus.QUEUED, alone.getStatus());
		assertEquals(ReturnTo.NONE, alone.getInvestigation().returnTo(), "no task: nowhere to return to");
		assertNull(alone.getInvestigation().commissionedBy().role());
		assertEquals(PERSON, alone.getInvestigation().commissionedBy().by());
		assertNull(alone.getBudgetMicros(), "a person's commission has no role default");

		AgentTaskData from = held(r, "coder", r.coder());
		AgentTaskData fromTask = agentTaskService.commission(board(r), ask("tester", from.getUuid()), null, PERSON, WU);
		assertEquals(ReturnTo.TASK, fromTask.getInvestigation().returnTo());
		assertEquals("coder", fromTask.getInvestigation().commissionedBy().role(), "the task's role takes the report back");

		RelizaException taskless = assertThrows(RelizaException.class, () -> agentTaskService.commission(board(r),
				new AgentTaskService.CommissionRequest("tester", "t", null, null, List.of(), null, null, null,
						ReturnTo.TASK, null, null, null), null, PERSON, WU));
		assertTrue(taskless.getMessage().contains("fromTask"), taskless.getMessage());
	}

	@Test
	public void coordinatorIntakeWaitsForTheCoordinatorWhoMayAuthorizeOnlyTheInvestigatingRole() throws RelizaException {
		Rig r = rig(CommissionIntake.COORDINATOR);
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid()));
		assertEquals(TaskStatus.PENDING_INTAKE, inv.getStatus());
		assertTrue(infos(r, inv.getKey()).get(0).contains("waiting for the coordinator's intake"));
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentTaskService.authorize(inv.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU));
		assertTrue(e.getMessage().contains("is an investigation for tester"), e.getMessage());
		AgentTaskData authorized = agentTaskService.authorize(inv.getUuid(), board(r), "tester", 10, null, null, null,
				null, COORD, WU);
		assertEquals(TaskStatus.QUEUED, authorized.getStatus());
	}

	@Test
	public void theBudgetDefaultsFromTheRoleAndTheBoardCapsIt() throws RelizaException {
		Rig r = rig();
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("budgetMicros", 1_500_000L);
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), settings, WU);
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData capped = commissionedFrom(r, from, ask("tester", from.getUuid()));
		assertEquals(1_500_000L, capped.getBudgetMicros(), "the role's 2 USD default cut to the board's 1.5");
		RelizaException over = assertThrows(RelizaException.class, () -> commissionedFrom(r, from,
				new AgentTaskService.CommissionRequest("tester", "t", null, from.getUuid(), List.of(), 3_000_000L, null,
						null, null, null, null, null)));
		assertTrue(over.getMessage().contains("over the board's"), over.getMessage());
	}

	@Test
	public void aDeadlineInThePastIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		RelizaException e = assertThrows(RelizaException.class, () -> commissionedFrom(r, from,
				new AgentTaskService.CommissionRequest("tester", "t", null, from.getUuid(), List.of(), null,
						ZonedDateTime.now().minusMinutes(5), null, null, null, null, null)));
		assertTrue(e.getMessage().contains("has passed"), e.getMessage());
	}

	@Test
	public void theInputsArePinnedAndTheInvestigatorsWorkTaskInputsDoNotApply() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		ReleaseData design = publish(r.architect(), from, RearmSpecificationType.ARCHITECTURE);
		AgentTaskData inv = commissionedFrom(r, from, new AgentTaskService.CommissionRequest("tester", "t", "brief",
				from.getUuid(), List.of(design.getUuid()), null, null, null, null, null, null, null));
		assertEquals(1, inv.getRequiredInputs().size());
		assertEquals(design.getUuid(), inv.getRequiredInputs().get(0).release());
		assertEquals(InputKind.DOCUMENT, inv.getRequiredInputs().get(0).kind());
		// The tester needs a test plan on a work task; an investigation has none, and is offered anyway.
		assertTrue(agentTaskInputService.satisfied(board(r), roleNamed(r, "tester"), inv));
		AgentTaskService.WorkerAssignment wa = agentTaskService.assign(inv.getUuid(), board(r), r.tester().agentUuid(),
				r.tester().sessionUuid(), WU);
		List<ResolvedInput> bound = wa.resolvedInputs();
		assertEquals(1, bound.size(), bound.toString());
		assertEquals(design.getUuid(), bound.get(0).release(), "the pinned release, not the component's newest");
		assertEquals(RearmSpecificationType.ARCHITECTURE, bound.get(0).specification());

		RelizaException missing = assertThrows(RelizaException.class, () -> commissionedFrom(r, from,
				new AgentTaskService.CommissionRequest("tester", "t", null, from.getUuid(), List.of(UUID.randomUUID()),
						null, null, null, null, null, null, null)));
		assertTrue(missing.getMessage().contains("Input release not found"), missing.getMessage());
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.reliza.model.AgentTaskData.ConditionSkip;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.AgentBoardService.RoleConfigSpec;
import io.reliza.service.AgentTaskInputService.InputVerdict;
import io.reliza.ws.App;

/**
 * TPF-155 mutation round: what the conditional-role classes leave open. What an entry waives (a report is never
 * waived, a type once), the record a question's routing writes for a producer it passes over, a set of producers that
 * are all passed over standing, the clause that names a passed-over role when no role can run, an investigation-only
 * role never queued by the walk, an older call site's upsert leaving the condition, and the waiver of an input that a
 * document met, of a document that is not the task's, and of the bindings.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentConditionalRoleEdgesIntegrationTest extends AgentConditionalRoleTestBase {

	@Autowired AgentTaskInputService agentTaskInputService;

	private InputVerdict verdictFor(CRig c, AgentTaskData t, String role) {
		return agentTaskInputService.evaluate(board(c.rig()), roleOf(c, role), reload(t)).stream()
				.filter(v -> BTR == v.requirement().specification()).findFirst().orElseThrow();
	}

	/** The mutant is the only producer of a BOARD_TEST_REPORT the tester requires. */
	private CRig waiverBoard() throws RelizaException {
		return conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(new ProducedOutput(BTR,
				InputScope.TASK, true))), testerDef(List.of(input(DD), assembled(BTR)), List.of()));
	}

	/** The coder, holding the task, asks about the BOARD_TEST_REPORT and signs off REJECTED. */
	private void coderAsksAboutTheReport(CRig c, AgentTaskData t) throws RelizaException {
		assign(c.rig(), t, c.w("coder"));
		ReleaseData q = index(c.w("coder"), reload(t), RearmSpecificationType.BOARD_QUESTIONS, "REJECTED", BTR, "q1", 1,
				"OPEN");
		signOff(c.w("coder"), t, SignOffOutcome.REJECTED, "which report?", List.of(q.getUuid()));
	}

	@Test
	public void anEntryWaivesAReportNeverAndATypeOnce() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(
				new ProducedOutput(BTR, InputScope.TASK, false),
				new ProducedOutput(RearmSpecificationType.BOARD_INVESTIGATION_REPORT, InputScope.TASK, false),
				new ProducedOutput(BTR, InputScope.COMPONENT, false))), testerDef(List.of(input(DD)), List.of()));
		AgentTaskData t = designAndCode(c, task(c, "what is waived"));

		assertEquals(List.of(BTR), skips(t).get(0).waives());
	}

	@Test
	public void aQuestionRecordsTheProducerItPassesOver() throws RelizaException {
		CRig c = board(List.of(architectDef(), coderDef(),
				new RoleDef("mutant", 44, RoleNecessity.OPTIONAL, List.of(assembled(DD)), List.of(produces(BTR)),
						TAGGED_MUTATION),
				testerDef(List.of(input(DD)), List.of(produces(BTR)))));
		AgentTaskData t = task(c, "untagged, asked before the walk reaches the mutant");
		design(c.rig(), t, "fits one coder pass");
		assertTrue(skips(t).isEmpty(), "nothing has walked past the mutant yet");

		coderAsksAboutTheReport(c, t);

		assertQueuedFor(t, "tester");
		List<ConditionSkip> recorded = skips(t);
		assertEquals(1, recorded.size(), recorded.toString());
		assertEquals("mutant", recorded.get(0).role());
		assertEquals(NO_MUTATION_TAG, recorded.get(0).reason());
	}

	@Test
	public void producersThatAreAllPassedOverStand() throws RelizaException {
		CRig c = board(List.of(architectDef(), coderDef(),
				new RoleDef("mutant_a", 44, RoleNecessity.OPTIONAL, List.of(assembled(DD)), List.of(produces(BTR)),
						TAGGED_MUTATION),
				new RoleDef("mutant_b", 46, RoleNecessity.OPTIONAL, List.of(assembled(DD)), List.of(produces(BTR)),
						TAGGED_MUTATION),
				testerDef(List.of(input(DD)), List.of())));
		AgentTaskData t = task(c, "every producer of the type is conditional");
		design(c.rig(), t, "fits one coder pass");

		coderAsksAboutTheReport(c, t);

		assertQueuedFor(t, "mutant_a");
	}

	@Test
	public void theCoordinatorMessageNamesThePassedOverRole() throws RelizaException {
		RequiredInput never = new RequiredInput(InputKind.DOCUMENT, TM, InputScope.TASK, null,
				ReleaseLifecycle.READY_TO_SHIP, null);
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of()),
				testerDef(List.of(input(DD), never), List.of(produces(BTR))));
		AgentTaskData t = designAndCode(c, task(c, "nobody can run"));

		AgentTaskData now = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, now.getStatus(), now.getStatusHistory().toString());
		List<String> said = boardEvents(c.rig()).stream().filter(e -> e.contains("no role can run")).toList();
		assertEquals(1, said.size(), boardEvents(c.rig()).toString());
		assertTrue(said.get(0).contains("required role(s) have not passed"), said.get(0));
		assertTrue(said.get(0).contains("passed over " + passedOver("mutant", NO_MUTATION_TAG)), said.get(0));
	}

	@Test
	public void anInvestigationOnlyRoleIsNeverQueuedByTheWalk() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of()),
				testerDef(List.of(input(DD)), List.of(produces(BTR))),
				new RoleDef("investigator", 42, RoleNecessity.OPTIONAL, List.of(),
						List.of(produces(RearmSpecificationType.BOARD_INVESTIGATION_REPORT)), null));
		AgentTaskData t = designAndCode(c, task(c, "an investigator sits before the mutant"));

		assertQueuedFor(t, "tester");
	}

	@Test
	public void anUpsertThatNamesNoConditionLeavesIt() throws RelizaException {
		CRig c = conditional();
		agentBoardService.upsertRoleConfig(board(c.rig()), new RoleConfigSpec("mutant", "a new prompt", null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null), true, WU);

		assertEquals(TAGGED_MUTATION, roleOf(c, "mutant").getRunWhen());
		assertEquals("a new prompt", roleOf(c, "mutant").getPrompt());
	}

	@Test
	public void aWaivedRequirementBindsNothingAndAMetOneKeepsItsDocument() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(new ProducedOutput(BTR,
				InputScope.TASK, true))), testerDef(List.of(input(DD), input(BTR)), List.of()));
		AgentTaskData t = designAndCode(c, task(c, "waived, then a report appears"));
		assertQueuedFor(t, "tester");

		List<?> bindings = agentTaskInputService.bindings(board(c.rig()), roleOf(c, "tester"), reload(t));
		assertEquals(1, bindings.size(), bindings.toString());
		assertTrue(bindings.stream().noneMatch(Objects::isNull), "a waived requirement has no binding");
		assertTrue(verdictFor(c, t, "tester").waived());

		assign(c.rig(), t, c.rig().tester());
		ReleaseData report = passingReport(c.rig().tester(), t);
		InputVerdict met = verdictFor(c, t, "tester");
		assertFalse(met.waived(), "a requirement a document meets is not waived: " + met);
		assertNotNull(met.resolved(), met.toString());
		assertEquals(report.getUuid(), met.resolved().release());
	}

	@Test
	public void aDocumentOfAnotherScopeIsNotWaived() throws RelizaException {
		RequiredInput onTheNode = new RequiredInput(InputKind.DOCUMENT, BTR, InputScope.COMPONENT, null,
				ReleaseLifecycle.ASSEMBLED, null);
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(new ProducedOutput(BTR,
				InputScope.TASK, true))), testerDef(List.of(input(DD), onTheNode), List.of()));
		AgentTaskData t = designAndCode(c, task(c, "a requirement on the board's target"));

		InputVerdict v = verdictFor(c, t, "tester");
		assertFalse(v.waived(), v.toString());
		assertFalse(v.met(), v.toString());
	}
}

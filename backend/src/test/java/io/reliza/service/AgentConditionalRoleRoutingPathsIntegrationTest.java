/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.ConditionSkip;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecision;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;

/**
 * Where routing picks a role other than the forward walk, and where a person or the seat names one (task TPF-155,
 * C4, 5.3, 5.4, 5.8): an explicit hold-and-lift, authorize or a sole producer runs the conditional role and the record
 * stays; the TPF reject loop on an untagged task never reaches a mutant role, through {@code builderAfter}, the
 * {@code about} pick of {@code routeUpstream} and of {@code routeDecided}; a blind role reads the record whole. T-83,
 * T-94, T-97, T-101.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentConditionalRoleRoutingPathsIntegrationTest extends AgentConditionalRoleTestBase {

	/** T-83 (a): hold then lift to the mutant runs it, once; the next round's walk reads the condition again. */
	@Test
	public void aHoldAndLiftRunsTheSkippedRoleForThatRoundOnly() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "lift to the mutant"));
		ConditionSkip first = skips(t).get(0);
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "run the mutant", COORD, WU);
		agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, null, "mutant", WU);
		assertQueuedFor(t, "mutant");
		pass(c.rig(), t, c.w("mutant"));
		assertQueuedFor(t, "tester");
		AgentTaskData after = reload(t);
		assertEquals(List.of(first), after.getRolesSkippedByCondition(), "exactly the one entry");
		assertNull(after.liveConditionSkip(first.roleUuid(), "mutant"), "a sign-off after its at");
		assertEquals(1, signOffsBy(t, "mutant"));
		assertEquals("mutant passed; next tester", lastNote(t));

		testRejects(c.rig(), t, DD, "T-1");
		assertQueuedFor(t, "coder");
		code(c.rig(), t);
		assertQueuedFor(t, "tester");
		List<ConditionSkip> skips = skips(t);
		assertEquals(2, skips.size(), "the mutant's pass is stale once DETAILED_DESIGN moved: " + skips);
		assertTrue(skips.get(1).at().isAfter(first.at()));
		assertEquals(1, signOffsBy(t, "mutant"), "the mutant did not run again");
	}

	/** T-83 (b): authorize --role once the task is with the coordinator; no new entry. */
	@Test
	public void anAuthorizeRunsTheSkippedRole() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "authorized to the mutant"));
		assign(c.rig(), t, c.rig().tester());
		agentTaskService.returnTask(t.getUuid(), c.rig().tester().sessionUuid(), TaskReturnReason.OTHER, "needs mutants", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(t).getStatus());
		AgentTaskData queued = authorize(c.rig(), t, "mutant", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("mutant", queued.getRole());
		assertEquals(1, skips(t).size());
	}

	/** T-83 (c): an item about a type only the conditional role produces goes to it whatever the tags. */
	@Test
	public void aSoleConditionalProducerIsStillAskedAboutItsType() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of(produces(BTR))),
				testerDef(List.of(input(DD)), List.of()));
		AgentTaskData t = designAndCode(c, task(c, "sole producer"));
		assertQueuedFor(t, "tester");
		assertEquals(List.of(BTR), skips(t).get(0).waives());
		testRejects(c.rig(), t, BTR, "T-1");
		assertQueuedFor(t, "mutant");
		assertEquals(1, skips(t).size(), "no new entry");
	}

	/** T-94: a blind tester reads the record whole at assignment. */
	@Test
	public void aBlindRoleReadsTheRecordWhole() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of()),
				new RoleDef("tester", 50, RoleNecessity.REQUIRED, List.of(input(DD)), List.of(produces(BTR)), null, null,
						Boolean.TRUE));
		AgentTaskData t = designAndCode(c, task(c, "blind tester"));
		ConditionSkip stored = skips(t).get(0);
		WorkerAssignment wa = agentTaskService.assign(t.getUuid(), board(c.rig()), c.rig().tester().agentUuid(),
				c.rig().tester().sessionUuid(), WU);
		assertEquals(List.of(stored), wa.task().getRolesSkippedByCondition());
	}

	/**
	 * T-97: the TPF reject loop on an untagged task: the coder's answering round goes back to the tester, never to a
	 * mutant role; tagged, it goes to mutant_coder. An item about BOARD_TEST_REPORT goes to the tester untagged and to
	 * mutant_tester tagged.
	 */
	@Test
	public void theTpfRejectLoopNeverQueuesAMutantRoleOnAnUntaggedTask() throws RelizaException {
		CRig c = tpfShaped();
		AgentTaskData t = designAndCode(c, task(c, "untagged loop"));
		assertQueuedFor(t, "tester");
		assertEquals(List.of("mutant_coder", "mutant_tester"), skips(t).stream().map(ConditionSkip::role).toList());
		assertEquals("coder passed; next tester; passed over " + passedOver("mutant_coder", NO_MUTATION_TAG) + ", "
				+ passedOver("mutant_tester", NO_MUTATION_TAG), lastNote(t));
		List<ConditionSkip> before = skips(t);

		testRejects(c.rig(), t, DD, "T-1");
		assertQueuedFor(t, "coder");
		code(c.rig(), t);
		assertQueuedFor(t, "tester");
		assertTrue(lastNote(t).contains("tester"), lastNote(t));
		assertEquals(0, signOffsBy(t, "mutant_coder") + signOffsBy(t, "mutant_tester"));
		assertEquals(before, skips(t), "both live, nothing appended");
		assertTrue(notes(t).stream().noneMatch(n -> null != n && n.contains("to mutant_coder")), notes(t).toString());

		// tagged: the same loop goes to mutant_coder
		AgentTaskData tagged = designAndCode(c, task(c, "tagged loop", MUTATION));
		assertQueuedFor(tagged, "mutant_coder");
		pass(c.rig(), tagged, c.w("mutant_coder"));
		pass(c.rig(), tagged, c.w("mutant_tester"));
		testRejects(c.rig(), tagged, DD, "T-1");
		code(c.rig(), tagged);
		assertQueuedFor(tagged, "mutant_coder");
		assertEquals("coder passed with a new round; to mutant_coder", lastNote(tagged));

		// an item about BOARD_TEST_REPORT: the unmet mutant_tester is dropped untagged, picked tagged
		AgentTaskData about = designAndCode(c, task(c, "about the report"));
		testRejects(c.rig(), about, BTR, "T-1");
		assertQueuedFor(about, "tester");
		assertEquals("open T-1; back to tester", lastNote(about));
		AgentTaskData aboutTagged = designAndCode(c, task(c, "about the report, tagged", MUTATION));
		pass(c.rig(), aboutTagged, c.w("mutant_coder"));
		pass(c.rig(), aboutTagged, c.w("mutant_tester"));
		testRejects(c.rig(), aboutTagged, BTR, "T-1");
		assertQueuedFor(aboutTagged, "mutant_tester");
		assertEquals("open T-1; back to mutant_tester", lastNote(aboutTagged));
	}

	/**
	 * T-101: a person's decision that adds a blocking item about BOARD_TEST_REPORT reaches {@code routeDecided}, whose
	 * producer pick drops the unmet mutant_tester on an untagged task; tagged, it picks mutant_tester.
	 */
	@Test
	public void aPersonsDecisionGoesToTheTesterOnAnUntaggedTask() throws RelizaException {
		CRig c = tpfShaped();
		AgentTaskData t = designAndCode(c, task(c, "decided, untagged"));
		testRejects(c.rig(), t, BTR, "T-1");
		assertEquals("open T-1; back to tester", lastNote(t));
		AgentTaskData decided = decideAddingAnItem(t);
		assertEquals(TaskStatus.QUEUED, decided.getStatus(), decided.getStatusHistory().toString());
		assertEquals("tester", decided.getRole());
		assertEquals("review items decided; back to tester", lastNote(t));
		assertEquals(List.of("Review items decided on task " + t.label() + "; back to tester"),
				messages(c.rig(), BoardEventKind.INFO, "Review items decided on task " + t.label()));

		AgentTaskData tagged = designAndCode(c, task(c, "decided, tagged", MUTATION));
		pass(c.rig(), tagged, c.w("mutant_coder"));
		pass(c.rig(), tagged, c.w("mutant_tester"));
		testRejects(c.rig(), tagged, BTR, "T-1");
		decideAddingAnItem(tagged);
		assertQueuedFor(tagged, "mutant_tester");
		assertEquals("review items decided; back to mutant_tester", lastNote(tagged));
		assertFalse(skips(tagged).stream().anyMatch(s -> "mutant_tester".equals(s.role())));
	}

	private AgentTaskData decideAddingAnItem(AgentTaskData t) throws RelizaException {
		return agentTaskService.decideReviewItems(t.getUuid(), BTR,
				List.of(new BoardReviewItemDecision(BoardReviewItemDecisionAction.FILE, null, 1, "a person's find", null,
						null)), null, PERSON, WU);
	}
}

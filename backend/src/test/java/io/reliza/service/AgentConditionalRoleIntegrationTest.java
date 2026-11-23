/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.ws.App;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.ConditionSkip;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.RoleKind;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.AgentTaskRoleConfigData.RunWhen;

/**
 * Routing passes an OPTIONAL role over when the task's tags do not meet its runWhen (task TPF-155, sections 5 and 6):
 * the skip is recorded with no hop and no event, a met condition runs the role, completion is unaffected, a tag added
 * later changes nothing until routing next reaches the role, the two lists combine by AND on keys only, a HUMAN role
 * takes a condition too, one entry stays live per role until the role runs or is reopened to. T-80, T-81, T-85 to T-88,
 * T-95, T-96, T-98.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentConditionalRoleIntegrationTest extends AgentConditionalRoleTestBase {

	/** T-80: an untagged task goes coder -> tester; the mutant is recorded as passed over, with no hop and no event. */
	@Test
	public void routingSkipsAnUnmetOptionalRoleWithTheRecordAndNoHop() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "untagged"));

		AgentTaskData after = reload(t);
		assertQueuedFor(after, "tester");
		List<ConditionSkip> skips = skips(after);
		assertEquals(1, skips.size(), skips.toString());
		ConditionSkip s = skips.get(0);
		assertEquals("mutant", s.role());
		assertEquals(roleOf(c, "mutant").getUuid(), s.roleUuid());
		assertEquals(NO_MUTATION_TAG, s.reason());
		assertEquals(AgentActor.ActorKind.SYSTEM, s.by().kind());
		assertEquals("routing", s.by().name());
		SignOff coders = after.lastSignOffForRole(null, "coder");
		assertFalse(s.at().isBefore(coders.signedOffAt()), s.at() + " before " + coders.signedOffAt());
		assertFalse(s.at().isAfter(ZonedDateTime.now()));
		assertEquals(List.of(), s.waives());
		assertEquals(0, signOffsBy(after, "mutant"));
		assertEquals("coder passed; next tester; passed over " + passedOver("mutant", NO_MUTATION_TAG), lastNote(after));
		assertTrue(boardEvents(c.rig()).stream().noneMatch(e -> e.contains("mutant")), boardEvents(c.rig()).toString());
		assertTrue(null == after.getRequiredRolesSkipped() || after.getRequiredRolesSkipped().isEmpty());
	}

	/** T-81: a task tagged mutation runs the mutant, with no record. */
	@Test
	public void aMetConditionRunsTheRole() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "tagged", MUTATION));
		assertQueuedFor(t, "mutant");
		assertTrue(skips(t).isEmpty(), skips(t).toString());
		assertEquals("coder passed; next mutant", lastNote(t));
		pass(c.rig(), t, c.w("mutant"));
		assertQueuedFor(t, "tester");
	}

	/** T-85: the tester's pass completes the task; the skipped OPTIONAL role is not missing, and no second entry. */
	@Test
	public void completionIsUnaffected() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "completes"));
		pass(c.rig(), t, c.rig().tester());
		AgentTaskData done = reload(t);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), done.getStatusHistory().toString());
		assertTrue(null == done.getRequiredRolesSkipped() || done.getRequiredRolesSkipped().isEmpty());
		assertEquals(1, skips(t).size(), "the completing walk passes the mutant over on its live entry: " + skips(t));
		assertTrue(boardAlerts(c.rig()).isEmpty(), boardAlerts(c.rig()).toString());
	}

	/**
	 * T-86: a tag added after the skip re-routes nothing and writes nothing; the coder's answering round reads it at
	 * {@code builderAfter}; the variant reads it at the next walk after the tester's pass.
	 */
	@Test
	public void aTagAddedAfterTheSkipChangesNothingOnItsOwn() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "tag later"));
		List<String> rowsBefore = notes(t);
		ConditionSkip entry = skips(t).get(0);
		int eventsBefore = boardEvents(c.rig()).size();
		tag(t, MUTATION);
		assertQueuedFor(t, "tester");
		assertEquals(List.of(entry), skips(t));
		assertEquals(rowsBefore, notes(t), "no new status row");
		assertEquals(eventsBefore, boardEvents(c.rig()).size(), "no event");

		testRejects(c.rig(), t, DD, "T-1");
		assertQueuedFor(t, "coder");
		code(c.rig(), t);
		assertQueuedFor(t, "mutant");
		assertEquals("coder passed with a new round; to mutant", lastNote(t));
		AgentTaskData now = reload(t);
		assertEquals(List.of(entry), now.getRolesSkippedByCondition());
		assertEquals(entry, now.liveConditionSkip(entry.roleUuid(), "mutant"), "still live: the mutant has not signed off");

		// the variant: tagged after the skip, and the tester passes instead
		AgentTaskData v = designAndCode(c, task(c, "tag later, tester passes"));
		tag(v, MUTATION);
		pass(c.rig(), v, c.rig().tester());
		assertQueuedFor(v, "mutant");
		assertEquals("tester passed; next mutant", lastNote(v));
		pass(c.rig(), v, c.w("mutant"));
		assertEquals(TaskStatus.COMPLETED, reload(v).getStatus(), reload(v).getStatusHistory().toString());
	}

	/** T-87: taggedNone runs the role on an untagged task and passes it over on a task carrying the key. */
	@Test
	public void taggedNoneRunsUnlessTheTaskCarriesTheKey() throws RelizaException {
		CRig c = conditional(new RunWhen(List.of(), List.of("no-mutation")));
		AgentTaskData untagged = designAndCode(c, task(c, "untagged"));
		assertQueuedFor(untagged, "mutant");
		AgentTaskData tagged = designAndCode(c, task(c, "opted out", "no-mutation"));
		assertQueuedFor(tagged, "tester");
		assertEquals("runWhen not met: tagged no-mutation", skips(tagged).get(0).reason());
	}

	/** T-88: both lists combine by AND, the taggedAny reason wins, values and the stored case are ignored. */
	@Test
	public void bothListsCombineByAndOnKeysOnly() throws RelizaException {
		CRig c = conditional(new RunWhen(List.of(MUTATION), List.of("skip-mutation")));
		assertQueuedFor(designAndCode(c, task(c, "mutation only", MUTATION)), "mutant");

		AgentTaskData both = designAndCode(c, task(c, "both", MUTATION, "skip-mutation"));
		assertQueuedFor(both, "tester");
		assertEquals("runWhen not met: tagged skip-mutation", skips(both).get(0).reason());

		AgentTaskData untagged = designAndCode(c, task(c, "untagged"));
		assertEquals(NO_MUTATION_TAG, skips(untagged).get(0).reason());

		AgentTaskData skipOnly = designAndCode(c, task(c, "skip-mutation only", "skip-mutation"));
		assertEquals(NO_MUTATION_TAG, skips(skipOnly).get(0).reason(), "the taggedAny reason wins");

		AgentTaskData valued = task(c, "valued");
		tagWithValue(valued, MUTATION, "later");
		assertQueuedFor(designAndCode(c, valued), "mutant");

		AgentTaskData upper = task(c, "upper case");
		tag(upper, "MUTATION");
		assertEquals(MUTATION, reload(upper).getTags().get(0).key(), "setTags lower-cases");
		assertQueuedFor(designAndCode(c, upper), "mutant");

		CRig two = conditional(new RunWhen(List.of(MUTATION, "perf"), List.of()));
		AgentTaskData none = designAndCode(two, task(two, "untagged, two keys"));
		assertEquals("runWhen not met: no mutation or perf tag", skips(none).get(0).reason());
	}

	/** T-95: a HUMAN role takes a condition: passed over untagged, queued for the human stage when tagged. */
	@Test
	public void aHumanRoleMayCarryIt() throws RelizaException {
		CRig c = conditional(new RoleDef("signer", 45, RoleNecessity.OPTIONAL, List.of(input(DD)), List.of(),
				new RunWhen(List.of("sign"), List.of()), RoleKind.HUMAN, null),
				testerDef(List.of(input(DD)), List.of(produces(BTR))));
		AgentTaskData untagged = designAndCode(c, task(c, "untagged"));
		assertQueuedFor(untagged, "tester");
		assertEquals("signer", skips(untagged).get(0).role());
		assertEquals("runWhen not met: no sign tag", skips(untagged).get(0).reason());

		AgentTaskData tagged = designAndCode(c, task(c, "to sign", "sign"));
		assertEquals("signer", reload(tagged).getRole(), reload(tagged).getStatusHistory().toString());
		assertTrue(skips(tagged).isEmpty());
	}

	/** T-96: one live entry per role; once the role ran, the next pass-over appends a second. */
	@Test
	public void oneLiveEntryPerRoleAndANewOneAfterTheRoleRan() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "runs once by name"));
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "run the mutant", COORD, WU);
		agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, null, "mutant", WU);
		pass(c.rig(), t, c.w("mutant"));
		pass(c.rig(), t, c.rig().tester());
		assertEquals(TaskStatus.COMPLETED, reload(t).getStatus(), reload(t).getStatusHistory().toString());
		assertEquals(1, skips(t).size());

		agentTaskService.reopen(t.getUuid(), "coder", "the PR no longer merges", COORD, WU);
		code(c.rig(), t);
		assertQueuedFor(t, "tester");
		List<ConditionSkip> skips = skips(t);
		assertEquals(2, skips.size(), skips.toString());
		assertTrue(skips.get(1).at().isAfter(skips.get(0).at()));
		AgentTaskData now = reload(t);
		assertEquals(skips.get(1), now.liveConditionSkip(skips.get(1).roleUuid(), "mutant"));
	}

	/** T-98: a reopen to the role while its entry is live ends the entry; the role runs, the task completes. */
	@Test
	public void aReopenWhileTheEntryIsLiveEndsIt() throws RelizaException {
		CRig c = conditional();
		AgentTaskData t = designAndCode(c, task(c, "reopened to the mutant"));
		pass(c.rig(), t, c.rig().tester());
		AgentTaskData done = reload(t);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		ConditionSkip entry = skips(t).get(0);
		assertEquals(entry, done.liveConditionSkip(entry.roleUuid(), "mutant"));

		AgentTaskData reopened = agentTaskService.reopen(t.getUuid(), "mutant", "run it", PERSON, WU);
		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		assertEquals("mutant", reopened.getRole());
		AgentTaskData now = reload(t);
		assertNull(now.liveConditionSkip(entry.roleUuid(), "mutant"));
		assertEquals(List.of(entry), now.getRolesSkippedByCondition(), "the entry itself unchanged");
		assertTrue(null == now.getRequiredRolesSkipped() || now.getRequiredRolesSkipped().isEmpty());
		pass(c.rig(), t, c.w("mutant"));
		AgentTaskData completed = reload(t);
		assertEquals(TaskStatus.COMPLETED, completed.getStatus(), completed.getStatusHistory().toString());
		assertNotNull(completed.lastSignOffForRole(null, "mutant"));
	}
}

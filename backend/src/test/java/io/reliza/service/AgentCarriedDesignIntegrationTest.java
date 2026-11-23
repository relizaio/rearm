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
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskData.CarriedRound;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * A split child marked {@code designFromParent} (task TPF-161, built by TPF-162): what the split records, how the
 * child goes to the coder and on to completion on the parent's passes, and the split's refusals. Rows T-2 to T-8 and
 * T-12 to T-15 of the TPF-161 architecture, round 6 (TPF-162 architecture, round 1).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCarriedDesignIntegrationTest extends AgentCarriedDesignTestBase {

	/** T-2: the flagged child carries the record; the plain child does not; both pin the design. */
	@Test
	public void theFlaggedChildCarriesTheParentsReviewedDesign() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "carried, the record");
		CarriedDesign cd = c.flagged().getCarriedDesign();
		assertEquals(c.parent().getUuid(), cd.parentTask());
		assertEquals(c.parent().keyOrUuid(), cd.parentKey());
		List<UUID> design = c.design().stream().map(ReleaseData::getUuid).toList();
		assertEquals(design, cd.rounds().stream().map(CarriedRound::release).toList());
		assertEquals(design, pins(c.flagged()), "the record's rounds are the child's pins");
		for (int i = 0; i < design.size(); i++) {
			ReleaseData rd = c.design().get(i);
			CarriedRound round = cd.rounds().get(i);
			assertEquals(rd.getDocument().specification(), round.specification());
			assertEquals(rd.getDocument().round(), round.round());
			assertEquals(rd.getDocument().path(), round.path());
		}
		assertEquals(DESIGN_ROLES, cd.roles().stream().map(CarriedRole::role).toList());
		for (String role : DESIGN_ROLES) {
			SignOff pass = lastOf(c.parent(), role);
			CarriedRole cr = carriedRole(c.flagged(), role);
			assertEquals(roleConfig(r, role).getUuid(), cr.roleUuid());
			assertEquals(pass.signedOffAt(), cr.signedOffAt(), role);
			assertEquals(pass.session(), cr.session(), role);
			assertNull(cr.reviewedBy(), role);
			assertFalse(cr.decidedOver(), role);
		}
		assertEquals(COORD, cd.by());
		assertTrue(null != cd.at() && !cd.at().isBefore(lastOf(c.parent(), "security_reviewer").signedOffAt()));
		assertNull(c.plain().getCarriedDesign(), "the plain child carries nothing");
		assertEveryChildPins(List.of(c.flagged(), c.plain()), c.design());
	}

	/** T-3: the flagged child's checkpoint is settled SENT_ON over the carried rounds; the plain child has none. */
	@Test
	public void theFlaggedChildsCheckpointIsSentOnOverTheCarriedRounds() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "carried, the checkpoint");
		DesignCheckpoint cp = c.flagged().getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(c.design().stream().map(ReleaseData::getUuid).toList(), cp.design());
		assertEquals(COORD, cp.settledBy());
		assertEquals("security_reviewer", cp.afterRole());
		assertEquals("coder", cp.beforeRole());
		assertEquals("carried from " + c.parent().keyOrUuid() + ": " + round(c.design().get(0)) + ", "
				+ round(c.design().get(1)), cp.note());
		assertNull(c.plain().getDesignCheckpoint());
	}

	/** T-4: the seat's authorize sends the flagged child to the coder with no stop and writes nothing on the record. */
	@Test
	public void theSeatsAuthorizeSendsTheFlaggedChildToTheCoder() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "carried, authorized");
		DesignCheckpoint before = c.flagged().getDesignCheckpoint();
		AgentTaskData queued = authorize(r, c.flagged(), "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		assertEquals(before, reload(c.flagged()).getDesignCheckpoint(), "the split's record survives the authorize");
		assertTrue(checkpointAlerts(r, c.flagged()).isEmpty());
		assertTrue(sentOnInfos(r, c.flagged()).isEmpty(), "settle wrote nothing");
		for (String reviewer : List.of("functional_reviewer", "security_reviewer")) {
			List<UUID> reviewed = lastOf(c.parent(), reviewer).reviewedInputs();
			for (ReleaseData rd : c.design()) assertTrue(reviewed.contains(rd.getUuid()), reviewer + " bound " + round(rd));
		}
	}

	/** T-5: the coder's assignment binds each parent release once: the pin and the role's requirement deduped. */
	@Test
	public void theCodersAssignmentBindsEachCarriedReleaseOnce() throws RelizaException {
		Rig r = reviewed();
		Carried c = coderHolds(r, "carried, assigned");
		assertEquals(TaskStatus.ASSIGNED, c.flagged().getStatus());
		List<ResolvedInput> bound = c.flagged().getAssignment().resolvedInputs();
		assertEquals(c.design().stream().map(ReleaseData::getUuid).toList(),
				bound.stream().map(ResolvedInput::release).toList(), bound.toString());
		assertEquals(List.of(ARCH, TM), bound.stream().map(ResolvedInput::specification).toList());
	}

	/** T-6: the plain child is refused at the coder's assign, as before. */
	@Test
	public void thePlainChildStillWaitsOnItsOwnDesign() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "carried, the plain one");
		authorize(r, c.plain(), "coder", COORD);
		RelizaException e = refused(() -> assign(r, c.plain(), r.coder()));
		assertTrue(e.getMessage().contains("is waiting on its inputs: ")
				&& e.getMessage().contains("this task has published no ARCHITECTURE yet"), e.getMessage());
	}

	/** T-7 and T-8: after the coder the tester runs, no design role is queued, and the child completes. */
	@Test
	public void theCarriedChildGoesOnToTheTesterAndCompletes() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "carried, completed");
		AgentTaskData t = c.flagged();
		code(r, t);
		AgentTaskData atTester = reload(t);
		assertEquals(TaskStatus.QUEUED, atTester.getStatus(), atTester.getStatusHistory().toString());
		assertEquals("tester", atTester.getRole());
		for (String role : DESIGN_ROLES) {
			assertNull(atTester.lastSignOffForRole(null, role), role + " never ran on the child");
			assertTrue(carryLive(r, t, role), role);
		}
		assertTrue(atTester.getStatusHistory().stream().noneMatch(sc -> null != sc.note()
				&& DESIGN_ROLES.stream().anyMatch(role -> sc.note().contains("next " + role))),
				atTester.getStatusHistory().toString());

		pass(r, t, r.tester());
		AgentTaskData passed = reload(t);
		assertTrue(agentTaskService.missingRequiredRoles(passed).isEmpty(),
				String.valueOf(agentTaskService.missingRequiredRoles(passed)));
		if (TaskStatus.COMPLETED != passed.getStatus()) {
			agentTaskService.complete(t.getUuid(), "done", COORD, WU);
		}
		AgentTaskData done = reload(t);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), done.getStatusHistory().toString());
		assertTrue(routingService.carryStanding(done));
	}

	/** T-12 (R-1): a parent with no reviewed design round refuses a flagged child; nothing is registered. */
	@Test
	public void aParentWithNoDesignRefusesAFlaggedChild() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData parent = task(r, "no design yet");
		RelizaException e = refused(() -> split(r, parent, child("plain"), flagged("part 1")));
		assertRefusal(e, "part 1", "a reviewed design round on " + parent.keyOrUuid() + "; it has none");
		assertNoChildren(parent);
	}

	/** T-13 (R-3): a reviewer's pass reopened since refuses the flag, naming the reviewer. */
	@Test
	public void aReopenedReviewerRefusesAFlaggedChild() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "reopened reviewer");
		sentOn(r, parent);
		code(r, parent);
		pass(r, parent, r.tester());
		if (TaskStatus.COMPLETED != reload(parent).getStatus()) agentTaskService.complete(parent.getUuid(), "done", COORD, WU);
		agentTaskService.reopen(parent.getUuid(), "security_reviewer", "look again", COORD, WU);
		assertEquals(TaskStatus.QUEUED, reload(parent).getStatus());
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", parent.keyOrUuid() + "'s design stage complete; security_reviewer has no current"
				+ " pass on it");
		assertNoChildren(parent);
	}

	/** T-14 (R-4): a coder that reads a document the design does not carry refuses the flag. */
	@Test
	public void aBuildRoleReadingWhatTheDesignDoesNotCarryRefusesTheFlag() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "coder reads more");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), role("coder", 40, HumanGate.NONE,
				List.of(input(ARCH), input(TM), input(DD)), List.of(produces(DD)), CODER_HOP, Boolean.TRUE), true, WU);
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", "DETAILED_DESIGN for coder; " + parent.keyOrUuid()
				+ "'s design carries only ARCHITECTURE, THREAT_MODEL");
		assertNoChildren(parent);
	}

	/** T-15 (D9): the split INFO names the flagged children and the build role; all of them when every one is. */
	@Test
	public void theSplitInfoNamesTheChildrenThatBuildFromTheDesign() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "carried, the INFO");
		List<AgentTaskData> children = List.of(c.flagged(), c.plain());
		String rounds = round(c.design().get(0)) + ", " + round(c.design().get(1));
		assertEquals(List.of(splitInfo(c.parent(), children, rounds) + "; " + c.flagged().keyOrUuid()
				+ " build from it and go to coder"), splitInfos(r, c.parent()));

		AgentTaskData all = task(r, "carried, all flagged");
		atCheckpoint(r, all);
		List<ReleaseData> design = parentDesign(all);
		List<AgentTaskData> both = split(r, all, flagged("part 1"), flagged("part 2", 0));
		assertEquals(List.of(splitInfo(all, both, round(design.get(0)) + ", " + round(design.get(1)))
				+ "; all build from it and go to coder"), splitInfos(r, all));
		for (AgentTaskData child : both) assertTrue(reload(child).isDesignCarried(), child.keyOrUuid());

		AgentTaskData none = task(r, "carried, none flagged");
		atCheckpoint(r, none);
		List<ReleaseData> plainDesign = parentDesign(none);
		List<AgentTaskData> plain = split(r, none, child("part 1"));
		assertEquals(List.of(splitInfo(none, plain, round(plainDesign.get(0)) + ", " + round(plainDesign.get(1)))),
				splitInfos(r, none));
	}
}

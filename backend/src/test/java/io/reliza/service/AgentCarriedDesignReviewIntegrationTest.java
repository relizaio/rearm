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
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecision;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction;
import io.reliza.ws.App;

/**
 * Whose passes a carried design rests on (task TPF-161, D2 R-3 and R-5, D3, D8, D10; built by TPF-162): the design
 * stage complete on the parent, each carried reviewer's pass bound to the carried round, a pass a person decided
 * over, a further split of a carried child, and a blind reader. Rows T-19 and T-28 to T-31 of the TPF-161
 * architecture, round 6 (TPF-162 architecture, round 1), with the T-31 variant of TPF-162's functional review F-1.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCarriedDesignReviewIntegrationTest extends AgentCarriedDesignTestBase {

	/**
	 * security_reviewer holds the parent, bound to the first rounds; another session of the architect's agent
	 * publishes an advisory THREAT_MODEL round, which the reviewer acknowledges and signs off with {@code outcome}
	 * (REJECTED with one P2 item about ARCHITECTURE when not PASSED). Returns the advisory round.
	 */
	ReleaseData advisoryDuringTheSecurityHop(Rig r, AgentTaskData t, SignOffOutcome outcome) throws RelizaException {
		design(r, t, "two rounds");
		pass(r, t, r.functional());
		assign(r, t, r.security());
		ReleaseData advisory = publish(otherArchitectSession(r), t, TM, true);
		assertEquals(Boolean.TRUE, release(advisory.getUuid()).getDocument().advisory());
		List<UUID> outputs = List.of();
		if (SignOffOutcome.PASSED != outcome) {
			outputs = List.of(index(r.security(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "REJECTED",
					ARCH, "S-1", 2, "OPEN").getUuid());
		}
		agentTaskService.signOff(t.getUuid(), r.security().sessionUuid(), outcome, "reviewed", outputs,
				List.of(advisory.getUuid()), WU);
		return advisory;
	}

	/** A person accepts the reviewer's item: the decision round reads PASSED over the rejection. */
	AgentTaskData decidedOver(AgentTaskData t) throws RelizaException {
		return agentTaskService.decideReviewItems(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				List.of(new BoardReviewItemDecision(BoardReviewItemDecisionAction.ACCEPT, "S-1", null, null, null,
						"accepted, build it")), null, PERSON, WU);
	}

	/** T-28 (R-5): a reviewer's pass that only acknowledged an advisory round refuses the flag. */
	@Test
	public void aPassThatDidNotBindTheCarriedRoundRefusesTheFlag() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData parent = task(r, "advisory threat model");
		ReleaseData advisory = advisoryDuringTheSecurityHop(r, parent, SignOffOutcome.PASSED);
		AgentTaskData stopped = reload(parent);
		assertTrue(stopped.checkpointPending(), stopped.getStatusHistory().toString());
		assertTrue(routingService.hasCurrentPass(stopped, board(r), roleConfig(r, "security_reviewer")),
				"the time check alone lets it through");
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", "security_reviewer's pass on THREAT_MODEL round 2; its pass reviewed THREAT_MODEL"
				+ " round 1");
		assertNoChildren(parent);

		List<AgentTaskData> children = split(r, parent, child("part 1"));
		assertTrue(pins(children.get(0)).contains(advisory.getUuid()), "the unflagged split pins round 2 as before");
	}

	/** T-29a (R-3): a reviewer's REJECTED with an open item is no pass. */
	@Test
	public void aRejectingReviewerRefusesTheFlag() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "security rejects");
		design(r, parent, "two rounds");
		pass(r, parent, r.functional());
		assign(r, parent, r.security());
		ReleaseData items = index(r.security(), reload(parent), RearmSpecificationType.BOARD_REVIEW_ITEMS, "REJECTED",
				ARCH, "S-1", 2, "OPEN");
		signOff(r.security(), parent, SignOffOutcome.REJECTED, "no", List.of(items.getUuid()));
		AgentTaskData queued = reload(parent);
		assertTrue(TaskStatus.QUEUED == queued.getStatus() || TaskStatus.AWAITING_COORDINATOR == queued.getStatus(),
				queued.getStatus().name());
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", parent.keyOrUuid() + "'s design stage complete; security_reviewer has no current"
				+ " pass on it");
		assertNoChildren(parent);
	}

	/** T-29b (R-3): a reviewer that has not signed off yet. */
	@Test
	public void aReviewerWithNoSignOffRefusesTheFlag() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "security not yet");
		design(r, parent, "two rounds");
		pass(r, parent, r.functional());
		AgentTaskData queued = reload(parent);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("security_reviewer", queued.getRole());
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", parent.keyOrUuid() + "'s design stage complete; security_reviewer has no current"
				+ " pass on it");
		assertNoChildren(parent);
	}

	/** T-29c (R-3, TPF board): a stale pass after a new THREAT_MODEL round; functional_reviewer reads ARCHITECTURE only. */
	@Test
	public void aStaleReviewerRefusesTheFlag() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData parent = task(r, "stale security");
		atCheckpoint(r, parent);
		authorize(r, parent, "architect", COORD);
		redesign(r, parent, TM);
		AgentTaskData queued = reload(parent);
		assertEquals(TaskStatus.QUEUED, queued.getStatus(), queued.getStatusHistory().toString());
		assertEquals("security_reviewer", queued.getRole());
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", parent.keyOrUuid() + "'s design stage complete; security_reviewer has no current"
				+ " pass on it");
		assertNoChildren(parent);
	}

	/** T-31: a reviewer's rejection a person decided over is carried, marked decidedOver, on the agent's sign-off. */
	@Test
	public void aDecidedOverRejectionIsCarriedAsSuch() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "decided over");
		design(r, parent, "two rounds");
		pass(r, parent, r.functional());
		assign(r, parent, r.security());
		ReleaseData items = index(r.security(), reload(parent), RearmSpecificationType.BOARD_REVIEW_ITEMS, "REJECTED",
				ARCH, "S-1", 2, "OPEN");
		signOff(r.security(), parent, SignOffOutcome.REJECTED, "no", List.of(items.getUuid()));
		AgentTaskData stopped = decidedOver(parent);
		assertTrue(reload(parent).checkpointPending(), stopped.getStatusHistory().toString());
		SignOff rejected = lastOf(parent, "security_reviewer");
		assertEquals(SignOffOutcome.REJECTED, rejected.outcome());

		AgentTaskData child = reload(split(r, parent, flagged("part 1")).get(0));
		CarriedRole security = carriedRole(child, "security_reviewer");
		assertTrue(security.decidedOver());
		assertEquals(r.security().sessionUuid(), security.session());
		assertNull(security.reviewedBy());
		assertEquals(rejected.signedOffAt(), security.signedOffAt());
		assertFalse(carriedRole(child, "functional_reviewer").decidedOver());
	}

	/** T-31's R-5 variant (TPF-162 functional review F-1): the decided-over hop bound the older THREAT_MODEL round. */
	@Test
	public void aDecidedOverRejectionOfAnOlderRoundRefusesTheFlag() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData parent = task(r, "decided over, older round");
		advisoryDuringTheSecurityHop(r, parent, SignOffOutcome.REJECTED);
		decidedOver(parent);
		assertTrue(reload(parent).checkpointPending(), reload(parent).getStatusHistory().toString());
		RelizaException e = refused(() -> split(r, parent, flagged("part 1")));
		assertRefusal(e, "part 1", "security_reviewer's pass on THREAT_MODEL round 2; its pass reviewed THREAT_MODEL"
				+ " round 1");
		assertNoChildren(parent);
	}

	/** T-19 (D8): a carried child split again pins the same rounds on the grandchildren, which may be flagged too. */
	@Test
	public void aCarriedChildSplitAgainPinsTheSameRounds() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "carried, split again");
		List<AgentTaskData> grandchildren = split(r, c.flagged(), flagged("part 1a"), child("part 1b"));
		assertEveryChildPins(grandchildren, c.design());
		assertEquals(List.of(splitInfo(reload(c.flagged()), grandchildren, round(c.design().get(0)) + ", "
				+ round(c.design().get(1))) + "; " + grandchildren.get(0).keyOrUuid() + " build from it and go to coder"),
				splitInfos(r, reload(c.flagged())));
		CarriedDesign inherited = reload(grandchildren.get(0)).getCarriedDesign();
		assertEquals(c.flagged().getCarriedDesign().rounds(), inherited.rounds());
		assertEquals(c.flagged().getCarriedDesign().roles(), inherited.roles(), "the original passes, copied");
		assertEquals(c.flagged().getUuid(), inherited.parentTask());
	}

	/** T-30 (D3, D10): a blind reader reads no carried role's session; everyone else reads it whole. */
	@Test
	public void aBlindReaderReadsNoCarriedSession() throws RelizaException {
		Rig r = reviewed();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("tester", null, null, null,
				null, null, null, null, null, null, null, null, null, null, true, null), true, WU);
		UUID testerKey = UUID.randomUUID();
		AgentSessionData blind = agentSessionService.initialize(r.org().getUuid(), r.tester().agentUuid(), testerKey,
				"blind-" + UUID.randomUUID(), "blind tester", null, null, WU);
		Carried c = authorizedForCoder(r, "carried, a blind tester");
		AgentTaskData t = c.flagged();
		code(r, t);
		AgentTaskService.WorkerAssignment wa = agentTaskService.assign(t.getUuid(), board(r), r.tester().agentUuid(),
				blind.getUuid(), WU);
		CarriedDesign stored = reload(t).getCarriedDesign();

		assertBlind(stored, agentTaskService.forReader(reload(t), testerKey).getCarriedDesign());
		assertBlind(stored, wa.task().getCarriedDesign());
		CarriedDesign whole = agentTaskService.forReader(reload(t), UUID.randomUUID()).getCarriedDesign();
		assertEquals(stored, whole);
		for (String role : DESIGN_ROLES) {
			assertEquals(lastOf(c.parent(), role).session(), whole.roleFor(null, role).session(), role);
		}
		assertEquals(stored, reload(t).getCarriedDesign(), "the redaction is on a copy");
	}

	private static void assertBlind(CarriedDesign stored, CarriedDesign seen) {
		assertEquals(stored.parentTask(), seen.parentTask());
		assertEquals(stored.parentKey(), seen.parentKey());
		assertEquals(stored.rounds(), seen.rounds());
		assertEquals(stored.at(), seen.at());
		assertEquals(stored.by(), seen.by());
		assertEquals(stored.roles().size(), seen.roles().size());
		for (int i = 0; i < stored.roles().size(); i++) {
			CarriedRole s = stored.roles().get(i);
			CarriedRole b = seen.roles().get(i);
			assertTrue(null != s.session(), s.role());
			assertNull(b.session(), b.role());
			assertEquals(s.role(), b.role());
			assertEquals(s.roleUuid(), b.roleUuid());
			assertEquals(s.signedOffAt(), b.signedOffAt());
			assertEquals(s.reviewedBy(), b.reviewedBy());
			assertEquals(s.decidedOver(), b.decidedOver());
		}
	}
}

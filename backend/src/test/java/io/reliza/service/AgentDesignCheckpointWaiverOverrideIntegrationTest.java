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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.DesignCheckpointWaiver;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentActor;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * The coordinator's verbs on a waived task (task TPF-152, W8, section 5): hold, lift with or without a role, the
 * coder's return, authorize and split behave as on a task the seat sent on; the architect's --no-change --no-split
 * re-asserts the waiver without a second event. T-37 (a) to (e), T-39 and T-42. Since task TPF-154 merged task
 * SPLIT-9 in, a split pins the design whatever the record says (T-37 (d)), so the class extends the split request
 * base for its helpers.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointWaiverOverrideIntegrationTest extends AgentSplitRequestTestBase {

	static final String NOTE = "fits one coder pass";

	@Autowired AgentRoutingService routingService;

	/** A waived task on T-1's board, QUEUED for the coder. */
	private AgentTaskData waived(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		designNoSplit(r, t, NOTE);
		AgentTaskData queued = reload(t);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		return queued;
	}

	/** (a) A hold and a lift without a role: QUEUED for the coder, nothing posted, the record unchanged. */
	@Test
	public void aLiftWithoutARoleRequeuesTheCoder() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = waived(r, "held after a waiver");
		DesignCheckpoint cp = checkpoint(t);
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "wait", COORD, WU);
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, WU);
		assertEquals(TaskStatus.QUEUED, lifted.getStatus());
		assertEquals("coder", lifted.getRole());
		assertTrue(checkpointAlerts(r, lifted).isEmpty());
		assertEquals(1, waivedInfos(r, lifted).size(), "no second INFO");
		assertEquals(cp, lifted.getDesignCheckpoint());
	}

	/**
	 * (b) A lift to the architect leaves the waiver; its --no-change --no-split answer queues the coder with no event
	 * (T-42: the waiver re-asserted with a newer at and the same round, the row marked both).
	 */
	@Test
	public void aLiftToTheArchitectAndANoChangeNoSplitAnswer() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = waived(r, "lifted to the architect");
		DesignCheckpoint cp = checkpoint(t);
		DesignCheckpointWaiver first = waiver(t);
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "rethink", COORD, WU);
		AgentTaskData toArchitect = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, null,
				"architect", WU);
		assertEquals(TaskStatus.QUEUED, toArchitect.getStatus());
		assertEquals("architect", toArchitect.getRole());
		assertEquals(first, toArchitect.getDesignCheckpointWaiver(), "the waiver still present");

		assign(r, t, r.architect());
		signOffNoSplit(r.architect(), t, "still fits", List.of(), Boolean.TRUE);
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(cp, after.getDesignCheckpoint(), "the record stands");
		assertEquals(1, waivedInfos(r, after).size(), "no INFO");
		assertTrue(checkpointAlerts(r, after).isEmpty());
		DesignCheckpointWaiver again = after.getDesignCheckpointWaiver();
		assertTrue(again.at().isAfter(first.at()), "re-asserted");
		assertEquals(first.designRound(), again.designRound(), "the same round");
		assertEquals("still fits", again.note());
		SignOff row = after.getSignOffs().get(after.getSignOffs().size() - 1);
		assertEquals(Boolean.TRUE, row.noSplit());
		assertEquals(Boolean.TRUE, row.noChange());
	}

	/**
	 * (c) A coder's OTHER return (free text) lands at the coordinator with the return ALERT and no split request;
	 * authorize writes nothing. The SPLIT return is {@code AgentDesignCheckpointWaiverSplitRequestIntegrationTest}.
	 */
	@Test
	public void aCodersReturnLandsAtTheCoordinator() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = waived(r, "returned for a split");
		DesignCheckpoint cp = checkpoint(t);
		DesignCheckpointWaiver w = waiver(t);
		assign(r, t, r.coder());
		agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER,
				"design recommends a split", WU);
		AgentTaskData returned = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, returned.getStatus());
		assertEquals(1, messages(r, BoardEventKind.ALERT, "returned by coder").stream()
				.filter(m -> m.contains(returned.label())).count(), "the return ALERT");
		assertEquals(cp, returned.getDesignCheckpoint());
		assertEquals(w, returned.getDesignCheckpointWaiver());
		assertNull(returned.getSplitRequest(), "an OTHER return makes no split request");
		List<String> eventsAfterReturn = boardEvents(r);

		AgentTaskData queued = authorize(r, t, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals(cp, queued.getDesignCheckpoint(), "nothing written");
		assertEquals(eventsAfterReturn, boardEvents(r), "no INFO");
	}

	/**
	 * (d) A split from the returned state pins the parent's reviewed design on every child (task SPLIT-9 D1, which
	 * supersedes TPF-152's "no pins from a settled record" since task TPF-154), and leaves the parent's record SENT_ON by
	 * the waiver and the waiver as they were; no child carries a waiver, a checkpoint record or a split request.
	 */
	@Test
	public void aSplitFromAWaivedRecordPinsTheDesignAndLeavesTheRecord() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "split after a waiver");
		List<ReleaseData> design = designNoSplit(r, t, NOTE);
		assertEquals(DesignCheckpointState.SENT_ON, checkpoint(t).state());
		assign(r, t, r.coder());
		agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER,
				"design recommends a split", WU);
		DesignCheckpoint cp = checkpoint(t);
		DesignCheckpointWaiver w = waiver(t);
		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEquals(2, children.size());
		assertEveryChildPins(children, design);
		for (AgentTaskData c : children) {
			AgentTaskData child = reload(c);
			assertEquals(TaskStatus.PENDING_INTAKE, child.getStatus());
			assertNull(child.getDesignCheckpointWaiver(), child.keyOrUuid());
			assertNull(child.getDesignCheckpoint(), child.keyOrUuid());
			assertNull(child.getSplitRequest(), child.keyOrUuid());
		}
		AgentTaskData after = reload(t);
		assertEquals(cp, after.getDesignCheckpoint(), "the record whole, settledAt and note included");
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), cp.settledBy());
		assertEquals(w, after.getDesignCheckpointWaiver());
		assertNull(after.getSplitRequest(), "an OTHER return makes none");
		assertEquals(List.of(splitInfo(after, children, round(release(design.get(0).getUuid())) + ", "
				+ round(release(design.get(1).getUuid())))), splitInfos(r, after));
	}

	/**
	 * (e) An advisory round during a hold, then the operator's lift to the coder: settled by the lifter, the "sent it
	 * on" INFO, no waiver INFO, the waiver unchanged and no longer standing. Variant: a lift without a role stops.
	 */
	@Test
	public void anAdvisoryRoundDuringAHold() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = waived(r, "advisory during a hold");
		DesignCheckpointWaiver w = waiver(t);
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "wait", COORD, WU);
		ReleaseData advisory = publish(r.architect(), t, ARCH, true);
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, "go on", "coder", WU);
		assertEquals(TaskStatus.QUEUED, lifted.getStatus());
		DesignCheckpoint cp = lifted.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(PERSON, cp.settledBy());
		assertEquals(advisory.getUuid(), cp.design().get(0));
		assertTrue(cp.note().contains("lifted"), cp.note());
		assertEquals(1, sentOnInfos(r, lifted).size());
		assertEquals(1, waivedInfos(r, lifted).size(), "no waiver INFO");
		assertEquals(w, lifted.getDesignCheckpointWaiver());
		assertFalse(routingService.waiverStands(lifted, board(r)), "a newer round than the waiver's");

		AgentTaskData other = waived(r, "advisory during a hold, lifted without a role");
		agentTaskService.hold(other.getUuid(), HoldLevel.COORDINATOR, "wait", COORD, WU);
		publish(r.architect(), other, ARCH, true);
		AgentTaskData stopped = agentTaskService.liftHold(other.getUuid(), HoldLevel.COORDINATOR, COORD, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus());
		List<String> alerts = checkpointAlerts(r, stopped);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("ARCHITECTURE round 2 (ASSEMBLED)"), alerts.get(0));
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.DesignCheckpointWaiver;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.SplitRequest;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.BoardReviewItemIndex.About;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecision;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction;
import io.reliza.ws.App;

/**
 * Where the architect's checkpoint waiver (task TPF-152) meets the split request and the split's pin (task SPLIT-9),
 * as task TPF-154 merged them: the waiver, the checkpoint record and the split request are three independent records,
 * each with its own writer. A split after a waiver pins the design and leaves the waived record alone; a SPLIT return
 * on a waived task lands as any SPLIT return; a decline, a lift or a split leaves the waiver as it was, which ends only
 * on a new ARCHITECTURE round without the flag or a reopen to a design role; a blind reader sees none of the three
 * accounts. T-65 to T-69 of the TPF-154 architecture, round 2.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointWaiverSplitRequestIntegrationTest extends AgentSplitRequestTestBase {

	static final String NOTE = "fits one coder pass";

	@Autowired AgentRoutingService routingService;

	/** A waived task and the design rounds its architect passed. */
	record Waived(AgentTaskData task, List<ReleaseData> design) {}

	/** On {@code r}, the architect passes --no-split: QUEUED for the coder, the record SENT_ON by the waiver. */
	Waived waived(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		List<ReleaseData> design = designNoSplit(r, t, NOTE);
		AgentTaskData queued = reload(t);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), queued.getDesignCheckpoint().settledBy());
		assertEquals(1, waivedInfos(r, queued).size());
		return new Waived(queued, design);
	}

	/** A waived task the coder returned SPLIT with two proposed children, B depending on A. */
	Waived requested(Rig r, String title) throws RelizaException {
		Waived w = waived(r, title);
		AgentTaskData back = takeAndReturnSplit(r, w.task(), r.coder(), List.of(child("A"), child("B", 0)));
		assertTrue(back.splitRequested());
		return new Waived(back, w.design());
	}

	AgentTaskData authorizeWithNote(Rig r, AgentTaskData t, String role, String note) throws RelizaException {
		return agentTaskService.authorize(t.getUuid(), board(r), role, null, null, null, null, null, note, COORD, WU);
	}

	StatusChange lastRow(AgentTaskData t) {
		List<StatusChange> rows = reload(t).getStatusHistory();
		return rows.get(rows.size() - 1);
	}

	/** Every event on the board naming the task, any kind, as "KIND message". */
	List<String> taskEvents(Rig r, AgentTaskData t) {
		return boardEvents(r).stream().filter(m -> m.contains(t.label())).toList();
	}

	String rounds(List<ReleaseData> design) {
		return round(release(design.get(0).getUuid())) + ", " + round(release(design.get(1).getUuid()));
	}

	/** The children carry the design and nothing of the parent's three records. */
	void assertChildrenFresh(List<AgentTaskData> children) {
		for (AgentTaskData c : children) {
			AgentTaskData child = reload(c);
			assertEquals(TaskStatus.PENDING_INTAKE, child.getStatus());
			assertNull(child.getDesignCheckpointWaiver(), child.keyOrUuid());
			assertNull(child.getDesignCheckpoint(), child.keyOrUuid());
			assertNull(child.getSplitRequest(), child.keyOrUuid());
		}
	}

	/** T-65 (a): a split after a waiver pins the design and leaves the record and the waiver alone. */
	@Test
	public void aSplitAfterAWaiverPinsTheDesignAndLeavesTheRecord() throws RelizaException {
		Rig r = plain();
		Waived w = waived(r, "waived, then split on request");
		AgentTaskData t = w.task();
		DesignCheckpoint cp = checkpoint(t);
		DesignCheckpointWaiver waiver = waiver(t);
		AgentTaskData back = takeAndReturnSplit(r, t, r.coder(), List.of(child("A"), child("B", 0)));
		SplitRequest sr = back.getSplitRequest();
		assertEquals(SplitRequestState.REQUESTED, sr.state());
		assertEquals("coder", sr.role());
		assertEquals(2, sr.proposedChildren().size());
		assertEquals(List.of("Task " + back.label() + " returned by coder (SPLIT): " + WHY + "; proposes 2 children: A; B"),
				returnAlerts(r, back));
		assertEquals(cp, back.getDesignCheckpoint(), "the return reads neither record");
		assertEquals(waiver, back.getDesignCheckpointWaiver());
		assertTrue(routingService.waiverStands(back, board(r)));

		List<AgentTaskData> children = split(r, t, child("A"), child("B", 0));
		assertEquals(2, children.size());
		assertEveryChildPins(children, w.design());
		assertEquals(List.of(children.get(0).getUuid()), reload(children.get(1)).getDependsOn());
		assertChildrenFresh(children);
		AgentTaskData after = reload(t);
		assertEquals(cp, after.getDesignCheckpoint(), "SENT_ON by the architect's session, the same settledAt");
		assertEquals(waiver, after.getDesignCheckpointWaiver());
		SplitRequest settled = after.getSplitRequest();
		assertEquals(SplitRequestState.SPLIT, settled.state());
		assertEquals(COORD, settled.settledBy());
		assertEquals("2 children: " + keys(children), settled.note());
		assertEquals(List.of(splitInfo(after, children, rounds(w.design()))), splitInfos(r, after));
		assertEquals(1, waivedInfos(r, after).size(), "no new waiver INFO");
		assertTrue(checkpointAlerts(r, after).isEmpty(), "no checkpoint ALERT since the waived pass");

		// the same split from QUEUED, before the coder takes the task
		Waived q = waived(r, "waived, split from QUEUED");
		DesignCheckpoint qcp = checkpoint(q.task());
		DesignCheckpointWaiver qw = waiver(q.task());
		List<AgentTaskData> qChildren = split(r, q.task(), child("A"), child("B", 0));
		assertEveryChildPins(qChildren, q.design());
		assertChildrenFresh(qChildren);
		AgentTaskData qAfter = reload(q.task());
		assertEquals(qcp, qAfter.getDesignCheckpoint());
		assertEquals(qw, qAfter.getDesignCheckpointWaiver());
		assertNull(qAfter.getSplitRequest());
		assertEquals(List.of(splitInfo(qAfter, qChildren, rounds(q.design()))), splitInfos(r, qAfter));
	}

	/** T-66 (b): declining, by authorize with a note or a lift naming a role, leaves the waiver; a bare lift lapses. */
	@Test
	public void decliningLeavesTheWaiverAsItWas() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = requested(r, "waived, declined").task();
		DesignCheckpoint cp = checkpoint(t);
		DesignCheckpointWaiver waiver = waiver(t);
		int sentOn = sentOnInfos(r, t).size();

		AgentTaskData queued = authorizeWithNote(r, t, "coder", "one pass is fine");
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		SplitRequest sr = queued.getSplitRequest();
		assertEquals(SplitRequestState.DECLINED, sr.state());
		assertEquals(COORD, sr.settledBy());
		assertEquals("one pass is fine", sr.note());
		assertNotNull(sr.settledAt());
		StatusChange row = lastRow(t);
		assertEquals(StatusTrigger.AUTHORIZE, row.trigger());
		assertEquals("one pass is fine", row.note());
		assertEquals(List.of("Task " + queued.label() + ": " + COORD.display() + " declined the split request, sent to"
				+ " coder: one pass is fine"), declineInfos(r, queued));
		assertEquals(sentOn, sentOnInfos(r, queued).size(), "the record stood: nothing written");
		assertEquals(cp, queued.getDesignCheckpoint());
		assertEquals(waiver, queued.getDesignCheckpointWaiver());
		assertTrue(routingService.waiverStands(queued, board(r)));
		assertEquals(1, waivedInfos(r, queued).size(), "no second consumption");

		// a second SPLIT return, a hold over the wait and a person's lift to the coder
		takeAndReturnSplit(r, t, r.coder(), List.of(child("A")));
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "reading", COORD, WU);
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, null, "coder", WU);
		assertEquals(TaskStatus.QUEUED, lifted.getStatus());
		assertEquals(SplitRequestState.DECLINED, lifted.getSplitRequest().state());
		assertEquals(PERSON, lifted.getSplitRequest().settledBy());
		assertEquals("hold lifted to coder", lifted.getSplitRequest().note());
		assertEquals(cp, lifted.getDesignCheckpoint());
		assertEquals(waiver, lifted.getDesignCheckpointWaiver());
		assertEquals(2, declineInfos(r, lifted).size());

		// a third SPLIT return, a hold and a lift without a role: routing queues the coder and the request lapses
		takeAndReturnSplit(r, t, r.coder(), List.of(child("A")));
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "reading", COORD, WU);
		AgentTaskData routed = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, WU);
		assertEquals(TaskStatus.QUEUED, routed.getStatus(), routed.getStatusHistory().toString());
		assertEquals("coder", routed.getRole());
		assertEquals(SplitRequestState.LAPSED, routed.getSplitRequest().state());
		assertTrue(routed.getSplitRequest().note().startsWith("routed to coder"), routed.getSplitRequest().note());
		assertEquals(2, declineInfos(r, routed).size(), "no decline INFO for a lapse");
		assertEquals(1, waivedInfos(r, routed).size(), "no second waiver INFO");
		assertEquals(cp, routed.getDesignCheckpoint());
		assertEquals(waiver, routed.getDesignCheckpointWaiver());
	}

	/**
	 * T-67 (b): the waiver survives a request declined to the architect until a new ARCHITECTURE round comes in: (i) a
	 * round without the flag clears it and the task stops; (ii) a round with the flag waives again; (iii) a --no-change
	 * answer without the flag leaves it.
	 */
	@Test
	public void theWaiverSurvivesADeclineUnlessANewRoundComesIn() throws RelizaException {
		Rig r = plain();
		// (i)
		AgentTaskData t = requested(r, "declined to the architect, redesigned").task();
		DesignCheckpoint cp = checkpoint(t);
		DesignCheckpointWaiver waiver = waiver(t);
		AgentTaskData toArchitect = authorizeWithNote(r, t, "architect", "redesign first");
		assertEquals(TaskStatus.QUEUED, toArchitect.getStatus());
		assertEquals("architect", toArchitect.getRole());
		SplitRequest declined = reload(t).getSplitRequest(); // stored precision
		assertEquals(SplitRequestState.DECLINED, declined.state());
		assertEquals("redesign first", declined.note());
		assertEquals(cp, toArchitect.getDesignCheckpoint(), "a design role named: nothing written");
		assertEquals(waiver, toArchitect.getDesignCheckpointWaiver());

		ReleaseData round2 = redesign(r, t, ARCH);
		AgentTaskData stopped = reload(t);
		assertNull(stopped.getDesignCheckpointWaiver(), "a new round without the flag clears it");
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), stopped.getStatusHistory().toString());
		List<String> alerts = checkpointAlerts(r, stopped);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("ARCHITECTURE round 2"), alerts.get(0));
		assertFalse(alerts.get(0).contains("ignored"), alerts.get(0));
		assertTrue(stopped.checkpointPending());
		assertTrue(stopped.getDesignCheckpoint().design().contains(round2.getUuid()),
				String.valueOf(stopped.getDesignCheckpoint()));
		assertEquals(declined, stopped.getSplitRequest(), "DECLINED, the same settledAt");
		AgentTaskData sent = authorize(r, t, "coder", COORD);
		assertEquals(DesignCheckpointState.SENT_ON, sent.getDesignCheckpoint().state());
		assertEquals(COORD, sent.getDesignCheckpoint().settledBy());
		assertEquals(1, sentOnInfos(r, sent).size());
		assertEquals(declined, sent.getSplitRequest(), "a DECLINED record is never settled again");

		// (ii)
		AgentTaskData t2 = requested(r, "declined to the architect, waived again").task();
		DesignCheckpoint cp2 = checkpoint(t2);
		authorizeWithNote(r, t2, "architect", "redesign first");
		SplitRequest declined2 = reload(t2).getSplitRequest();
		ReleaseData round2b = redesignNoSplit(r, t2, "round 2 fits");
		AgentTaskData requeued = reload(t2);
		assertEquals(TaskStatus.QUEUED, requeued.getStatus(), requeued.getStatusHistory().toString());
		assertEquals("coder", requeued.getRole());
		List<String> waived = waivedInfos(r, requeued);
		assertEquals(2, waived.size(), waived.toString());
		assertTrue(waived.get(1).contains("ARCHITECTURE round 2"), waived.get(1));
		DesignCheckpoint fresh = requeued.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, fresh.state());
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), fresh.settledBy());
		assertTrue(fresh.settledAt().isAfter(cp2.settledAt()), "a fresh record");
		assertEquals(round2b.getUuid(), requeued.getDesignCheckpointWaiver().designRound());
		assertEquals(declined2, requeued.getSplitRequest());

		// (iii)
		AgentTaskData t3 = requested(r, "declined to the architect, no change").task();
		authorizeWithNote(r, t3, "architect", "redesign first");
		DesignCheckpoint cp3 = checkpoint(t3);
		DesignCheckpointWaiver w3 = waiver(t3);
		SplitRequest declined3 = reload(t3).getSplitRequest();
		List<String> before = taskEvents(r, t3);
		assign(r, t3, r.architect());
		agentTaskService.signOff(t3.getUuid(), r.architect().sessionUuid(), SignOffOutcome.PASSED, "no change",
				List.of(), null, Boolean.TRUE, null, null, WU);
		AgentTaskData noChange = reload(t3);
		assertEquals(TaskStatus.QUEUED, noChange.getStatus(), noChange.getStatusHistory().toString());
		assertEquals("coder", noChange.getRole());
		assertEquals(before, taskEvents(r, t3), "no event");
		assertEquals(w3, noChange.getDesignCheckpointWaiver());
		assertEquals(cp3, noChange.getDesignCheckpoint());
		assertEquals(declined3, noChange.getSplitRequest());
	}

	/**
	 * T-68 (b): on the reviewed board a reviewer's SPLIT return before the waiver is consumed: (a) declined to a
	 * reviewer, routing consumes the waiver only when it queues the coder; (b) the seat's own authorize of the coder
	 * settles the record in its name and posts no waiver INFO; (c) routing's own exit lapses the request and leaves the
	 * record and the waiver.
	 */
	@Test
	public void theSeatsChoicePastAnUnconsumedWaiverAndRoutingsOwnExit() throws RelizaException {
		Rig r = reviewed();
		// (a)
		AgentTaskData t = task(r, "reviewer asks for a split, declined to a reviewer");
		designNoSplit(r, t, NOTE);
		AgentTaskData back = takeAndReturnSplit(r, t, r.functional(), List.of(child("A"), child("B")));
		assertEquals("functional_reviewer", back.getSplitRequest().role());
		assertNull(back.getDesignCheckpoint(), "not consumed");
		assertTrue(routingService.waiverStands(back, board(r)));
		AgentTaskData toSecurity = authorizeWithNote(r, t, "security_reviewer", "finish the reviews");
		assertEquals(TaskStatus.QUEUED, toSecurity.getStatus());
		assertEquals("security_reviewer", toSecurity.getRole());
		SplitRequest declined = reload(t).getSplitRequest(); // stored precision
		assertEquals(SplitRequestState.DECLINED, declined.state());
		assertNull(toSecurity.getDesignCheckpoint());
		assertTrue(waivedInfos(r, toSecurity).isEmpty());

		pass(r, t, r.security());
		AgentTaskData afterSecurity = reload(t);
		assertEquals(TaskStatus.QUEUED, afterSecurity.getStatus());
		assertEquals("functional_reviewer", afterSecurity.getRole(), "the requester returned; it never passed");
		assertTrue(waivedInfos(r, afterSecurity).isEmpty());
		assertNull(afterSecurity.getDesignCheckpoint());
		assertTrue(routingService.waiverStands(afterSecurity, board(r)));

		pass(r, t, r.functional());
		AgentTaskData queued = reload(t);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		assertEquals(List.of(waiverInfo(queued, "ARCHITECTURE round 1", "READY_TO_SHIP", NOTE)),
				waivedInfos(r, queued));
		DesignCheckpoint cp = queued.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), cp.settledBy());
		assertTrue(cp.note().startsWith("waived by architect: "), cp.note());
		assertEquals("functional_reviewer", cp.afterRole());
		assertEquals("coder", cp.beforeRole());
		assertEquals(declined, queued.getSplitRequest(), "DECLINED, settledAt as at the decline");

		// (b)
		AgentTaskData t2 = task(r, "reviewer asks for a split, the seat sends it to the coder");
		designNoSplit(r, t2, NOTE);
		takeAndReturnSplit(r, t2, r.functional(), List.of(child("A"), child("B")));
		AgentTaskData toCoder = authorizeWithNote(r, t2, "coder", "small enough");
		assertEquals(TaskStatus.QUEUED, toCoder.getStatus());
		assertEquals("coder", toCoder.getRole());
		DesignCheckpoint seat = toCoder.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, seat.state());
		assertEquals(COORD, seat.settledBy());
		assertEquals("authorized for coder", seat.note());
		assertEquals(1, sentOnInfos(r, toCoder).size());
		assertTrue(waivedInfos(r, toCoder).isEmpty(), "the waiver is never consulted on the seat's choice");
		assertEquals(SplitRequestState.DECLINED, toCoder.getSplitRequest().state());
		assertNotNull(toCoder.getDesignCheckpointWaiver());
		assertTrue(routingService.waiverStands(toCoder, board(r)));

		// (c)
		Rig p = plain();
		AgentTaskData t3 = requested(p, "waived, routed back to the architect").task();
		DesignCheckpoint cp3 = checkpoint(t3);
		DesignCheckpointWaiver w3 = waiver(t3);
		AgentTaskData routed = agentTaskService.decideReviewItems(t3.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS,
				List.of(new BoardReviewItemDecision(BoardReviewItemDecisionAction.FILE, null, 1, "filed at P1", null, null)),
				new About(RearmSpecificationType.ARCHITECTURE, null), PERSON, WU);
		assertEquals(TaskStatus.QUEUED, routed.getStatus());
		assertEquals("architect", routed.getRole());
		assertEquals(SplitRequestState.LAPSED, routed.getSplitRequest().state());
		assertTrue(routed.getSplitRequest().note().startsWith("routed to architect: "), routed.getSplitRequest().note());
		assertEquals(cp3, routed.getDesignCheckpoint());
		assertEquals(w3, routed.getDesignCheckpointWaiver());
	}

	/** T-69 (security): a blind reader sees none of the three accounts through one read; the feed quotes no worker. */
	@Test
	public void aBlindReaderSeesNoneOfTheThreeAccounts() throws RelizaException {
		Rig r = reviewed();
		for (String blind : List.of("functional_reviewer", "tester")) {
			agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(blind, null, null, null,
					null, null, null, null, null, null, null, null, null, null, true, null), true, WU);
		}
		UUID reviewerKey = UUID.randomUUID();
		UUID testerKey = UUID.randomUUID();
		AgentSessionData reviewer = agentSessionService.initialize(r.org().getUuid(), r.functional().agentUuid(),
				reviewerKey, "blind-" + UUID.randomUUID(), "blind reviewer", null, null, WU);
		AgentSessionData tester = agentSessionService.initialize(r.org().getUuid(), r.tester().agentUuid(), testerKey,
				"blind-" + UUID.randomUUID(), "blind tester", null, null, WU);
		Worker blindReviewer = new Worker(r.functional().agent(), reviewer);

		AgentTaskData t = task(r, "blind board, waived, split requested");
		List<ReleaseData> design = designNoSplit(r, t, NOTE);
		assign(r, t, blindReviewer);
		AgentTaskData back = returnSplit(blindReviewer, t, WHY, List.of(child("secret A"), child("secret B")));
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertEquals(List.of("Task " + back.label() + " returned by functional_reviewer (SPLIT); proposes 2 children"),
				returnAlerts(r, back));
		authorizeWithNote(r, t, "functional_reviewer", "reviews first");
		assertEquals(TaskStatus.QUEUED, reload(t).getStatus());

		// (a) the blind holder's read
		assign(r, t, blindReviewer);
		AgentTaskData mine = agentTaskService.forReader(reload(t), reviewerKey);
		assertBlindWaiver(mine.getDesignCheckpointWaiver(), design.get(0));
		assertBlindRequest(mine.getSplitRequest());
		assertNull(mine.getDesignCheckpoint(), "not consumed yet");

		// (b) the reviews pass, the coder is queued and passes, the blind tester reads the waived record
		signOff(blindReviewer, t, SignOffOutcome.PASSED, "fine", List.of());
		pass(r, t, r.security());
		assertEquals("coder", reload(t).getRole());
		code(r, t);
		agentTaskService.assign(t.getUuid(), board(r), r.tester().agentUuid(), tester.getUuid(), WU);
		AgentTaskData stored = reload(t);
		AgentTaskData seen = agentTaskService.forReader(reload(t), testerKey);
		DesignCheckpoint cp = seen.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertNull(cp.settledBy());
		assertEquals("waived by architect", cp.note());
		assertEquals(stored.getDesignCheckpoint().design(), cp.design());
		assertEquals(stored.getDesignCheckpoint().settledAt(), cp.settledAt());
		assertBlindWaiver(seen.getDesignCheckpointWaiver(), design.get(0));
		assertBlindRequest(seen.getSplitRequest());

		AgentTaskData whole = agentTaskService.forReader(reload(t), UUID.randomUUID());
		assertEquals(r.architect().sessionUuid(), whole.getDesignCheckpointWaiver().bySession());
		assertEquals(r.architect().agentUuid(), whole.getDesignCheckpointWaiver().byAgent());
		assertEquals(NOTE, whole.getDesignCheckpointWaiver().note());
		assertEquals("waived by architect: " + NOTE, whole.getDesignCheckpoint().note());
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), whole.getDesignCheckpoint().settledBy());
		assertEquals(WHY, whole.getSplitRequest().description());
		assertEquals(reviewer.getUuid(), whole.getSplitRequest().session());
		assertEquals(2, whole.getSplitRequest().proposedChildren().size());

		// (c) the feed on the blind board
		AgentTaskData now = reload(t);
		assertEquals(List.of(waiverInfo(now, "ARCHITECTURE round 1", "READY_TO_SHIP", null)), waivedInfos(r, now));
		assertEquals(List.of("Task " + now.label() + " returned by functional_reviewer (SPLIT); proposes 2 children"),
				returnAlerts(r, now));
		assertEquals(List.of("Task " + now.label() + ": " + COORD.display() + " declined the split request, sent to"
				+ " functional_reviewer: reviews first"), declineInfos(r, now));
		for (String m : taskEvents(r, now)) {
			assertFalse(m.contains(NOTE), m);
			assertFalse(m.contains("secret"), m);
		}
	}

	private static void assertBlindWaiver(DesignCheckpointWaiver w, ReleaseData round) {
		assertNotNull(w);
		assertNull(w.bySession());
		assertNull(w.byAgent());
		assertNull(w.note());
		assertEquals("architect", w.role());
		assertNotNull(w.at());
		assertEquals(round.getUuid(), w.designRound());
	}

	private static void assertBlindRequest(SplitRequest sr) {
		assertNotNull(sr);
		assertNull(sr.session());
		assertNull(sr.description());
		assertEquals(List.of(), sr.proposedChildren());
		assertEquals(SplitRequestState.DECLINED, sr.state());
		assertEquals("functional_reviewer", sr.role());
		assertEquals("reviews first", sr.note());
		assertEquals(COORD, sr.settledBy());
	}
}

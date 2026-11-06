/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SplitRequest;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.BoardReviewItemIndex.About;
import io.reliza.model.RearmSpecificationType;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecision;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction;
import io.reliza.ws.App;

/**
 * How a split request is settled (task SPLIT-9, D7, D8; built by SPLIT-11): declined by the coordinator's authorize
 * or a lift naming a role, with the note; split by the split; lapsed by every other way out of the wait; and what a
 * blind reader sees of it. T-10 to T-13. T-12 runs on {@link #plain()} (the checkpoint and the request together),
 * T-13 on the unflagged reviewed board with a blind functional reviewer, the rest on {@link #unflagged()}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSplitRequestSettleIntegrationTest extends AgentSplitRequestTestBase {

	static final String NOTE = "one pass is fine: shared fixture lands first";

	/** A task on {@code r} whose design passed, returned SPLIT by the coder with two proposed children. */
	AgentTaskData requested(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		design(r, t, "fits one coder pass");
		AgentTaskData back = takeAndReturnSplit(r, t, r.coder(), List.of(child("A"), child("B", 0)));
		assertTrue(back.splitRequested());
		return back;
	}

	AgentTaskData authorizeWithNote(Rig r, AgentTaskData t, String role, String note) throws RelizaException {
		return agentTaskService.authorize(t.getUuid(), board(r), role, null, null, null, null, null, note, COORD, WU);
	}

	StatusChange lastRow(AgentTaskData t) {
		List<StatusChange> rows = reload(t).getStatusHistory();
		return rows.get(rows.size() - 1);
	}

	/** T-10: the seat's authorize with a note declines the request, on the record, the status row and one INFO. */
	@Test
	public void anAuthorizeWithANoteDeclinesTheRequest() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = requested(r, "declined");
		AgentTaskData queued = authorizeWithNote(r, t, "coder", NOTE);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		SplitRequest sr = queued.getSplitRequest();
		assertEquals(SplitRequestState.DECLINED, sr.state());
		assertEquals(COORD, sr.settledBy());
		assertEquals(NOTE, sr.note());
		assertNotNull(sr.settledAt());
		StatusChange row = lastRow(t);
		assertEquals(StatusTrigger.AUTHORIZE, row.trigger());
		assertEquals(NOTE, row.note());
		assertEquals(List.of("Task " + queued.label() + ": " + COORD.display() + " declined the split request, sent to"
				+ " coder: " + NOTE), declineInfos(r, queued));
	}

	/** T-10: without a note the decline says what the authorize did. */
	@Test
	public void anAuthorizeWithoutANoteDeclinesWithTheDefault() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = requested(r, "declined by default");
		AgentTaskData queued = authorize(r, t, "coder", COORD);
		assertEquals("authorized for coder", queued.getSplitRequest().note());
		assertNull(lastRow(t).note(), "no note on the status row when none was given");
		assertEquals(List.of("Task " + queued.label() + ": " + COORD.display() + " declined the split request, sent to"
				+ " coder: authorized for coder"), declineInfos(r, queued));
	}

	/** T-10, F-10: an over-long note is refused before anything is written; a line break is folded. */
	@Test
	public void theNoteIsCheckedBeforeAnythingAndFoldedToOneLine() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = requested(r, "note bound");
		int rows = reload(t).getStatusHistory().size();
		int events = events(r).size();
		RelizaException e = refused(() -> authorizeWithNote(r, t, "coder", "n".repeat(4001)));
		assertEquals("note is at most 4000 characters", e.getMessage());
		AgentTaskData still = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, still.getStatus());
		assertEquals(SplitRequestState.REQUESTED, still.getSplitRequest().state());
		assertNull(still.getSplitRequest().settledBy());
		assertEquals(rows, still.getStatusHistory().size(), "no AUTHORIZE row");
		assertEquals(events, events(r).size(), "no event");

		authorizeWithNote(r, t, "coder", "one pass\nis fine");
		assertEquals("one pass is fine", reload(t).getSplitRequest().note());
		assertEquals("one pass is fine", lastRow(t).note());
		assertTrue(declineInfos(r, t).get(0).endsWith("sent to coder: one pass is fine"), declineInfos(r, t).toString());
	}

	/** T-10: an authorize with no record, or a settled one, writes nothing on it and posts nothing. */
	@Test
	public void anAuthorizeWithoutARequestDeclinesNothing() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData plainReturn = task(r, "no request");
		design(r, plainReturn, "fits one coder pass");
		assign(r, plainReturn, r.coder());
		agentTaskService.returnTask(plainReturn.getUuid(), r.coder().sessionUuid(), TaskReturnReason.TASK_UNCLEAR,
				"which branch?", List.of(), WU);
		int events = events(r).size();
		authorizeWithNote(r, plainReturn, "coder", NOTE);
		assertNull(reload(plainReturn).getSplitRequest());
		assertEquals(NOTE, lastRow(plainReturn).note(), "the note is still the authorize's");
		assertEquals(events, events(r).size(), "the board's log is unchanged by the authorize");

		AgentTaskData t = requested(r, "declined once");
		authorize(r, t, "coder", COORD);
		SplitRequest declined = reload(t).getSplitRequest();
		assign(r, t, r.coder());
		agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER, "later", List.of(), WU);
		int before = events(r).size();
		authorizeWithNote(r, t, "coder", "again");
		assertEquals(declined, reload(t).getSplitRequest(), "a DECLINED record is not declined again");
		assertEquals(before, events(r).size());
	}

	/** T-10: a lift naming a role declines in the lifter's name; a hold over the wait leaves the request standing. */
	@Test
	public void aLiftNamingARoleDeclinesTheRequest() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = requested(r, "lifted to the coder");
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "reading", COORD, WU);
		AgentTaskData held = reload(t);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(SplitRequestState.REQUESTED, held.getSplitRequest().state(), "a hold is not an exit");

		AgentTaskData sent = agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, null, "coder", WU);
		assertEquals(TaskStatus.QUEUED, sent.getStatus());
		SplitRequest sr = sent.getSplitRequest();
		assertEquals(SplitRequestState.DECLINED, sr.state());
		assertEquals(PERSON, sr.settledBy());
		assertEquals("hold lifted to coder", sr.note());
		assertEquals(1, declineInfos(r, sent).size());
	}

	/** T-10, F-1: a lift without a role routes the task on, and the request lapses with no INFO of its own. */
	@Test
	public void aLiftWithoutARoleLapsesTheRequest() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = requested(r, "lifted, routed");
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "reading", COORD, WU);
		AgentTaskData routed = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, WU);
		assertEquals(TaskStatus.QUEUED, routed.getStatus(), routed.getStatusHistory().toString());
		SplitRequest sr = routed.getSplitRequest();
		assertEquals(SplitRequestState.LAPSED, sr.state());
		assertEquals(AgentActor.system("routing"), sr.settledBy());
		assertTrue(sr.note().startsWith("routed to coder"), sr.note());
		assertTrue(declineInfos(r, routed).isEmpty());
		assertTrue(messages(r, BoardEventKind.INFO, "split request").isEmpty());
	}

	/** T-10, F-1: completing or cancelling lapses the request; a reopen of the completed task writes nothing on it. */
	@Test
	public void completeAndCancelLapseTheRequest() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData done = requested(r, "completed");
		agentTaskService.complete(done.getUuid(), "enough", COORD, WU);
		SplitRequest lapsed = reload(done).getSplitRequest();
		assertEquals(SplitRequestState.LAPSED, lapsed.state());
		assertEquals("completed", lapsed.note());
		agentTaskService.reopen(done.getUuid(), "coder", "build it after all", COORD, WU);
		assertEquals(lapsed, reload(done).getSplitRequest(), "settledAt unchanged");

		AgentTaskData gone = requested(r, "cancelled");
		agentTaskService.cancel(gone.getUuid(), "not needed", COORD, WU);
		assertEquals(SplitRequestState.LAPSED, reload(gone).getSplitRequest().state());
		assertEquals("cancelled", reload(gone).getSplitRequest().note());
		assertTrue(declineInfos(r, gone).isEmpty());
	}

	/** T-11: the split reads its own list, not the proposal, and settles the request SPLIT. */
	@Test
	public void theSplitSettlesTheRequestWithItsOwnList() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = requested(r, "split differently");
		List<AgentTaskData> children = split(r, t, child("X"), child("Y"), child("Z", 0, 1));
		assertEquals(List.of("X", "Y", "Z"), children.stream().map(AgentTaskData::getTitle).toList());
		SplitRequest sr = reload(t).getSplitRequest();
		assertEquals(SplitRequestState.SPLIT, sr.state());
		assertEquals(COORD, sr.settledBy());
		assertEquals("3 children: " + keys(children), sr.note());
		assertEquals(2, sr.proposedChildren().size(), "the proposal is kept as it was made");
	}

	/** The plain board's task at the checkpoint, sent back to the architect, who returns SPLIT (D8). */
	AgentTaskData bothRecords(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		DesignCheckpoint first = atCheckpoint(r, t).getDesignCheckpoint();
		authorize(r, t, "architect", COORD);
		AgentTaskData back = takeAndReturnSplit(r, t, r.architect(), List.of(child("A"), child("B")));
		assertTrue(back.checkpointPending());
		assertTrue(first.reachedAt().isEqual(back.getDesignCheckpoint().reachedAt()));
		assertEquals(SplitRequestState.REQUESTED, back.getSplitRequest().state());
		return back;
	}

	/** T-12 (a): at a PENDING checkpoint with a request, the split settles both and pins the record's design. */
	@Test
	public void aSplitSettlesBothRecords() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = bothRecords(r, "both, split");
		List<UUID> design = t.getDesignCheckpoint().design();
		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		AgentTaskData after = reload(t);
		assertEquals(DesignCheckpointState.SPLIT, after.getDesignCheckpoint().state());
		assertEquals(SplitRequestState.SPLIT, after.getSplitRequest().state());
		for (AgentTaskData c : children) assertEquals(design, pins(c));
	}

	/** T-12 (b): an authorize of the coder settles each record through its own helper, two INFOs in that order. */
	@Test
	public void anAuthorizeSettlesEachRecord() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = bothRecords(r, "both, authorized");
		authorize(r, t, "coder", COORD);
		AgentTaskData after = reload(t);
		assertEquals(DesignCheckpointState.SENT_ON, after.getDesignCheckpoint().state());
		assertEquals(SplitRequestState.DECLINED, after.getSplitRequest().state());
		List<String> infos = messages(r, BoardEventKind.INFO, after.label());
		assertEquals(2, infos.size(), infos.toString());
		assertTrue(infos.get(0).contains("sent it on to coder at the design checkpoint"), infos.toString());
		assertTrue(infos.get(1).contains("declined the split request, sent to coder"), infos.toString());
	}

	/** T-12 (c), F-1: routing's own exit lapses the request and leaves the checkpoint; a later return does not revive it. */
	@Test
	public void routingsOwnExitLapsesTheRequest() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = bothRecords(r, "both, routed");
		DesignCheckpoint cp = t.getDesignCheckpoint();
		AgentTaskData routed = agentTaskService.decideReviewItems(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				List.of(new BoardReviewItemDecision(BoardReviewItemDecisionAction.FILE, null, 1, "filed at P1", null, null)),
				new About(RearmSpecificationType.ARCHITECTURE, null), PERSON, WU);
		assertEquals(TaskStatus.QUEUED, routed.getStatus());
		assertEquals("architect", routed.getRole());
		SplitRequest sr = routed.getSplitRequest();
		assertEquals(SplitRequestState.LAPSED, sr.state());
		assertTrue(sr.note().startsWith("routed to architect: "), sr.note());
		assertTrue(sr.note().endsWith("; back to architect"), sr.note());
		assertEquals(cp, routed.getDesignCheckpoint(), "the checkpoint record untouched");
		sr = reload(t).getSplitRequest();

		assign(r, t, r.architect());
		agentTaskService.returnTask(t.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "not mine",
				List.of(), WU);
		AgentTaskData back = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertEquals(sr, back.getSplitRequest(), "LAPSED, as it was");
		authorize(r, t, "coder", COORD);
		assertEquals(sr, reload(t).getSplitRequest(), "nothing written on the request");
		assertTrue(declineInfos(r, t).isEmpty());
	}

	/** T-13: on a board with a blind reviewer the feed names the count only and the blind read drops the account. */
	@Test
	public void aBlindReaderSeesTheStateNotTheAccount() throws RelizaException {
		Rig r = rig(true, false, HumanGate.NONE, List.of(AgentCapability.CODE_PUSH));
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("functional_reviewer", null,
				null, null, null, null, null, null, null, null, null, null, null, null, true, null), true, WU);
		AgentTaskData t = task(r, "blind");
		design(r, t, "fits one coder pass");
		reviewsPass(r, t);
		AgentTaskData back = takeAndReturnSplit(r, t, r.coder(), List.of(child("secret A"), child("secret B")));
		assertEquals(List.of("Task " + back.label() + " returned by coder (SPLIT); proposes 2 children"),
				returnAlerts(r, back));

		authorizeWithNote(r, t, "functional_reviewer", "review it again");
		UUID reviewerKey = UUID.randomUUID();
		AgentSessionData blind = agentSessionService.initialize(r.org().getUuid(), r.functional().agentUuid(),
				reviewerKey, "blind-" + UUID.randomUUID(), "blind reviewer", null, null, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.functional().agentUuid(), blind.getUuid(), WU);

		SplitRequest seen = agentTaskService.forReader(reload(t), reviewerKey).getSplitRequest();
		assertNull(seen.description());
		assertNull(seen.session());
		assertEquals(List.of(), seen.proposedChildren());
		assertEquals("coder", seen.role());
		assertEquals(SplitRequestState.DECLINED, seen.state());
		assertNotNull(seen.requestedAt());
		assertEquals(COORD, seen.settledBy());
		assertEquals("review it again", seen.note(), "the coordinator's note stays");

		SplitRequest whole = agentTaskService.forReader(reload(t), UUID.randomUUID()).getSplitRequest();
		assertEquals(WHY, whole.description());
		assertEquals(r.coder().sessionUuid(), whole.session());
		assertEquals(2, whole.proposedChildren().size());
	}
}

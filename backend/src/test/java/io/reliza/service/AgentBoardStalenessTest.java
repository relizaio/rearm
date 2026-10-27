/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.SessionUsageSource;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.ws.App;

/**
 * Stale work on a board (task RD3-4): each rule from fixtures, on and off by its threshold; the
 * re-alert policy; the facts a poll records; the release a person makes and the refusals that follow;
 * and the block's round trip through the board file. The sweep is driven with a clock ahead of now,
 * which is how minutes pass here.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBoardStalenessTest extends AgentBoardStalenessTestBase {

	// ---------- the hop rule ----------

	@Test
	public void aHopWithNothingFromItsHolderIsStaleAndAPublishOrAUsageReportIsProgress() throws RelizaException {
		Rig r = rig();
		setStaleness(r, block("hopNoProgressMinutes", 60));
		AgentTaskData published = queued(r, "reviewer");
		agentTaskService.assign(published.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		AgentTaskData reported = queued(r, "reviewer");
		agentTaskService.assign(reported.getUuid(), board(r), r.b().getUuid(), r.sb().getUuid(), WU);

		assertTrue(breaches(r, in(30)).isEmpty(), "under the threshold: quiet");
		List<BoardStalenessService.Breach> stale = breaches(r, in(61));
		assertEquals(2, stale.size(), stale.toString());
		assertTrue(stale.get(0).message().contains("stalled: assigned to reviewer"), stale.toString());
		assertTrue(stale.get(0).message().contains("no progress reported; release it with task unassign if the agent is gone"));

		agentDocumentService.publish(r.sa(), new PublishRequest(published.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null,
				Map.of("kind", "BOARD_REVIEW_ITEMS", "verdict", "PASSED", "reviewItems", List.of()), null, null, null,
				null, null), WU);
		usageService.report(agentSessionService.getSessionData(r.sb().getUuid()).orElseThrow(),
				new UsageReport(r.sb().getUuid(), null, SEQ.incrementAndGet(), SessionUsageSource.TRANSCRIPT,
						ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, reported.getUuid(), Map.of(),
						List.of(new UsageLine("claude-opus-5-5", null, 0L, 1, 100, 0, 0, 0, null, 100L, 100L, null))), WU);
		assertTrue(breaches(r, in(61)).isEmpty(), "a publish and a usage report are both progress: " + breaches(r, in(61)));

		// the rule is off without its threshold
		setStaleness(r, block("roleUnstaffedMinutes", 60));
		AgentTaskData silent = queued(r, "coder");
		agentTaskService.assign(silent.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		assertTrue(breaches(r, in(600)).stream().noneMatch(b -> b.key().startsWith(BoardStalenessService.HOP_NO_PROGRESS)));
	}

	// ---------- the unstaffed rule ----------

	@Test
	public void aRoleIsUnstaffedUntilASessionThatTakesItPolls() throws RelizaException {
		Rig r = rig();
		setStaleness(r, block("roleUnstaffedMinutes", 60));
		queued(r, "coder");
		queued(r, "coder");
		ZonedDateTime at = in(61);
		List<BoardStalenessService.Breach> b = breaches(r, at);
		assertEquals(1, b.size(), b.toString());
		assertEquals(BoardStalenessService.ROLE_UNSTAFFED + ":coder", b.get(0).key());
		assertTrue(b.get(0).message().startsWith("coder unstaffed: 2 tasks queued for up to 61 min"), b.get(0).message());

		// a session that polls for another role does not staff this one
		agentSessionService.recordBoardPolls(r.sb().getUuid(), List.of(r.board().getUuid()), List.of("reviewer"), null, at);
		assertEquals(1, breaches(r, at).size());
		// one that declares no roles takes any
		agentSessionService.recordBoardPolls(r.sa().getUuid(), List.of(r.board().getUuid()), List.of(), null, at);
		assertTrue(breaches(r, at).isEmpty());
		// a poll older than the span no longer counts
		assertEquals(1, breaches(r, at.plusMinutes(61)).size());
	}

	// ---------- the delivery rule ----------

	@Test
	public void aDeliveryThatDoesNotLandIsStuck() throws RelizaException {
		Rig r = rig(false);
		setStaleness(r, block("deliveryStuckMinutes", 120));
		AgentTaskData t = queued(r, "coder");
		agentTaskService.assign(t.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		String url = "https://github.com/acme/app/pull/" + UUID.randomUUID().toString().substring(0, 6);
		agentTaskService.linkPr(t.getUuid(), url, WU);
		AgentTaskData d = agentTaskService.signOff(t.getUuid(), r.sa().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		// Task RD4-2: the rule reads the PR's row, so a PR whose CI reports elsewhere is not in play and the rule
		// stays silent however long it waits. A registered PR's case is in AgentRegisteredPrsIntegrationTest.
		assertTrue(breaches(r, in(60)).isEmpty());
		assertTrue(breaches(r, in(121)).isEmpty(), "an unregistered PR is not in play");
		AgentBoardData bd = board(r);
		assertTrue(staleness.taskBreaches(bd, agentTaskService.listByBoard(bd.getUuid(), null), in(121)).isEmpty(),
				"nor on the task's own marks (task TPF-69)");
	}

	// ---------- the seat rule ----------

	@Test
	public void workWaitingOnAHeldSeatIsStaleOnlyWhileTheSeatIsHeld() throws RelizaException {
		Rig r = rig();
		setStaleness(r, block("seatSilentMinutes", 30));
		AgentTaskData intake = agentTaskService.register(board(r), null, "intake " + UUID.randomUUID(), null, null,
				null, null, null, null, PERSON, true, WU);
		assertEquals(TaskStatus.PENDING_INTAKE, intake.getStatus());
		assertTrue(breaches(r, in(31)).isEmpty(), "nobody holds the seat");

		AgentData coord = agentService.findOrRegisterRootAgent(r.org().getUuid(), UUID.randomUUID(), "c-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData seat = session(r.org(), coord);
		agentBoardService.claimCoordinatorSeat(r.board().getUuid(), seat.getUuid(), coord.getUuid(), WU);
		assertTrue(breaches(r, in(20)).isEmpty());
		List<BoardStalenessService.Breach> b = breaches(r, in(31));
		assertEquals(1, b.size(), b.toString());
		assertTrue(b.get(0).message().contains("waiting on the coordinator: PENDING_INTAKE for 31 min while the seat is held"));
	}

	// ---------- the re-alert policy and what the sweep writes ----------

	@Test
	public void aBreachAlertsOnceThenAfterRepeatMinutesAndAtOnceWhenItReturns() throws RelizaException {
		Rig r = rig();
		setStaleness(r, block("roleUnstaffedMinutes", 60, "repeatMinutes", 120));
		queued(r, "coder");
		int boardRevision = boardRevision(r);
		ZonedDateTime t0 = in(61);

		assertEquals(1, staleness.sweepBoard(board(r), t0));
		assertEquals(0, staleness.sweepBoard(board(r), t0.plusMinutes(1)), "a standing breach is quiet");
		assertEquals(0, staleness.sweepBoard(board(r), t0.plusMinutes(119)));
		assertEquals(1, staleness.sweepBoard(board(r), t0.plusMinutes(120)), "again after repeatMinutes");
		assertEquals(2, alerts(r, "coder unstaffed").size());

		// cleared by a poll: the map drops it, so when it comes back it alerts at once
		ZonedDateTime polled = t0.plusMinutes(121);
		agentSessionService.recordBoardPolls(r.sa().getUuid(), List.of(r.board().getUuid()), List.of("coder"), null, polled);
		assertEquals(0, staleness.sweepBoard(board(r), polled));
		assertTrue(board(r).getStalenessAlerted().isEmpty(), board(r).getStalenessAlerted().toString());
		assertEquals(1, staleness.sweepBoard(board(r), polled.plusMinutes(61)), "back well inside repeatMinutes, alerted at once");
		assertEquals(3, alerts(r, "coder unstaffed").size());

		assertEquals(boardRevision, boardRevision(r), "the sweep writes no revision of the board");
		assertEquals(0, staleness.sweepBoard(board(r), polled.plusMinutes(62)));
	}

	@Test
	public void aBoardWithoutTheBlockIsNotRead() throws RelizaException {
		Rig r = rig();
		queued(r, "coder");
		assertEquals(0, staleness.sweepBoard(board(r), in(10_000)));
		assertTrue(agentBoardService.listWatchingStaleness().stream().noneMatch(b -> b.getUuid().equals(r.board().getUuid())));
		setStaleness(r, block("roleUnstaffedMinutes", 60));
		assertTrue(agentBoardService.listWatchingStaleness().stream().anyMatch(b -> b.getUuid().equals(r.board().getUuid())));
		setStaleness(r, null);
		assertNull(board(r).getStaleness(), "declared null turns every rule off");
	}

	// ---------- what a poll records ----------

	@Test
	public void taskNextRecordsThePollAndTheOfferWithoutARevision() throws RelizaException {
		Rig r = rig();
		queued(r, "coder");
		int revision = sessionRevision(r.sa());

		assertTrue(agentTaskService.next(List.of(board(r)), r.a().getUuid(), r.sa().getUuid(), List.of("reviewer")).isEmpty());
		AgentSessionData.BoardActivity polled = agentSessionService.getSessionData(r.sa().getUuid()).orElseThrow()
				.getBoardActivity().get(r.board().getUuid().toString());
		assertNotNull(polled.lastPollAt());
		assertNull(polled.lastOfferAt(), "nothing offered");
		assertEquals(List.of("reviewer"), polled.roles());

		assertTrue(agentTaskService.next(List.of(board(r)), r.a().getUuid(), r.sa().getUuid(), List.of("coder")).isPresent());
		AgentSessionData.BoardActivity offered = agentSessionService.getSessionData(r.sa().getUuid()).orElseThrow()
				.getBoardActivity().get(r.board().getUuid().toString());
		assertNotNull(offered.lastOfferAt());
		assertEquals(List.of("coder"), offered.roles());
		assertEquals(revision, sessionRevision(r.sa()), "bookkeeping, not a revision");
	}

	// ---------- a person releases ----------

	@Test
	public void anUnassignedHopQueuesTheTaskAndItsSessionIsToldSo() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = queued(r, "reviewer");
		agentTaskService.assign(t.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);

		AgentTaskData released = agentTaskService.unassign(t.getUuid(), "the agent is gone", PERSON, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertEquals("reviewer", released.getRole());
		assertNull(released.getAssignment());
		StatusChange row = released.getStatusHistory().get(released.getStatusHistory().size() - 1);
		assertEquals(StatusTrigger.UNASSIGN, row.trigger());
		assertEquals("unassigned by " + PERSON.display() + ": the agent is gone", row.note());
		AgentTaskData.Unassignment ra = released.getUnassignments().get(0);
		assertEquals(r.sa().getUuid(), ra.session());
		assertEquals(PERSON, ra.unassignedBy());
		assertEquals(AgentSessionData.SessionStatus.OPEN,
				agentSessionService.getSessionData(r.sa().getUuid()).orElseThrow().getStatus(), "the session stays open");
		assertEquals(1, agentBoardService.recentEvents(r.board().getUuid()).stream()
				.filter(e -> e.message().contains("unassigned by " + PERSON.display() + ": the agent is gone")).count());

		String expected = ra.refusal(released.keyOrUuid());
		assertTrue(expected.matches("you were unassigned from " + released.getKey() + " by " + PERSON.display()
				+ " at \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ; the task is queued again"), expected);
		RelizaException signOff = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.sa().getUuid(), SignOffOutcome.PASSED, "late", WU));
		assertEquals(expected, signOff.getMessage());
		RelizaException publish = assertThrows(RelizaException.class, () -> agentDocumentService.publish(r.sa(),
				new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null,
						Map.of("kind", "BOARD_REVIEW_ITEMS", "verdict", "PASSED", "reviewItems", List.of()), null, null, null,
						null, null), WU));
		assertEquals(expected, publish.getMessage());

		// the next session of the role takes it, and only the released one is refused
		agentTaskService.assign(t.getUuid(), board(r), r.b().getUuid(), r.sb().getUuid(), WU);
		assertEquals(expected, assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.sa().getUuid(), SignOffOutcome.PASSED, "late", WU)).getMessage());
		AgentTaskData signed = agentTaskService.signOff(t.getUuid(), r.sb().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(r.sb().getUuid(), signed.getSignOffs().get(signed.getSignOffs().size() - 1).session(),
				"the new holder's sign-off is taken");

		// only an ASSIGNED task can be unassigned, and a reason is required
		RelizaException notAssigned = assertThrows(RelizaException.class,
				() -> agentTaskService.unassign(t.getUuid(), "again", PERSON, WU));
		assertTrue(notAssigned.getMessage().contains("only an ASSIGNED task can be unassigned"), notAssigned.getMessage());
		AgentTaskData other = queued(r, "coder");
		agentTaskService.assign(other.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		assertThrows(RelizaException.class, () -> agentTaskService.unassign(other.getUuid(), " ", PERSON, WU));
		assertEquals(TaskStatus.ASSIGNED, reload(other).getStatus(), "a blank reason releases nothing");
	}

	// ---------- the board file ----------

	@Test
	public void theBlockRoundTripsThroughTheBoardFileAndZeroIsRefused() throws RelizaException {
		Rig r = rig();
		String name = "stale-file-" + UUID.randomUUID();
		Map<String, Object> st = block("roleUnstaffedMinutes", 60, "hopNoProgressMinutes", 90, "repeatMinutes", 300);
		ApplyResult created = apply(r, name, block("staleness", st));
		assertEquals(0, created.getErrors(), created.getChanges().toString());
		AgentBoardData bd = agentBoardService.listByOrg(r.org().getUuid()).stream().filter(b -> name.equals(b.getName()))
				.findFirst().orElseThrow();
		assertEquals(new AgentBoardData.Staleness(60, 90, null, null, 300), bd.getStaleness());

		AgentBoardService.BoardSpecDto export = agentBoardService.exportBoard(bd.getUuid());
		assertEquals(bd.getStaleness(), export.getSettings().getStaleness(), "the export carries the block");
		ApplyResult again = apply(r, name, block("staleness", st));
		assertTrue(again.getChanges().stream().allMatch(c -> Action.UNCHANGED == c.getAction()), "no drift: " + again.getChanges());
		ApplyResult changed = apply(r, name, block("staleness", block("roleUnstaffedMinutes", 45)));
		assertTrue(changed.getChanges().stream().anyMatch(c -> Action.UPDATE == c.getAction()), changed.getChanges().toString());
		assertEquals(new AgentBoardData.Staleness(45, null, null, null, null), agentBoardService.getBoardData(bd.getUuid())
				.orElseThrow().getStaleness(), "the block is replaced whole");

		ApplyResult zero = apply(r, name, block("staleness", block("seatSilentMinutes", 0)));
		assertEquals(1, zero.getErrors(), zero.getChanges().toString());
		assertTrue(zero.getChanges().toString().contains("settings.staleness.seatSilentMinutes must be at least 1 minute"),
				zero.getChanges().toString());
		Map<String, Object> off = new LinkedHashMap<>();
		off.put("staleness", null);
		assertEquals(0, apply(r, name, off).getErrors());
		assertNull(agentBoardService.getBoardData(bd.getUuid()).orElseThrow().getStaleness(), "null turns it off");
	}

	private ApplyResult apply(Rig r, String name, Map<String, Object> settings) throws RelizaException {
		Map<String, Object> spec = block("kind", "BOARD", "version", 1, "name", name, "target", r.target(),
				"settings", settings, "roles", List.of(block("name", "coder", "prompt", "code it")));
		return agentBoardService.applyBoard(r.org().getUuid(), AgentBoardService.boardSpecFromInput(spec), false, null,
				PERSON, WU);
	}
}

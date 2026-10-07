/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board's event feed as a log read since a point (task 1c5442d2): every event reaches the log,
 * the board serves its newest 50 from it (the row no longer keeps them, task RD3-2), and a follower
 * pages through all of them exactly once. The
 * log keeps each board's events for its eventRetentionDays, and a reader that asked for deleted
 * ones is told (task 04dedcc5).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBoardEventLogIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private io.reliza.repositories.NotificationOutboxEventRepository outbox;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor ACTOR = AgentActor.ofUser(UUID.randomUUID(), "operator");

	private AgentBoardData board() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("events_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), "events-" + UUID.randomUUID(), "events", List.of(),
				"coordinate", 4, null, target.getUuid(), null, WU);
	}

	/** The log's own events of a board, oldest first, ignoring what creating it posted. */
	private List<AgentBoardService.LoggedEvent> all(AgentBoardData bd, Long after) throws RelizaException {
		List<AgentBoardService.LoggedEvent> out = new ArrayList<>();
		Long cursor = after;
		AgentBoardService.EventPage page;
		do {
			page = agentBoardService.eventsOf(bd.getUuid(), cursor, null, AgentBoardService.EVENTS_PAGE_MAX);
			out.addAll(page.events());
			cursor = page.nextAfter();
		} while (page.hasMore());
		return out;
	}

	private long lastSeq(AgentBoardData bd) throws RelizaException {
		List<AgentBoardService.LoggedEvent> before = all(bd, null);
		return before.isEmpty() ? 0L : before.get(before.size() - 1).seq();
	}

	/** Sixty INFO events, "e1" to "e60", after whatever the board had. */
	private long sixty(AgentBoardData bd) throws RelizaException {
		long start = lastSeq(bd);
		for (int i = 1; i <= 60; i++) {
			agentBoardService.postEvent(bd.getUuid(), BoardEventKind.INFO, "e" + i, ACTOR, WU);
		}
		return start;
	}

	@Test
	public void everyEventReachesTheLogAndTheBoardServesItsNewestFifty() throws RelizaException {
		AgentBoardData bd = board();
		long start = sixty(bd);
		// From the log since RD3-2; the row no longer carries a window of them.
		List<AgentBoardData.BoardEvent> recent = agentBoardService.recentEvents(bd.getUuid());
		assertEquals(AgentBoardService.RECENT_EVENTS, recent.size(), "the dashboard's window");
		assertEquals("e11", recent.get(0).message(), "oldest first");
		assertEquals("e60", recent.get(recent.size() - 1).message());
		List<AgentBoardService.LoggedEvent> logged = all(bd, start);
		assertEquals(60, logged.size(), "the log keeps them all");
		assertEquals("e1", logged.get(0).message());
		assertEquals(ACTOR, logged.get(0).actor(), "the actor round-trips");
		assertEquals(BoardEventKind.INFO, logged.get(0).kind());
	}

	@Test
	public void sinceTheFifthNewestReturnsFive() throws RelizaException {
		AgentBoardData bd = board();
		long start = sixty(bd);
		List<AgentBoardService.LoggedEvent> logged = all(bd, start);
		ZonedDateTime fifthNewest = logged.get(logged.size() - 5).eventAt();
		AgentBoardService.EventPage since = agentBoardService.eventsOf(bd.getUuid(), start, fifthNewest, null);
		assertEquals(List.of("e56", "e57", "e58", "e59", "e60"), since.events().stream()
				.map(AgentBoardService.LoggedEvent::message).toList());
		assertFalse(since.hasMore());
	}

	@Test
	public void pagingReturnsEveryEventOnceInOrder() throws RelizaException {
		AgentBoardData bd = board();
		long start = sixty(bd);
		List<String> seen = new ArrayList<>();
		List<Boolean> more = new ArrayList<>();
		Long cursor = start;
		AgentBoardService.EventPage page;
		do {
			page = agentBoardService.eventsOf(bd.getUuid(), cursor, null, 25);
			page.events().forEach(e -> seen.add(e.message()));
			more.add(page.hasMore());
			cursor = page.nextAfter();
		} while (page.hasMore());
		assertEquals(IntStream.rangeClosed(1, 60).mapToObj(i -> "e" + i).toList(), seen);
		assertEquals(List.of(true, true, false), more, "25, 25, 10");
		AgentBoardService.EventPage empty = agentBoardService.eventsOf(bd.getUuid(), cursor, null, 25);
		assertTrue(empty.events().isEmpty());
		assertEquals(cursor, empty.nextAfter(), "an empty page keeps the follower's place");
	}

	@Test
	public void aPageIsCappedAndALimitBelowOneRefused() throws RelizaException {
		AgentBoardData bd = board();
		long start = lastSeq(bd);
		// written straight into the log: 1001 posts through the board would be slow for nothing
		for (int i = 0; i < AgentBoardService.EVENTS_PAGE_MAX + 1; i++) {
			jdbcTemplate.update("INSERT INTO rearm.agent_board_events (uuid, org, board, kind, message, event_at)"
					+ " VALUES (?, ?, ?, 'INFO', ?, now())", UUID.randomUUID(), bd.getOrg(), bd.getUuid(), "bulk" + i);
		}
		AgentBoardService.EventPage page = agentBoardService.eventsOf(bd.getUuid(), start, null, 5000);
		assertEquals(AgentBoardService.EVENTS_PAGE_MAX, page.events().size());
		assertTrue(page.hasMore());
		assertEquals(AgentBoardService.EVENTS_PAGE_DEFAULT,
				agentBoardService.eventsOf(bd.getUuid(), start, null, null).events().size(), "the default");
		assertThrows(RelizaException.class, () -> agentBoardService.eventsOf(bd.getUuid(), null, null, 0));
	}

	@Test
	public void pausesAreLoggedToo() throws RelizaException {
		AgentBoardData bd = board();
		long start = lastSeq(bd);
		agentBoardService.setPause(bd.getUuid(), BoardPauseLevel.OPERATOR, true, "freeze", ACTOR, WU);
		agentBoardService.setPause(bd.getUuid(), BoardPauseLevel.OPERATOR, false, "thaw", ACTOR, WU);
		assertEquals(List.of(BoardEventKind.PAUSED, BoardEventKind.RESUMED),
				all(bd, start).stream().map(AgentBoardService.LoggedEvent::kind).toList());
	}

	@Test
	public void theLogRowRollsBackWithTheBoardSave() throws RelizaException {
		AgentBoardData bd = board();
		long start = lastSeq(bd);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			try {
				agentBoardService.postEvent(bd.getUuid(), BoardEventKind.INFO, "rolled back", ACTOR, WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
			status.setRollbackOnly();
		});
		assertTrue(all(bd, start).isEmpty(), "no row outlives the board save it belonged to");
		assertTrue(agentBoardService.recentEvents(agentBoardService.getBoardData(bd.getUuid()).orElseThrow().getUuid()).stream()
				.noneMatch(e -> "rolled back".equals(e.message())));
	}

	// ---------- Retention (task 04dedcc5) ----------

	private static final ZonedDateTime NOW = ZonedDateTime.now(ZoneOffset.UTC);

	/** One event written straight into the log, the given days old; its seq. */
	private long aged(AgentBoardData bd, String message, int daysOld) {
		return jdbcTemplate.queryForObject("INSERT INTO rearm.agent_board_events (uuid, org, board, kind, message,"
				+ " event_at) VALUES (?, ?, ?, 'INFO', ?, ?) RETURNING seq", Long.class, UUID.randomUUID(), bd.getOrg(),
				bd.getUuid(), message, Timestamp.from(NOW.minusDays(daysOld).toInstant()));
	}

	private List<String> messages(AgentBoardData bd) {
		return jdbcTemplate.queryForList("SELECT message FROM rearm.agent_board_events WHERE board = ?"
				+ " AND message LIKE 'd%' ORDER BY seq", String.class, bd.getUuid());
	}

	private AgentBoardData retaining(Integer days) throws RelizaException {
		AgentBoardData bd = board();
		Map<String, Object> settings = new HashMap<>();
		settings.put("eventRetentionDays", days);
		return agentBoardService.updateSettingsFromInput(bd.getUuid(), settings, WU);
	}

	@Test
	public void theSweepDeletesOnlyRowsPastTheWindowOfThatBoard() throws RelizaException {
		AgentBoardData three = retaining(3);
		AgentBoardData thirty = retaining(30);
		for (AgentBoardData bd : List.of(three, thirty)) {
			agentBoardService.postEvent(bd.getUuid(), BoardEventKind.INFO, "on the row", ACTOR, WU);
			aged(bd, "d40", 40);
			aged(bd, "d10", 10);
			aged(bd, "d1", 1);
		}
		assertFalse(agentBoardService.recentEvents(three.getUuid()).isEmpty(), "the post is in the feed");

		assertEquals(2, agentBoardService.sweepEventLog(List.of(three.getUuid()), NOW, agentBoardService::sweepEventLogOf));
		assertEquals(List.of("d1"), messages(three));
		assertEquals(List.of("d40", "d10", "d1"), messages(thirty), "another board's rows are its own window's");
		assertEquals(1, agentBoardService.sweepEventLog(List.of(thirty.getUuid()), NOW, agentBoardService::sweepEventLogOf));
		assertEquals(List.of("d10", "d1"), messages(thirty));
		assertEquals(List.of("d1"), messages(three));

		// The feed is the log since RD3-2, so it shows what the sweep kept, in the log's order.
		assertEquals(List.of("on the row", "d1"), agentBoardService.recentEvents(three.getUuid()).stream()
				.map(AgentBoardData.BoardEvent::message).toList(), "the feed follows the log");
		assertEquals(List.of("on the row", "d10", "d1"), agentBoardService.recentEvents(thirty.getUuid()).stream()
				.map(AgentBoardData.BoardEvent::message).toList());
		assertEquals(0, agentBoardService.sweepEventLogOf(three.getUuid(), NOW), "a second sweep finds nothing");
	}

	@Test
	public void theDefaultIsFifteenDaysAndZeroKeepsEverything() throws RelizaException {
		AgentBoardData byDefault = board();
		assertNull(byDefault.getEventRetentionDays());
		assertEquals(15, byDefault.getEffectiveEventRetentionDays());
		aged(byDefault, "d16", 16);
		aged(byDefault, "d14", 14);
		AgentBoardData keepAll = retaining(0);
		aged(keepAll, "d400", 400);
		long revision = jdbcTemplate.queryForObject("SELECT revision FROM rearm.agent_boards WHERE uuid = ?",
				Long.class, byDefault.getUuid());

		agentBoardService.sweepEventLog(NOW);
		assertEquals(List.of("d14"), messages(byDefault));
		assertEquals(List.of("d400"), messages(keepAll), "0 keeps everything");
		assertEquals(revision, jdbcTemplate.queryForObject("SELECT revision FROM rearm.agent_boards WHERE uuid = ?",
				Long.class, byDefault.getUuid()), "the sweep adds no board revision");
		assertNull(agentBoardService.getBoardData(keepAll.getUuid()).orElseThrow().getEventLogPruned());
	}

	@Test
	public void aFailingBoardDoesNotStopTheSweep() throws RelizaException {
		AgentBoardData failing = retaining(3);
		AgentBoardData next = retaining(3);
		aged(failing, "d10", 10);
		aged(next, "d10", 10);
		int deleted = agentBoardService.sweepEventLog(List.of(failing.getUuid(), next.getUuid()), NOW, (b, now) -> {
			if (b.equals(failing.getUuid())) throw new IllegalStateException("stubbed failure");
			return agentBoardService.sweepEventLogOf(b, now);
		});
		assertEquals(1, deleted);
		assertEquals(List.of("d10"), messages(failing), "the failing board kept its rows");
		assertTrue(messages(next).isEmpty(), "the board after it was swept");
	}

	@Test
	public void aReaderPastTheWindowSeesTheGap() throws RelizaException {
		AgentBoardData bd = retaining(3);
		long d10 = aged(bd, "d10", 10);
		long d5 = aged(bd, "d5", 5);
		long d1 = aged(bd, "d1", 1);
		AgentBoardService.EventPage before = agentBoardService.eventsOf(bd.getUuid(), d10, null, null);
		assertFalse(before.gap(), "nothing deleted yet");
		assertNotNull(before.truncatedBefore());
		assertEquals(2, agentBoardService.sweepEventLogOf(bd.getUuid(), NOW));
		long oldest = jdbcTemplate.queryForObject("SELECT min(seq) FROM rearm.agent_board_events WHERE board = ?",
				Long.class, bd.getUuid());

		AgentBoardService.EventPage fromLost = agentBoardService.eventsOf(bd.getUuid(), d10, null, null);
		assertTrue(fromLost.gap(), "d5 was after the cursor and is gone");
		assertEquals(List.of("d1"), fromLost.events().stream().map(AgentBoardService.LoggedEvent::message).toList(),
				"the page starts at the oldest event kept after the cursor");
		ZonedDateTime window = fromLost.truncatedBefore();
		assertTrue(Math.abs(Duration.between(ZonedDateTime.now().minusDays(3), window).toMinutes()) < 5, window.toString());

		AgentBoardService.EventPage sinceLost = agentBoardService.eventsOf(bd.getUuid(), null, NOW.minusDays(20), null);
		assertTrue(sinceLost.gap(), "a since before the window asked for d10 and d5");
		assertEquals(oldest, sinceLost.events().get(0).seq(), "the page starts at the oldest event kept");

		assertFalse(agentBoardService.eventsOf(bd.getUuid(), d5, null, null).gap(),
				"a reader that saw the newest deleted event lost nothing");
		assertFalse(agentBoardService.eventsOf(bd.getUuid(), d1, null, null).gap(), "a cursor inside the window");
		assertFalse(agentBoardService.eventsOf(bd.getUuid(), null, NOW.minusDays(2), null).gap(),
				"a since inside the window");
		assertFalse(agentBoardService.eventsOf(bd.getUuid(), null, null, null).gap(),
				"a read from the start reads what is kept");

		agentBoardService.postEvent(bd.getUuid(), BoardEventKind.INFO, "a board save after the sweep", ACTOR, WU);
		assertTrue(agentBoardService.eventsOf(bd.getUuid(), d10, null, null).gap(),
				"a board save keeps what the sweep recorded");
	}

	@Test
	public void aCursorBelowTheBoardsOldestRowIsNoGapWhenNothingWasDeleted() throws RelizaException {
		// The seq is shared by every board's log, so another board's events sit between this one's.
		AgentBoardData bd = retaining(3);
		AgentBoardData other = board();
		long mine = aged(bd, "d1", 1);
		aged(other, "d1", 1);
		aged(other, "d1", 1);
		long later = aged(bd, "d0", 0);
		assertTrue(later > mine + 1);
		assertEquals(0, agentBoardService.sweepEventLogOf(bd.getUuid(), NOW));
		assertFalse(agentBoardService.eventsOf(bd.getUuid(), mine, null, null).gap());
		assertFalse(agentBoardService.eventsOf(bd.getUuid(), 0L, null, null).gap());
		assertNull(agentBoardService.eventsOf(retaining(0).getUuid(), null, null, null).truncatedBefore(),
				"a board that keeps everything has no window");
	}

	// ---------- RD3-2: an event is a log row, not a board revision ----------

	private long revision(AgentBoardData bd) {
		return jdbcTemplate.queryForObject("SELECT revision FROM rearm.agent_boards WHERE uuid = ?", Long.class, bd.getUuid());
	}

	private int auditRows(AgentBoardData bd) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.audit WHERE entity_name = 'agent_boards'"
				+ " AND entity_uuid = ?", Integer.class, bd.getUuid());
	}

	private boolean rowCarriesEvents(AgentBoardData bd) {
		return jdbcTemplate.queryForObject("SELECT record_data -> 'events' IS NOT NULL FROM rearm.agent_boards WHERE uuid = ?",
				Boolean.class, bd.getUuid());
	}

	@Test
	public void postingEventsWritesNoBoardRevisionAndAnAlertIsStillNotified() throws RelizaException {
		AgentBoardData bd = board();
		long revision = revision(bd);
		int audit = auditRows(bd);
		long start = lastSeq(bd);
		for (int i = 1; i <= 30; i++) agentBoardService.postEvent(bd.getUuid(), BoardEventKind.INFO, "i" + i, ACTOR, WU);
		String alert = "alert " + UUID.randomUUID();
		agentBoardService.postEvent(bd.getUuid(), BoardEventKind.ALERT, alert, ACTOR, WU);

		assertEquals(revision, revision(bd), "no board revision for an event");
		assertEquals(audit, auditRows(bd), "and no audit copy of the board");
		assertFalse(rowCarriesEvents(bd), "the row carries no events");
		assertEquals(31, all(bd, start).size(), "each is a log row");
		List<AgentBoardData.BoardEvent> recent = agentBoardService.recentEvents(bd.getUuid());
		assertEquals(alert, recent.get(recent.size() - 1).message(), "the newest last");
		assertEquals(1, outbox.findRecentByOrg(bd.getOrg(), 5000).stream()
				.filter(e -> e.getEventType() == io.reliza.model.NotificationEventType.AGENT_BOARD_ALERT)
				.filter(e -> String.valueOf(e.getRecordData().get("message")).contains(alert)).count(),
				"the ALERT still goes out");
	}

	@Test
	public void aPauseAndAResumeAreOneRevisionEachWithTheirEvent() throws RelizaException {
		AgentBoardData bd = board();
		long revision = revision(bd);
		long start = lastSeq(bd);
		agentBoardService.setPause(bd.getUuid(), AgentBoardData.BoardPauseLevel.OPERATOR, true, "freeze", ACTOR, WU);
		assertEquals(revision + 1, revision(bd), "the pause is one revision");
		agentBoardService.setPause(bd.getUuid(), AgentBoardData.BoardPauseLevel.OPERATOR, false, "thaw", ACTOR, WU);
		assertEquals(revision + 2, revision(bd), "the resume is one more");
		assertEquals(List.of(BoardEventKind.PAUSED, BoardEventKind.RESUMED),
				all(bd, start).stream().map(AgentBoardService.LoggedEvent::kind).toList(), "each with its event row");
		assertFalse(rowCarriesEvents(bd));
	}

	@Test
	public void aRowStillCarryingEventsReadsAndDropsThemOnItsNextRealSave() throws RelizaException {
		AgentBoardData bd = board();
		jdbcTemplate.update("UPDATE rearm.agent_boards SET record_data = jsonb_set(record_data, '{events}',"
				+ " '[{\"kind\": \"INFO\", \"message\": \"from before\", \"eventAt\": 1790000000.0}]'::jsonb)"
				+ " WHERE uuid = ?", bd.getUuid());
		assertTrue(rowCarriesEvents(bd), "an old row");
		AgentBoardData read = agentBoardService.getBoardData(bd.getUuid()).orElseThrow();
		assertEquals(bd.getName(), read.getName(), "reads as before");
		Map<String, Object> settings = new HashMap<>();
		settings.put("eventRetentionDays", 5);
		agentBoardService.updateSettingsFromInput(bd.getUuid(), settings, WU);
		assertFalse(rowCarriesEvents(bd), "the next real save drops the list");
	}
}

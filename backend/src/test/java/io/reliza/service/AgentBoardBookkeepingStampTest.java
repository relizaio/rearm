/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertContentSave;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageAck;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board's bookkeeping writers stamp in place (TPF-71 / TPF-105, architecture round 3, 8.1 the
 * board half of A2 and A4, 8.2): the coordinator-share totals and cursor and the soft-alert flag add no
 * revision, no audit row and no last_updated_date and touch only their keys; a content save still
 * audits and carries the last stamp into the trail.
 *
 * <p>The rig is AgentCoordinatorShareIntegrationTest's (97-147): the seat reports through the real usage
 * path, priced at one micro per input token.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBoardBookkeepingStampTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private CoordinatorShareService coordinatorShareService;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private JdbcTemplate jdbcTemplate;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/tpf105-board";
	private static final String DOCS_SOURCE = "github:acme/tpf105-board-docs";
	private static final String DOCS = "https://github.com/acme/tpf105-board-docs";
	private static final AtomicInteger ISSUE = new AtomicInteger(10700);

	private record Rig(AgentBoardData board, AgentSessionData seat, String model) {
		AgentActor seatActor() {
			return AgentActor.ofSession(seat.getUuid());
		}
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "tpf105-" + UUID.randomUUID(),
				"board stamps", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder",
				"build it", 10, null, false, true, null, null, null, null, List.of(), List.of(),
				null, 1_000L), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData coord = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"coord-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData seat = agentSessionService.initialize(org.getUuid(), coord.getUuid(), null,
				"s-" + UUID.randomUUID(), "coordinator", null, null, WU);
		agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.getUuid(), coord.getUuid(), WU);
		String model = "tpf105-model-" + UUID.randomUUID();
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), model, null, WU).model();
		modelOntologyService.addModelPricing(m.getUuid(), new PricingEntry(null,
				ZonedDateTime.now().minusDays(1), null, "USD", PricingUnit.PER_MILLION_TOKENS,
				1_000_000L, 0L, 0L, 0L, null, null, null, null, null, null), WU);
		return new Rig(board, seat, model);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData movedBySeat(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				r.seatActor(), WU);
	}

	private UsageAck seatReports(Rig r, long micros) throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(r.seat().getUuid()).orElseThrow();
		return usageService.report(fresh, new UsageReport(r.seat().getUuid(), null, System.nanoTime(),
				SessionUsageSource.TRANSCRIPT, ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1,
				60, null, null, Map.of(), List.of(new UsageLine(r.model(), null, 0L, 1, micros, 0, 0, 0,
						null, micros, micros, null))), WU);
	}

	private RowSnapshot boardRow(UUID uuid) {
		return snapshot(jdbcTemplate, TableName.AGENT_BOARDS, uuid);
	}

	private static final Set<String> SHARE_KEYS = Set.of("coordinatorSharesMicros", "coordinatorTransitions",
			"coordinatorShareCursor");

	/** The F-3 pin: the stored cursor is a JSON number within half a microsecond of the seat's newest reported_at. */
	private void cursorIsTheNewestReport(Rig r) {
		UUID b = r.board().getUuid();
		assertEquals("number", jdbcTemplate.queryForObject(
				"select jsonb_typeof(record_data->'coordinatorShareCursor') from rearm.agent_boards where uuid = ?",
				String.class, b));
		BigDecimal stored = jdbcTemplate.queryForObject(
				"select cast(record_data->>'coordinatorShareCursor' as numeric) from rearm.agent_boards where uuid = ?",
				BigDecimal.class, b);
		BigDecimal reported = jdbcTemplate.queryForObject(
				"select extract(epoch from max(reported_at)) from rearm.agent_session_usages where session = ?",
				BigDecimal.class, r.seat().getUuid());
		assertTrue(stored.subtract(reported).abs().compareTo(new BigDecimal("0.000001")) <= 0,
				"the cursor is the report time: " + stored + " against " + reported);
		OffsetDateTime reportedAt = jdbcTemplate.queryForObject(
				"select max(reported_at) from rearm.agent_session_usages where session = ?",
				OffsetDateTime.class, r.seat().getUuid());
		ZonedDateTime read = board(r).getCoordinatorShareCursor();
		assertNotNull(read);
		assertTrue(Math.abs(ChronoUnit.NANOS.between(reportedAt, read.toOffsetDateTime())) <= 2_000,
				"the data object reads the cursor back: " + read + " against " + reportedAt);
	}

	// ---------- 8.1 A2, the board half ----------

	@Test
	public void apportionOnceAdvancesTheSharesAndTheCursorWithoutARevision() throws RelizaException {
		Rig r = rig();
		movedBySeat(r);
		movedBySeat(r);
		UUID b = r.board().getUuid();
		RowSnapshot before = boardRow(b);

		seatReports(r, 1_000);
		RowSnapshot once = boardRow(b);
		assertStampedOnly(before, once, SHARE_KEYS);
		AgentBoardData bd = board(r);
		assertEquals(1_000L, bd.getCoordinatorSharesMicros());
		assertEquals(2L, bd.getCoordinatorTransitions());
		cursorIsTheNewestReport(r);

		movedBySeat(r);                                          // one more move: the next delta is its alone
		RowSnapshot moved = boardRow(b);
		seatReports(r, 300);
		RowSnapshot twice = boardRow(b);
		assertStampedOnly(moved, twice, SHARE_KEYS);
		bd = board(r);
		assertEquals(1_300L, bd.getCoordinatorSharesMicros(), "the counters are the sum of both reports");
		assertEquals(3L, bd.getCoordinatorTransitions());
		cursorIsTheNewestReport(r);
	}

	@Test
	public void withNobodyToShareWithOnlyTheCursorMoves() throws RelizaException {
		Rig r = rig();
		UUID b = r.board().getUuid();
		RowSnapshot before = boardRow(b);
		seatReports(r, 400);
		RowSnapshot after = boardRow(b);
		assertStampedOnly(before, after, SHARE_KEYS);
		AgentBoardData bd = board(r);
		assertEquals(0L, bd.getCoordinatorSharesMicros(), "nobody to give it to");
		assertEquals(0L, bd.getCoordinatorTransitions());
		cursorIsTheNewestReport(r);
	}

	@Test
	public void aNonPositiveDeltaMovesOnlyTheCursorEvenWhenTheSeatMovedTasks() throws Exception {
		Rig r = rig();
		movedBySeat(r);
		movedBySeat(r);
		UUID b = r.board().getUuid();
		RowSnapshot before = boardRow(b);

		Map<UUID, Long> shares = coordinatorShareService.apportionOnce(b, r.seat().getUuid(), 0, ZonedDateTime.now(), WU);
		assertTrue(shares.isEmpty(), "nothing to share");
		assertStampedOnly(before, boardRow(b), SHARE_KEYS);
		AgentBoardData bd = board(r);
		assertEquals(0L, bd.getCoordinatorSharesMicros());
		assertEquals(0L, bd.getCoordinatorTransitions(), "the seat's two moves are counted with a share, not without one");
		assertNotNull(bd.getCoordinatorShareCursor(), "the cursor still advances");
	}

	@Test
	public void theApportionmentGateLetsOneMicroThroughAndStopsZero() throws Exception {
		Rig r = rig();
		AgentTaskData t = movedBySeat(r);
		UUID b = r.board().getUuid();

		coordinatorShareService.apportionAfterCommit(b, r.seat().getUuid(), 0, ZonedDateTime.now(), WU);
		assertNotEquals("number", jdbcTemplate.queryForObject(
				"select jsonb_typeof(record_data->'coordinatorShareCursor') from rearm.agent_boards where uuid = ?", String.class, b),
				"a zero delta apportions nothing and leaves the cursor where it was");
		assertEquals(0L, board(r).getCoordinatorSharesMicros());

		coordinatorShareService.apportionAfterCommit(b, r.seat().getUuid(), 1, ZonedDateTime.now(), WU);
		assertEquals(1L, jdbcTemplate.queryForObject("select cast(record_data->>'coordinatorEstimateMicros' as bigint) from rearm.agent_tasks where uuid = ?",
				Long.class, t.getUuid()), "one micro goes to the one task the seat moved");
		AgentBoardData bd = board(r);
		assertEquals(1L, bd.getCoordinatorSharesMicros());
		assertEquals(1L, bd.getCoordinatorTransitions());
		assertEquals("number", jdbcTemplate.queryForObject(
				"select jsonb_typeof(record_data->'coordinatorShareCursor') from rearm.agent_boards where uuid = ?", String.class, b));
	}

	// ---------- 8.1 A4: the soft-alert flag ----------

	@Test
	public void markSoftAlertPostedIsOneStampAndThenNothing() throws RelizaException {
		Rig r = rig();
		UUID b = r.board().getUuid();
		assertFalse(board(r).isSoftAlertPosted());
		RowSnapshot before = boardRow(b);

		AgentBoardData first = agentBoardService.markSoftAlertPosted(b, WU);
		assertTrue(first.isSoftAlertPosted(), "the returned board is the refreshed row");
		RowSnapshot marked = boardRow(b);
		assertStampedOnly(before, marked, Set.of("softAlertPosted"));
		assertTrue(board(r).isSoftAlertPosted());

		AgentBoardData second = agentBoardService.markSoftAlertPosted(b, WU);
		assertTrue(second.isSoftAlertPosted());
		RowSnapshot again = boardRow(b);
		assertEquals(marked, again, "the second mark changes nothing: the WHERE held");

		// A whole-board content save keeps the flag the stamp set, and a mark after it is still nothing.
		String description = "tpf105 " + UUID.randomUUID();
		agentBoardService.updateBoard(b, description, null, null, null, null, null, null, null, WU);
		RowSnapshot saved = boardRow(b);
		assertContentSave(again, saved);
		assertTrue(board(r).isSoftAlertPosted(), "the content save kept the stamped flag");
		agentBoardService.markSoftAlertPosted(b, WU);
		assertEquals(saved, boardRow(b), "a mark after the content save is still nothing");
	}

	// ---------- 8.2: content saves still audit, and the stamp rides into the trail with the next one ----------

	@Test
	@SuppressWarnings("unchecked")
	public void aContentSaveBetweenTwoStampsIsTheOneRevisionAndCarriesTheFirstStamp() throws RelizaException {
		Rig r = rig();
		movedBySeat(r);
		UUID b = r.board().getUuid();
		RowSnapshot start = boardRow(b);

		seatReports(r, 1_000);                                   // stamp 1
		RowSnapshot stamped = boardRow(b);
		assertStampedOnly(start, stamped, SHARE_KEYS);

		agentBoardService.updateBoard(b, "tpf105 " + UUID.randomUUID(), null, null, null, null, null, null, null, WU);
		RowSnapshot saved = boardRow(b);                         // the content save: updateBoard (AgentBoardService 766)
		assertContentSave(stamped, saved);
		Map<String, Object> copy = Utils.OM.readValue(jdbcTemplate.queryForObject(
				"select cast(revision_record_data as text) from rearm.audit where entity_name = ? and entity_uuid = ? and revision = ?",
				String.class, TableName.AGENT_BOARDS.toString(), b, stamped.revision()), Map.class);
		assertEquals(stamped.recordData(), copy, "the audit row is the board as it was before the content save");
		assertEquals(1_000L, ((Number) copy.get("coordinatorSharesMicros")).longValue(), "the first stamp rode into the trail");

		seatReports(r, 300);                                     // stamp 2 (the open-tasks fallback)
		RowSnapshot end = boardRow(b);
		assertStampedOnly(saved, end, SHARE_KEYS);
		assertEquals(start.revision() + 1, end.revision(), "one revision over stamp, content save, stamp");
		assertEquals(start.auditRows() + 1, end.auditRows(), "one audit row over the three");
		assertEquals(1_300L, board(r).getCoordinatorSharesMicros(), "the live row carries the second stamp");
	}
}

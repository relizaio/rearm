/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertContentSave;
import static io.reliza.testsupport.StampAssertions.assertOnlyNestedKeyChanged;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.componentNames;
import static io.reliza.testsupport.StampAssertions.returnsOf;
import static io.reliza.testsupport.StampAssertions.signOffsOf;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
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

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.BaseHead;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.Promotion;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageAck;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The task's bookkeeping writers stamp in place (TPF-71 / TPF-105, architecture round 3, 8.1 A1, the
 * task half of A2, 8.2 and 8.4): the usage refresh of closed hops and the coordinator share add no
 * revision, no audit row and no last_updated_date, touch only their keys, and leave every component
 * of a sign-off or a return but its usage as it was; a content save still audits.
 *
 * <p>The rig is AgentSessionUsageRefreshIntegrationTest's (91-164), with the coordinator seat held by a
 * session the test keeps so it can report as the seat (AgentCoordinatorShareIntegrationTest 97-147).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTaskBookkeepingStampTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private HopUsageRefresher hopUsageRefresher;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private JdbcTemplate jdbcTemplate;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/tpf105-task";
	private static final String DOCS_SOURCE = "github:acme/tpf105-task-docs";
	private static final String DOCS = "https://github.com/acme/tpf105-task-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(10500);
	private static final long ALLOWANCE = 1_000L;

	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session, AgentSessionData seat,
			ModelOntologyData model, String modelName) {
		AgentActor seatActor() {
			return AgentActor.ofSession(seat.getUuid());
		}
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "tpf105-" + UUID.randomUUID(),
				"task stamps", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder",
				"build it", 10, null, false, true, null, null, null, null, List.of(), List.of(),
				null, ALLOWANCE), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData coord = register(org, "coord");
		AgentSessionData seat = open(org, coord);
		agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.getUuid(), coord.getUuid(), WU);
		String modelName = "tpf105-model-" + UUID.randomUUID();
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), modelName, null, WU).model();
		m = modelOntologyService.addModelPricing(m.getUuid(), new PricingEntry(null, ZonedDateTime.now().minusDays(1),
				null, "USD", PricingUnit.PER_MILLION_TOKENS, 1_000_000L, 0L, 0L, 0L, null, null, null, null, null, null), WU);
		AgentData worker = register(org, "w");
		return new Rig(board, worker, open(org, worker), seat, m, modelName);
	}

	private AgentData register(Organization org, String prefix) throws RelizaException {
		return agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				prefix + "-" + UUID.randomUUID(), null, null, null, WU);
	}

	private AgentSessionData open(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "test session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	/** A task the rig's session holds as coder, authorised by {@code by}. */
	private AgentTaskData assigned(Rig r, AgentActor by) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		t = agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, by, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return reload(t);
	}

	private AgentTaskData assigned(Rig r) throws RelizaException {
		return assigned(r, COORD);
	}

	private AgentTaskData signedOff(Rig r, AgentTaskData t) throws RelizaException {
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
	}

	/** A task the seat authorised: one coordinator-caused transition. */
	private AgentTaskData movedBySeat(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				r.seatActor(), WU);
	}

	/** The worker reports {@code tokens} input tokens on {@code t}, which price at that many micros. */
	private UsageAck report(Rig r, AgentTaskData t, long tokens) throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(r.session().getUuid()).orElseThrow();
		return usageService.report(fresh, new UsageReport(r.session().getUuid(), null, System.nanoTime(),
				SessionUsageSource.TRANSCRIPT, ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null,
				t.getUuid(), Map.of(), List.of(new UsageLine(r.modelName(), null, 0L, 1, tokens, 0, 0, 0, null, tokens,
						tokens, null))), WU);
	}

	/** The seat reports {@code micros} input tokens, which price at that many micros and are apportioned. */
	private UsageAck seatReports(Rig r, long micros) throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(r.seat().getUuid()).orElseThrow();
		return usageService.report(fresh, new UsageReport(r.seat().getUuid(), null, System.nanoTime(),
				SessionUsageSource.TRANSCRIPT, ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1,
				60, null, null, Map.of(), List.of(new UsageLine(r.modelName(), null, 0L, 1, micros, 0, 0, 0,
						null, micros, micros, null))), WU);
	}

	private RowSnapshot task(UUID uuid) {
		return snapshot(jdbcTemplate, TableName.AGENT_TASKS, uuid);
	}

	/** The audit copy stored for {@code revision} of the task. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> auditCopy(UUID uuid, int revision) {
		String text = jdbcTemplate.queryForObject("select cast(revision_record_data as text) from rearm.audit "
				+ "where entity_name = ? and entity_uuid = ? and revision = ?", String.class,
				TableName.AGENT_TASKS.toString(), uuid, revision);
		return Utils.OM.readValue(text, Map.class);
	}

	private AgentTaskData tagged(AgentTaskData t) throws RelizaException {
		return agentTaskService.setTags(t.getUuid(), List.of(new CommonVariables.TagRecord("tpf105",
				UUID.randomUUID().toString(), CommonVariables.Removable.YES)), WU);
	}

	// ---------- 8.1 A1: the usage refresh of closed hops ----------

	@Test
	public void refreshTaskMovesTheUsageWithoutARevisionOrAnAuditRow() throws Exception {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));            // a real coder sign-off by r.session(): usage reports 0
		AgentTaskData td = reload(t);
		SignOff real = td.getSignOffs().get(0);
		ZonedDateTime at = ZonedDateTime.now();
		UUID foreign = UUID.randomUUID();                       // a session that never reports: its element must not move
		SignOff full = new SignOff(real.role(), real.roleUuid(), real.agent(), real.session(), real.assignedAt(),
				real.signedOffAt(), SignOffOutcome.PASSED, "note-" + UUID.randomUUID(), "prompt-" + UUID.randomUUID(),
				AgentActor.ofUser(UUID.randomUUID(), "operator-" + UUID.randomUUID()), real.usage(),
				List.of(UUID.randomUUID()), List.of(UUID.randomUUID()),
				List.of(new Promotion(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED, null),
						new Promotion(UUID.randomUUID(), null, "guard-" + UUID.randomUUID())),
				Boolean.TRUE, UUID.randomUUID(), Boolean.FALSE,
				Map.of("rearm-saas", new BaseHead(UUID.randomUUID(), "main", "0123abcd", at)));
		SignOff other = new SignOff(real.role(), real.roleUuid(), UUID.randomUUID(), foreign, at.minusHours(2),
				at.minusHours(1), SignOffOutcome.REJECTED, "note-" + UUID.randomUUID(), "prompt-" + UUID.randomUUID(),
				AgentActor.ofUser(UUID.randomUUID(), "operator-" + UUID.randomUUID()),
				new HopUsage(1, 2, 3, 4, 1, 1, 1, 5L, List.of(UUID.randomUUID()), true, 7L, at.minusHours(1)),
				List.of(UUID.randomUUID()), List.of(UUID.randomUUID()),
				List.of(new Promotion(UUID.randomUUID(), null, "guard-" + UUID.randomUUID())),
				Boolean.FALSE, UUID.randomUUID(), Boolean.TRUE,
				Map.of("rearm", new BaseHead(UUID.randomUUID(), "main", "89abcdef", at.minusHours(1))));
		TaskReturn ret = new TaskReturn(real.role(), real.roleUuid(), real.agent(), real.session(), TaskReturnReason.OTHER,
				"desc-" + UUID.randomUUID(), at, HopUsage.empty(), List.of(UUID.randomUUID()));
		td.setSignOffs(List.of(full, other));
		td.setReturns(List.of(ret));
		agentTaskService.saveData(td, WU);                      // the seed: one audited content save, before the first snapshot
		RowSnapshot before = task(t.getUuid());
		assertEquals(componentNames(SignOff.class), signOffsOf(before).get(0).keySet(), "the fixture sets every SignOff component");
		assertEquals(componentNames(TaskReturn.class), returnsOf(before).get(0).keySet(), "the fixture sets every TaskReturn component");
		// F-6 (functional round 4): Utils.OM writes a null component as a JSON null, so the key sets above
		// alone would not see a component the fixture left unset; no seeded value may be null.
		for (Map<String, Object> element : List.of(signOffsOf(before).get(0), signOffsOf(before).get(1), returnsOf(before).get(0))) {
			assertTrue(element.values().stream().allMatch(v -> null != v), "the fixture leaves a component null: " + element);
		}

		report(r, t, 300);                                      // the public entry: report -> afterCommit -> refreshTask, filled = 2
		RowSnapshot after = task(t.getUuid());
		assertStampedOnly(before, after, Set.of("signOffs", "returns"));
		assertOnlyNestedKeyChanged(before, after, "signOffs", "usage", Set.of(0));
		assertOnlyNestedKeyChanged(before, after, "returns", "usage", Set.of(0));
		AgentTaskData read = reload(t);                         // the typed side of the same pin: the reader the UI uses
		assertEquals(1, read.getSignOffs().get(0).usage().reports());
		assertNotNull(read.getSignOffs().get(0).usage().refreshedAt());
		assertEquals(1, read.getReturns().get(0).usage().reports());
		assertEquals(1, read.getSignOffs().get(1).usage().reports(), "the foreign session's element is untouched");
		assertEquals(full.reviewedInputs(), read.getSignOffs().get(0).reviewedInputs());
		assertEquals(full.promotions(), read.getSignOffs().get(0).promotions());
		assertEquals(full.baseHeads().keySet(), read.getSignOffs().get(0).baseHeads().keySet());
		assertEquals(full.outcome(), read.getSignOffs().get(0).outcome());
		assertEquals(ret.description(), read.getReturns().get(0).description());
		assertEquals(ret.reason(), read.getReturns().get(0).reason());
	}

	@Test
	public void aRefreshWithNothingNewRewritesNothing() throws Exception {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		report(r, t, 300);                                      // fills the hop: its usage now has a row
		assertEquals(1, reload(t).getSignOffs().get(0).usage().reports());
		jdbcTemplate.update("update rearm.agent_tasks set record_data = jsonb_set(record_data, '{signOffs,0,keptByNobody}', '1'::jsonb) where uuid = ?",
				t.getUuid());                                   // a key the typed read would drop, were the arrays rewritten
		RowSnapshot before = task(t.getUuid());
		assertTrue(signOffsOf(before).get(0).containsKey("keptByNobody"));

		assertEquals(0, hopUsageRefresher.refreshTask(t.getUuid(), r.session().getUuid(), WU), "no hop has a new row");
		assertEquals(before, task(t.getUuid()), "a refresh that fills nothing writes nothing");
	}

	/** A sign-off in the reporting session's hop window, or a foreign one carrying the same window. */
	private SignOff signOffInWindow(SignOff real, UUID agent, UUID session, String note) {
		return new SignOff(real.role(), real.roleUuid(), agent, session, real.assignedAt(), real.signedOffAt(),
				SignOffOutcome.PASSED, note, "prompt-" + UUID.randomUUID(),
				AgentActor.ofUser(UUID.randomUUID(), "operator-" + UUID.randomUUID()), HopUsage.empty(),
				List.of(UUID.randomUUID()), List.of(UUID.randomUUID()),
				List.of(new Promotion(UUID.randomUUID(), null, "guard-" + UUID.randomUUID())),
				Boolean.TRUE, UUID.randomUUID(), Boolean.FALSE,
				Map.of("rearm-saas", new BaseHead(UUID.randomUUID(), "main", "0123abcd", real.signedOffAt())));
	}

	private TaskReturn returnInWindow(SignOff real, UUID agent, UUID session, ZonedDateTime at, String description) {
		return new TaskReturn(real.role(), real.roleUuid(), agent, session, TaskReturnReason.OTHER, description, at,
				HopUsage.empty(), List.of(UUID.randomUUID()));
	}

	@Test
	public void aRefreshFillsEveryHopOfTheReportingSessionAndNoHopOfAnotherAndCountsThem() throws Exception {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		AgentTaskData td = reload(t);
		SignOff real = td.getSignOffs().get(0);
		ZonedDateTime at = ZonedDateTime.now();
		UUID foreign = UUID.randomUUID();
		// four of each, foreign / real / foreign / real; the foreign ones carry the real hop's own window and an empty
		// usage, so only the session guard keeps them from being filled from this session's rows
		td.setSignOffs(List.of(signOffInWindow(real, UUID.randomUUID(), foreign, "foreign-1"), signOffInWindow(real, real.agent(), real.session(), "real-1"),
				signOffInWindow(real, UUID.randomUUID(), foreign, "foreign-2"), signOffInWindow(real, real.agent(), real.session(), "real-2")));
		td.setReturns(List.of(returnInWindow(real, UUID.randomUUID(), foreign, at, "foreign-1"), returnInWindow(real, real.agent(), real.session(), at, "real-1"),
				returnInWindow(real, UUID.randomUUID(), foreign, at, "foreign-2"), returnInWindow(real, real.agent(), real.session(), at, "real-2")));
		agentTaskService.saveData(td, WU);
		RowSnapshot before = task(t.getUuid());

		report(r, t, 300);
		RowSnapshot after = task(t.getUuid());
		assertStampedOnly(before, after, Set.of("signOffs", "returns"));
		assertOnlyNestedKeyChanged(before, after, "signOffs", "usage", Set.of(1, 3));
		assertOnlyNestedKeyChanged(before, after, "returns", "usage", Set.of(1, 3));

		// the same hops again, their usage emptied by SQL: a refresh fills the four of the reporting session and says four
		String empty = Utils.OM.writeValueAsString(HopUsage.empty());
		jdbcTemplate.update("update rearm.agent_tasks set record_data = jsonb_set(jsonb_set(jsonb_set(jsonb_set(record_data, "
				+ "'{signOffs,1,usage}', cast(? as jsonb)), '{signOffs,3,usage}', cast(? as jsonb)), "
				+ "'{returns,1,usage}', cast(? as jsonb)), '{returns,3,usage}', cast(? as jsonb)) where uuid = ?",
				empty, empty, empty, empty, t.getUuid());
		assertEquals(4, hopUsageRefresher.refreshTask(t.getUuid(), r.session().getUuid(), WU), "two sign-offs and two returns filled");
		assertEquals(0, hopUsageRefresher.refreshTask(t.getUuid(), r.session().getUuid(), WU), "and nothing left to fill");
		assertEquals(1, reload(t).getSignOffs().get(3).usage().reports());
		assertEquals(0, reload(t).getSignOffs().get(2).usage().reports(), "the foreign hop stays empty");
	}

	@Test
	public void aRefreshWaitsForTheRowLockAndKeepsWhatWasCommittedMeanwhile() throws Exception {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		report(r, t, 300);                                      // fills the hop once
		jdbcTemplate.update("update rearm.agent_tasks set record_data = jsonb_set(record_data, '{signOffs,0,usage}', cast(? as jsonb)) where uuid = ?",
				Utils.OM.writeValueAsString(HopUsage.empty()), t.getUuid());   // and empties it again: a row to fill, a hop to fill it in
		assertEquals(0, reload(t).getSignOffs().get(0).usage().reports());

		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			java.util.concurrent.Future<Integer> refresh = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
				jdbcTemplate.queryForList("select uuid from rearm.agent_tasks where uuid = ? for update", t.getUuid());
				java.util.concurrent.Future<Integer> f = executor.submit(() -> hopUsageRefresher.refreshTask(t.getUuid(), r.session().getUuid(), WU));
				try {
					Thread.sleep(2_500);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertTrue(!f.isDone(), "the refresh waits for the row lock the other transaction holds");
				jdbcTemplate.update("update rearm.agent_tasks set record_data = jsonb_set(record_data, '{signOffs,0,note}', to_jsonb(cast(? as text))) where uuid = ?",
						"edited-while-locked", t.getUuid());
				return f;
			});
			assertEquals(1, refresh.get(60, java.util.concurrent.TimeUnit.SECONDS), "then the hop is filled");
		} finally {
			executor.shutdownNow();
		}
		AgentTaskData read = reload(t);
		assertEquals("edited-while-locked", read.getSignOffs().get(0).note(), "what was committed while the refresh waited is kept");
		assertEquals(1, read.getSignOffs().get(0).usage().reports());
	}

	// ---------- 8.1 A2, the task half: the coordinator share ----------

	@Test
	public void apportionOnceAddsTheEstimateInSqlWithoutARevision() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		AgentTaskData b = movedBySeat(r);
		RowSnapshot beforeA = task(a.getUuid());
		RowSnapshot beforeB = task(b.getUuid());

		seatReports(r, 1_000);                                  // both moved by the seat: 500 each
		RowSnapshot onceA = task(a.getUuid());
		assertStampedOnly(beforeA, onceA, Set.of("coordinatorEstimateMicros"));
		assertStampedOnly(beforeB, task(b.getUuid()), Set.of("coordinatorEstimateMicros"));
		assertEquals(500L, reload(a).getCoordinatorEstimateMicros());
		assertEquals(500L, reload(b).getCoordinatorEstimateMicros());

		seatReports(r, 300);                                    // no new move: the open-tasks fallback, 150 each
		RowSnapshot twiceA = task(a.getUuid());
		assertStampedOnly(beforeA, twiceA, Set.of("coordinatorEstimateMicros"));
		assertEquals(650L, reload(a).getCoordinatorEstimateMicros(), "the second share is added to the first");
		assertEquals(650L, reload(b).getCoordinatorEstimateMicros());
		assertEquals(650L, (long) jdbcTemplate.queryForObject("select cast(record_data->>'coordinatorEstimateMicros' as bigint) "
				+ "from rearm.agent_tasks where uuid = ?", Long.class, a.getUuid()), "stored as a JSON number");
	}

	// ---------- 8.2: content saves still audit, and the stamp rides into the trail with the next one ----------

	@Test
	public void aContentSaveBetweenTwoStampsIsTheOneRevisionAndCarriesTheFirstStamp() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = movedBySeat(r);                       // the only open task on the board
		RowSnapshot start = task(t.getUuid());

		seatReports(r, 1_000);                                  // stamp 1: the estimate is 1000
		RowSnapshot stamped = task(t.getUuid());
		assertStampedOnly(start, stamped, Set.of("coordinatorEstimateMicros"));

		tagged(t);                                              // the content save: setTags (AgentTaskService 1922)
		RowSnapshot saved = task(t.getUuid());
		assertContentSave(stamped, saved);
		Map<String, Object> copy = auditCopy(t.getUuid(), stamped.revision());
		assertEquals(stamped.recordData(), copy, "the audit row is the record as it was before the content save");
		assertEquals(1_000L, ((Number) copy.get("coordinatorEstimateMicros")).longValue(), "the first stamp rode into the trail");

		seatReports(r, 300);                                    // stamp 2: the open-tasks fallback gives it all 300
		RowSnapshot end = task(t.getUuid());
		assertStampedOnly(saved, end, Set.of("coordinatorEstimateMicros"));
		assertEquals(start.revision() + 1, end.revision(), "one revision over stamp, content save, stamp");
		assertEquals(start.auditRows() + 1, end.auditRows(), "one audit row over the three");
		assertEquals(1_300L, reload(t).getCoordinatorEstimateMicros(), "the live row carries the second stamp");
	}

	// ---------- 8.4: the task page's updatedAt is unaffected by A1 and A2 ----------

	@Test
	public void theTasksUpdatedAtMovesOnAContentSaveOnly() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r, r.seatActor()));   // the seat authorised it: it takes the share
		ZonedDateTime updatedAt = reload(t).getUpdatedDate();
		RowSnapshot before = task(t.getUuid());

		report(r, t, 300);                                            // A1
		assertEquals(1, reload(t).getSignOffs().get(0).usage().reports(), "the hop was filled");
		assertEquals(updatedAt, reload(t).getUpdatedDate(), "a usage refresh does not move updatedAt");

		seatReports(r, 400);                                          // A2
		assertNotNull(reload(t).getCoordinatorEstimateMicros(), "the share was written");
		assertEquals(updatedAt, reload(t).getUpdatedDate(), "a coordinator share does not move updatedAt");
		assertEquals(before.lastUpdatedDate(), task(t.getUuid()).lastUpdatedDate(), "to the microsecond");

		// setTags refuses a COMPLETED task ("its tags are history"), and the one-role sign-off completed
		// this one, so the content save is the audited funnel itself with a budget, as a budget edit is.
		AgentTaskData td = reload(t);
		td.setBudgetMicros(5_000L);
		agentTaskService.saveData(td, WU);
		assertNotEquals(updatedAt, reload(t).getUpdatedDate(), "a content save moves updatedAt");
		assertTrue(reload(t).getUpdatedDate().isAfter(updatedAt));
	}
}

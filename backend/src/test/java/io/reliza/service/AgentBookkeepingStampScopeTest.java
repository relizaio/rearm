/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertJsonType;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.util.List;
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
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AgentSessionRepository;
import io.reliza.repositories.AgentTaskRepository;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The bookkeeping stamps change the one row they name and report what they did (TPF-71 / TPF-105,
 * mutation round): each of the six statements leaves a second row of its table byte for byte as it
 * was, and an unknown uuid changes nothing; the soft-alert stamp says 1 when it marked the board and 0
 * when it already was; the counters add to 0 when their keys are missing.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBookkeepingStampScopeTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentBoardRepository agentBoardRepository;
	@Autowired private AgentTaskRepository agentTaskRepository;
	@Autowired private AgentSessionRepository agentSessionRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/tpf105-scope";
	private static final String DOCS_SOURCE = "github:acme/tpf105-scope-docs";
	private static final String DOCS = "https://github.com/acme/tpf105-scope-docs";
	private static final AtomicInteger ISSUE = new AtomicInteger(11700);

	private AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("scope_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "scope-" + UUID.randomUUID(), "scope",
				List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build it", 10, null,
				false, true, null, null, null, null, List.of(), List.of(), null, 1_000L), true, WU);
		return agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
	}

	private AgentTaskData task(AgentBoardData board) throws RelizaException {
		return agentTaskService.register(agentBoardService.getBoardData(board.getUuid()).orElseThrow(),
				SRC + "#" + ISSUE.incrementAndGet(), "work", null, null, null, null, null, null, WU);
	}

	private AgentSessionData session(Organization org) throws RelizaException {
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"scope-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null, "s-" + UUID.randomUUID(),
				"coder", null, null, WU);
	}

	private RowSnapshot row(TableName table, UUID uuid) {
		return snapshot(jdbcTemplate, table, uuid);
	}

	@Test
	public void theTaskStampsChangeTheirOwnRowOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData board = board(org);
		UUID mine = task(board).getUuid(), other = task(board).getUuid();
		RowSnapshot otherBefore = row(TableName.AGENT_TASKS, other), mineBefore = row(TableName.AGENT_TASKS, mine);

		assertEquals(1, agentTaskRepository.addCoordinatorEstimate(mine, 40));
		assertEquals(otherBefore, row(TableName.AGENT_TASKS, other), "addCoordinatorEstimate changed another task");
		assertNotEquals(mineBefore.recordData(), row(TableName.AGENT_TASKS, mine).recordData());

		assertEquals(1, agentTaskRepository.stampHopUsage(mine, "[]", "[]"));
		assertEquals(otherBefore, row(TableName.AGENT_TASKS, other), "stampHopUsage changed another task");

		UUID unknown = UUID.randomUUID();
		assertEquals(0, agentTaskRepository.addCoordinatorEstimate(unknown, 40));
		assertEquals(0, agentTaskRepository.stampHopUsage(unknown, "[]", "[]"));
		assertEquals(otherBefore, row(TableName.AGENT_TASKS, other), "a stamp of an unknown task changed a task");
	}

	@Test
	public void theBoardStampsChangeTheirOwnRowOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID mine = board(org).getUuid(), other = board(org).getUuid();
		RowSnapshot otherBefore = row(TableName.AGENT_BOARDS, other);

		assertEquals(1, agentBoardRepository.stampCoordinatorShares(mine, 30, 2, new BigDecimal("1700000000.5")));
		assertEquals(otherBefore, row(TableName.AGENT_BOARDS, other), "stampCoordinatorShares changed another board");
		assertEquals(1, agentBoardRepository.stampSoftAlertPosted(mine));
		assertEquals(otherBefore, row(TableName.AGENT_BOARDS, other), "stampSoftAlertPosted marked another board");

		UUID unknown = UUID.randomUUID();
		assertEquals(0, agentBoardRepository.stampCoordinatorShares(unknown, 30, 2, new BigDecimal("1700000000.5")));
		assertEquals(0, agentBoardRepository.stampSoftAlertPosted(unknown));
		assertEquals(otherBefore, row(TableName.AGENT_BOARDS, other), "a stamp of an unknown board changed a board");
	}

	@Test
	public void theSessionStampsChangeTheirOwnRowOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID mine = session(org).getUuid(), other = session(org).getUuid();
		RowSnapshot otherBefore = row(TableName.AGENT_SESSIONS, other);

		assertEquals(1, agentSessionRepository.stampUsageTotals(mine, totals(1), true));
		assertEquals(otherBefore, row(TableName.AGENT_SESSIONS, other), "stampUsageTotals changed another session");
		assertEquals(1, agentSessionRepository.stampUsageTotalsObserved(mine, totals(2), false));
		assertEquals(otherBefore, row(TableName.AGENT_SESSIONS, other), "stampUsageTotalsObserved changed another session");

		UUID unknown = UUID.randomUUID();
		assertEquals(0, agentSessionRepository.stampUsageTotals(unknown, "{}", true));
		assertEquals(0, agentSessionRepository.stampUsageTotalsObserved(unknown, "{}", true));
		assertEquals(otherBefore, row(TableName.AGENT_SESSIONS, other), "a stamp of an unknown session changed a session");
	}

	@Test
	public void theSessionStampsWriteTheirKeysAndTheAssertionMovesOneWay() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID s = session(org).getUuid();
		assertEquals("DECLARED", assertion(s));

		assertEquals(1, agentSessionRepository.stampUsageTotals(s, totals(1), true));
		assertEquals(1, requests(s));
		assertEquals("true", mismatch(s));
		assertEquals("DECLARED", assertion(s), "the plain stamp observes nothing");

		assertEquals(1, agentSessionRepository.stampUsageTotalsObserved(s, totals(2), true));
		assertEquals(2, requests(s));
		assertEquals("true", mismatch(s), "the observed stamp writes the flag it is given");
		assertEquals("RUNTIME_OBSERVED", assertion(s));

		assertEquals(1, agentSessionRepository.stampUsageTotalsObserved(s, totals(3), false));
		assertEquals(3, requests(s));
		assertEquals("false", mismatch(s));

		assertEquals(1, agentSessionRepository.stampUsageTotals(s, totals(4), false));
		assertEquals(4, requests(s));
		assertEquals("RUNTIME_OBSERVED", assertion(s), "the plain stamp never writes DECLARED back");
	}

	private String assertion(UUID s) {
		return jdbcTemplate.queryForObject("select record_data->>'modelAssertion' from rearm.agent_sessions where uuid = ?", String.class, s);
	}

	private String mismatch(UUID s) {
		return jdbcTemplate.queryForObject("select record_data->>'modelMismatch' from rearm.agent_sessions where uuid = ?", String.class, s);
	}

	/**
	 * A complete UsageTotals as rebuildSessionTotals writes it, told apart by its requests count: the
	 * sessions this class stamps stay OPEN until the end-of-run org cleanup, and every later reader of
	 * sessions across organisations (the idle sweep) parses them, so a partial object would break it.
	 */
	private static String totals(int requests) throws Exception {
		AgentSessionData.UsageTotals e = AgentSessionData.UsageTotals.empty();
		return Utils.OM.writeValueAsString(new AgentSessionData.UsageTotals(e.inputTokens(), e.outputTokens(),
				e.cacheReadTokens(), e.cacheWriteTokens(), requests, e.turns(), e.toolCalls(), e.wallSeconds(),
				e.reports(), e.byModel(), e.derivedCostMicros(), e.priceVersions(), e.costComplete()));
	}

	private int requests(UUID s) {
		return jdbcTemplate.queryForObject("select cast(record_data->'usageTotals'->>'requests' as integer) from rearm.agent_sessions where uuid = ?",
				Integer.class, s);
	}

	@Test
	public void everyStampedValueIsStoredAsItsJsonType() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData board = board(org);
		UUID boardUuid = board.getUuid(), taskUuid = task(board).getUuid(), sessionUuid = session(org).getUuid();

		assertEquals(1, agentBoardRepository.stampCoordinatorShares(boardUuid, 30, 2, new BigDecimal("1700000000.5")));
		assertJsonType(jdbcTemplate, TableName.AGENT_BOARDS, boardUuid, "coordinatorSharesMicros", "number");
		assertJsonType(jdbcTemplate, TableName.AGENT_BOARDS, boardUuid, "coordinatorTransitions", "number");
		assertJsonType(jdbcTemplate, TableName.AGENT_BOARDS, boardUuid, "coordinatorShareCursor", "number");
		assertEquals(1, agentBoardRepository.stampSoftAlertPosted(boardUuid));
		assertJsonType(jdbcTemplate, TableName.AGENT_BOARDS, boardUuid, "softAlertPosted", "boolean");

		assertEquals(1, agentTaskRepository.addCoordinatorEstimate(taskUuid, 40));
		assertJsonType(jdbcTemplate, TableName.AGENT_TASKS, taskUuid, "coordinatorEstimateMicros", "number");
		assertEquals(1, agentTaskRepository.stampHopUsage(taskUuid, "[]", "[]"));
		assertJsonType(jdbcTemplate, TableName.AGENT_TASKS, taskUuid, "signOffs", "array");
		assertJsonType(jdbcTemplate, TableName.AGENT_TASKS, taskUuid, "returns", "array");

		assertEquals(1, agentSessionRepository.stampUsageTotals(sessionUuid, totals(1), true));
		assertJsonType(jdbcTemplate, TableName.AGENT_SESSIONS, sessionUuid, "usageTotals", "object");
		assertJsonType(jdbcTemplate, TableName.AGENT_SESSIONS, sessionUuid, "modelMismatch", "boolean");
		jdbcTemplate.update("update rearm.agent_sessions set record_data = record_data - 'modelMismatch' where uuid = ?", sessionUuid);
		assertEquals(1, agentSessionRepository.stampUsageTotalsObserved(sessionUuid, totals(2), false));
		assertJsonType(jdbcTemplate, TableName.AGENT_SESSIONS, sessionUuid, "modelMismatch", "boolean");
		assertJsonType(jdbcTemplate, TableName.AGENT_SESSIONS, sessionUuid, "modelAssertion", "string");
	}

	@Test
	public void theSoftAlertStampSaysWhetherItMarkedTheBoard() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID boardUuid = board(org).getUuid();

		assertEquals(1, agentBoardRepository.stampSoftAlertPosted(boardUuid), "marked now");
		assertEquals(0, agentBoardRepository.stampSoftAlertPosted(boardUuid), "already marked");
		assertEquals("true", jdbcTemplate.queryForObject(
				"select record_data->>'softAlertPosted' from rearm.agent_boards where uuid = ?", String.class, boardUuid));

		jdbcTemplate.update("update rearm.agent_boards set record_data = jsonb_set(record_data, '{softAlertPosted}', 'false'::jsonb) where uuid = ?", boardUuid);
		assertEquals(1, agentBoardRepository.stampSoftAlertPosted(boardUuid), "marked from false");
		jdbcTemplate.update("update rearm.agent_boards set record_data = record_data - 'softAlertPosted' where uuid = ?", boardUuid);
		assertEquals(1, agentBoardRepository.stampSoftAlertPosted(boardUuid), "marked from a missing key");
		assertEquals(0, agentBoardRepository.stampSoftAlertPosted(boardUuid), "and then it is marked");
	}

	@Test
	public void theCountersAddToZeroWhenTheirKeysAreMissing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData board = board(org);
		UUID boardUuid = board.getUuid(), taskUuid = task(board).getUuid();
		jdbcTemplate.update("update rearm.agent_boards set record_data = record_data - 'coordinatorSharesMicros' - 'coordinatorTransitions' - 'coordinatorShareCursor' where uuid = ?", boardUuid);
		jdbcTemplate.update("update rearm.agent_tasks set record_data = record_data - 'coordinatorEstimateMicros' where uuid = ?", taskUuid);

		assertEquals(1, agentBoardRepository.stampCoordinatorShares(boardUuid, 7, 2, new BigDecimal("1700000000.123456789")));
		assertEquals(7L, boardNumber(boardUuid, "coordinatorSharesMicros").longValueExact());
		assertEquals(2L, boardNumber(boardUuid, "coordinatorTransitions").longValueExact());
		assertEquals(0, new BigDecimal("1700000000.123456789").compareTo(boardNumber(boardUuid, "coordinatorShareCursor")));
		assertEquals(1, agentBoardRepository.stampCoordinatorShares(boardUuid, 5, 1, new BigDecimal("1700000001")));
		assertEquals(12L, boardNumber(boardUuid, "coordinatorSharesMicros").longValueExact());
		assertEquals(3L, boardNumber(boardUuid, "coordinatorTransitions").longValueExact());
		assertEquals(0, new BigDecimal("1700000001").compareTo(boardNumber(boardUuid, "coordinatorShareCursor")));

		assertEquals(1, agentTaskRepository.addCoordinatorEstimate(taskUuid, 5));
		assertEquals(5L, jdbcTemplate.queryForObject(
				"select cast(record_data->>'coordinatorEstimateMicros' as bigint) from rearm.agent_tasks where uuid = ?", Long.class, taskUuid));
		assertEquals(1, agentTaskRepository.addCoordinatorEstimate(taskUuid, 7));
		assertEquals(12L, jdbcTemplate.queryForObject(
				"select cast(record_data->>'coordinatorEstimateMicros' as bigint) from rearm.agent_tasks where uuid = ?", Long.class, taskUuid));
	}

	private BigDecimal boardNumber(UUID boardUuid, String key) {
		return jdbcTemplate.queryForObject("select cast(record_data->>'" + key + "' as numeric) from rearm.agent_boards where uuid = ?",
				BigDecimal.class, boardUuid);
	}
}

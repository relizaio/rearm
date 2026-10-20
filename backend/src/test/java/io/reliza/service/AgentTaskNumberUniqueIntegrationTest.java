/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * One number per board (TPF-43): the unique index {@code agent_tasks_board_number} (V96) refuses a
 * second task with a board's number, and the draw starts past the board's highest stored number, so
 * a counter put below a stored number neither hands it out again nor wedges registration.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTaskNumberUniqueIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final String INDEX = "agent_tasks_board_number";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private JdbcTemplate jdbcTemplate;

	private AgentBoardData board(Organization org, String name, String prefix) throws RelizaException {
		Component target = componentService.createComponent("numbers_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), name, "numbers", List.of(), "coordinate", 4, null,
				target.getUuid(), null, prefix, WU);
	}

	/** A fresh board with prefix TN plus a random suffix: its first task draws 1. */
	private AgentBoardData freshBoard() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String prefix = "TN" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
		return board(org, "Numbers " + UUID.randomUUID(), prefix);
	}

	private AgentBoardData reload(AgentBoardData bd) {
		return agentBoardService.getBoardData(bd.getUuid()).orElseThrow();
	}

	private AgentTaskData task(AgentBoardData bd, String title) throws RelizaException {
		return agentTaskService.register(reload(bd), null, title, null, null, null, null, null, null, WU);
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private int indexCount() {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_indexes WHERE schemaname = 'rearm' AND indexname = ?", Integer.class, INDEX);
	}

	private void setNumber(UUID task, int n, String key) {
		jdbcTemplate.update("UPDATE rearm.agent_tasks SET record_data = jsonb_set(jsonb_set(record_data, '{number}', "
				+ "to_jsonb(?::int)), '{key}', to_jsonb(?::text)) WHERE uuid = ?", n, key, task);
	}

	/** The counter put back by hand, as TPF-42's fixtures do. */
	private void setCounter(UUID board, int n) {
		jdbcTemplate.update("UPDATE rearm.agent_boards SET record_data = jsonb_set(record_data, '{nextTaskNumber}', "
				+ "to_jsonb(?::int)) WHERE uuid = ?", n, board);
	}

	// ---------- 1. the index ----------

	@Test
	@Timeout(60)
	public void aDuplicateNumberIsRefusedOnInsertAndUpdateAndRowsWithoutANumberAreNotIndexed() throws RelizaException {
		assertEquals(1, indexCount(), "the migration applied");
		AgentBoardData bd = freshBoard();
		String prefix = bd.getTaskPrefix();
		AgentTaskData t1 = task(bd, "one");
		AgentTaskData t2 = task(bd, "two");
		assertEquals(1, t1.getNumber());
		assertEquals(2, t2.getNumber());

		DuplicateKeyException onInsert = assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
				"INSERT INTO rearm.agent_tasks (record_data) SELECT record_data FROM rearm.agent_tasks WHERE uuid = ?",
				t1.getUuid()));
		assertTrue(onInsert.getMessage().contains(INDEX), onInsert.getMessage());
		DuplicateKeyException onUpdate = assertThrows(DuplicateKeyException.class,
				() -> setNumber(t2.getUuid(), 1, prefix + "-1"));
		assertTrue(onUpdate.getMessage().contains(INDEX), onUpdate.getMessage());
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.agent_tasks "
				+ "WHERE record_data->>'board' = ? AND record_data->>'number' = '1'", Integer.class,
				bd.getUuid().toString()));
		assertEquals(2, reload(t2).getNumber(), "the refused update changed nothing");

		List<UUID> unnumbered = new ArrayList<>();
		try {
			for (int i = 0; i < 2; i++) {
				UUID u = jdbcTemplate.queryForObject("INSERT INTO rearm.agent_tasks (record_data) "
						+ "SELECT record_data - 'number' - 'key' FROM rearm.agent_tasks WHERE uuid = ? RETURNING uuid",
						UUID.class, t1.getUuid());
				assertNotNull(u);
				unnumbered.add(u);
			}
			assertEquals(2, unnumbered.size(), "two rows without a number sit on one board");
		} finally {
			for (UUID u : unnumbered) jdbcTemplate.update("DELETE FROM rearm.agent_tasks WHERE uuid = ?", u);
		}
	}

	// ---------- 2. registration ----------

	@Test
	@Timeout(60)
	public void registrationAfterACounterRollbackSkipsTheTakenNumberAndSucceeds() throws RelizaException {
		AgentBoardData bd = freshBoard();
		String prefix = bd.getTaskPrefix();
		AgentTaskData t1 = task(bd, "one");
		assertEquals(1, t1.getNumber());
		setCounter(bd.getUuid(), 0);

		AgentTaskData t2 = task(bd, "two");
		assertEquals(2, t2.getNumber(), "the draw starts past the stored 1");
		assertEquals(prefix + "-2", t2.getKey());
		assertEquals(2, reload(bd).getNextTaskNumber());
		AgentTaskData t3 = task(bd, "three");
		assertEquals(3, t3.getNumber());
		assertEquals(prefix + "-3", t3.getKey());

		setCounter(bd.getUuid(), 1);
		assertEquals(4, task(bd, "four").getNumber(), "one below the highest stored number still draws past it");
	}

	// ---------- 3. a board from before keys ----------

	@Test
	@Timeout(60)
	public void numberingABoardFromBeforeKeysDrawsPastItsStoredNumbers() throws RelizaException {
		AgentBoardData bd = freshBoard();
		String prefix = bd.getTaskPrefix();
		AgentTaskData t1 = task(bd, "one");
		AgentTaskData t2 = task(bd, "two");
		AgentTaskData raw = reload(t2);
		raw.setNumber(null);
		raw.setKey(null);
		agentTaskService.saveData(raw, WU);
		AgentBoardData rb = reload(bd);
		rb.setTasksNumbered(false);
		agentBoardService.saveData(rb, WU);
		setCounter(bd.getUuid(), 0);

		agentTaskService.ensureNumbered(bd.getUuid(), WU);
		assertEquals(prefix + "-1", reload(t1).getKey(), "a numbered task keeps its key");
		assertEquals(2, reload(t2).getNumber(), "the unnumbered task is drawn past the stored 1");
		assertEquals(prefix + "-2", reload(t2).getKey());
		assertTrue(reload(bd).isTasksNumbered());
		assertEquals(2, reload(bd).getNextTaskNumber());
	}

	// ---------- 4. split ----------

	@Test
	@Timeout(60)
	public void aSplitAfterACounterRollbackNumbersItsChildrenPastTheStoredNumbers() throws RelizaException {
		AgentBoardData bd = freshBoard();
		String prefix = bd.getTaskPrefix();
		AgentTaskData parent = task(bd, "parent");
		assertEquals(1, parent.getNumber());
		setCounter(bd.getUuid(), 0);

		List<AgentTaskData> children = agentTaskService.split(parent.getUuid(), reload(bd),
				List.of(new SplitChild("child one", null, null, null, null, null),
						new SplitChild("child two", null, null, null, null, null)), COORD, WU);
		assertEquals(List.of(2, 3), children.stream().map(AgentTaskData::getNumber).toList());
		assertEquals(List.of(prefix + "-2", prefix + "-3"), children.stream().map(AgentTaskData::getKey).toList());
		assertEquals(3, reload(bd).getNextTaskNumber());
	}
}

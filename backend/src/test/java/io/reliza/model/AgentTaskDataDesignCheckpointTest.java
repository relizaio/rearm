/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;

/**
 * T-14 (task SPLIT-5): the design checkpoint's record and the role flag in JSONB. Rows written before them read as
 * no record and no flag; the record round-trips; settling it stamps the time.
 */
public class AgentTaskDataDesignCheckpointTest {

	@Test
	public void aTaskWrittenBeforeTheRecordReadsNone() {
		AgentTaskData td = Utils.OM.convertValue(Map.of("title", "old", "status", "QUEUED"), AgentTaskData.class);
		assertNull(td.getDesignCheckpoint());
		assertFalse(td.checkpointPending());
	}

	@Test
	public void theRecordRoundTrips() {
		UUID design = UUID.randomUUID();
		DesignCheckpoint cp = new DesignCheckpoint(ZonedDateTime.now(), "architect", "coder", UUID.randomUUID(),
				List.of(design), DesignCheckpointState.PENDING, null, null, null);
		AgentTaskData td = new AgentTaskData();
		td.setDesignCheckpoint(cp);
		assertTrue(td.checkpointPending());

		Map<?, ?> stored = Utils.OM.convertValue(td, Map.class);
		AgentTaskData back = Utils.OM.convertValue(stored, AgentTaskData.class);
		DesignCheckpoint read = back.getDesignCheckpoint();
		assertEquals("architect", read.afterRole());
		assertEquals("coder", read.beforeRole());
		assertEquals(cp.beforeRoleUuid(), read.beforeRoleUuid());
		assertEquals(List.of(design), read.design());
		assertEquals(DesignCheckpointState.PENDING, read.state());
		assertTrue(cp.reachedAt().isEqual(read.reachedAt()));
		assertTrue(back.checkpointPending());
	}

	@Test
	public void aRecordWithoutDesignReadsAnEmptyList() {
		DesignCheckpoint cp = Utils.OM.convertValue(Map.of("beforeRole", "coder", "state", "PENDING"),
				DesignCheckpoint.class);
		assertEquals(List.of(), cp.design());
		assertTrue(cp.pending());
	}

	@Test
	public void settlingStampsTheTimeAndKeepsTheRest() {
		AgentActor seat = AgentActor.ofSession(UUID.randomUUID());
		ZonedDateTime reached = ZonedDateTime.now().minusHours(1);
		DesignCheckpoint cp = new DesignCheckpoint(reached, "architect", "coder", null, List.of(UUID.randomUUID()),
				DesignCheckpointState.PENDING, null, null, null);
		ZonedDateTime before = ZonedDateTime.now();
		DesignCheckpoint settled = cp.withSettled(DesignCheckpointState.SENT_ON, seat, "authorized for coder");
		assertEquals(DesignCheckpointState.SENT_ON, settled.state());
		assertFalse(settled.pending());
		assertEquals(seat, settled.settledBy());
		assertEquals("authorized for coder", settled.note());
		assertNotNull(settled.settledAt());
		assertFalse(settled.settledAt().isBefore(before));
		assertEquals(reached, settled.reachedAt());
		assertEquals(cp.design(), settled.design());
	}

	@Test
	public void aRoleWrittenBeforeTheFlagReadsFalse() {
		AgentTaskRoleConfigData rc = Utils.OM.convertValue(Map.of("name", "coder", "blindReview", true),
				AgentTaskRoleConfigData.class);
		assertFalse(rc.isCoordinatorCheckpoint());
		rc.setCoordinatorCheckpoint(true);
		AgentTaskRoleConfigData back = Utils.OM.convertValue(Utils.OM.convertValue(rc, Map.class),
				AgentTaskRoleConfigData.class);
		assertTrue(back.isCoordinatorCheckpoint());
	}
}

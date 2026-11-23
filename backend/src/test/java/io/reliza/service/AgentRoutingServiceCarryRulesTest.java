/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskRoleConfigData;

/**
 * TPF-162 mutation round: the build boundary a split reads (the role carrying the coordinator checkpoint before the
 * first role that holds CODE_PUSH, active roles in order), and the carried design on a task that has none or has no
 * time to measure a carry from.
 */
class AgentRoutingServiceCarryRulesTest {

	private static AgentTaskRoleConfigData role(String name, int order, boolean active, boolean checkpoint, boolean codePush) {
		AgentTaskRoleConfigData rc = new AgentTaskRoleConfigData();
		rc.setName(name);
		rc.setOrderIndex(order);
		rc.setActive(active);
		rc.setCoordinatorCheckpoint(checkpoint);
		if (codePush) rc.setRequiredCapabilities(List.of(AgentCapability.CODE_PUSH));
		return rc;
	}

	@Test
	public void theCheckpointRoleIsTheBoundaryEvenWhenACodePushRoleComesFirst() {
		AgentTaskRoleConfigData pusher = role("pusher", 40, true, false, true);
		AgentTaskRoleConfigData flagged = role("flagged", 45, true, true, false);
		assertSame(flagged, AgentRoutingService.buildBoundary(List.of(pusher, flagged)));
		assertSame(pusher, AgentRoutingService.buildBoundary(List.of(pusher, role("after", 50, true, false, false))),
				"without a checkpoint role the first CODE_PUSH holder is");
		assertNull(AgentRoutingService.buildBoundary(List.of(role("plain", 10, true, false, false))));
	}

	@Test
	public void rolesAreReadInOrderAndAnInactiveOneIsNotTheBoundary() {
		AgentTaskRoleConfigData late = role("late", 50, true, true, false);
		AgentTaskRoleConfigData early = role("early", 45, true, true, false);
		AgentTaskRoleConfigData retired = role("retired", 30, false, true, false);
		assertSame(early, AgentRoutingService.buildBoundary(List.of(late, retired, early)));
	}

	@Test
	public void aCarryWithNoTimeToMeasureFromIsNotLive() {
		UUID roleUuid = UUID.randomUUID();
		AgentTaskRoleConfigData rc = role("architect", 10, true, false, false);
		AgentTaskData td = new AgentTaskData();
		td.setCarriedDesign(new CarriedDesign(UUID.randomUUID(), "TPF-1", List.of(),
				List.of(new CarriedRole("architect", roleUuid, ZonedDateTime.now(), null, null, false)), null, null));

		assertFalse(new AgentRoutingService().carryLive(td, rc));
	}

	@Test
	public void aTaskCarryingNothingHasNoStandingDesign() {
		AgentRoutingService routing = new AgentRoutingService();
		assertFalse(routing.carryStanding(null));
		assertFalse(routing.carryStanding(new AgentTaskData()));
		assertEquals(false, routing.carryLive(new AgentTaskData(), role("architect", 10, true, false, false)));
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;

/**
 * TPF-162 mutation round: a blind reader of a split child sees the carried design without the reviewers' sessions, but
 * the person who reviewed, the time and whether a rejection was decided over are not worker words and stay.
 */
class AgentTaskServiceCarriedRedactionTest {

	@Test
	public void theSessionIsDroppedAndThePersonAndTheDecisionStay() {
		UUID roleUuid = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		AgentActor person = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
		ZonedDateTime at = ZonedDateTime.now().minusMinutes(3);
		AgentTaskData td = new AgentTaskData();
		td.setCarriedDesign(new CarriedDesign(UUID.randomUUID(), "TPF-1", List.of(),
				List.of(new CarriedRole("security_reviewer", roleUuid, at, session, person, true)), at, AgentActor.system("seat")));

		AgentTaskData seen = AgentTaskService.redactForBlindRole(td);

		CarriedRole cr = seen.getCarriedDesign().roles().get(0);
		assertNull(cr.session());
		assertEquals(person, cr.reviewedBy());
		assertTrue(cr.decidedOver());
		assertEquals(at, cr.signedOffAt());
		assertEquals(roleUuid, cr.roleUuid());
	}
}

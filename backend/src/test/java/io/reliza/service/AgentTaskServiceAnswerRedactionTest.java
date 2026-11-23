/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerRound;
import io.reliza.model.RearmSpecificationType;

/**
 * Task TPF-171 (D15, T-20b): a blind reader sees an answer's confirmation without the answering session and agent;
 * the role, the rounds, the verdict, who gave it, when, and the coordinator's note stay.
 */
class AgentTaskServiceAnswerRedactionTest {

	@Test
	public void theAnsweringSessionAndAgentAreDroppedAndTheVerdictStays() {
		AnswerRound round = new AnswerRound(UUID.randomUUID(), RearmSpecificationType.ARCHITECTURE, 3, "1.0.2",
				"design/a-3.md", UUID.randomUUID(), 2, "1.0.1", "design/a-2.md");
		AgentActor seat = AgentActor.ofSession(UUID.randomUUID());
		AnswerConfirmation ac = new AnswerConfirmation(ZonedDateTime.now().minusMinutes(5), "architect",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				List.of(round), AnswerConfirmationState.CONFIRMED, seat, ZonedDateTime.now(), "wording only");
		AgentTaskData td = new AgentTaskData();
		td.addAnswerConfirmation(ac);

		AnswerConfirmation seen = AgentTaskService.redactForBlindRole(td).getAnswerConfirmations().get(0);

		assertNull(seen.session());
		assertNull(seen.agent());
		assertEquals(new AnswerConfirmation(ac.answeredAt(), ac.role(), ac.roleUuid(), null, null, ac.askingRole(),
				ac.questionsRelease(), ac.rounds(), ac.state(), ac.settledBy(), ac.settledAt(), ac.note()), seen);
	}
}

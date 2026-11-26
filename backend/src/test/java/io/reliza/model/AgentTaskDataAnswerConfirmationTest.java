/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerRound;
import io.reliza.model.AgentTaskData.AnswerVerdict;

/**
 * Task TPF-171 (D4, D8): the confirmation record on the task. Only CONFIRMED entries keep their rounds out of the
 * staleness reads; only a PENDING last entry waits on the coordinator; a task stored before the record reads none.
 */
class AgentTaskDataAnswerConfirmationTest {

	private static AnswerConfirmation entry(AnswerConfirmationState state, UUID... releases) {
		List<AnswerRound> rounds = Arrays.stream(releases).map(r -> new AnswerRound(r,
				RearmSpecificationType.ARCHITECTURE, 2, "1.0.1", "design/a.md", UUID.randomUUID(), 1, "1.0.0",
				"design/a.md")).toList();
		return new AnswerConfirmation(ZonedDateTime.now(), "architect", null, null, null, null, null, rounds, state, null,
				null, null);
	}

	@Test
	public void onlyConfirmedRoundsAreLeftOutOfTheStalenessReads() {
		UUID confirmed = UUID.randomUUID();
		AgentTaskData td = new AgentTaskData();
		td.addAnswerConfirmation(entry(AnswerConfirmationState.CONFIRMED, confirmed));
		td.addAnswerConfirmation(entry(AnswerConfirmationState.REFUSED, UUID.randomUUID()));
		td.addAnswerConfirmation(entry(AnswerConfirmationState.LAPSED, UUID.randomUUID()));
		td.addAnswerConfirmation(entry(AnswerConfirmationState.PENDING, UUID.randomUUID()));
		assertEquals(Set.of(confirmed), td.confirmedNoChangeRounds());
	}

	@Test
	public void onlyAPendingLastEntryWaits() {
		AgentTaskData td = new AgentTaskData();
		assertNull(td.pendingAnswerConfirmation());
		AnswerConfirmation pending = entry(AnswerConfirmationState.PENDING, UUID.randomUUID());
		td.addAnswerConfirmation(pending);
		assertSame(pending, td.pendingAnswerConfirmation());
		td.settleAnswerConfirmation(AnswerConfirmationState.CONFIRMED, AgentActor.system("seat"), "fine");
		assertNull(td.pendingAnswerConfirmation());
		AnswerConfirmation settled = td.getAnswerConfirmations().get(0);
		assertEquals(AnswerConfirmationState.CONFIRMED, settled.state());
		assertEquals("fine", settled.note());
		assertEquals(pending.rounds(), settled.rounds());
		td.settleAnswerConfirmation(AnswerConfirmationState.REFUSED, AgentActor.system("seat"), "again");
		assertEquals(AnswerConfirmationState.CONFIRMED, td.getAnswerConfirmations().get(0).state(),
				"a settled entry is never settled again");
	}

	@Test
	public void aTaskStoredBeforeTheRecordReadsNone() throws Exception {
		AgentTaskData td = Utils.OM.readValue("{\"status\":\"QUEUED\"}", AgentTaskData.class);
		assertTrue(td.getAnswerConfirmations().isEmpty());
		assertTrue(td.confirmedNoChangeRounds().isEmpty());
	}

	@Test
	public void theVerdictParsesTheSchemaSpelling() {
		assertEquals(AnswerVerdict.CONFIRMED, AnswerVerdict.parse("CONFIRMED"));
		assertEquals(AnswerVerdict.REFUSED, AnswerVerdict.parse("refused"));
		assertEquals(AnswerVerdict.CONFIRMED, AnswerVerdict.parse(AnswerVerdict.CONFIRMED));
		assertNull(AnswerVerdict.parse("maybe"));
		assertNull(AnswerVerdict.parse(null));
	}
}

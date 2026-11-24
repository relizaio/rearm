/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerRound;
import io.reliza.model.AgentTaskData.AnswerVerdict;
import io.reliza.model.RearmSpecificationType;

/**
 * TPF-172 mutation round (TPF-171 D4, D6): the verdict parser reads a padded word, a record keeps its own copy of
 * the rounds it was given and settles with a time, and a stored task whose property is an explicit null reads as an
 * empty list.
 */
class AgentTaskDataAnswerEdgesTest {

	private static AnswerRound round() {
		return new AnswerRound(UUID.randomUUID(), RearmSpecificationType.ARCHITECTURE, 2, "v2", "a.md", UUID.randomUUID(), 1,
				"v1", "a.md");
	}

	@Test
	public void theVerdictParserReadsAPaddedWordInAnyCase() {
		assertEquals(AnswerVerdict.CONFIRMED, AnswerVerdict.parse("  confirmed "));
		assertEquals(AnswerVerdict.REFUSED, AnswerVerdict.parse("Refused"));
		assertEquals(AnswerVerdict.CONFIRMED, AnswerVerdict.parse(AnswerVerdict.CONFIRMED));
		assertNull(AnswerVerdict.parse("MAYBE"));
		assertNull(AnswerVerdict.parse(null));
		assertNull(AnswerVerdict.parse(7));
	}

	@Test
	public void aRecordKeepsItsOwnCopyOfTheRoundsAndNeverNull() {
		List<AnswerRound> given = new ArrayList<>(List.of(round()));
		AnswerConfirmation ac = new AnswerConfirmation(null, "architect", null, null, null, null, null, given,
				AnswerConfirmationState.PENDING, null, null, null);
		given.add(round());
		assertEquals(1, ac.rounds().size(), "a later change of the caller's list does not reach the record");
		assertThrows(UnsupportedOperationException.class, () -> ac.rounds().add(round()));
		assertTrue(new AnswerConfirmation(null, "architect", null, null, null, null, null, null,
				AnswerConfirmationState.PENDING, null, null, null).rounds().isEmpty());
	}

	@Test
	public void settlingStampsATimeAndKeepsTheRest() {
		AnswerConfirmation ac = new AnswerConfirmation(null, "architect", null, null, null, null, null, List.of(round()),
				AnswerConfirmationState.PENDING, null, null, null);
		AnswerConfirmation settled = ac.withSettled(AnswerConfirmationState.CONFIRMED, null, "why");
		assertNotNull(settled.settledAt());
		assertEquals(AnswerConfirmationState.CONFIRMED, settled.state());
		assertEquals("why", settled.note());
		assertEquals(ac.rounds(), settled.rounds());
	}

	@Test
	public void aStoredTaskWithAnExplicitNullListReadsAsEmpty() {
		Map<String, Object> stored = new HashMap<>(Utils.dataToRecord(new AgentTaskData()));
		stored.put("answerConfirmations", null);
		AgentTaskData back = Utils.OM.convertValue(stored, AgentTaskData.class);
		assertNotNull(back.getAnswerConfirmations());
		assertTrue(back.getAnswerConfirmations().isEmpty());
		assertNull(back.pendingAnswerConfirmation());
		assertTrue(back.confirmedNoChangeRounds().isEmpty());
	}
}

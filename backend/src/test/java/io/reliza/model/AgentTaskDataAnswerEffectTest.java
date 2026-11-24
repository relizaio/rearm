/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData.AnswerEffect;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;

/**
 * Task TPF-171 (D1, T-7): what an answering hop said its answer does to the work, read from the sign-off's
 * {@code noChange}. Only a true mark reads NO_CHANGE: absent, null and false read CHANGE, the safe path.
 */
class AgentTaskDataAnswerEffectTest {

	private static SignOff signOff(Boolean noChange) {
		return new SignOff("architect", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now(),
				ZonedDateTime.now(), SignOffOutcome.PASSED, "answered", null, null, null, List.of(), null, null, noChange,
				null);
	}

	@Test
	public void onlyATrueMarkReadsNoChange() {
		assertEquals(AnswerEffect.CHANGE, signOff(null).answerEffect());
		assertEquals(AnswerEffect.CHANGE, signOff(Boolean.FALSE).answerEffect());
		assertEquals(AnswerEffect.NO_CHANGE, signOff(Boolean.TRUE).answerEffect());
	}

	@Test
	public void aStoredSignOffWithoutTheMarkReadsChange() throws Exception {
		String json = Utils.OM.writeValueAsString(signOff(null));
		assertFalse(json.contains("noChange"), json);
		SignOff read = Utils.OM.readValue(json, SignOff.class);
		assertEquals(AnswerEffect.CHANGE, read.answerEffect());
		assertFalse(Utils.OM.writeValueAsString(read).contains("answerEffect"), "the effect is read, never stored");
	}
}

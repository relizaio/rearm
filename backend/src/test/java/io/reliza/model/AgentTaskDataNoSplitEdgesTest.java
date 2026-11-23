/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;

/**
 * TPF-152 mutation round: only a noSplit that is true says the design fits one coder pass; an explicit false, like an
 * absent mark, does not.
 */
public class AgentTaskDataNoSplitEdgesTest {

	private static SignOff signOff(Boolean noSplit) {
		return new SignOff("architect", null, null, null, null, ZonedDateTime.now(), SignOffOutcome.PASSED, "n", null, null,
				null, List.of(), null, null, null, null, null, null, noSplit);
	}

	@Test
	public void onlyATrueNoSplitSaysSo() {
		assertTrue(signOff(Boolean.TRUE).saysNoSplit());
		assertFalse(signOff(Boolean.FALSE).saysNoSplit());
		assertFalse(signOff(null).saysNoSplit());
	}
}

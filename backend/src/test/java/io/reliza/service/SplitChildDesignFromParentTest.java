/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskInput.SplitChild;

/**
 * T-1 of task TPF-161 (built by TPF-162), the service's half: {@code checkSplitChildren}, which the split and a split
 * request's proposal both run, keeps {@code designFromParent} true, false and absent. The fetcher's half is in
 * {@code SplitChildConversionTest}.
 */
class SplitChildDesignFromParentTest {

	static SplitChild child(String title, Boolean designFromParent) {
		return new SplitChild(title, null, null, null, null, List.of(), null, null, null, designFromParent);
	}

	@Test
	void theCheckKeepsTheFlagAsGiven() throws RelizaException {
		List<SplitChild> out = AgentTaskService.checkSplitChildren(List.of(child(" part 1 ", Boolean.TRUE),
				child("part 2", Boolean.FALSE), child("part 3", null)));
		assertEquals(Boolean.TRUE, out.get(0).designFromParent());
		assertEquals("part 1", out.get(0).title(), "the rest of the check still runs");
		assertEquals(Boolean.FALSE, out.get(1).designFromParent());
		assertNull(out.get(2).designFromParent());
	}

	@Test
	void theNineFieldConstructorLeavesItAbsent() {
		assertNull(new SplitChild("part 1", null, null, null, null, List.of(), null, null, null).designFromParent());
		assertNull(new SplitChild("part 1", null, null, null, null, List.of()).designFromParent());
	}
}

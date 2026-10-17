/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ElementFamilies;
import io.reliza.model.RearmSpecificationType;

/**
 * BOARD_INVESTIGATION_REPORT's element grammar is references only (task RD4-12, design §3.1 and §5.2): no default family
 * defines ids in it, it is not among the prose types a board's own family defaults to, and a board cannot name it in
 * definedIn.
 */
class InvestigationReportElementsTest {

	private static final RearmSpecificationType REPORT = RearmSpecificationType.BOARD_INVESTIGATION_REPORT;

	@Test
	void noFamilyDefinesItsIdsByDefault() {
		Map<String, List<RearmSpecificationType>> lists = new AgentBoardData().getEffectiveElementFamilyDefinedIn();
		assertFalse(ElementFamilies.definesAnything(lists, REPORT));
		assertFalse(ElementFamilies.PROSE_TYPES.contains(REPORT));
		assertTrue(ElementFamilies.REFERENCE_ONLY_TYPES.contains(REPORT));
		assertFalse(ElementFamilies.defaultDefinedIn("a-family-of-the-boards-own").contains(REPORT));
	}

	@Test
	void aBoardCannotMakeItDefineIds() {
		RelizaException e = assertThrows(RelizaException.class, () -> AgentBoardService.elementFamilies(
				Map.of("RISK", Map.of("family", "risk", "definedIn", List.of("BOARD_INVESTIGATION_REPORT")))));
		assertTrue(e.getMessage().contains("references only"), e.getMessage());
	}
}

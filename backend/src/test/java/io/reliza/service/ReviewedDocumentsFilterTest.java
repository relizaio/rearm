/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.DocumentRef;

/**
 * What a reviewer's pass may promote (task 32b071f5): another role's work only. The three
 * exclusions, one by one, including the board's own rounds, which no session published.
 */
class ReviewedDocumentsFilterTest {

	private static final UUID HOP = UUID.randomUUID();
	private static final Set<RearmSpecificationType> REVIEWER = Set.of(RearmSpecificationType.BOARD_REVIEW_ITEMS);

	private static DocumentRef doc(RearmSpecificationType spec, UUID session) {
		return new DocumentRef(spec, "p", "d", "text/markdown", null, null, UUID.randomUUID(), session, 1, null);
	}

	@Test
	void anotherRolesDocumentIsPromoted() {
		assertTrue(AgentTaskService.anotherRolesWork(doc(RearmSpecificationType.ARCHITECTURE, UUID.randomUUID()), HOP, REVIEWER));
	}

	@Test
	void thisHopsOwnRoundIsNot() {
		assertFalse(AgentTaskService.anotherRolesWork(doc(RearmSpecificationType.ARCHITECTURE, HOP), HOP, REVIEWER),
				"published by this very session, whatever its kind");
	}

	@Test
	void aKindTheRoleProducesIsNotWhoeverPublishedIt() {
		assertFalse(AgentTaskService.anotherRolesWork(doc(RearmSpecificationType.BOARD_REVIEW_ITEMS, UUID.randomUUID()), HOP, REVIEWER));
	}

	@Test
	void aBoardRoundIsNot() {
		assertFalse(AgentTaskService.anotherRolesWork(doc(RearmSpecificationType.BOARD_QUESTIONS, null), HOP, REVIEWER),
				"an answer or unwind round is nobody's work to review");
		assertFalse(AgentTaskService.anotherRolesWork(null, HOP, REVIEWER), "not a document");
	}
}

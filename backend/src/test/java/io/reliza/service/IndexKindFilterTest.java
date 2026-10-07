/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.DocumentRef;

/** Which rounds are of the kind they are filed under (task bc7fc25a). */
class IndexKindFilterTest {

	private static DocumentRef filed(RearmSpecificationType spec, RearmSpecificationType kind) {
		BoardReviewItemIndex idx = new BoardReviewItemIndex(kind, 1, null, null, List.of(), null);
		return new DocumentRef(spec, null, null, null, null, null, UUID.randomUUID(), null, 1, idx);
	}

	@Test
	void aQuestionsRoundIsOne() {
		assertTrue(AgentDocumentService.indexIsOfItsKind(filed(RearmSpecificationType.BOARD_QUESTIONS, RearmSpecificationType.BOARD_QUESTIONS)));
	}

	@Test
	void aReviewItemIndexFiledAsQuestionsIsNot() {
		assertFalse(AgentDocumentService.indexIsOfItsKind(filed(RearmSpecificationType.BOARD_QUESTIONS, RearmSpecificationType.BOARD_TEST_REPORT)));
		assertFalse(AgentDocumentService.indexIsOfItsKind(filed(RearmSpecificationType.BOARD_QUESTIONS, RearmSpecificationType.BOARD_REVIEW_ITEMS)));
	}

	@Test
	void aRoundWithoutAKindCountsAsWhatItIsFiledUnder() {
		assertTrue(AgentDocumentService.indexIsOfItsKind(filed(RearmSpecificationType.BOARD_QUESTIONS, null)));
	}
}

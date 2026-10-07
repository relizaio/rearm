/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict;
import io.reliza.model.RearmSpecificationType;

/**
 * Who decided a review item, and the digest that makes a round idempotent (operator-actions D7, D21,
 * D22). Pure functions, so pinned here rather than through a board.
 */
public class ReviewItemAttributionTest {

	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");
	private static final AgentActor SESSION = AgentActor.ofSession(UUID.randomUUID());
	private static final UUID ROUND = UUID.randomUUID();

	private static BoardReviewItem open(String id, int priority) {
		return new BoardReviewItem(id, priority, BoardReviewItemStatus.OPEN, "review item " + id, null, null, null, null, null);
	}

	private static BoardReviewItemIndex index(BoardReviewItem... items) {
		return new BoardReviewItemIndex(RearmSpecificationType.BOARD_REVIEW_ITEMS, null, BoardReviewVerdict.REJECTED, null,
				List.of(items));
	}

	@Test
	public void absentAttributionIsOmittedSoOldRoundsDigestAsBefore() throws Exception {
		String json = Utils.OM.writeValueAsString(open("f1", 1));
		assertFalse(json.contains("decidedBy"), json);
		assertFalse(json.contains("decidedIn"), json);
	}

	@Test
	public void theDigestLeavesDecidedInOut() {
		BoardReviewItem decided = open("f1", 1).decidedBy(PERSON);
		assertEquals(AgentDocumentService.canonicalIndexDigest(index(decided)),
				AgentDocumentService.canonicalIndexDigest(index(decided.withDecidedIn(ROUND))),
				"the round a decision was made in is not known until the round exists");
		// decidedBy is part of the identity: another person's same decision is another round.
		assertFalse(AgentDocumentService.canonicalIndexDigest(index(decided)).equals(
				AgentDocumentService.canonicalIndexDigest(index(open("f1", 1).decidedBy(SESSION)))));
	}

	@Test
	public void everyCopyKeepsTheAttribution() {
		BoardReviewItem f = open("f1", 1).decidedBy(PERSON).withDecidedIn(ROUND);
		for (BoardReviewItem copy : List.of(f.closedBy(BoardReviewItemStatus.RESOLVED, UUID.randomUUID(), "fixed"),
				f.withResolvedBy(UUID.randomUUID().toString()), f.withStatus(BoardReviewItemStatus.WITHDRAWN, "no"),
				f.withPriority(3), f.withLegacyPointerConverted("words"))) {
			assertEquals(PERSON, copy.decidedBy());
			assertEquals(ROUND, copy.decidedIn());
		}
		BoardReviewItem legacy = new BoardReviewItem("f2", 1, BoardReviewItemStatus.RESOLVED, "t", null, "fixed in abc", null, PERSON,
				ROUND);
		BoardReviewItem carried = BoardReviewItemIndex.carryForward(List.of(legacy)).get(0);
		assertNull(carried.resolvedBy());
		assertEquals(PERSON, carried.decidedBy());
		assertEquals(ROUND, carried.decidedIn());
	}

	@Test
	public void anAgentRoundCarriesDecisionsAndRecordsItsOwn() throws RelizaException {
		BoardReviewItemIndex previous = index(open("f1", 1).decidedBy(PERSON).withDecidedIn(ROUND), open("f2", 2),
				open("f3", 3));
		BoardReviewItemIndex next = AgentDocumentService.attributeAgentRound(index(
				open("f1", 1).withStatus(BoardReviewItemStatus.RESOLVED, "fixed"),
				open("f2", 2).withStatus(BoardReviewItemStatus.WITHDRAWN, "wrong"),
				open("f3", 1),
				open("f4", 2)), previous, SESSION);
		BoardReviewItem f1 = next.reviewItems().get(0);
		assertEquals(PERSON, f1.decidedBy(), "resolving is not a decision; the person's stands");
		assertEquals(ROUND, f1.decidedIn());
		assertEquals(SESSION, next.reviewItems().get(1).decidedBy(), "withdrawing is the agent's decision");
		assertNull(next.reviewItems().get(1).decidedIn(), "stamped once the round exists");
		assertEquals(SESSION, next.reviewItems().get(2).decidedBy(), "so is a new priority");
		assertNull(next.reviewItems().get(3).decidedBy(), "a filed item's author is the round's session");
	}

	@Test
	public void anAgentMayRepeatTheAttributionButNotWriteItsOwn() throws RelizaException {
		BoardReviewItemIndex previous = index(open("f1", 1).decidedBy(PERSON).withDecidedIn(ROUND));
		AgentDocumentService.attributeAgentRound(index(open("f1", 1).decidedBy(PERSON).withDecidedIn(ROUND)),
				previous, SESSION);
		assertThrows(RelizaException.class, () -> AgentDocumentService.attributeAgentRound(
				index(open("f1", 1).decidedBy(SESSION)), previous, SESSION));
		assertThrows(RelizaException.class, () -> AgentDocumentService.attributeAgentRound(
				index(open("f1", 1).decidedBy(PERSON).withDecidedIn(UUID.randomUUID())), previous, SESSION));
	}
}

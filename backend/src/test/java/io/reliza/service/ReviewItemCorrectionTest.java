/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentActor;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemLocation;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict;
import io.reliza.model.RearmSpecificationType;

/**
 * A correction (task cac71351): an item a person filed while accepting, open work that never
 * blocks. Pure functions of the index, so pinned here rather than through a board.
 */
public class ReviewItemCorrectionTest {

	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");

	private static BoardReviewItem open(String id, int priority) {
		return new BoardReviewItem(id, priority, BoardReviewItemStatus.OPEN, "review item " + id, null, null, null, null, null);
	}

	private static BoardReviewItemIndex index(BoardReviewItem... items) {
		return new BoardReviewItemIndex(RearmSpecificationType.BOARD_REVIEW_ITEMS, null, BoardReviewVerdict.PASSED, null,
				List.of(items));
	}

	@Test
	public void aCorrectionIsOpenButNeverBlocks() {
		BoardReviewItem correction = open("P-1", 1).decidedBy(PERSON).asCorrection();
		BoardReviewItem blocker = open("f1", 3);
		BoardReviewItemIndex idx = index(correction, blocker);
		assertEquals(List.of(blocker), idx.blockingReviewItems(null), "strict: every open item but the correction");
		assertEquals(List.of(), idx.blockingReviewItems(2), "f1 is below the line, P-1 is a correction");
		assertEquals(List.of(correction, blocker), idx.openReviewItems());
		assertTrue(index(correction).blockingReviewItems(null).isEmpty());
	}

	@Test
	public void theFlagIsWrittenOnlyWhenTrue() throws Exception {
		String plain = Utils.OM.writeValueAsString(open("f1", 1));
		assertFalse(plain.contains("correction"), plain);
		String flagged = Utils.OM.writeValueAsString(open("P-1", 1).asCorrection());
		assertTrue(flagged.contains("\"correction\":true"), flagged);

		BoardReviewItem back = Utils.OM.readValue(flagged, BoardReviewItem.class);
		assertTrue(back.isCorrection());
		BoardReviewItem legacy = Utils.OM.readValue("{\"id\":\"f1\",\"priority\":1,\"status\":\"OPEN\",\"title\":\"t\"}",
				BoardReviewItem.class);
		assertNull(legacy.correction());
		assertFalse(legacy.isCorrection());
		// false is not a state: it reads, writes and digests as absent, so an agent echoing it
		// changes nothing and a round written before corrections keeps its identity.
		BoardReviewItem explicitFalse = Utils.OM.readValue(
				"{\"id\":\"f1\",\"priority\":1,\"status\":\"OPEN\",\"title\":\"t\",\"correction\":false}", BoardReviewItem.class);
		assertNull(explicitFalse.correction());
		assertEquals(AgentDocumentService.canonicalIndexDigest(index(legacy)),
				AgentDocumentService.canonicalIndexDigest(index(explicitFalse)));
		assertFalse(AgentDocumentService.canonicalIndexDigest(index(legacy)).equals(
				AgentDocumentService.canonicalIndexDigest(index(legacy.asCorrection()))),
				"the flag is part of a round's identity");
	}

	/**
	 * Every copy keeps the flag, found by reflection so a copy added later is covered too: a copy
	 * that rebuilt the review item without it would quietly turn a correction into a blocker.
	 */
	@Test
	public void everyCopyKeepsTheFlag() throws Exception {
		BoardReviewItem f = open("P-1", 1).decidedBy(PERSON).asCorrection();
		List<String> copies = new ArrayList<>();
		for (Method m : BoardReviewItem.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())
					|| m.getReturnType() != BoardReviewItem.class) {
				continue;
			}
			Object[] args = new Object[m.getParameterCount()];
			Class<?>[] types = m.getParameterTypes();
			for (int i = 0; i < types.length; i++) args[i] = argumentOf(types[i], f);
			BoardReviewItem copy = (BoardReviewItem) m.invoke(f, args);
			assertTrue(copy.isCorrection(), m.getName() + " drops the correction flag");
			copies.add(m.getName());
		}
		assertTrue(copies.size() >= 10, "copies found: " + copies);
		assertTrue(BoardReviewItemIndex.carryForward(List.of(f.withResolvedBy("legacy words"))).get(0).isCorrection());
		assertFalse(f.withCorrectionOf(open("P-1", 1)).isCorrection(), "withCorrectionOf takes the other's flag");
		assertFalse(f.withAttributionOf(null).isCorrection(), "and a new item has none to take");
	}

	private static Object argumentOf(Class<?> type, BoardReviewItem self) {
		if (type == BoardReviewItemStatus.class) return BoardReviewItemStatus.RESOLVED;
		if (type == UUID.class) return UUID.randomUUID();
		if (type == String.class) return "words";
		if (type == Integer.class) return 3;
		if (type == AgentActor.class) return AgentActor.ofSession(UUID.randomUUID());
		if (type == BoardReviewItemLocation.class) return new BoardReviewItemLocation("a/B.java", 1, null);
		if (type == BoardReviewItem.class) return self;
		throw new IllegalArgumentException("no argument for " + type);
	}
}

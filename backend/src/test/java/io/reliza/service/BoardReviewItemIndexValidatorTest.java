/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict;
import io.reliza.model.RearmSpecificationType;

/**
 * The review item index rules, exercised directly. They are pure data checks, so routing them through
 * the publish path would spend a Spring context to test arithmetic on a map.
 */
class BoardReviewItemIndexValidatorTest {

	private static BoardReviewItem reviewItem(String id, int priority, BoardReviewItemStatus status) {
		return new BoardReviewItem(id, priority, status, "Something is wrong in " + id, null, null, null, null, null);
	}

	private static BoardReviewItemIndex index(BoardReviewVerdict verdict, BoardReviewItem... reviewItems) {
		return new BoardReviewItemIndex(RearmSpecificationType.BOARD_REVIEW_ITEMS, 1, verdict, null,
				new ArrayList<>(List.of(reviewItems)));
	}

	private static void validate(BoardReviewItemIndex index, BoardReviewItemIndex previous) throws RelizaException {
		BoardReviewItemIndexValidator.validate(index, RearmSpecificationType.BOARD_REVIEW_ITEMS, 3, previous);
	}

	@Test
	void aWellFormedRoundPasses() {
		assertDoesNotThrow(() -> validate(index(BoardReviewVerdict.REJECTED,
				reviewItem("F-1", 1, BoardReviewItemStatus.OPEN),
				reviewItem("F-2", 3, BoardReviewItemStatus.RESOLVED)), null));
	}

	@Test
	void anEmptyReviewItemsListIsValid() {
		// A passing review with nothing to say still publishes an index: "no review items" is a claim
		// worth recording, and it is what lets a router distinguish reviewed-and-clean from
		// never-reviewed.
		assertDoesNotThrow(() -> validate(index(BoardReviewVerdict.PASSED), null));
	}

	// ---------- the carry-forward rule ----------

	@Test
	void aRoundThatDropsAnOpenReviewItemIsRefusedWithTheIdNamed() {
		// The rule that matters. Routing reads only the newest round, so a dropped open review item
		// makes the release look clean while the problem stands, and nothing ever surfaces it
		// again. The message names the ids because the reviewer has to put them back by hand.
		BoardReviewItemIndex previous = index(BoardReviewVerdict.REJECTED,
				reviewItem("F-1", 1, BoardReviewItemStatus.OPEN), reviewItem("F-2", 2, BoardReviewItemStatus.OPEN));
		BoardReviewItemIndex current = index(BoardReviewVerdict.REJECTED, reviewItem("F-1", 1, BoardReviewItemStatus.OPEN));

		RelizaException e = assertThrows(RelizaException.class, () -> validate(current, previous));
		assertTrue(e.getMessage().contains("F-2"), "the dropped id must be named: " + e.getMessage());
		assertTrue(!e.getMessage().contains("F-1"), "a carried id must not be reported: " + e.getMessage());
	}

	@Test
	void everyClosedStatusMayBeDropped() {
		// This is how the index stays short: once a review item is closed the round that closed it is
		// the record, and later rounds need not repeat it.
		for (BoardReviewItemStatus closed : List.of(BoardReviewItemStatus.RESOLVED, BoardReviewItemStatus.ACCEPTED,
				BoardReviewItemStatus.WITHDRAWN)) {
			assertTrue(closed.isClosed(), closed + " should count as closed");
			BoardReviewItemIndex previous = index(BoardReviewVerdict.REJECTED, reviewItem("F-9", 2, closed));
			assertDoesNotThrow(() -> validate(index(BoardReviewVerdict.PASSED), previous),
					closed + " should be droppable in the next round");
		}
	}

	@Test
	void closingAReviewItemInThisRoundSatisfiesTheCarryForward() {
		BoardReviewItemIndex previous = index(BoardReviewVerdict.REJECTED, reviewItem("F-1", 1, BoardReviewItemStatus.OPEN));
		assertDoesNotThrow(() -> validate(
				index(BoardReviewVerdict.PASSED, reviewItem("F-1", 1, BoardReviewItemStatus.RESOLVED)), previous));
	}

	@Test
	void reopeningAPreviouslyClosedReviewItemIsAllowed() {
		// A fix that did not hold comes back under the same id -- that is the point of stable ids.
		BoardReviewItemIndex previous = index(BoardReviewVerdict.PASSED, reviewItem("F-1", 1, BoardReviewItemStatus.RESOLVED));
		assertDoesNotThrow(() -> validate(
				index(BoardReviewVerdict.REJECTED, reviewItem("F-1", 1, BoardReviewItemStatus.OPEN)), previous));
	}

	@Test
	void theFirstRoundHasNothingToCarry() {
		assertDoesNotThrow(() -> validate(
				index(BoardReviewVerdict.REJECTED, reviewItem("F-1", 1, BoardReviewItemStatus.OPEN)), null));
	}

	// ---------- rules the type cannot express ----------

	@Test
	void aPriorityOutsideTheOrgsLevelsIsRefused() {
		assertThrows(RelizaException.class, () -> validate(
				index(BoardReviewVerdict.REJECTED, reviewItem("F-1", 4, BoardReviewItemStatus.OPEN)), null));
		assertThrows(RelizaException.class, () -> validate(
				index(BoardReviewVerdict.REJECTED, reviewItem("F-1", 0, BoardReviewItemStatus.OPEN)), null));
	}

	@Test
	void duplicateIdsAreRefused() {
		// Two review items under one id make carry-forward and "resolved by" ambiguous, and a fixer
		// working the list would do one of them twice.
		assertThrows(RelizaException.class, () -> validate(index(BoardReviewVerdict.REJECTED,
				reviewItem("F-1", 1, BoardReviewItemStatus.OPEN), reviewItem("F-1", 2, BoardReviewItemStatus.OPEN)), null));
	}

	@Test
	void theEnvelopeMustMatchTheDocumentType() {
		// A BOARD_TEST_REPORT index attached to a BOARD_REVIEW_ITEMS release would put test failures where
		// a fixer looks for review review items.
		BoardReviewItemIndex mismatched = new BoardReviewItemIndex(RearmSpecificationType.BOARD_TEST_REPORT, 1,
				BoardReviewVerdict.PASSED, null, List.of());
		assertThrows(RelizaException.class, () -> validate(mismatched, null));
	}

	@Test
	void anIndexWithoutAVerdictIsRefused() {
		BoardReviewItemIndex noVerdict = new BoardReviewItemIndex(RearmSpecificationType.BOARD_REVIEW_ITEMS, 1,
				null, null, List.of());
		assertThrows(RelizaException.class, () -> validate(noVerdict, null));
	}

	@Test
	void anIndexIsRequiredAtAll() {
		assertThrows(RelizaException.class, () -> validate(null, null));
	}

	@Test
	void aReviewItemMissingItsEssentialsIsRefused() {
		assertThrows(RelizaException.class, () -> validate(index(BoardReviewVerdict.REJECTED,
				new BoardReviewItem(null, 1, BoardReviewItemStatus.OPEN, "t", null, null, null, null, null)), null));
		assertThrows(RelizaException.class, () -> validate(index(BoardReviewVerdict.REJECTED,
				new BoardReviewItem("F-1", null, BoardReviewItemStatus.OPEN, "t", null, null, null, null, null)), null));
		assertThrows(RelizaException.class, () -> validate(index(BoardReviewVerdict.REJECTED,
				new BoardReviewItem("F-1", 1, null, "t", null, null, null, null, null)), null));
		assertThrows(RelizaException.class, () -> validate(index(BoardReviewVerdict.REJECTED,
				new BoardReviewItem("F-1", 1, BoardReviewItemStatus.OPEN, "  ", null, null, null, null, null)), null));
	}

	// ---------- reads ----------

	@Test
	void openReviewItemsExcludesEverythingClosed() {
		BoardReviewItemIndex i = index(BoardReviewVerdict.REJECTED,
				reviewItem("F-1", 1, BoardReviewItemStatus.OPEN), reviewItem("F-2", 2, BoardReviewItemStatus.RESOLVED),
				reviewItem("F-3", 3, BoardReviewItemStatus.WITHDRAWN), reviewItem("F-4", 1, BoardReviewItemStatus.ACCEPTED),
				reviewItem("F-5", 2, BoardReviewItemStatus.OPEN));
		assertEquals(List.of("F-1", "F-5"), i.openReviewItems().stream().map(BoardReviewItem::id).toList());
	}

	@Test
	void reviewItemsIsNeverNullSoConsumersNeedNoGuard() {
		BoardReviewItemIndex nullList = new BoardReviewItemIndex(RearmSpecificationType.BOARD_REVIEW_ITEMS, 1,
				BoardReviewVerdict.PASSED, null, null);
		assertTrue(nullList.reviewItems().isEmpty());
		assertTrue(nullList.openReviewItems().isEmpty());
	}

	// ---------- deserialisation IS validation ----------

	@Test
	void anUnknownStatusFailsToDeserialiseRatherThanBeingCarriedAsAString() throws Exception {
		// The whole point of typing this. An index naming a status nobody defined used to survive
		// as a plain string that every consumer had to re-check and none of them rejected; it now
		// fails at the boundary, with the document named.
		String json = """
				{"kind":"BOARD_REVIEW_ITEMS","verdict":"REJECTED","reviewItems":[
				  {"id":"F-1","priority":1,"status":"PROBABLY_FINE","title":"t"}]}""";
		assertThrows(Exception.class, () -> Utils.OM.readValue(json, BoardReviewItemIndex.class));
	}

	@Test
	void anUnknownVerdictFailsToDeserialise() {
		String json = """
				{"kind":"BOARD_REVIEW_ITEMS","verdict":"MOSTLY_FINE","reviewItems":[]}""";
		assertThrows(Exception.class, () -> Utils.OM.readValue(json, BoardReviewItemIndex.class));
	}

	@Test
	void aWellFormedIndexRoundTrips() throws Exception {
		String json = """
				{"kind":"BOARD_REVIEW_ITEMS","round":2,"verdict":"REJECTED","reviewItems":[
				  {"id":"F-3","priority":1,"status":"OPEN","title":"null deref",
				   "location":{"path":"a/B.java","line":412}},
				  {"id":"F-1","priority":2,"status":"RESOLVED","title":"index","resolvedBy":"1b2c"}]}""";
		BoardReviewItemIndex i = Utils.OM.readValue(json, BoardReviewItemIndex.class);
		assertEquals(RearmSpecificationType.BOARD_REVIEW_ITEMS, i.kind());
		assertEquals(2, i.round());
		assertEquals(BoardReviewVerdict.REJECTED, i.verdict());
		assertEquals(2, i.reviewItems().size());
		assertEquals(412, i.reviewItems().get(0).location().line());
		assertEquals("1b2c", i.reviewItems().get(1).resolvedBy());
		assertEquals(List.of("F-3"), i.openReviewItems().stream().map(BoardReviewItem::id).toList());
	}

	@Test
	void aTestReportCarriesItsCounts() throws Exception {
		String json = """
				{"kind":"BOARD_TEST_REPORT","verdict":"REJECTED","counts":{"passed":412,"failed":2,"skipped":8},
				 "reviewItems":[{"id":"T-1","priority":1,"status":"OPEN","title":"flaky"}]}""";
		BoardReviewItemIndex i = Utils.OM.readValue(json, BoardReviewItemIndex.class);
		assertEquals(412, i.counts().passed());
		assertEquals(2, i.counts().failed());
		assertEquals(8, i.counts().skipped());
	}

	@Test
	void unknownFieldsAreIgnoredSoAnOlderServerReadsANewerIndex() throws Exception {
		// Forward compatibility: a field a later version adds must not make the document
		// unreadable, or an upgrade would strand every round published after it.
		String json = """
				{"kind":"BOARD_REVIEW_ITEMS","verdict":"PASSED","somethingNew":true,"reviewItems":[
				  {"id":"F-1","priority":1,"status":"OPEN","title":"t","alsoNew":"x"}]}""";
		BoardReviewItemIndex i = Utils.OM.readValue(json, BoardReviewItemIndex.class);
		assertEquals(1, i.reviewItems().size());
	}

	// ---------- the rework point: a review item names an element (elements.md §8) ----------

	private static final java.util.UUID DESIGN = java.util.UUID.randomUUID();
	private static final java.util.UUID INPUT = java.util.UUID.randomUUID();

	private static java.util.Map<String, BoardReviewItemIndexValidator.ElementPosition> known() {
		java.util.Map<String, BoardReviewItemIndexValidator.ElementPosition> m = new java.util.LinkedHashMap<>();
		m.put("REQ-F-012", new BoardReviewItemIndexValidator.ElementPosition("design/arch.md", 14, DESIGN));
		m.put("REQ-F-013", new BoardReviewItemIndexValidator.ElementPosition("design/arch.md", 20, DESIGN));
		m.put("REQ-N-001", new BoardReviewItemIndexValidator.ElementPosition("design/arch.md", 31, DESIGN));
		m.put("DS-4", new BoardReviewItemIndexValidator.ElementPosition("data/model.md", 7, INPUT));
		return m;
	}

	private static BoardReviewItem at(String id, BoardReviewItemIndex.BoardReviewItemLocation where) {
		return new BoardReviewItem(id, 2, BoardReviewItemStatus.OPEN, "about " + id, where, null, null, null, null);
	}

	@Test
	public void aNamedElementIsResolvedAndItsPositionFilledIn() throws RelizaException {
		BoardReviewItemIndex stamped = BoardReviewItemIndexValidator.stampElements(
				index(BoardReviewVerdict.REJECTED, at("F-1", new BoardReviewItemIndex.BoardReviewItemLocation(null, null, null, "REQ-F-012")),
						at("F-2", new BoardReviewItemIndex.BoardReviewItemLocation(null, null, null, "DS-4"))), null, known());
		assertEquals(new BoardReviewItemIndex.BoardReviewItemLocation("design/arch.md", 14, null, "REQ-F-012"),
				stamped.reviewItems().get(0).location());
		assertEquals("data/model.md", stamped.reviewItems().get(1).location().path(), "an element of a bound input resolves too");
	}

	@Test
	public void aPositionTheAuthorWroteIsKept() throws RelizaException {
		BoardReviewItemIndex stamped = BoardReviewItemIndexValidator.stampElements(index(BoardReviewVerdict.REJECTED,
				at("F-1", new BoardReviewItemIndex.BoardReviewItemLocation("design/arch.md", 16, null, "REQ-F-012"))), null, known());
		assertEquals(Integer.valueOf(16), stamped.reviewItems().get(0).location().line());
	}

	@Test
	public void anUnknownElementIsRefusedWithTheNearestIds() {
		RelizaException e = assertThrows(RelizaException.class, () -> BoardReviewItemIndexValidator.stampElements(
				index(BoardReviewVerdict.REJECTED, at("F-1", new BoardReviewItemIndex.BoardReviewItemLocation(null, null, null, "REQ-F-021"))),
				null, known()));
		assertTrue(e.getMessage().contains("names element REQ-F-021"), e.getMessage());
		assertTrue(e.getMessage().contains("(nearest: REQ-F-012, REQ-F-013, REQ-N-001)"), e.getMessage());
	}

	@Test
	public void aCarriedElementIsNotReCheckedButANewOrChangedOneIs() throws RelizaException {
		BoardReviewItemIndex.BoardReviewItemLocation gone = new BoardReviewItemIndex.BoardReviewItemLocation("design/arch.md", 40, null, "REQ-F-099");
		BoardReviewItemIndex previous = index(BoardReviewVerdict.REJECTED, at("F-1", gone));
		// carried as it was: the element has left the document since, and that is not this round's doing
		BoardReviewItemIndex carried = index(BoardReviewVerdict.REJECTED, at("F-1", gone));
		assertEquals(carried, BoardReviewItemIndexValidator.stampElements(carried, previous, known()));
		// the same id pointed at another missing element is this round's
		BoardReviewItemIndex moved = index(BoardReviewVerdict.REJECTED,
				at("F-1", new BoardReviewItemIndex.BoardReviewItemLocation(null, null, null, "REQ-F-098")));
		assertThrows(RelizaException.class, () -> BoardReviewItemIndexValidator.stampElements(moved, previous, known()));
	}

	@Test
	public void aLocationWithoutAnElementIsUntouchedAndOldJsonReads() throws Exception {
		BoardReviewItemIndex plain = index(BoardReviewVerdict.REJECTED,
				at("F-1", new BoardReviewItemIndex.BoardReviewItemLocation("a.java", 3, null)), reviewItem("F-2", 1, BoardReviewItemStatus.OPEN));
		assertEquals(plain, BoardReviewItemIndexValidator.stampElements(plain, null, known()));
		BoardReviewItemIndex old = Utils.OM.readValue("""
				{"kind":"BOARD_REVIEW_ITEMS","verdict":"REJECTED","reviewItems":[
				  {"id":"F-1","priority":1,"status":"OPEN","title":"t","location":{"path":"a.java","line":3}}]}""",
				BoardReviewItemIndex.class);
		assertEquals(null, old.reviewItems().get(0).location().element());
		assertTrue(!Utils.OM.writeValueAsString(old).contains("element"), "a location without one writes as before");
	}
}

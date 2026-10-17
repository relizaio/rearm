/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemLocation;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.RearmSpecificationType;

/**
 * The rules a review item index must satisfy that its TYPE cannot express.
 *
 * <p>Deserialising into {@link BoardReviewItemIndex} already rejects an unknown status or verdict, a
 * non-integer priority and a malformed shape, so none of that is here. What is left is the three
 * things a record cannot state about itself: priorities inside the org's scale, ids unique within
 * one index, and the carry-forward rule between two consecutive rounds.
 *
 * <p>Pure and static: the rules are a data check with no repository or clock in them, so they are
 * tested directly rather than through the publish path they guard.
 *
 * <p><b>The carry-forward rule is the one that matters.</b> Each round must repeat every review item
 * the previous round left open. A round that silently drops one would make the newest release look
 * clean while the problem still exists, and since routing reads only the newest round, the review item
 * would never be seen again. Dropping something already closed is fine and expected -- that is how
 * the index stays short.
 */
public final class BoardReviewItemIndexValidator {

	private BoardReviewItemIndexValidator() {}

	/**
	 * @param index the deserialised index
	 * @param spec which document type this is, so the envelope's kind can be checked against it
	 * @param priorityLevels the org's level count; a priority outside 1..levels is refused
	 * @param previousRound the previous round's index for this task and type, or null for round 1
	 * @throws RelizaException naming what is wrong and, for carry-forward, which ids
	 */
	public static void validate(BoardReviewItemIndex index, RearmSpecificationType spec,
			int priorityLevels, BoardReviewItemIndex previousRound) throws RelizaException {
		if (null == index) {
			throw new RelizaException("A " + spec + " release requires a review item index");
		}
		if (spec != index.kind()) {
			throw new RelizaException("Index kind '" + index.kind()
					+ "' does not match the document type " + spec);
		}
		if (null == index.verdict()) {
			throw new RelizaException("Index verdict is required: PASSED or REJECTED");
		}

		validateTested(index);

		Set<String> ids = new LinkedHashSet<>();
		for (BoardReviewItem f : index.reviewItems()) {
			if (StringUtils.isBlank(f.id())) {
				throw new RelizaException("Every review item needs a non-empty id");
			}
			if (!ids.add(f.id())) {
				// Duplicate ids make carry-forward and "resolved by" ambiguous, and a fixer
				// working the list would silently do one of them twice.
				throw new RelizaException("Duplicate review item id '" + f.id() + "' in the index");
			}
			if (null == f.priority()) {
				throw new RelizaException("Review item " + f.id() + " needs an integer priority");
			}
			if (f.priority() < 1 || f.priority() > priorityLevels) {
				throw new RelizaException("Review item " + f.id() + " has priority " + f.priority()
						+ ", outside this organization's 1.." + priorityLevels + " levels");
			}
			if (null == f.status()) {
				throw new RelizaException("Review item " + f.id() + " needs a status");
			}
			if (StringUtils.isBlank(f.title())) {
				throw new RelizaException("Review item " + f.id() + " needs a title");
			}
			// Only on items this round SETS. A round rewrites the whole list, so items carried
			// from an earlier round may still hold text written before resolvedBy became a
			// pointer; those are converted by BoardReviewItemIndex.carryForward, not refused here --
			// refusing them would block a publish because of what some earlier round wrote.
			if (setsResolvedBy(previousRound, f) && null != f.resolvedBy()
					&& !BoardReviewItemIndex.isUuid(f.resolvedBy())) {
				throw new RelizaException("Review item " + f.id() + " has resolvedBy '" + f.resolvedBy()
						+ "', which is not a release uuid. resolvedBy points at the round that"
						+ " closed the item; the words belong in resolution.");
			}
		}

		List<String> overruled = overruledPersonDecisions(previousRound, index);
		if (!overruled.isEmpty()) {
			throw new RelizaException("Review item(s) " + String.join(", ", overruled) + " were decided by a"
					+ " person. An agent's round may resolve them (OPEN to RESOLVED) but may not change"
					+ " their priority or otherwise their status.");
		}
		List<String> dropped = droppedOpenIds(previousRound, index);
		if (!dropped.isEmpty()) {
			throw new RelizaException("This round drops review item(s) the previous round left open: "
					+ String.join(", ", dropped)
					+ ". Carry them forward, or mark them RESOLVED, ACCEPTED or WITHDRAWN.");
		}
	}

	/** Where an element is defined: its document's path, its line, and the release it is in. */
	public record ElementPosition(String path, Integer line, UUID release) {}

	/**
	 * Resolve the elements review items name (gaps §2.A, elements.md §8) and fill in the file position
	 * each one leaves out, so every reader still has a path and a line.
	 *
	 * <p>Only what this round sets is checked: a review item new in this round, or one whose element
	 * changed. A carried item keeps what an earlier round resolved, even when the element has since
	 * gone from the document -- refusing it would block a publish over what some earlier round wrote.
	 * A path and line written by the author stay as written.
	 *
	 * @param known the elements a review item may name, in the order they win: the task's documents, then
	 *        the releases bound to the current assignment
	 * @throws RelizaException naming the id that resolves nowhere and up to five of the nearest
	 */
	public static BoardReviewItemIndex stampElements(BoardReviewItemIndex index, BoardReviewItemIndex previousRound,
			Map<String, ElementPosition> known) throws RelizaException {
		if (null == index) return null;
		Map<String, String> before = new HashMap<>();
		if (null != previousRound) {
			for (BoardReviewItem p : previousRound.reviewItems()) {
				before.put(p.id(), null == p.location() ? null : p.location().element());
			}
		}
		List<BoardReviewItem> out = new ArrayList<>();
		boolean changed = false;
		for (BoardReviewItem f : index.reviewItems()) {
			String element = null == f.location() ? null : StringUtils.trimToNull(f.location().element());
			if (null == element) {
				out.add(f);
				continue;
			}
			boolean setHere = !before.containsKey(f.id()) || !Objects.equals(before.get(f.id()), element);
			ElementPosition at = known.get(element);
			if (null == at) {
				if (setHere) {
					throw new RelizaException("Review item " + f.id() + " names element " + element
							+ ", which is in none of the task's documents or bound inputs" + nearest(element, known.keySet())
							+ ". Correct the id, or leave element out and keep path and line.");
				}
				out.add(f);
				continue;
			}
			BoardReviewItemLocation stamped = f.location().withPosition(at.path(), at.line());
			changed = changed || !stamped.equals(f.location());
			out.add(f.withLocation(stamped));
		}
		return changed ? new BoardReviewItemIndex(index.kind(), index.round(), index.verdict(), index.counts(), out,
				index.about(), index.tested()) : index;
	}

	/** Up to five ids of the same family, the longest shared prefix first: a wrong id is usually a typo. */
	static String nearest(String element, java.util.Collection<String> ids) {
		String family = ElementIndexValidator.familyPrefix(element);
		List<String> same = ids.stream()
				.filter(id -> null != family && family.equals(ElementIndexValidator.familyPrefix(id)))
				.sorted(java.util.Comparator.comparingInt((String id) -> -sharedPrefix(id, element))
						.thenComparing(java.util.Comparator.naturalOrder()))
				.limit(5).toList();
		return same.isEmpty() ? "" : " (nearest: " + String.join(", ", same) + ")";
	}

	private static int sharedPrefix(String a, String b) {
		int n = 0;
		while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n)) n++;
		return n;
	}

	/**
	 * Whether this round is the one writing {@code resolvedBy} on this review item.
	 *
	 * <p>Unchanged from the previous round means carried, whatever it holds. A new id, a changed
	 * value, or no previous round at all means this round set it and owns the format.
	 */
	private static boolean setsResolvedBy(BoardReviewItemIndex previousRound, BoardReviewItem f) {
		if (null == previousRound) return true;
		for (BoardReviewItem p : previousRound.reviewItems()) {
			if (f.id().equals(p.id())) return !Objects.equals(p.resolvedBy(), f.resolvedBy());
		}
		return true;
	}

	/**
	 * Ids the previous round left open that this round does not mention.
	 *
	 * <p>Separate and package-visible so the rule can be tested on its own: it is the only part of
	 * the validation that compares two documents, and the part a reviewer is most likely to get
	 * wrong by hand.
	 */
	static List<String> droppedOpenIds(BoardReviewItemIndex previousRound, BoardReviewItemIndex index) {
		List<String> dropped = new ArrayList<>();
		if (null == previousRound) return dropped;
		Set<String> present = new LinkedHashSet<>();
		for (BoardReviewItem f : index.reviewItems()) {
			if (StringUtils.isNotBlank(f.id())) present.add(f.id());
		}
		for (BoardReviewItem f : previousRound.reviewItems()) {
			if (StringUtils.isBlank(f.id()) || null == f.status()) continue;
			// Anything the previous round closed may be dropped; anything it left open may not.
			if (!f.status().isClosed() && !present.contains(f.id())) dropped.add(f.id());
		}
		return dropped;
	}

	/**
	 * People outrank agents on review items (operator-actions D7): an item a person decided keeps its
	 * priority, and its status unless the change is OPEN to RESOLVED -- a reviewer still closes a
	 * person-filed review item once the fix lands.
	 */
	static List<String> overruledPersonDecisions(BoardReviewItemIndex previousRound, BoardReviewItemIndex index) {
		if (null == previousRound) return List.of();
		java.util.Map<String, BoardReviewItem> now = new java.util.HashMap<>();
		index.reviewItems().forEach(f -> now.put(f.id(), f));
		List<String> out = new java.util.ArrayList<>();
		for (BoardReviewItem before : previousRound.reviewItems()) {
			if (!before.decidedByPerson()) continue;
			BoardReviewItem after = now.get(before.id());
			if (null == after) continue; // a dropped item is the carry-forward rule's to refuse
			boolean priorityChanged = !java.util.Objects.equals(before.priority(), after.priority());
			boolean statusChanged = before.status() != after.status()
					&& !(BoardReviewItemStatus.OPEN == before.status() && BoardReviewItemStatus.RESOLVED == after.status());
			if (priorityChanged || statusChanged) out.add(before.id());
		}
		return out;
	}

	private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");

	/**
	 * The PR heads a round says it covered (task 3b97ccfd), in shape only: whether each names a
	 * PR the task links, and whether a pass names all of them, is the sign-off's check, where the
	 * task's links are known.
	 */
	static void validateTested(BoardReviewItemIndex index) throws RelizaException {
		if (null == index.tested()) return;
		if (index.kind() != RearmSpecificationType.BOARD_TEST_REPORT && index.kind() != RearmSpecificationType.BOARD_REVIEW_ITEMS) {
			throw new RelizaException("tested names the PR heads a review or test covered; it is for BOARD_TEST_REPORT"
					+ " and BOARD_REVIEW_ITEMS rounds, not " + index.kind());
		}
		Set<String> prs = new LinkedHashSet<>();
		for (BoardReviewItemIndex.TestedHead t : index.tested()) {
			if (null == t || StringUtils.isBlank(t.pr())) {
				throw new RelizaException("Every tested entry needs the PR URL it covers (tested: [{pr, head}])");
			}
			if (null == t.head() || !SHA.matcher(t.head()).matches()) {
				throw new RelizaException("tested head for " + t.pr() + " is '" + t.head()
						+ "'; a head is the commit sha you tested, 7 to 40 hex characters");
			}
			if (!prs.add(AgentDeliveryService.matchKey(t.pr()))) {
				throw new RelizaException("tested names " + t.pr() + " twice; name one head per PR");
			}
		}
	}
}

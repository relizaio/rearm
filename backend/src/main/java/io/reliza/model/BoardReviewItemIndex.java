/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The review item index carried by a {@code BOARD_REVIEW_ITEMS} or {@code BOARD_TEST_REPORT} release.
 *
 * <p>Typed, and that is the point of it. This is the one structure in the agentic stack designed to
 * be read by machines -- a router deciding whether to re-queue a coder, the UI grouping by
 * priority, a coordinator counting three review items against thirty. Left as a {@code Map<String,
 * Object>} every one of those consumers re-parses it, each with its own idea of what a missing
 * field or an odd type means, and the vocabulary drifts silently because nothing declares it.
 *
 * <p>Deserialisation now does most of the validation: an unknown status or verdict fails here
 * rather than being carried as a string nobody checks. {@link io.reliza.service.BoardReviewItemIndexValidator}
 * is left with the rules Jackson cannot express -- priority bounds, unique ids, and the
 * carry-forward rule across rounds.
 *
 * @param kind which document type this index belongs to; checked against the release's
 * @param round 1-based round for display; the authoritative value is on the release
 * @param verdict PASSED or REJECTED
 * @param counts test totals, on a BOARD_TEST_REPORT; absent on a review
 * @param reviewItems one entry per review item, or per failed case on a test report
 * @param about the input these items are about, which is what routes them to the role that
 *        produces it; absent on a BOARD_REVIEW_ITEMS index, where the code under review is meant
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoardReviewItemIndex(
		@JsonProperty("kind") RearmSpecificationType kind,
		@JsonProperty("round") Integer round,
		@JsonProperty("verdict") BoardReviewVerdict verdict,
		@JsonProperty("counts") TestCounts counts,
		@JsonProperty("reviewItems") List<BoardReviewItem> reviewItems,
		@JsonProperty("about") About about,
		@JsonProperty("tested") @JsonInclude(JsonInclude.Include.NON_NULL) List<TestedHead> tested)
		implements Serializable {

	private static final long serialVersionUID = 20260925L;

	/** Never null, so every consumer can iterate without a guard. */
	public BoardReviewItemIndex {
		if (null == reviewItems) reviewItems = List.of();
	}

	/**
	 * An index that says nothing about what it is about.
	 *
	 * <p>Which is every BOARD_REVIEW_ITEMS and BOARD_TEST_REPORT round: the thing under review is the work
	 * itself, and routing falls back to it. Only a BOARD_QUESTIONS round has to name an input.
	 */
	public BoardReviewItemIndex(RearmSpecificationType kind, Integer round, BoardReviewVerdict verdict,
			TestCounts counts, List<BoardReviewItem> reviewItems) {
		this(kind, round, verdict, counts, reviewItems, null, null);
	}

	/** An index that names no tested heads: every round the board cuts, and every BOARD_QUESTIONS round. */
	public BoardReviewItemIndex(RearmSpecificationType kind, Integer round, BoardReviewVerdict verdict,
			TestCounts counts, List<BoardReviewItem> reviewItems, About about) {
		this(kind, round, verdict, counts, reviewItems, about, null);
	}

	/**
	 * The PR head a review or test round covered (task 3b97ccfd): which commit of the linked PR the
	 * verdict is about, so the board can tell when a later push leaves it untested.
	 *
	 * @param pr one of the task's linked PR URLs
	 * @param head the commit sha tested, 7 to 40 hex characters
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TestedHead(@JsonProperty("pr") String pr, @JsonProperty("head") String head)
			implements Serializable {
		private static final long serialVersionUID = 20260925L;

		/** Whether a commit sha is this head: a prefix match either way, case-insensitively. */
		public boolean matches(String sha) {
			if (null == head || null == sha) return false;
			String a = head.toLowerCase(Locale.ROOT);
			String b = sha.toLowerCase(Locale.ROOT);
			return a.startsWith(b) || b.startsWith(a);
		}
	}

	/**
	 * What an index's items are about, and therefore who answers them.
	 *
	 * <p>Routing asks the board which active roles produce this specification type; those are the
	 * roles the question goes to. The release pins WHICH version is being questioned, so an answer
	 * that supersedes it is recognisable as an answer rather than an unrelated new document.
	 *
	 * @param specification the input type in question
	 * @param release the release of it the items refer to
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record About(@JsonProperty("specification") RearmSpecificationType specification,
			@JsonProperty("release") UUID release) implements Serializable {
		private static final long serialVersionUID = 20260921L;
	}

	/**
	 * Items that block: open, not a correction, and urgent enough to matter under the board's
	 * blocking priority. A correction is work a person accepted the task past (task cac71351): it is
	 * open and carried until its producer closes it, but it never holds the task back.
	 *
	 * @param blockingPriority the board's setting; null means strict, where every open item blocks
	 */
	public List<BoardReviewItem> blockingReviewItems(Integer blockingPriority) {
		return openReviewItems().stream()
				.filter(f -> !f.isCorrection())
				.filter(f -> null == blockingPriority || null == f.priority()
						|| f.priority() <= blockingPriority)
				.toList();
	}

	/** Entries still open, corrections included: the current state of the task, since each round carries them forward. */
	public List<BoardReviewItem> openReviewItems() {
		return reviewItems.stream().filter(f -> BoardReviewItemStatus.OPEN == f.status()).toList();
	}

	/**
	 * Copy items into a new round, converting any legacy {@code resolvedBy} text as they pass.
	 *
	 * <p>Every round rewrites the whole list rather than a delta, so a round published today
	 * carries items written before {@code resolvedBy} became a pointer. Validating those on the
	 * way in would refuse a legitimate publish because of data some earlier round wrote, and the
	 * refusal would land on whoever happened to publish next. So they are converted instead: the
	 * text is appended to {@code resolution} and the pointer is cleared, which means the words
	 * survive, nothing needs a migration sweep, and the field has exactly one meaning from here on.
	 *
	 * <p>Every round-cutting path runs items through this. A uuid, or an absent value, passes
	 * through untouched.
	 */
	public static List<BoardReviewItem> carryForward(List<BoardReviewItem> items) {
		if (null == items) return List.of();
		return items.stream().map(BoardReviewItemIndex::convertLegacyPointer).toList();
	}

	private static BoardReviewItem convertLegacyPointer(BoardReviewItem f) {
		if (null == f.resolvedBy() || isUuid(f.resolvedBy())) return f;
		String words = null == f.resolution() || f.resolution().isBlank()
				? f.resolvedBy()
				: f.resolution() + "\n" + f.resolvedBy();
		return f.withLegacyPointerConverted(words);
	}

	/** Whether a {@code resolvedBy} value is the pointer it is now required to be. */
	public static boolean isUuid(String s) {
		if (null == s) return false;
		try {
			UUID.fromString(s);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** What a review concluded. */
	public enum BoardReviewVerdict { PASSED, REJECTED }

	/**
	 * Where a review item stands.
	 *
	 * <p>The three closed statuses are not interchangeable and the difference is the audit trail:
	 * {@code RESOLVED} says the work was done, {@code ACCEPTED} says an operator took the risk
	 * knowingly, {@code WITHDRAWN} says the reviewer was wrong. Only {@code OPEN} must be carried
	 * into the next round.
	 */
	public enum BoardReviewItemStatus {
		OPEN,
		RESOLVED,
		ACCEPTED,
		/**
		 * Left open when a policy stop -- a cycle cap, a no-progress stop or a budget -- completed
		 * the task anyway. Distinct from ACCEPTED, which stays "a person took the risk": nobody
		 * looked at this one, the loop simply ended.
		 */
		POLICY_ACCEPTED,
		WITHDRAWN;

		/** True when a later round may drop the review item: the round that set this said its piece. */
		public boolean isClosed() {
			return this != OPEN;
		}
	}

	/**
	 * One review item.
	 *
	 * <p>{@code resolvedBy} and {@code resolution} divide what used to be one field: a pointer and
	 * the words. Before they were split, the policy round wrote its stop reason into
	 * {@code resolvedBy} and agents following the field's old javadoc ("the release or comment that
	 * closed it") wrote prose there too, so nothing could rely on it being resolvable. The
	 * conversion is {@link #carryForward(List)}, applied wherever a round copies items forward.
	 *
	 * @param id stable within a task across rounds; a reviewer carries an open review item forward
	 *        under the same id rather than minting a new one
	 * @param priority 1 highest, at most the org's level count
	 * @param status see {@link BoardReviewItemStatus}
	 * @param title one line; the reasoning lives in the markdown under a heading carrying the id
	 * @param location optional: where in the code or in a document
	 * @param resolvedBy the release whose round closed it -- a uuid or null, never text. A round is
	 *        validated on the items it SETS; items it carries are converted rather than refused.
	 * @param resolution the words that closed it: a fixer's note, a policy stop's reason, or the
	 *        answer to a question. Free text, optional, one paragraph.
	 * @param correction true on an item a person filed while accepting (task cac71351): open work
	 *        the producer's next round addresses, never blocking. Only a person sets it, and an
	 *        agent's round carries it from the previous one. Written only when true.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BoardReviewItem(
			@JsonProperty("id") String id,
			@JsonProperty("priority") Integer priority,
			@JsonProperty("status") BoardReviewItemStatus status,
			@JsonProperty("title") String title,
			@JsonProperty("location") BoardReviewItemLocation location,
			@JsonProperty("resolvedBy") String resolvedBy,
			@JsonProperty("resolution") String resolution,
			@JsonProperty("decidedBy") @JsonInclude(JsonInclude.Include.NON_NULL) AgentActor decidedBy,
			@JsonProperty("decidedIn") @JsonInclude(JsonInclude.Include.NON_NULL) UUID decidedIn,
			@JsonProperty("correction") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean correction)
			implements Serializable {

		private static final long serialVersionUID = 20260926L;

		/** Stored only when true, so an index written before corrections reads and digests the same. */
		public BoardReviewItem {
			if (!Boolean.TRUE.equals(correction)) correction = null;
		}

		/** A review item that is not a correction: every one filed before corrections, and every agent's. */
		public BoardReviewItem(String id, Integer priority, BoardReviewItemStatus status, String title, BoardReviewItemLocation location,
				String resolvedBy, String resolution, AgentActor decidedBy, UUID decidedIn) {
			this(id, priority, status, title, location, resolvedBy, resolution, decidedBy, decidedIn, null);
		}

		// A review item changes only through the copies below, never by rebuilding it field by field:
		// a rebuild that forgot decidedBy or decidedIn would compile and silently drop who decided
		// it, and the check that people outrank agents could not notice, because it compares
		// against a previous round that would already have lost them (operator-actions D21).

		/** The same review item closed by {@code round}, with the words that closed it. */
		public BoardReviewItem closedBy(BoardReviewItemStatus newStatus, UUID round, String words) {
			return new BoardReviewItem(id, priority, newStatus, title, location,
					null == round ? null : round.toString(), words, decidedBy, decidedIn, correction);
		}

		/** The same review item pointing at the round that closed it. */
		public BoardReviewItem withResolvedBy(String round) {
			return new BoardReviewItem(id, priority, status, title, location, round, resolution, decidedBy, decidedIn,
					correction);
		}

		/** The same review item with a new status and the words that go with it; the pointer is left alone. */
		public BoardReviewItem withStatus(BoardReviewItemStatus newStatus, String words) {
			return new BoardReviewItem(id, priority, newStatus, title, location, resolvedBy, words, decidedBy, decidedIn,
					correction);
		}

		/** The same review item at another priority. */
		public BoardReviewItem withPriority(Integer newPriority) {
			return new BoardReviewItem(id, newPriority, status, title, location, resolvedBy, resolution, decidedBy,
					decidedIn, correction);
		}

		/** The same review item with legacy pointer text moved into the words and the pointer cleared. */
		public BoardReviewItem withLegacyPointerConverted(String words) {
			return new BoardReviewItem(id, priority, status, title, location, null, words, decidedBy, decidedIn, correction);
		}

		/**
		 * The same review item decided by {@code by} in a round not yet cut: {@code decidedIn} is cleared
		 * and stamped once the round exists, like {@code resolvedBy}'s pointer to its own round.
		 */
		public BoardReviewItem decidedBy(AgentActor by) {
			return new BoardReviewItem(id, priority, status, title, location, resolvedBy, resolution, by, null, correction);
		}

		/** The same review item naming the round that decided it. */
		public BoardReviewItem withDecidedIn(UUID round) {
			return new BoardReviewItem(id, priority, status, title, location, resolvedBy, resolution, decidedBy, round,
					correction);
		}

		/**
		 * The same review item with the attribution another copy of it carries (operator-actions D22), and
		 * its correction flag: both are the server's to carry, never an agent's to write (task cac71351).
		 */
		public BoardReviewItem withAttributionOf(BoardReviewItem other) {
			return new BoardReviewItem(id, priority, status, title, location, resolvedBy, resolution,
					null == other ? null : other.decidedBy(), null == other ? null : other.decidedIn(),
					null == other ? null : other.correction());
		}

		/** The same review item with another copy's correction flag, its attribution left alone (task cac71351). */
		public BoardReviewItem withCorrectionOf(BoardReviewItem other) {
			return new BoardReviewItem(id, priority, status, title, location, resolvedBy, resolution, decidedBy, decidedIn,
					null == other ? null : other.correction());
		}

		/** The same review item filed as a correction: work a person accepted the task past (task cac71351). */
		public BoardReviewItem asCorrection() {
			return new BoardReviewItem(id, priority, status, title, location, resolvedBy, resolution, decidedBy, decidedIn,
					Boolean.TRUE);
		}

		/** Whether this is a correction: open work that never blocks (task cac71351). */
		public boolean isCorrection() {
			return Boolean.TRUE.equals(correction);
		}

		/** The same review item at another location. */
		public BoardReviewItem withLocation(BoardReviewItemLocation where) {
			return new BoardReviewItem(id, priority, status, title, where, resolvedBy, resolution, decidedBy, decidedIn,
					correction);
		}

		/** Whether a person decided this review item (operator-actions D7). */
		public boolean decidedByPerson() {
			return null != decidedBy && AgentActor.ActorKind.USER == decidedBy.kind();
		}
	}

	/**
	 * Where a review item points.
	 *
	 * <p>{@code path} and {@code line} for code; {@code ref} for a requirement or design section.
	 * All optional -- plenty of review items are about a whole change rather than a line.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BoardReviewItemLocation(
			@JsonProperty("path") String path,
			@JsonProperty("line") Integer line,
			@JsonProperty("ref") String ref,
			@JsonProperty("element") @JsonInclude(JsonInclude.Include.NON_NULL) String element) implements Serializable {

		private static final long serialVersionUID = 20260920L;

		/** Without an element: every location written before elements (gaps §2.A). */
		public BoardReviewItemLocation(String path, Integer line, String ref) {
			this(path, line, ref, null);
		}

		/** The same location with a file position, where it had none. */
		public BoardReviewItemLocation withPosition(String p, Integer l) {
			return new BoardReviewItemLocation(null == path ? p : path, null == line ? l : line, ref, element);
		}
	}

	/** Totals of a test run. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TestCounts(
			@JsonProperty("passed") Integer passed,
			@JsonProperty("failed") Integer failed,
			@JsonProperty("skipped") Integer skipped) implements Serializable {

		private static final long serialVersionUID = 20260920L;
	}
}

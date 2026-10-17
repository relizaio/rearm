/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.reliza.model.AgentBoardData.BoardEventKind;

/**
 * What a task-path operation wants done to its board, collected and applied after it commits.
 *
 * <p>The task path holds the TASK row lock. Every board write takes the BOARD row lock, and
 * {@code AgentDocumentService.publish} takes them in the other order -- board, then task -- and
 * says so at both of its lock sites. A sign-off that posted an alert inline would hold task and
 * then want board, which is the inversion that deadlocks against a concurrent publish on the same
 * board. So nothing here is applied inside the task transaction: the caller collects effects,
 * commits, and then applies them in a fresh transaction that takes the board lock in the normal
 * order.
 *
 * <p>Everything collected here is advisory or recoverable. An alert that is lost tells a human
 * something they can also see on the task; the budget pause is a signal, and the check that
 * actually refuses work reads the rollups at assignment rather than the lock. Losing atomicity for
 * those is the price of never deadlocking, and it is the right way round.
 */
public class BoardEffects {

	/** One board event to post. */
	/**
	 * @param notifies whether the event also goes out as a notification. False for an ALERT a person is
	 *        already notified of another way -- the human gate's, which the needs-a-person notification
	 *        covers (task RD2-15) -- so the feed keeps it and the inbox gets one row, not two.
	 */
	public record Event(BoardEventKind kind, String message, boolean notifies) {
		public Event(BoardEventKind kind, String message) {
			this(kind, message, true);
		}
	}

	private final UUID board;
	private final List<Event> events = new ArrayList<>();
	private boolean pauseForBudget;
	private String pauseReason;
	private Long softAlertCrossedMicros;
	private RoleHistoryEntry roleHistory;
	private PolicyRound policyRound;
	private CloseQuestions closeQuestions;
	private UUID sessionWorked;
	private final List<UUID> returnReports = new ArrayList<>();

	/** One sign-off's contribution to the affinity table (D32). */
	public record RoleHistoryEntry(UUID board, UUID agent, UUID role) {}

	/**
	 * A policy round to cut: the board closing items nobody is going to answer.
	 *
	 * <p>Collected rather than done inline because publishing is a BOARD-first operation -- it
	 * gets-or-creates the shared document component under the board lock, precisely so two
	 * publishes racing cannot create it twice. Cutting it from the task path would take that lock
	 * second, and two tasks reaching a policy stop on one board at the same moment would
	 * reintroduce the race the publish path was built to prevent.
	 */
	public record PolicyRound(UUID task, String reason) {}

	/**
	 * Questions to close, because the role that produces what they were about has passed (D11).
	 *
	 * <p>Collected for the same reason as the policy round -- publishing is board-first -- and it
	 * is the record rather than the input: the asker is re-queued with the answering release
	 * pinned, and this round is what stops the asked ids reading OPEN forever afterwards.
	 *
	 * @param task the task whose frame popped
	 * @param questionsRelease the round carrying the items that were asked
	 * @param answeringRelease the document whose publication answered them
	 */
	public record CloseQuestions(UUID task, UUID questionsRelease, UUID answeringRelease) {}

	public BoardEffects(UUID board) {
		this.board = board;
	}

	public UUID board() {
		return board;
	}

	public BoardEffects alert(String message) {
		events.add(new Event(BoardEventKind.ALERT, message));
		return this;
	}

	/** An ALERT for the board's feed only: its notification is another's (see {@link Event#notifies}). */
	public BoardEffects alertOnFeed(String message) {
		events.add(new Event(BoardEventKind.ALERT, message, false));
		return this;
	}

	public BoardEffects info(String message) {
		events.add(new Event(BoardEventKind.INFO, message));
		return this;
	}

	/** Lock the board at OPERATOR level. The coordinator cannot lift it, which is the point. */
	public BoardEffects pauseForBudget(String reason) {
		this.pauseForBudget = true;
		this.pauseReason = reason;
		return this;
	}

	/** Record that spend crossed the soft-alert line, so the next crossing alerts once. */
	public BoardEffects softAlertCrossed(long spentMicros) {
		this.softAlertCrossedMicros = spentMicros;
		return this;
	}

	/** The session assigned a task here: the board goes on its boards worked (board-permissions.md D15). */
	public BoardEffects sessionWorked(UUID session) {
		this.sessionWorked = session;
		return this;
	}

	public UUID sessionWorkedEntry() {
		return sessionWorked;
	}

	public BoardEffects roleHistory(UUID agent, UUID role) {
		this.roleHistory = new RoleHistoryEntry(board, agent, role);
		return this;
	}

	public BoardEffects policyRound(UUID task, String reason) {
		this.policyRound = new PolicyRound(task, reason);
		return this;
	}

	public PolicyRound policyRoundEntry() {
		return policyRound;
	}

	public BoardEffects closeQuestions(UUID task, UUID questionsRelease, UUID answeringRelease) {
		this.closeQuestions = new CloseQuestions(task, questionsRelease, answeringRelease);
		return this;
	}

	public CloseQuestions closeQuestionsEntry() {
		return closeQuestions;
	}

	/**
	 * An investigation that completed with its report (task RD4-12), whose report goes back to the task that
	 * commissioned it once this commits: that is another task row, and the path completing the investigation
	 * holds this one's lock.
	 */
	public BoardEffects returnReport(UUID investigation) {
		if (null != investigation && !returnReports.contains(investigation)) returnReports.add(investigation);
		return this;
	}

	public List<UUID> returnReportEntries() {
		return List.copyOf(returnReports);
	}

	public List<Event> events() {
		return List.copyOf(events);
	}

	public boolean isPauseForBudget() {
		return pauseForBudget;
	}

	public String pauseReason() {
		return pauseReason;
	}

	public Long softAlertCrossedMicros() {
		return softAlertCrossedMicros;
	}

	public RoleHistoryEntry roleHistoryEntry() {
		return roleHistory;
	}

	/**
	 * Fold another collection's effects into this one.
	 *
	 * <p>Beside the fields on purpose. The caller used to copy them by hand in another class, and
	 * the list went stale exactly as that arrangement invites: the policy round was added here and
	 * not there, so a policy stop recorded the stop and never cut the round that closes what it
	 * stopped on. A field added above has to be added here, which is one file to keep honest
	 * rather than two, and the diff that adds it touches this method or it is wrong.
	 */
	public BoardEffects absorb(BoardEffects other) {
		if (null == other) return this;
		// Whole, so an event keeps whether it notifies.
		events.addAll(other.events());
		if (other.isPauseForBudget()) pauseForBudget(other.pauseReason());
		if (null != other.softAlertCrossedMicros()) softAlertCrossed(other.softAlertCrossedMicros());
		if (null != other.roleHistoryEntry()) {
			roleHistory(other.roleHistoryEntry().agent(), other.roleHistoryEntry().role());
		}
		if (null != other.policyRoundEntry()) {
			policyRound(other.policyRoundEntry().task(), other.policyRoundEntry().reason());
		}
		other.returnReportEntries().forEach(this::returnReport);
		if (null != other.closeQuestionsEntry()) {
			closeQuestions(other.closeQuestionsEntry().task(),
					other.closeQuestionsEntry().questionsRelease(),
					other.closeQuestionsEntry().answeringRelease());
		}
		return this;
	}

	public boolean isEmpty() {
		return events.isEmpty() && !pauseForBudget && null == softAlertCrossedMicros
				&& null == roleHistory && null == policyRound && null == closeQuestions && null == sessionWorked
				&& returnReports.isEmpty();
	}
}

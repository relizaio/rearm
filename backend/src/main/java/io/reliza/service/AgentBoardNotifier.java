/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskHold;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.NotificationEventType;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.dto.notifications.AgentBoardEventPayload;
import lombok.extern.slf4j.Slf4j;

/**
 * Board events that need a person, as notifications (task 82880ea6, gaps §1.1).
 *
 * <p>Everything that needs a person on a board was recorded on the board feed and stayed there;
 * nobody was told. These go to the notification outbox, where the existing machinery -- dedup,
 * subscriptions with CEL filters, the in-app inbox, Slack, Teams, webhooks, email -- takes over.
 *
 * <p><b>After the write commits, in a transaction of its own, never failing the caller.</b> A
 * rolled-back hold or alert notifies nobody; a notification that cannot be written is logged and
 * the board or task change stands. Writing inside the caller's transaction would let an outbox
 * failure abort a sign-off, since Postgres refuses every statement after a failed one.
 *
 * <p><b>To the people who can act on the board</b> (board-permissions.md D14): every event about a
 * board carries the snapshot of its BOARD_WRITE holders taken when it is written, and the fan-out
 * delivers it to them alone, whatever the org's subscriptions say.
 */
@Slf4j
@Service
public class AgentBoardNotifier {

	@Autowired private ReleaseNotificationSupport releaseNotificationSupport;
	@Autowired private io.reliza.repositories.NotificationOutboxEventRepository outboxRepository;
	@Autowired @Lazy private AgentBoardService agentBoardService;
	@Autowired @Lazy private AgentBoardNotifier self;
	@Autowired @Lazy private AuthorizationService authorizationService;
	@Autowired @Lazy private UserService userService;

	// ---------- what is sent ----------

	/** A board ALERT, or the board being paused. */
	public void boardEvent(AgentBoardData board, AgentBoardData.BoardEventKind kind, String message) {
		if (null == board || null == kind) return;
		if (kind != AgentBoardData.BoardEventKind.ALERT && kind != AgentBoardData.BoardEventKind.PAUSED) return;
		AgentBoardEventPayload p = new AgentBoardEventPayload(board.getUuid(), board.getName(), null, null,
				kind.name(), message, null, null, AgentBoardEventPayload.boardLink(board.getOrg(), board.getUuid()), null);
		afterCommit(board.getOrg(), NotificationEventType.AGENT_BOARD_ALERT,
				alertKey(board.getUuid(), message), p, ALERT_WINDOW);
	}

	/** Whether a hold is one only a person can release. */
	public static boolean needsAPerson(TaskHold h) {
		if (null == h) return false;
		return h.kind() == HoldKind.HUMAN_GATE || h.kind() == HoldKind.QUESTION || h.level() == HoldLevel.OPERATOR;
	}

	/** Whether this save placed a hold a person must release (not one already there). */
	public static boolean placedForAPerson(TaskHold before, TaskHold after) {
		if (!needsAPerson(after)) return false;
		return null == before || before.kind() != after.kind() || before.level() != after.level()
				|| !Objects.equals(before.heldAt(), after.heldAt());
	}

	public void needsPerson(AgentTaskData td) {
		TaskHold h = null == td ? null : td.getHold();
		if (!needsAPerson(h)) return;
		AgentBoardEventPayload p = new AgentBoardEventPayload(td.getBoard(), boardName(td.getBoard()), td.getUuid(),
				td.label(), h.kind().name(), h.reason(), h.kind().name(), null == h.level() ? null : h.level().name(),
				AgentBoardEventPayload.taskLink(td.getUuid()), null);
		afterCommit(td.getOrg(), NotificationEventType.AGENT_TASK_NEEDS_PERSON,
				"board:person:" + td.getUuid() + ":" + h.kind() + ":" + h.heldAt(), p);
	}

	public void returned(AgentTaskData td, TaskReturn tr) {
		if (null == td || null == tr || tr.reason() == TaskReturnReason.SESSION_CLOSED) return;
		AgentBoardEventPayload p = new AgentBoardEventPayload(td.getBoard(), boardName(td.getBoard()), td.getUuid(),
				td.label(), tr.reason().name(),
				"Returned by " + tr.role() + " (" + tr.reason() + ")"
						+ (null == tr.description() || tr.description().isBlank() ? "" : ": " + tr.description().strip()),
				null, null, AgentBoardEventPayload.taskLink(td.getUuid()), null);
		afterCommit(td.getOrg(), NotificationEventType.AGENT_TASK_RETURNED,
				"board:return:" + td.getUuid() + ":" + tr.returnedAt(), p);
	}

	/**
	 * One per crossing: the key buckets on when the hold was placed, so a task that stays held is
	 * told about once, not every minute the scheduler looks.
	 */
	public void queueAge(AgentBoardData board, AgentTaskData td, long ageMinutes) {
		TaskHold h = td.getHold();
		AgentBoardEventPayload p = new AgentBoardEventPayload(board.getUuid(), board.getName(), td.getUuid(),
				td.label(), "QUEUE_AGE", "Waiting " + ageMinutes + " min for a person"
						+ (null == h || null == h.reason() ? "" : ": " + h.reason()),
				null == h ? null : h.kind().name(), null == h || null == h.level() ? null : h.level().name(),
				AgentBoardEventPayload.taskLink(td.getUuid()), ageMinutes);
		afterCommit(board.getOrg(), NotificationEventType.AGENT_TASK_QUEUE_AGE,
				"board:age:" + td.getUuid() + ":" + (null == h ? "" : h.heldAt()), p, ONCE);
	}

	/**
	 * The idle sweep's warning (task 6e7fe6fe): the session closes soon unless something calls with
	 * its id. Once per idle stretch: the key names the activity stamp the warning was about.
	 */
	public void sessionIdleWarning(AgentSessionData sd, String agentName, String message) {
		if (null == sd) return;
		String name = "session " + (null == sd.getTitle() ? sd.getUuid().toString() : sd.getTitle())
				+ (null == agentName ? "" : " of " + agentName);
		AgentBoardEventPayload p = new AgentBoardEventPayload(null, name, null, null, "SESSION_IDLE", message,
				null, null, AgentBoardEventPayload.sessionLink(sd.getUuid()), null);
		afterCommit(sd.getOrg(), NotificationEventType.AGENT_SESSION_IDLE_WARNING,
				"session:idle:" + sd.getUuid() + ":" + sd.getLastActivityAt(), p, ONCE);
	}

	/** How long a person has had this task, when a person must release its hold; null otherwise. */
	public static Long waitingMinutes(AgentTaskData td, ZonedDateTime now) {
		TaskHold h = null == td ? null : td.getHold();
		if (!needsAPerson(h) || null == h.heldAt()) return null;
		return Duration.between(h.heldAt(), now).toMinutes();
	}

	// ---------- how it is sent ----------

	static String alertKey(UUID board, String message) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(String.valueOf(message).getBytes(StandardCharsets.UTF_8));
			return "board:alert:" + board + ":" + HexFormat.of().formatHex(d);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private String boardName(UUID board) {
		try {
			return agentBoardService.getBoardData(board).map(AgentBoardData::getName).orElse(null);
		} catch (Exception e) {
			log.error("Could not read board {} for a notification", board, e);
			return null;
		}
	}

	/**
	 * The same alert text inside a day is one notification, the window a subscription dedups
	 * deliveries in by default: a coordinator repeating itself, or a loop re-raising the same
	 * stop, tells a person once.
	 */
	static final Duration ALERT_WINDOW = Duration.ofDays(1);
	/** One per key, ever: a queue-age crossing is keyed by the hold it is about. */
	static final Duration ONCE = Duration.ofDays(36500);

	private void afterCommit(UUID org, NotificationEventType type, String dedupKey, AgentBoardEventPayload payload) {
		afterCommit(org, type, dedupKey, payload, null);
	}

	/** @param suppressFor write nothing when an event with this key was written within it; null writes always */
	private void afterCommit(UUID org, NotificationEventType type, String dedupKey, AgentBoardEventPayload payload,
			Duration suppressFor) {
		if (null == org) return;
		Runnable write = () -> {
			try {
				self.write(org, type, dedupKey, payload, suppressFor);
			} catch (Exception e) {
				log.error("Board notification {} ({}) was not written", type, dedupKey, e);
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			write.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				write.run();
			}
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void write(UUID org, NotificationEventType type, String dedupKey, AgentBoardEventPayload payload,
			Duration suppressFor) {
		if (null != suppressFor && outboxRepository.existsByDedupKeyAndOccurredAtAfter(dedupKey,
				ZonedDateTime.now().minus(suppressFor))) {
			return;
		}
		// Who it goes to, as the board stands now; a session event has no board and keeps the org's routing.
		AgentBoardEventPayload routed = null == payload || null == payload.board() ? payload
				: payload.withTargetUsers(boardRecipients(org, payload.board()));
		releaseNotificationSupport.writeOutboxEventStrict(org, type, dedupKey, routed);
	}

	/**
	 * The people an event about a board goes to (board-permissions.md D14): the org's active members
	 * who hold BOARD_WRITE on it at WRITE -- their own grants and their teams' -- which takes in the
	 * org's admins. Sorted, so the snapshot reads the same for the same board.
	 */
	public List<UUID> boardRecipients(UUID org, UUID board) {
		return userService.listUserDataByOrg(org).stream()
				.filter(ud -> authorizationService.boardPermission(ud, org, board, PermissionFunction.BOARD_WRITE,
						CallType.WRITE))
				.map(UserData::getUuid).sorted().toList();
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One payload for the agent-board notification family (AGENT_BOARD_ALERT, AGENT_TASK_NEEDS_PERSON,
 * AGENT_TASK_RETURNED, AGENT_TASK_QUEUE_AGE; task 82880ea6; AGENT_SESSION_IDLE_WARNING, task 6e7fe6fe). A subscription narrows by board or
 * kind through its CEL filter ({@code event.board == '…'}, {@code event.kind == 'ALERT'}).
 *
 * @param kind ALERT or LOCKED for a board alert, the hold kind for a person's hold, the return
 *        reason for a return, QUEUE_AGE for the age crossing
 * @param link where a person acts on it: the task page, or the board when there is no task
 * @param ageMinutes how long the task has waited on a person, for AGENT_TASK_QUEUE_AGE
 * @param targetUsers who receives it (board-permissions.md D14): the people who can act on the board,
 *        snapshotted when the event is written -- its BOARD_WRITE holders, org admins among them. Set on
 *        every event about a board; the fan-out delivers those to these people only, as inbox rows, and
 *        never through the org's subscriptions. Null on a session event and on events written before.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentBoardEventPayload(UUID board, String boardName, UUID task, String taskTitle, String kind,
		String message, String holdKind, String holdLevel, String link, Long ageMinutes, List<UUID> targetUsers) {

	public AgentBoardEventPayload(UUID board, String boardName, UUID task, String taskTitle, String kind,
			String message, String holdKind, String holdLevel, String link, Long ageMinutes) {
		this(board, boardName, task, taskTitle, kind, message, holdKind, holdLevel, link, ageMinutes, null);
	}

	/** This payload with its recipient snapshot. */
	public AgentBoardEventPayload withTargetUsers(List<UUID> users) {
		return new AgentBoardEventPayload(board, boardName, task, taskTitle, kind, message, holdKind, holdLevel, link,
				ageMinutes, null == users ? null : List.copyOf(users));
	}

	public static String taskLink(UUID task) {
		return "/aiAgentTask/" + task;
	}

	public static String sessionLink(UUID session) {
		return "/aiAgentSession/" + session;
	}

	public static String boardLink(UUID org, UUID board) {
		return "/aiAgentsOfOrg/" + org + "?tab=boards&board=" + board;
	}

	/**
	 * The headline every renderer shares: the task, key first (its label), then its board; the board
	 * alone when there is no task (RD2-22, board-documents.md D12: the key leads every human surface).
	 */
	public String title() {
		String b = null == boardName ? "board" : boardName;
		return null == taskTitle ? b : taskTitle + " · " + b;
	}
}

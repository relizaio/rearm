/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Map;

import io.reliza.common.Utils;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.AgentBoardEventPayload;

/**
 * What every channel says about an agent-board notification (task 82880ea6), so the inbox, Slack,
 * Teams, email and Sentinel read the same: one headline naming the board and the task, the board's
 * own words as the body, and the page a person acts on.
 */
public final class AgentBoardRenderSupport {

	private AgentBoardRenderSupport() {}

	public static AgentBoardEventPayload payload(NotificationOutboxEvent event) {
		if (null == event || null == event.getRecordData()) return null;
		return Utils.OM.convertValue(event.getRecordData(), AgentBoardEventPayload.class);
	}

	public static String headline(NotificationEventType type, AgentBoardEventPayload p) {
		String what = switch (type) {
			case AGENT_BOARD_ALERT -> "PAUSED".equals(p.kind()) ? "Board paused" : "Board alert";
			case AGENT_TASK_NEEDS_PERSON -> "HUMAN_GATE".equals(p.holdKind()) ? "Awaiting human review"
					: "QUESTION".equals(p.holdKind()) ? "A question needs an answer" : "Needs a person";
			case AGENT_TASK_RETURNED -> "Task returned";
			case AGENT_TASK_QUEUE_AGE -> "Waiting " + (null == p.ageMinutes() ? "?" : p.ageMinutes()) + " min for a person";
			case AGENT_SESSION_IDLE_WARNING -> "Agent session closing idle";
			default -> type.name();
		};
		return what + " — " + p.title();
	}

	public static String body(AgentBoardEventPayload p) {
		return null == p.message() ? "" : p.message();
	}

	/** The absolute link, or null when the instance has no web base URI configured. */
	public static String url(String webBaseUri, AgentBoardEventPayload p) {
		if (null == webBaseUri || webBaseUri.isBlank() || null == p.link()) return null;
		return webBaseUri + p.link();
	}

	/**
	 * A fixed severity per kind, so a route with a minimum severity sees them: a person being
	 * waited on is HIGH, an alert or a return MEDIUM. Without one the severity gate fails closed and
	 * drops every board notification on such a route.
	 */
	public static NotificationSeverity severity(NotificationEventType type) {
		return switch (type) {
			case AGENT_TASK_NEEDS_PERSON, AGENT_TASK_QUEUE_AGE, AGENT_SESSION_IDLE_WARNING -> NotificationSeverity.HIGH;
			default -> NotificationSeverity.MEDIUM;
		};
	}

	/** The payload's fields, for the CEL activation ({@code event.board}, {@code event.kind}, ...) and log records. */
	public static void populate(Map<String, Object> m, AgentBoardEventPayload p) {
		if (null == p) return;
		m.put("board", null == p.board() ? null : p.board().toString());
		m.put("boardName", p.boardName());
		m.put("task", null == p.task() ? null : p.task().toString());
		m.put("taskTitle", p.taskTitle());
		m.put("kind", p.kind());
		m.put("message", p.message());
		m.put("holdKind", p.holdKind());
		m.put("holdLevel", p.holdLevel());
		m.put("link", p.link());
		m.put("ageMinutes", p.ageMinutes());
	}
}

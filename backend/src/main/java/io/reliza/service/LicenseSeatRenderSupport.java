/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.Map;

import io.reliza.common.Utils;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.LicenseSeatEventPayload;

/**
 * What every channel says about a LICENSE_SEAT_EVENT (TPF-167, TPF-165 D9): the headline
 * {@code License seats} and the payload's message as the body.
 */
public final class LicenseSeatRenderSupport {

	public static final String HEADLINE = "License seats";

	private LicenseSeatRenderSupport() {}

	public static LicenseSeatEventPayload payload(NotificationOutboxEvent event) {
		if (null == event || null == event.getRecordData()) return null;
		return Utils.OM.convertValue(event.getRecordData(), LicenseSeatEventPayload.class);
	}

	public static String body(LicenseSeatEventPayload p) {
		return null == p || null == p.message() ? "" : p.message();
	}

	/** A fixed severity, so a route with a minimum severity at HIGH or below sees these. */
	public static NotificationSeverity severity() {
		return NotificationSeverity.HIGH;
	}

	/** The payload's fields for the CEL activation and log records: event.kind, event.email, event.group, event.message. */
	public static void populate(Map<String, Object> m, LicenseSeatEventPayload p) {
		if (null == p) return;
		m.put("kind", null == p.kind() ? null : p.kind().name());
		m.put("email", p.email());
		m.put("group", p.groupName());
		m.put("message", p.message());
	}
}

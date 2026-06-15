/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * Email-digest (rolling-cap batching) policy for an EMAIL notification
 * channel, parsed from the channel Integration's {@code parameters} map.
 *
 * <p>Semantics (Phase 5, BD-7): in {@link EmailDigestMode#ROLLING} mode
 * the first non-actionable event after a quiet period sends immediately;
 * subsequent events within {@code interval} of that send are held as
 * BATCHED delivery rows and flushed as one digest email when the window
 * expires. {@link EmailDigestMode#IMMEDIATE} disables batching entirely.
 * Actionable events ({@link NotificationEventType#isActionable()}) always
 * bypass the digest regardless of mode.
 *
 * <p>Read-side parsing is tolerant: absent or unparseable config falls
 * back to {@link #DEFAULT} (rolling, 24h) — digest-on-by-default per the
 * locked Phase 5 design. Write-side bounds are enforced at channel save
 * time in {@code NotificationChannelService.validateEmailConfig}.
 */
public record EmailDigestPolicy(EmailDigestMode mode, Duration interval) {

	/** Digest batching mode for an EMAIL channel. */
	public enum EmailDigestMode {
		/** Every event sends its own email as soon as the worker picks it up. */
		IMMEDIATE,
		/** Rolling cap: at most one non-actionable email per interval; overflow batches into a digest. */
		ROLLING;
	}

	/** Key under the channel integration's parameters map holding the recipient list. */
	public static final String RECIPIENTS_KEY = "recipients";

	/**
	 * Extract the recipient list from a channel's {@code parameters} map
	 * under {@link #RECIPIENTS_KEY}. Forgiving of legacy / API client
	 * shapes: accepts a JSON array ({@code List<String>}) or a
	 * comma-separated single string. Anything else returns empty.
	 *
	 * <p>Shared (model-level) so both the Pro dispatch path
	 * ({@code EmailChannelDispatcher} / {@code EmailDigestFlushService})
	 * and the shared channel read surface ({@code NotificationDataFetcher})
	 * parse recipients identically without the read surface depending on
	 * the Pro-only dispatcher.
	 */
	public static List<String> extractRecipients(Map<String, Object> parameters) {
		if (parameters == null) return List.of();
		Object raw = parameters.get(RECIPIENTS_KEY);
		if (raw == null) return List.of();
		List<String> out = new ArrayList<>();
		if (raw instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof String s && StringUtils.isNotBlank(s)) {
					out.add(s.trim());
				}
			}
		} else if (raw instanceof String s && StringUtils.isNotBlank(s)) {
			for (String part : s.split(",")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) out.add(trimmed);
			}
		}
		// Unexpected shapes fall through to empty; the caller decides how to
		// surface that (the Pro dispatcher marks NON_RETRIABLE with a clear
		// message). Logging the odd shape stays with the dispatcher caller
		// which has the channel uuid for context.
		return out;
	}
	/** Key under the channel integration's parameters map holding the digest mode name. */
	public static final String DIGEST_MODE_KEY = "digestMode";
	/** Key under the channel integration's parameters map holding the ISO-8601 digest interval. */
	public static final String DIGEST_INTERVAL_KEY = "digestInterval";

	public static final Duration DEFAULT_INTERVAL = Duration.ofHours(24);
	public static final Duration MIN_INTERVAL = Duration.ofMinutes(5);
	public static final Duration MAX_INTERVAL = Duration.ofDays(7);

	/** Absent config = digest on: rolling cap, one non-actionable email per 24h. */
	public static final EmailDigestPolicy DEFAULT =
			new EmailDigestPolicy(EmailDigestMode.ROLLING, DEFAULT_INTERVAL);

	public static final EmailDigestPolicy IMMEDIATE_POLICY =
			new EmailDigestPolicy(EmailDigestMode.IMMEDIATE, DEFAULT_INTERVAL);

	/**
	 * Parse a channel's parameters map. Tolerant by design — fan-out must
	 * never fail an event over a malformed digest config, so any
	 * unrecognized mode or unparseable/out-of-bounds interval falls back
	 * to the corresponding default rather than throwing.
	 */
	public static EmailDigestPolicy fromParameters (Map<String, Object> parameters) {
		if (null == parameters) return DEFAULT;
		EmailDigestMode mode = EmailDigestMode.ROLLING;
		Object rawMode = parameters.get(DIGEST_MODE_KEY);
		if (rawMode instanceof String s) {
			try {
				mode = EmailDigestMode.valueOf(s.trim());
			} catch (IllegalArgumentException e) {
				// fall through to default
			}
		}
		if (mode == EmailDigestMode.IMMEDIATE) return IMMEDIATE_POLICY;
		Duration interval = DEFAULT_INTERVAL;
		Object rawInterval = parameters.get(DIGEST_INTERVAL_KEY);
		if (rawInterval instanceof String s) {
			try {
				Duration parsed = Duration.parse(s.trim());
				if (parsed.compareTo(MIN_INTERVAL) >= 0 && parsed.compareTo(MAX_INTERVAL) <= 0) {
					interval = parsed;
				}
			} catch (DateTimeParseException e) {
				// fall through to default
			}
		}
		return new EmailDigestPolicy(EmailDigestMode.ROLLING, interval);
	}
}

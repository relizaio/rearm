/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import io.reliza.exceptions.RelizaException;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses agent-attribution trailers out of a commit-message string.
 * The trailer shape is locked in
 * {@code backend/ai-plans/agentic/README.md} §6:
 *
 * <pre>
 *   ReARM-Agentic-Session: &lt;clientSessionId&gt;
 *   ReARM-Agent: &lt;agentUuid&gt;
 * </pre>
 *
 * <h3>On-the-wire format (rearm-actions / CLI)</h3>
 * ReARM only ships the commit <em>subject</em> (`%s` in git-log
 * pretty), never the full message body — agent-generated commits
 * routinely have multi-kilobyte bodies that have no value in the
 * audit log. The CI action ({@code rearm-actions/initialize/action.yaml})
 * concatenates the relevant trailers onto the same line as the
 * subject using
 * {@code %(trailers:key=ReARM-Agent,key=ReARM-Agentic-Session,unfold,separator=%x20)},
 * keeping per-commit lines newline-delimited and field-delimited by
 * {@code |||} as before. This parser therefore matches trailers
 * <strong>whitespace-bounded</strong>, not line-anchored.
 *
 * <h3>Value constraints</h3>
 * Trailer values run from the colon up to the next whitespace
 * character. The agent must pick a {@code clientSessionId} without
 * embedded whitespace (alphanumeric / dashes / underscores) for the
 * trailer to round-trip cleanly through the subject. Agent uuids
 * are always UUIDs and therefore safe.
 *
 * <h3>Validation rules</h3>
 * <ul>
 *   <li>Multiple distinct values for the same trailer key → throws
 *       (a commit belongs to exactly one agent + one session).</li>
 *   <li>Repeated trailer with identical value → idempotent.</li>
 *   <li>Either trailer present in isolation → logged as a soft
 *       warning; the caller decides whether to act on a partial
 *       attribution (PR 2 ignores partial).</li>
 *   <li>Neither trailer present → returns
 *       {@link AgentCommitAttribution#EMPTY}.</li>
 * </ul>
 */
@Slf4j
public final class AgentCommitTrailerParser {

	private AgentCommitTrailerParser() {}

	/**
	 * Canonical character set for {@code clientSessionId}. Documented in
	 * {@code rearm-core/backend/ai-plans/agentic/README.md} §6 and used
	 * to validate at session-init time (so a bad id is rejected at the
	 * source instead of silently truncating later when the commit trailer
	 * is encoded). The pattern is intentionally stricter than the on-the-
	 * wire trailer regex below (which captures any non-whitespace run) —
	 * the trailer regex stays liberal so a malformed clientSessionId
	 * still surfaces as a REJECTED attribution rather than silently
	 * disappearing.
	 */
	public static final Pattern CLIENT_SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

	// Whitespace-bounded: trailer keyword preceded by start-of-string
	// or whitespace, value runs to the next whitespace. Case-insensitive
	// on the key.
	private static final Pattern AGENT_TRAILER = Pattern.compile(
			"(?:^|\\s)ReARM-Agent\\s*:\\s*(\\S+)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern SESSION_TRAILER = Pattern.compile(
			"(?:^|\\s)ReARM-Agentic-Session\\s*:\\s*(\\S+)",
			Pattern.CASE_INSENSITIVE);

	/**
	 * Parsed trailer payload. {@link #agentUuid} is non-null when the
	 * {@code ReARM-Agent:} trailer parsed cleanly; {@link #clientSessionId}
	 * is non-null when {@code ReARM-Agentic-Session:} did. Either may
	 * be {@code null} independently — the caller chooses the threshold
	 * via {@link #isFullAttribution()}.
	 */
	public record AgentCommitAttribution(UUID agentUuid, String clientSessionId) {
		public static final AgentCommitAttribution EMPTY = new AgentCommitAttribution(null, null);

		public boolean hasAgent() { return agentUuid != null; }
		public boolean hasSession() { return clientSessionId != null; }
		public boolean isFullAttribution() { return hasAgent() && hasSession(); }
	}

	/**
	 * Parse trailers out of a commit subject (or any string carrying
	 * the trailers). Returns {@link AgentCommitAttribution#EMPTY} when
	 * neither trailer is present (the common case for non-agent
	 * commits) — never throws on absence.
	 *
	 * @throws RelizaException when a trailer key appears with multiple
	 *         distinct values, or when {@code ReARM-Agent:} is present
	 *         but its value is not a valid UUID.
	 */
	public static AgentCommitAttribution parse(String commitSubject) throws RelizaException {
		if (StringUtils.isBlank(commitSubject)) return AgentCommitAttribution.EMPTY;

		String agentValue = singleValue(AGENT_TRAILER, commitSubject, "ReARM-Agent");
		String sessionValue = singleValue(SESSION_TRAILER, commitSubject, "ReARM-Agentic-Session");

		UUID agentUuid = null;
		if (StringUtils.isNotBlank(agentValue)) {
			try {
				agentUuid = UUID.fromString(agentValue);
			} catch (IllegalArgumentException e) {
				throw new RelizaException(
						"ReARM-Agent trailer is not a valid UUID: '" + agentValue + "'");
			}
		}

		if (agentUuid != null && StringUtils.isBlank(sessionValue)) {
			log.warn("Commit carries ReARM-Agent={} but no ReARM-Agentic-Session — attribution will be partial",
					agentUuid);
		} else if (StringUtils.isNotBlank(sessionValue) && agentUuid == null) {
			log.warn("Commit carries ReARM-Agentic-Session='{}' but no ReARM-Agent — attribution will be partial",
					sessionValue);
		}

		return new AgentCommitAttribution(agentUuid, sessionValue);
	}

	private static String singleValue(Pattern p, String text, String trailerKey) throws RelizaException {
		Matcher m = p.matcher(text);
		Set<String> distinct = new LinkedHashSet<>();
		while (m.find()) {
			distinct.add(m.group(1).trim());
		}
		if (distinct.isEmpty()) return null;
		if (distinct.size() > 1) {
			throw new RelizaException(trailerKey + " trailer has multiple distinct values: "
					+ String.join(", ", distinct));
		}
		return distinct.iterator().next();
	}
}

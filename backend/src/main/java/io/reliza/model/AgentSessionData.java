/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * One {@code ROOT} {@link Agent} invocation. Many concurrent sessions
 * per agent are allowed on the same API key.
 *
 * The {@link #agent} field is always the {@code ROOT}; sub-agents
 * contribute commits / artifacts to the same session via the commit
 * trailer (PR 2) and the leaf agent uuid resolves to this root via
 * {@link AgentData#getRootAgent()}.
 *
 * The agent supplies a {@link #clientSessionId} (its own natural id)
 * on initialize; it defaults to the row uuid when omitted so the
 * column is never NULL in practice. The PR 2 commit-trailer
 * ({@code ReARM-Agentic-Session: <clientSessionId>}) references this
 * value, so an agent can pick a stable id without a ReARM round-trip.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentSessionData extends RelizaDataParent implements RelizaObject {

	public enum SessionStatus {
		/** Live working session; commits trailered with this session's
		 *  clientSessionId resolve to it. Created on a successful
		 *  sessionInitializeProgrammatic with no BLOCK-severity input
		 *  policy failures. */
		OPEN,
		/** Agent or operator closed the session. Terminal. Commits
		 *  trailered with this id still resolve (historical view), but
		 *  the agent shouldn't keep working under it. */
		CLOSED,
		/** A BLOCK-severity INPUT policy failed on sessionInit. The row
		 *  is persisted so the audit / dashboard show the rejected
		 *  attempt with its policyEvents, but trailer attribution
		 *  refuses to resolve commits against it. Terminal — the agent
		 *  must mint a fresh clientSessionId to retry. */
		BLOCKED
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/**
	 * Owning {@code ROOT} {@link Agent}. Sub-agents share this session
	 * — they never appear here. Resolution of a leaf agent (from a
	 * commit trailer) to this root walks {@link AgentData#getRootAgent()}.
	 */
	@JsonProperty
	private UUID agent;

	/**
	 * Agent-supplied natural session id. Defaults to the row uuid
	 * stringified when the agent doesn't pick one. Unique within
	 * {@code (org, agent)} — enforced by the V37 partial unique index.
	 * The PR 2 commit-trailer references this value, not the row uuid,
	 * so the agent can choose a stable id without a ReARM round-trip.
	 */
	@JsonProperty
	private String clientSessionId;

	/**
	 * API key the agent presented when it opened the session. Used by
	 * monitoring to show which key owns a live session; an agent can
	 * have multiple sessions open concurrently on the same key.
	 */
	@JsonProperty
	private UUID apiKey;

	/**
	 * Human-readable title. The agent passes it on initialize; refinable
	 * later. UI shows it in the live-sessions row.
	 */
	@JsonProperty(CommonVariables.TITLE_FIELD)
	private String title;

	@JsonProperty(CommonVariables.STATUS_FIELD)
	private SessionStatus status = SessionStatus.OPEN;

	@JsonProperty
	private ZonedDateTime startedAt;

	@JsonProperty
	private ZonedDateTime closedAt;

	/**
	 * Last time the agent touched the session (any state-changing
	 * mutation OR an explicit heartbeat). Drives the "connected" pill
	 * on the dashboard and the inactivity autoclose path (PR 2/3).
	 */
	@JsonProperty
	private ZonedDateTime lastActivityAt;

	/**
	 * Artifacts the agent has attached. Reuses the existing
	 * {@code rearm.artifacts} table — each entry is an Artifact UUID.
	 * The reverse lookup (session-by-artifact) is via this list; v1
	 * doesn't tag artifacts back at the Artifact row.
	 */
	@JsonProperty
	private List<UUID> artifacts = new ArrayList<>();

	/**
	 * SCE UUIDs attributed to this session by the PR 2 commit-trailer
	 * parser. Reverse index of
	 * {@link SourceCodeEntryData#getAgentSession()} so the
	 * {@code release.sessions[]} resolver can walk
	 * {@code release.commits -> SCE.agentSession} without scanning
	 * every SCE row.
	 */
	@JsonProperty(CommonVariables.COMMITS_FIELD)
	private List<UUID> commits = new ArrayList<>();

	/**
	 * Append-only log of policy evaluations against this session.
	 * Populated by the saas-side policy hook (PR 4); empty on every
	 * CE deployment.
	 *
	 * Stored as typed {@link io.reliza.service.AgentPolicyHook.PolicyEvent}
	 * records so the {@code evaluatedAt} ZonedDateTime round-trips
	 * cleanly through the GraphQL DateTime coercer. Jackson tolerates
	 * unknown fields per {@code @JsonIgnoreProperties(ignoreUnknown = true)}
	 * on this class — adding fields to PolicyEvent stays backward-
	 * compatible with already-stored rows.
	 */
	@JsonProperty
	private List<io.reliza.service.AgentPolicyHook.PolicyEvent> policyEvents = new ArrayList<>();

	/**
	 * Pointer to a prior session this one continues from. Set by an
	 * agent that retries after a BLOCKED or CLOSED predecessor so the
	 * audit trail threads the related attempts (BLOCKED-due-to-policy
	 * → operator fixes policy → fresh session links back via
	 * parentSession). Null on a first-time-ever session. The chain
	 * doesn't need to be deep; v1 just records the immediate parent.
	 */
	@JsonProperty
	private UUID parentSession;

	public void addArtifact(UUID artifactUuid) {
		if (this.artifacts == null) {
			this.artifacts = new LinkedList<>();
		}
		if (!this.artifacts.contains(artifactUuid)) {
			this.artifacts.add(artifactUuid);
		}
	}

	public void addCommit(UUID sceUuid) {
		if (this.commits == null) {
			this.commits = new LinkedList<>();
		}
		if (!this.commits.contains(sceUuid)) {
			this.commits.add(sceUuid);
		}
	}

	public void addPolicyEvent(io.reliza.service.AgentPolicyHook.PolicyEvent event) {
		if (this.policyEvents == null) {
			this.policyEvents = new LinkedList<>();
		}
		if (event != null) {
			this.policyEvents.add(event);
		}
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static AgentSessionData dataFromRecord(AgentSession as) {
		if (as.getSchemaVersion() != 0) {
			throw new IllegalStateException("AgentSession schema version is " + as.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = as.getRecordData();
		AgentSessionData asd = Utils.OM.convertValue(recordData, AgentSessionData.class);
		asd.setUuid(as.getUuid());
		asd.setCreatedDate(as.getCreatedDate());
		return asd;
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
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
	 * Who closed the session (task 6e7fe6fe): the session itself when its agent closed it, the
	 * person who force-closed it, or the idle sweep. Null while open, and on sessions closed before
	 * this was recorded.
	 */
	@JsonProperty
	private AgentActor closedBy;

	/** Why it was closed, in words: "closed by the agent", a person's reason, or the idle time. */
	@JsonProperty
	private String closeReason;

	/**
	 * When the idle sweep warned that the session will close (task 6e7fe6fe). Cleared by any
	 * activity, so a session warned twice was idle twice.
	 */
	@JsonProperty
	private ZonedDateTime idleWarnedAt;

	/** The session did something at {@code at}: its idle clock restarts and any idle warning is spent. */
	public void markActive(ZonedDateTime at) {
		this.lastActivityAt = at;
		this.idleWarnedAt = null;
	}

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
	 * Boards this session was assigned a task on (board-permissions.md D15): who may read it
	 * besides its own agent. Recorded after each assignment commits; empty on sessions from before,
	 * which are read from their tasks once and written then.
	 */
	@JsonProperty
	private java.util.LinkedHashSet<UUID> boardsWorked = new java.util.LinkedHashSet<>();

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

	/**
	 * The AI model this session ran, as a {@link ModelOntology} UUID. The
	 * model is a property of the chat (this session), not of the durable
	 * {@link Agent} row — a developer switches models per conversation and
	 * reuses one agent. Set from the agent's declared model on
	 * {@code sessionInitializeProgrammatic}; null on legacy rows written
	 * before this field existed (read back as null via
	 * {@code @JsonIgnoreProperties}). The deprecated {@link Agent#getModel()}
	 * remains the fallback until the model is stripped off the agent row.
	 */
	@JsonProperty
	private UUID model;

	/**
	 * Trust level of {@link #model}. {@code DECLARED} when the agent
	 * self-reported the model string via the CLI (the only source today).
	 * Defaults to {@code DECLARED} so legacy rows — which carried a
	 * declared model on the agent — deserialize with the honest value
	 * rather than null.
	 */
	@JsonProperty
	private ModelAssertionState modelAssertion = ModelAssertionState.DECLARED;

	/**
	 * How much of this session's consumption we believe we have.
	 *
	 * <p>{@code COMPLETE}: the agent closed the session itself and reported after its last
	 * assignment opened. {@code INCOMPLETE}: the idle auto-close scheduler or an operator ended
	 * it, so the tail is missing. {@code NONE}: nothing ever reported. Enforcement later treats
	 * the last two conservatively; dashboards show which one they are looking at, because a
	 * cheap-looking session and an unreported session are not the same claim.
	 */
	public enum UsageCompleteness { COMPLETE, INCOMPLETE, NONE }

	/**
	 * Per-model slice of a session's consumption.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	/**
	 * @param model the catalogue row uuid
	 * @param modelName its display name, carried alongside so a consumer does not have to fetch
	 *        the whole catalogue to render a breakdown -- the rollup already has the row in hand
	 */
	public static record UsageByModel(UUID model, String modelName, long inputTokens, long outputTokens,
			long cacheReadTokens, long cacheWriteTokens, int requests, int turns,
			Long derivedCostMicros) implements Serializable {

		/** Pre-name constructor, so stored rollups written before this field read unchanged. */
		public UsageByModel(UUID model, long inputTokens, long outputTokens, long cacheReadTokens,
				long cacheWriteTokens, int requests, int turns, Long derivedCostMicros) {
			this(model, null, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
					requests, turns, derivedCostMicros);
		}
	}

	/**
	 * Rollup of the session's usage rows. A cache of the table, rebuildable from it at any time;
	 * held here so the common read -- "what has this session spent" -- is one row rather than a
	 * scan.
	 *
	 * @param priceVersions the pricing entries the derived cost was computed under, so a figure
	 *        stays meaningful after a rate changes
	 * @param costComplete false when any row had no applicable price entry; the cost is then a
	 *        lower bound rather than the answer
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record UsageTotals(long inputTokens, long outputTokens, long cacheReadTokens,
			long cacheWriteTokens, int requests, int turns, int toolCalls, int wallSeconds,
			int reports, List<UsageByModel> byModel, Long derivedCostMicros,
			List<UUID> priceVersions, boolean costComplete) implements Serializable {

		public static UsageTotals empty() {
			return new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null, List.of(), true);
		}
	}

	/**
	 * The agent tool's own id for a session this ReARM session ran in -- Claude Code's
	 * {@code session_id}, and whatever the equivalent is for the next tool. It is what joins a
	 * ReARM session back to the tool's transcript, and what a human needs to find the
	 * conversation that produced a commit.
	 *
	 * <p>Not unique in either direction. One tool session commonly opens many ReARM sessions, one
	 * per unit of work; and one ReARM session can outlive the tool session that opened it -- a
	 * conversation resumed elsewhere reports its new id against the same ReARM session. So this is
	 * a list, appended to and deduplicated on (provider, id).
	 *
	 * @param provider the tool, as the reporting client names it ({@code claude-code})
	 * @param id the tool's local id for the session -- for Claude Code, the id its transcript is
	 *        filed under
	 * @param remoteId the id a hosted surface of the tool knows the session by, when there is one
	 *        (Claude Code's bridge / web session id); null for a purely local run
	 * @param reportedAt when ReARM first heard of this pairing
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record ProviderSession(String provider, String id, String remoteId,
			ZonedDateTime reportedAt) implements Serializable {}

	/** How the session's caller authenticated when it opened the session. */
	public enum AuthMethod {
		/** An API key's secret, directly or exchanged for an access token. Says which key, never who. */
		KEY_SECRET,
		/** A CLI browser login (device flow) a signed-in user approved. */
		CLI_LOGIN,
		/** A federated identity exchange, e.g. a GitHub Actions OIDC token. */
		FEDERATED
	}

	/**
	 * Where {@link SessionOrigin#ownerUser()} came from, strongest first. They make different
	 * claims and the UI words them differently: a login says who approved this device, a
	 * personal key says whose key it is, and a holder is only the user accountable for a secret
	 * that may since have been handed to anyone.
	 */
	public enum OwnerSource { CLI_LOGIN, USER_KEY, KEY_HOLDER }

	/**
	 * A device as described to ReARM. Hostname and IP are personal data -- a hostname is often
	 * the owner's name -- and are shown only to org admins and the session's owner.
	 *
	 * @param observedIp the address the server saw, when the description came with a request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record SessionDevice(String hostname, String os, String timeZone, String client,
			String observedIp) implements Serializable {}

	/**
	 * The external identity a federated session came through, as the exchange's claims gave it.
	 * {@code actor} names a person and is restricted like a hostname; the rest describes a
	 * repository and a run.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record SessionFederation(List<UUID> ruleUuids, String provider, String issuer,
			String owner, String repository, String repositoryUri, String ref, String sha,
			String workflowRef, String environment, String event, String actor, String runId)
			implements Serializable {}

	/**
	 * How the session came to be opened, captured once at initialize: the session is the record
	 * of the work, so it keeps its own copy rather than pointing at a login that may be revoked
	 * and purged. Null on sessions opened before this was recorded.
	 *
	 * @param cliSession the CLI login, when {@link AuthMethod#CLI_LOGIN}
	 * @param ownerUser the person the session is attributed to, or null when nothing names one --
	 *        an organisation key, a federated identity, or a Free Form key with no holder
	 * @param loginDevice what the CLI login recorded about its device when it was approved
	 * @param reportedDevice what the client said about its device on initialize
	 * @param observedIp the address the server saw on initialize
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record SessionOrigin(AuthMethod authMethod, UUID cliSession, UUID ownerUser,
			OwnerSource ownerSource, SessionDevice loginDevice, SessionDevice reportedDevice,
			String observedIp, SessionFederation federation, ZonedDateTime capturedAt)
			implements Serializable {}

	/** See {@link SessionOrigin}. Read through a resolver that withholds personal fields. */
	@JsonProperty
	private SessionOrigin origin;

	/** See {@link ProviderSession}. Empty on sessions whose client reported none. */
	@JsonProperty
	private List<ProviderSession> providerSessions = new ArrayList<>();

	/** Rollup of this session's usage rows; null until the first report arrives. */
	@JsonProperty
	private UsageTotals usageTotals;

	/** See {@link UsageCompleteness}. */
	@JsonProperty
	private UsageCompleteness usageCompleteness = UsageCompleteness.NONE;

	/**
	 * Set when observed usage resolved to a different catalogue row than the session declared.
	 * The declared model stays primary -- the declaration is what the agent claimed and the
	 * mismatch is the finding -- and the rollup's byModel shows what actually ran.
	 */
	@JsonProperty
	private boolean modelMismatch;

	/**
	 * Per board (keyed by board uuid), when this session last polled it for work and last was offered a
	 * task, and the roles the poll declared -- empty for any role (task RD3-4). Written by `task next`
	 * through a targeted update, not a revision; the staleness sweep and the board's Agents tab read it.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record BoardActivity(ZonedDateTime lastPollAt, ZonedDateTime lastOfferAt, List<String> roles)
			implements java.io.Serializable {
		private static final long serialVersionUID = 20260928L;
	}

	@JsonProperty
	private Map<String, BoardActivity> boardActivity;


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

	/**
	 * Record a provider session, deduplicated on (provider, id). A repeat that now carries a
	 * remote id the first report lacked fills it in -- a client may learn the hosted id after it
	 * opened the session -- and keeps the original {@code reportedAt}. A repeat naming a
	 * different remote id is refused by the caller, not here.
	 *
	 * @return true when the list changed
	 */
	public boolean recordProviderSession(ProviderSession ps) {
		if (null == ps) return false;
		if (null == this.providerSessions) {
			this.providerSessions = new ArrayList<>();
		}
		for (int i = 0; i < this.providerSessions.size(); i++) {
			ProviderSession cur = this.providerSessions.get(i);
			if (cur.provider().equals(ps.provider()) && cur.id().equals(ps.id())) {
				if (null == cur.remoteId() && null != ps.remoteId()) {
					this.providerSessions.set(i, new ProviderSession(cur.provider(), cur.id(),
							ps.remoteId(), cur.reportedAt()));
					return true;
				}
				return false;
			}
		}
		this.providerSessions.add(ps);
		return true;
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

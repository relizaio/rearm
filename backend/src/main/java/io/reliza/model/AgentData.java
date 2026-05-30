/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

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
 * A coding agent registered against an org (Claude Code, Cursor Agent,
 * Codex CLI, …). Identity =
 * ({@link #org}, {@link #agentIdentity}, case-insensitive {@link #name}).
 * The row is created automatically on the first session opened under
 * a given (identity, name) pair.
 *
 * Why scope by identity, not just (org, name): many users would all
 * call themselves "Claude Code" and collide on a shared agents row.
 * Scoping under {@link #agentIdentity} (a pointer into the
 * {@code rearm.agent_identities} table) lets each FREEFORM key — and
 * eventually each OIDC subject — own its own "Claude Code" agent row.
 * SUB agents inherit their root's identity.
 *
 * Publisher / model / version identity is **not** carried on the
 * agent row — {@link #model} points to a {@link ModelOntology} that
 * holds those plus a full CycloneDX ML-BOM model card.
 *
 * Hierarchy (see {@code ai-plans/agentic/README.md} §4):
 * <ul>
 *   <li>{@link #agentType} = {@code ROOT} — registered top-level
 *       coding agent. Owns sessions. May spawn sub-agents listed in
 *       {@link #subAgents}.</li>
 *   <li>{@link #agentType} = {@code SUB} — child agent spawned by a
 *       root (or by another sub). {@link #rootAgent} points to the
 *       owning root. Does NOT own sessions; commits authored by the
 *       sub use the root's session via the commit trailer.</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentData extends RelizaDataParent implements RelizaObject {

	public enum AgentStatus {
		ACTIVE,
		ARCHIVED
	}

	public enum AgentType {
		ROOT,
		SUB
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/**
	 * Registration key, not a display label. The agent runtime supplies
	 * this via {@code --agent-name} on every session; it is the {@code name}
	 * half of the ({@link #org}, {@link #agentIdentity}, lower(name))
	 * resolution key used by {@code findOrRegisterRootAgent}. Treat as
	 * immutable — renaming it decouples the row from the runtime (which
	 * keeps registering under the original name) and would auto-create a
	 * duplicate. User-facing renames go through {@link #displayName}.
	 */
	@JsonProperty
	private String name;

	/**
	 * Optional human-chosen label shown in the UI in place of
	 * {@link #name}. Editable by org admins; falls back to {@link #name}
	 * when unset. Purely cosmetic — never used for resolution,
	 * attribution, or uniqueness, so it carries none of {@link #name}'s
	 * registration-key constraints.
	 */
	@JsonProperty
	private String displayName;

	/**
	 * Pointer to a {@link ModelOntology} row describing the AI/ML model
	 * this agent is running (publisher, model name, version, and a
	 * CycloneDX ML-BOM model card). Auto-upserted from the input
	 * {@code agentVendor}/{@code agentModel}/{@code agentModelVersion}
	 * triple on first session.
	 */
	@JsonProperty
	private UUID model;

	/**
	 * Optional dashboard glyph (single character or short token). UI may
	 * fall back to a default if unset.
	 */
	@JsonProperty
	private String iconKind;

	/**
	 * Optional dashboard accent colour (CSS hex, e.g. "#D97757").
	 */
	@JsonProperty
	private String color;

	/**
	 * Free-form notes the user sets on the dashboard. ReARM never reads
	 * this for routing decisions.
	 */
	@JsonProperty
	private String notes;

	/**
	 * Pointer to the owning {@code rearm.agent_identities} row. Scopes
	 * the (org, lower(name)) uniqueness to a per-identity boundary so
	 * two FREEFORM keys can each own a "Claude Code". SUB agents
	 * inherit this from their {@link #rootAgent}. Set on the row at
	 * creation time and never edited.
	 */
	@JsonProperty
	private UUID agentIdentity;

	@JsonProperty(CommonVariables.STATUS_FIELD)
	private AgentStatus status = AgentStatus.ACTIVE;

	/**
	 * Hierarchy bucket. ROOT = registered top-level agent that owns
	 * sessions; SUB = child spawned by a root (or transitively).
	 * Defaulted to ROOT on auto-registration through session initialize.
	 */
	@JsonProperty
	private AgentType agentType = AgentType.ROOT;

	/**
	 * Pointer to the owning {@code ROOT} agent. {@code null} when
	 * {@link #agentType} is {@code ROOT}. Denormalised so that resolving
	 * a leaf agent (any sub-agent) to its session-owning root is O(1)
	 * — used by the commit-trailer attribution path in PR 2 and by the
	 * monitoring read-side.
	 */
	@JsonProperty
	private UUID rootAgent;

	/**
	 * Direct children spawned by this agent (best-effort report from
	 * the root). Only meaningful on {@code ROOT} agents in v1; SUB
	 * agents can also accumulate their own sub-agents in v2+. ReARM
	 * trusts this list for display only — authoritative trees are
	 * built via {@link #rootAgent} pointers when needed.
	 */
	@JsonProperty
	private List<UUID> subAgents = new ArrayList<>();

	public void addSubAgent(UUID subAgentUuid) {
		if (this.subAgents == null) {
			this.subAgents = new LinkedList<>();
		}
		if (!this.subAgents.contains(subAgentUuid)) {
			this.subAgents.add(subAgentUuid);
		}
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static AgentData dataFromRecord(Agent a) {
		if (a.getSchemaVersion() != 0) {
			throw new IllegalStateException("Agent schema version is " + a.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = a.getRecordData();
		AgentData ad = Utils.OM.convertValue(recordData, AgentData.class);
		ad.setUuid(a.getUuid());
		ad.setCreatedDate(a.getCreatedDate());
		return ad;
	}
}

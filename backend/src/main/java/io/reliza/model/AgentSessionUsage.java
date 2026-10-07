/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One usage report line: what a session consumed on one model, at one hosting, in one context
 * band, over one reporting window.
 *
 * <p>Unusual for this codebase in carrying real columns rather than living entirely in
 * {@code record_data}: every field here is either filtered on or summed, and the rollups are
 * per-org and per-period, so jsonb extraction would be the wrong shape. Everything that is only
 * ever read back whole -- the reporting window, the raw payload, the declared model string, the
 * peeled variants -- stays in {@code record_data}.
 *
 * <p>Append-only. The one field that may change after insert is {@link #task}, which late
 * attribution may fill in.
 *
 * <p>Design: {@code backend/ai-plans/agentic/usage-telemetry-and-model-catalogue.md}.
 */
@Entity
@Getter
@Setter
@Table(schema = ModelProperties.DB_SCHEMA, name = "agent_session_usages")
public class AgentSessionUsage implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 20260919L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private int revision = 0;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	/** Denormalised from the session: authorization and period rollups both filter on it. */
	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID session;

	/** Denormalised from the session, so an agent's spend is one index scan. */
	@Column(nullable = false)
	private UUID agent;

	/** Null when the row is coordinator work or unattributed; see SessionUsageAttribution. */
	private UUID task;

	/** The task's board, or the board whose coordinator seat the session holds. */
	private UUID board;

	/** Resolved catalogue row. The string the client sent is kept in record_data. */
	@Column(nullable = false)
	private UUID model;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SessionUsageSource source = SessionUsageSource.SELF_REPORTED;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SessionUsageHosting hosting = SessionUsageHosting.DIRECT;

	/**
	 * The FLOOR of the pricing band these requests fall in, in tokens: 0 for the base band, 200000
	 * above the long-context threshold.
	 *
	 * <p>A number rather than the client's label. The label put formatting into a unique key --
	 * {@code 200k} and {@code 200000} would have been two bands -- and made every comparison a
	 * string match on something inherently ordered. A floor sorts, compares against a pricing
	 * entry's {@code contextAboveTokens} directly, and cannot be spelled two ways.
	 */
	@Column(name = "context_band", nullable = false)
	private long contextBand;

	/** Transcript byte offset in the CLI; 64-bit for that reason. */
	@Column(nullable = false)
	private long clientSeq;

	@Column(nullable = false)
	private ZonedDateTime reportedAt = ZonedDateTime.now();

	/** Distinct API requests summed into this row; the base of the oversize guard. */
	@Column(nullable = false)
	private int requests;

	@Column(nullable = false)
	private long inputTokens;

	@Column(nullable = false)
	private long outputTokens;

	@Column(nullable = false)
	private long cacheReadTokens;

	@Column(nullable = false)
	private long cacheWriteTokens;

	/** Largest single-request context (input + cache read + cache write); selects the price. */
	@Column(nullable = false)
	private long maxRequestContextTokens;

	/** Smallest; with the max it says whether this row straddles a pricing threshold. */
	@Column(nullable = false)
	private long minRequestContextTokens;

	@Column(nullable = false)
	private int turns;

	@Column(nullable = false)
	private int toolCalls;

	@Column(nullable = false)
	private int wallSeconds;

	/** The client's own figure. Shown beside the derived cost, never summed into it. */
	private Long reportedCostMicros;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> recordData;
}

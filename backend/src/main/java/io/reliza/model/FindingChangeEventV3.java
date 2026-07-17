/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.model.MetricsAudit.MetricsEntityType;

/**
 * The BRANCH-GRAIN "events-lite" fact row (board task #38 follow-on, fact-row dedup) -- the SOLE
 * finding-change fact store after the per-release v1/v2 tables were dropped in V64. Whereas the former
 * {@code finding_change_events_v2} kept v1's per-release grain (one row per release the finding appears
 * on -- the ~148x fan-out), this row is keyed to the per-branch finding timeline: a shared-dependency
 * CVE inherited unchanged from a branch predecessor emits NO row, so only genuine transitions land
 * (demo: ~98% fewer rows). See {@code finding_change_events_v3} (V58) and
 * {@code ai-agents/finding-events-dedup-v3-design.md}.
 *
 * <p>Structurally this is the former per-release fact + a {@code branchUuid} grain column, with
 * {@code releaseUuid} reinterpreted as {@code firstReleaseUuid} -- the release/scan that PRODUCED the
 * transition, kept for attribution + reverse-replay ordering, NOT identity. Values
 * (severity / previousSeverity / knownExploited / analysisState) stay BAKED on the event (demo-proven
 * constant across the fan-out), so reconstruction is byte-identical and the enrichment path is
 * untouched (Option A). It shares the V57 {@link FindingDim} dimension unchanged.
 *
 * <p>Reads hydrate this + its {@link FindingDim} into a full {@link FindingChangeEvent} for the
 * branch-grouped reconstruction engine (Stage 2). FK-free.
 */
@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="finding_change_events_v3")
public class FindingChangeEventV3 implements Serializable {
	private static final long serialVersionUID = 380004;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(name = "org", nullable = false)
	private UUID org;

	@Enumerated(EnumType.STRING)
	@Column(name = "entity_type", nullable = false)
	private MetricsEntityType entityType = MetricsEntityType.RELEASE;

	@Column(name = "component_uuid", nullable = false)
	private UUID componentUuid;

	/** Grain anchor: the branch whose finding timeline this transition belongs to. */
	@Column(name = "branch_uuid", nullable = false)
	private UUID branchUuid;

	/** Reference to {@link FindingDim#getUuid()} (bare, no FK) -- shared with v2. */
	@Column(name = "finding_dim", nullable = false)
	private UUID findingDim;

	@Enumerated(EnumType.STRING)
	@Column(name = "finding_kind", nullable = false)
	private FindingKind findingKind;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_kind", nullable = false)
	private FindingChangeKind changeKind;

	@Column(name = "change_date", nullable = false)
	private ZonedDateTime changeDate;

	/**
	 * PROVENANCE, not identity: the release whose scan produced this branch transition. Drives changelog
	 * attribution (which release a finding first appeared in) and the reverse-replay tie-break.
	 */
	@Column(name = "first_release_uuid", nullable = false)
	private UUID firstReleaseUuid;

	@Column(name = "version", nullable = false)
	private String version;

	@Column(name = "component_name", nullable = false)
	private String componentName;

	@Column(name = "to_metrics_revision", nullable = false)
	private int toMetricsRevision;

	@Column(name = "severity")
	private String severity;

	@Column(name = "previous_severity")
	private String previousSeverity;

	@Column(name = "known_exploited")
	private Boolean knownExploited;

	@Column(name = "analysis_state")
	private String analysisState;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public MetricsEntityType getEntityType() { return entityType; }
	public void setEntityType(MetricsEntityType entityType) { this.entityType = entityType; }

	public UUID getComponentUuid() { return componentUuid; }
	public void setComponentUuid(UUID componentUuid) { this.componentUuid = componentUuid; }

	public UUID getBranchUuid() { return branchUuid; }
	public void setBranchUuid(UUID branchUuid) { this.branchUuid = branchUuid; }

	public UUID getFindingDim() { return findingDim; }
	public void setFindingDim(UUID findingDim) { this.findingDim = findingDim; }

	public FindingKind getFindingKind() { return findingKind; }
	public void setFindingKind(FindingKind findingKind) { this.findingKind = findingKind; }

	public FindingChangeKind getChangeKind() { return changeKind; }
	public void setChangeKind(FindingChangeKind changeKind) { this.changeKind = changeKind; }

	public ZonedDateTime getChangeDate() { return changeDate; }
	public void setChangeDate(ZonedDateTime changeDate) { this.changeDate = changeDate; }

	public UUID getFirstReleaseUuid() { return firstReleaseUuid; }
	public void setFirstReleaseUuid(UUID firstReleaseUuid) { this.firstReleaseUuid = firstReleaseUuid; }

	public String getVersion() { return version; }
	public void setVersion(String version) { this.version = version; }

	public String getComponentName() { return componentName; }
	public void setComponentName(String componentName) { this.componentName = componentName; }

	public int getToMetricsRevision() { return toMetricsRevision; }
	public void setToMetricsRevision(int toMetricsRevision) { this.toMetricsRevision = toMetricsRevision; }

	public String getSeverity() { return severity; }
	public void setSeverity(String severity) { this.severity = severity; }

	public String getPreviousSeverity() { return previousSeverity; }
	public void setPreviousSeverity(String previousSeverity) { this.previousSeverity = previousSeverity; }

	public Boolean getKnownExploited() { return knownExploited; }
	public void setKnownExploited(Boolean knownExploited) { this.knownExploited = knownExploited; }

	public String getAnalysisState() { return analysisState; }
	public void setAnalysisState(String analysisState) { this.analysisState = analysisState; }

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }
}

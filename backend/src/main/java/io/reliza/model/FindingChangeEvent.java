/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;

/**
 * A single re-scan-driven finding change for one release (board task #38, phase 1).
 *
 * <p>Once the fat v1 ({@code finding_change_events}) and per-release v2 ({@code finding_change_events_v2})
 * fact tables were dropped (V64, board task #38 Stage 4 v3 era), this is NO LONGER a persisted entity: it
 * is a plain in-memory value object. The branch-grain v3 store ({@code finding_change_events_v3} +
 * {@code finding_dim}) is the sole persistence; the read path hydrates v3 facts + their dimension into
 * transient {@code FindingChangeEvent}s ({@code FindingDimBackfillService.stitchV3}) for the
 * reverse-replay reconstruction engine, and the write-time diff produces them for
 * {@code FindingDimBackfillService.writeEventsToV3}. It carries every field the engine and the v3 writer
 * need; there is no JPA mapping.
 *
 * <p>{@code previousSeverity} is populated for {@link FindingChangeKind#SEVERITY_INCREASED} and
 * {@link FindingChangeKind#SEVERITY_DECREASED} (the pre-change severity).
 * Exactly the finding-type-relevant denormalized fields are populated per {@link #findingKind}.
 */
public class FindingChangeEvent implements Serializable {
	private static final long serialVersionUID = 380001;

	/**
	 * Which finding family this change concerns. The denormalized identity fields
	 * populated depend on this: VULNERABILITY -> vulnId/purl/severity/knownExploited/aliases;
	 * VIOLATION -> violationType/purl; WEAKNESS -> cweId/ruleId/location/severity.
	 */
	public enum FindingKind {
		VULNERABILITY, VIOLATION, WEAKNESS
	}

	private UUID uuid = UUID.randomUUID();

	private UUID org;

	private MetricsEntityType entityType = MetricsEntityType.RELEASE;

	private UUID releaseUuid;

	private String version;

	private UUID componentUuid;

	private String componentName;

	private ZonedDateTime changeDate;

	private int toMetricsRevision;

	private FindingChangeKind changeKind;

	private FindingKind findingKind;

	private String findingKey;

	private String purl;

	private String vulnId;

	private String cweId;

	private String ruleId;

	private String location;

	private String violationType;

	private String severity;

	private String previousSeverity;

	private Boolean knownExploited;

	private String analysisState;

	private Set<VulnerabilityAliasDto> aliases;

	private ZonedDateTime createdDate = ZonedDateTime.now();

	/**
	 * The branch the producing release belongs to, carried through the shared diff so the branch-grain v3
	 * write path ({@code FindingDimBackfillService.writeEventsToV3}) can stamp it. Left null on the
	 * read-time diff.
	 */
	private UUID branchUuid;

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public MetricsEntityType getEntityType() {
		return entityType;
	}

	public void setEntityType(MetricsEntityType entityType) {
		this.entityType = entityType;
	}

	public UUID getReleaseUuid() {
		return releaseUuid;
	}

	public void setReleaseUuid(UUID releaseUuid) {
		this.releaseUuid = releaseUuid;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public UUID getComponentUuid() {
		return componentUuid;
	}

	public void setComponentUuid(UUID componentUuid) {
		this.componentUuid = componentUuid;
	}

	public String getComponentName() {
		return componentName;
	}

	public void setComponentName(String componentName) {
		this.componentName = componentName;
	}

	public ZonedDateTime getChangeDate() {
		return changeDate;
	}

	public void setChangeDate(ZonedDateTime changeDate) {
		this.changeDate = changeDate;
	}

	public int getToMetricsRevision() {
		return toMetricsRevision;
	}

	public void setToMetricsRevision(int toMetricsRevision) {
		this.toMetricsRevision = toMetricsRevision;
	}

	public FindingChangeKind getChangeKind() {
		return changeKind;
	}

	public void setChangeKind(FindingChangeKind changeKind) {
		this.changeKind = changeKind;
	}

	public FindingKind getFindingKind() {
		return findingKind;
	}

	public void setFindingKind(FindingKind findingKind) {
		this.findingKind = findingKind;
	}

	public String getFindingKey() {
		return findingKey;
	}

	public void setFindingKey(String findingKey) {
		this.findingKey = findingKey;
	}

	public String getPurl() {
		return purl;
	}

	public void setPurl(String purl) {
		this.purl = purl;
	}

	public String getVulnId() {
		return vulnId;
	}

	public void setVulnId(String vulnId) {
		this.vulnId = vulnId;
	}

	public String getCweId() {
		return cweId;
	}

	public void setCweId(String cweId) {
		this.cweId = cweId;
	}

	public String getRuleId() {
		return ruleId;
	}

	public void setRuleId(String ruleId) {
		this.ruleId = ruleId;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public String getViolationType() {
		return violationType;
	}

	public void setViolationType(String violationType) {
		this.violationType = violationType;
	}

	public String getSeverity() {
		return severity;
	}

	public void setSeverity(String severity) {
		this.severity = severity;
	}

	public String getPreviousSeverity() {
		return previousSeverity;
	}

	public void setPreviousSeverity(String previousSeverity) {
		this.previousSeverity = previousSeverity;
	}

	public Boolean getKnownExploited() {
		return knownExploited;
	}

	public void setKnownExploited(Boolean knownExploited) {
		this.knownExploited = knownExploited;
	}

	public String getAnalysisState() {
		return analysisState;
	}

	public void setAnalysisState(String analysisState) {
		this.analysisState = analysisState;
	}

	public Set<VulnerabilityAliasDto> getAliases() {
		return aliases;
	}

	public void setAliases(Set<VulnerabilityAliasDto> aliases) {
		this.aliases = aliases;
	}

	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(ZonedDateTime createdDate) {
		this.createdDate = createdDate;
	}

	public UUID getBranchUuid() {
		return branchUuid;
	}

	public void setBranchUuid(UUID branchUuid) {
		this.branchUuid = branchUuid;
	}
}

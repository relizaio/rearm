/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_components")
public class SbomComponent implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234736L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Version
	@Column(nullable = false)
	private int revision = 0;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private String canonicalPurl;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData;

	// Flat {scheme,value} union of identity assertions (purl, cpe, swid, ...)
	// backing the generalized canonical identity. Nullable for pre-migration rows.
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private List<ComponentIdentity> identities;

	// Declared licenses in the exact CycloneDX `licenses` array shape
	// ([{license:{id|name,...}} | {expression}]), re-emitted into synthetic BOMs
	// submitted to Dependency-Track. Stored as the raw CDX array (not the typed
	// LicenseChoice — see CdxLicenseUtil for why); materialized to a LicenseChoice
	// only at the emit boundary. Raw at parse time; overwritten in place with
	// BEAR-enriched licenses by the enrichment puller (see enrichedAt).
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private List<Map<String, Object>> licenses;

	// When BEAR-enriched licenses were pulled for this canonical component
	// (null = not yet enriched). The synthetic Dependency-Track gate ships a
	// BEAR-configured org's component only once this is set, so DTrack receives
	// enriched licenses. Stamped once (fill-once) by the enrichment puller.
	@Column
	private ZonedDateTime enrichedAt;

	/** Per-component queue/terminal state. See {@link SbomComponentFlowControl} and V75. */
	@Type(JsonBinaryType.class)
	@Column(name = "flow_control", columnDefinition = ModelProperties.JSONB)
	private SbomComponentFlowControl flowControl;

	// Sticky synthetic-DTrack bucket assignment (null = not yet assigned). Set
	// once when the component first becomes submittable and never changed, so a
	// new/enriched component only ever re-submits ITS bucket — unlike the old
	// positional slicing, where one insertion shifted every later bucket. See
	// SyntheticSbomService.submitOrg.
	@Column
	private Integer syntheticBucketIndex;

	// Per-component support disclosure (FDA-Readiness-1). First-class columns
	// because they are mutable (manufacturer attestation / enrichment refresh) and
	// queried (the approaching/past-EOS filter) -- unlike the parse-time-immutable
	// recordData. The support STATUS is DERIVED at read time (see SupportStatus),
	// never stored here. supportSource is set server-side per write path and drives
	// the MANUAL > SUPPLIER > ENRICHED precedence; the reconcile path load-merges and
	// leaves these columns untouched. supportNotes is internal-only (never exported).
	@Column(name = "end_of_support_date")
	private LocalDate endOfSupportDate;

	@Column(name = "end_of_life_date")
	private LocalDate endOfLifeDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "support_source")
	private SupportSource supportSource;

	@Column(name = "support_last_assessed")
	private ZonedDateTime supportLastAssessed;

	@Column(name = "support_asserted_by")
	private UUID supportAssertedBy;

	@Column(name = "support_notes")
	private String supportNotes;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	@Override
	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	@Override
	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(ZonedDateTime createdDate) {
		this.createdDate = createdDate;
	}

	@Override
	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}

	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) {
		this.lastUpdatedDate = lastUpdatedDate;
	}

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public String getCanonicalPurl() {
		return canonicalPurl;
	}

	public void setCanonicalPurl(String canonicalPurl) {
		this.canonicalPurl = canonicalPurl;
	}

	@Override
	public Map<String, Object> getRecordData() {
		return recordData;
	}

	@Override
	public void setRecordData(Map<String, Object> recordData) {
		this.recordData = recordData;
	}

	/**
	 * True when this is the BOM's own root/self component (synthesised from
	 * {@code bom.metadata.component}) — the release's artifact, not a dependency.
	 * Stored as a boolean flag inside {@link #recordData} (there is no dedicated
	 * column), so it's exposed here rather than read ad-hoc at call sites.
	 */
	public SbomComponentFlowControl getFlowControl() { return flowControl; }
	public void setFlowControl(SbomComponentFlowControl flowControl) { this.flowControl = flowControl; }

	/** Terminal = no mechanism can ever enrich this row; excluded from the matchable universe like roots. */
	public boolean isEnrichmentTerminal() {
		return flowControl != null && flowControl.enrichmentTerminalAt() != null;
	}

	public boolean isRoot() {
		return recordData != null && Boolean.TRUE.equals(recordData.get("isRoot"));
	}

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}

	public List<ComponentIdentity> getIdentities() {
		return identities;
	}

	public void setIdentities(List<ComponentIdentity> identities) {
		this.identities = identities;
	}

	public List<Map<String, Object>> getLicenses() {
		return licenses;
	}

	public void setLicenses(List<Map<String, Object>> licenses) {
		this.licenses = licenses;
	}

	public ZonedDateTime getEnrichedAt() {
		return enrichedAt;
	}

	public void setEnrichedAt(ZonedDateTime enrichedAt) {
		this.enrichedAt = enrichedAt;
	}

	public Integer getSyntheticBucketIndex() {
		return syntheticBucketIndex;
	}

	public void setSyntheticBucketIndex(Integer syntheticBucketIndex) {
		this.syntheticBucketIndex = syntheticBucketIndex;
	}

	public LocalDate getEndOfSupportDate() {
		return endOfSupportDate;
	}

	public void setEndOfSupportDate(LocalDate endOfSupportDate) {
		this.endOfSupportDate = endOfSupportDate;
	}

	public LocalDate getEndOfLifeDate() {
		return endOfLifeDate;
	}

	public void setEndOfLifeDate(LocalDate endOfLifeDate) {
		this.endOfLifeDate = endOfLifeDate;
	}

	public SupportSource getSupportSource() {
		return supportSource;
	}

	public void setSupportSource(SupportSource supportSource) {
		this.supportSource = supportSource;
	}

	public ZonedDateTime getSupportLastAssessed() {
		return supportLastAssessed;
	}

	public void setSupportLastAssessed(ZonedDateTime supportLastAssessed) {
		this.supportLastAssessed = supportLastAssessed;
	}

	public UUID getSupportAssertedBy() {
		return supportAssertedBy;
	}

	public void setSupportAssertedBy(UUID supportAssertedBy) {
		this.supportAssertedBy = supportAssertedBy;
	}

	public String getSupportNotes() {
		return supportNotes;
	}

	public void setSupportNotes(String supportNotes) {
		this.supportNotes = supportNotes;
	}
}

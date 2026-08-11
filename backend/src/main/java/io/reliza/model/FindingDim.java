/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;

/**
 * The distinct intrinsic identity of a finding (board task #38 normalization). One row per unique
 * intrinsic descriptor, referenced by many {@code finding_change_events_v2} facts -- the ~148x
 * cross-row repetition of finding identity is stored here ONCE.
 *
 * <p>Natural key = {@code (org, dimHash)}, where {@code dimHash} is the {@link FindingDimKey} digest
 * of the STABLE intrinsic tuple ({@code findingKind}, {@code findingKey}, {@code purl}, {@code vulnId},
 * {@code cweId}, {@code ruleId}, {@code location}, {@code violationType}). {@code aliases} is NON-KEY
 * payload (representative variant): it is the sole historically-drifting field, so keying on it would
 * split one finding into multiple dimension rows and break the dedup contract. FK-free, mirroring
 * {@link FindingChangeEvent} / {@link MetricsAudit}.
 */
@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="finding_dim")
public class FindingDim implements Serializable {
	private static final long serialVersionUID = 380002;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(name = "org", nullable = false)
	private UUID org;

	/** 16-byte {@link FindingDimKey} digest of (findingKind, findingKey); unique with org. */
	@Column(name = "dim_hash", nullable = false)
	private byte[] dimHash;

	/** {@code FindingDimKey.KEY_VERSION} the {@link #dimHash} was computed at (re-key detectability). */
	@Column(name = "key_version", nullable = false)
	private short keyVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "finding_kind", nullable = false)
	private FindingKind findingKind;

	@Column(name = "finding_key", nullable = false)
	private String findingKey;

	@Column(name = "purl")
	private String purl;

	@Column(name = "vuln_id")
	private String vulnId;

	@Column(name = "cwe_id")
	private String cweId;

	@Column(name = "rule_id")
	private String ruleId;

	@Column(name = "location")
	private String location;

	@Column(name = "violation_type")
	private String violationType;

	/** NON-KEY payload: representative alias variant for the finding (not part of dimHash). */
	@Type(JsonBinaryType.class)
	@Column(name = "aliases", columnDefinition = ModelProperties.JSONB)
	private Set<VulnerabilityAliasDto> aliases;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public byte[] getDimHash() { return dimHash; }
	public void setDimHash(byte[] dimHash) { this.dimHash = dimHash; }

	public short getKeyVersion() { return keyVersion; }
	public void setKeyVersion(short keyVersion) { this.keyVersion = keyVersion; }

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }

	public FindingKind getFindingKind() { return findingKind; }
	public void setFindingKind(FindingKind findingKind) { this.findingKind = findingKind; }

	public String getFindingKey() { return findingKey; }
	public void setFindingKey(String findingKey) { this.findingKey = findingKey; }

	public String getPurl() { return purl; }
	public void setPurl(String purl) { this.purl = purl; }

	public String getVulnId() { return vulnId; }
	public void setVulnId(String vulnId) { this.vulnId = vulnId; }

	public String getCweId() { return cweId; }
	public void setCweId(String cweId) { this.cweId = cweId; }

	public String getRuleId() { return ruleId; }
	public void setRuleId(String ruleId) { this.ruleId = ruleId; }

	public String getLocation() { return location; }
	public void setLocation(String location) { this.location = location; }

	public String getViolationType() { return violationType; }
	public void setViolationType(String violationType) { this.violationType = violationType; }

	public Set<VulnerabilityAliasDto> getAliases() { return aliases; }
	public void setAliases(Set<VulnerabilityAliasDto> aliases) { this.aliases = aliases; }
}

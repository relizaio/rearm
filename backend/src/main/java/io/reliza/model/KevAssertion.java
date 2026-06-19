/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
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

/**
 * One assertion that a {@link KevSource} reports {@code cveId} as known to
 * be exploited, scoped to a single org (V54 per-org refactor). The token
 * to fetch the catalog (where applicable, e.g. VulnCheck) lives on the
 * matching per-org {@code Integration} row.
 *
 * <p>Unique on {@code (org_id, source, cve_id)}: each (org, source) pair
 * asserts a CVE at most once; one org's CISA + VulnCheck assertions for the
 * same CVE produce two rows; two orgs that both source from CISA produce
 * two rows (deliberate duplication for clean isolation — the user accepted
 * the trade for SaaS multi-tenant correctness).
 *
 * <p>{@code revokedDate} is the soft-delete marker — set when a source
 * stops reporting the CVE within an org, never deleted. A CVE with any
 * assertion (even revoked) in this org still reads as known-exploited; the
 * revocation is surfaced as a note. The full per-source entry lives in
 * {@code recordData} as {@link KevAssertionData}.
 *
 * <p>{@link Version} on {@code revision}: Hibernate owns the increment.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "kev_assertions")
public class KevAssertion implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234752L;

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

	@Column(nullable = false, name = "org_id")
	private UUID orgId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private KevSource source;

	@Column(nullable = false, name = "cve_id")
	private String cveId;

	/** Soft-delete marker: null = active; set = source stopped reporting. */
	@Column(name = "revoked_date")
	private ZonedDateTime revokedDate;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData;

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

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
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

	public UUID getOrgId() {
		return orgId;
	}

	public void setOrgId(UUID orgId) {
		this.orgId = orgId;
	}

	public KevSource getSource() {
		return source;
	}

	public void setSource(KevSource source) {
		this.source = source;
	}

	public String getCveId() {
		return cveId;
	}

	public void setCveId(String cveId) {
		this.cveId = cveId;
	}

	public ZonedDateTime getRevokedDate() {
		return revokedDate;
	}

	public void setRevokedDate(ZonedDateTime revokedDate) {
		this.revokedDate = revokedDate;
	}

	@Override
	public Map<String, Object> getRecordData() {
		return recordData;
	}

	@Override
	public void setRecordData(Map<String, Object> recordData) {
		this.recordData = recordData;
	}
}

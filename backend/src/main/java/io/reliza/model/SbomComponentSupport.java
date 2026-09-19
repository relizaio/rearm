/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Current-state support attestation for one SBOM component (V83,
 * {@code rearm.sbom_component_support}). One row per ASSESSED component; the
 * payload lives in {@link SupportData}.
 *
 * <p><b>Keyed on {@code sbomComponentUuid}, deliberately not on
 * {@code (org, canonicalPurl)}.</b> The canonical purl is recomputed by
 * {@code sweepStaleCanonicalQualifiers} against {@code CANONICAL_FORM_VERSION},
 * so keying a regulatory record on it would convert a detectable dangling uuid
 * into an attestation that silently stops matching its component -- the failure
 * mode that cannot be allowed here. {@link #getCanonicalPurl()} is kept on the
 * row as a recovery hint for a re-link pass that IS NOT YET WRITTEN; no code
 * reads it today, and it must not be treated as an identity.
 *
 * <p>There is deliberately NO {@code schemaVersion}: payload evolution is by additive
 * JSONB fields only. The {@code *Data} classes that carry one pair it with a read guard
 * that throws on an unsupported version; this payload has no such guard, and a column
 * that is declared but never read tells the next person to reshape the payload that
 * versioning is handled when it is not.
 *
 * <p>Written only through {@code SbomComponentService.applySupport}. The BOM
 * reconcile path never touches this table, so a reconcile cannot clobber an
 * attestation -- the two are physically separate rows.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_component_support")
public class SbomComponentSupport implements Serializable {
	private static final long serialVersionUID = 830263L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Version
	@Column(nullable = false)
	private int revision = 0;

	@Column(name = "sbom_component_uuid", nullable = false)
	private UUID sbomComponentUuid;

	@Column(nullable = false)
	private UUID org;

	@Column(name = "canonical_purl", nullable = false)
	private String canonicalPurl;

	@Type(JsonBinaryType.class)
	@Column(name = "support_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private SupportData supportData;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(name = "last_updated_date", nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public int getRevision() { return revision; }

	public UUID getSbomComponentUuid() { return sbomComponentUuid; }
	public void setSbomComponentUuid(UUID sbomComponentUuid) { this.sbomComponentUuid = sbomComponentUuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public String getCanonicalPurl() { return canonicalPurl; }
	public void setCanonicalPurl(String canonicalPurl) { this.canonicalPurl = canonicalPurl; }

	public SupportData getSupportData() { return supportData; }
	public void setSupportData(SupportData supportData) { this.supportData = supportData; }

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }

	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }
}

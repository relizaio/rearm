/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only attestation history for per-component support facts: the
 * ALCOA input-side record of record (who asserted what, when). One row is
 * written per {@code setSbomComponentSupport} edit, capturing the AFTER-image
 * (the asserted values) plus the attester. Shaped after {@link MetricsAudit}:
 * surrogate uuid PK, entity_uuid + revision + org, no FK. Written ONLY on an
 * input edit (operator/enrichment), never by the BOM reconcile path.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_component_support_audit")
public class SbomComponentSupportAudit implements Serializable {
	private static final long serialVersionUID = 234737L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(name = "sbom_component_uuid", nullable = false)
	private UUID sbomComponentUuid;

	@Column(name = "org", nullable = false)
	private UUID org;

	@Column(name = "support_revision", nullable = false)
	private int supportRevision = 0;

	@Column(name = "end_of_support_date")
	private LocalDate endOfSupportDate;

	@Column(name = "end_of_life_date")
	private LocalDate endOfLifeDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "support_source", nullable = false)
	private SupportSource supportSource;

	@Column(name = "support_notes")
	private String supportNotes;

	@Column(name = "support_asserted_by")
	private UUID supportAssertedBy;

	@Column(name = "asserted_date", nullable = false)
	private ZonedDateTime assertedDate = ZonedDateTime.now();

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public UUID getSbomComponentUuid() {
		return sbomComponentUuid;
	}

	public void setSbomComponentUuid(UUID sbomComponentUuid) {
		this.sbomComponentUuid = sbomComponentUuid;
	}

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public int getSupportRevision() {
		return supportRevision;
	}

	public void setSupportRevision(int supportRevision) {
		this.supportRevision = supportRevision;
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

	public String getSupportNotes() {
		return supportNotes;
	}

	public void setSupportNotes(String supportNotes) {
		this.supportNotes = supportNotes;
	}

	public UUID getSupportAssertedBy() {
		return supportAssertedBy;
	}

	public void setSupportAssertedBy(UUID supportAssertedBy) {
		this.supportAssertedBy = supportAssertedBy;
	}

	public ZonedDateTime getAssertedDate() {
		return assertedDate;
	}

	public void setAssertedDate(ZonedDateTime assertedDate) {
		this.assertedDate = assertedDate;
	}
}

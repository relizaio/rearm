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

/**
 * Append-only history of support attestations (V83,
 * {@code rearm.sbom_component_support_audit}) -- the ALCOA input-side record of
 * record. One row per accepted write, storing the entire after-image.
 *
 * <p>There is deliberately NO delete path, on this class or its repository. A
 * correction supersedes by writing a further row (see
 * {@link SupportState#WITHDRAWN}); erasing history is what this table exists to
 * prevent.
 *
 * <p>{@link #getAssertedDate()} is the SYSTEM's contemporaneous record of when
 * the assertion was filed. It is NOT {@code supportData.assessedAt}, which is
 * caller-supplied and states when the human did the assessment. Those differ
 * whenever someone records earlier work, and conflating them would misstate the
 * evidence to a reviewer.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_component_support_audit")
public class SbomComponentSupportAudit implements Serializable {
	private static final long serialVersionUID = 830264L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(name = "sbom_component_uuid", nullable = false)
	private UUID sbomComponentUuid;

	@Column(nullable = false)
	private UUID org;

	@Column(name = "support_revision", nullable = false)
	private int supportRevision;

	@Type(JsonBinaryType.class)
	@Column(name = "support_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private SupportData supportData;

	@Column(name = "asserted_by")
	private UUID assertedBy;

	@Column(name = "asserted_date", nullable = false)
	private ZonedDateTime assertedDate = ZonedDateTime.now();

	/**
	 * Why this write happened -- NOT the same fact as
	 * {@code supportData.justification}, which is the basis for the level-of-support
	 * claim. Sharing one field would let a milestone removal's reason overwrite the
	 * basis for an ABANDONED attestation, and that basis is exported.
	 */
	@Column
	private String reason;

	/**
	 * The bulk sweep this row belongs to, or null when the attestation was made one component
	 * at a time. This is the only thing in the record that says hundreds of writes were one
	 * act rather than hundreds of separate judgements -- assessmentSource is MANUAL either way.
	 *
	 * <p>Server-ISSUED, not server-enforced. The server generates the id, and batches 2..n of a
	 * paged sweep echo back what the first call returned so one sweep is one id however many
	 * round trips it took. A caller may also supply an arbitrary one: the argument is taken
	 * verbatim. See the comment on the mutation for why validating it would break the
	 * legitimate paged case, and what the exposure is bounded to.
	 */
	@Column(name = "batch_id")
	private UUID batchId;

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getSbomComponentUuid() { return sbomComponentUuid; }
	public void setSbomComponentUuid(UUID sbomComponentUuid) { this.sbomComponentUuid = sbomComponentUuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public int getSupportRevision() { return supportRevision; }
	public void setSupportRevision(int supportRevision) { this.supportRevision = supportRevision; }

	public SupportData getSupportData() { return supportData; }
	public void setSupportData(SupportData supportData) { this.supportData = supportData; }

	public UUID getAssertedBy() { return assertedBy; }
	public void setAssertedBy(UUID assertedBy) { this.assertedBy = assertedBy; }

	public ZonedDateTime getAssertedDate() { return assertedDate; }
	public void setAssertedDate(ZonedDateTime assertedDate) { this.assertedDate = assertedDate; }

	public String getReason() { return reason; }
	public void setReason(String reason) { this.reason = reason; }

	public UUID getBatchId() { return batchId; }
	public void setBatchId(UUID batchId) { this.batchId = batchId; }
}

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
 * The current hash justifications of one release SBOM identity head (FDAW-14 3.1 item 1, 3.3), keyed by
 * {@code release_sboms.uuid}. Mutable: {@code revision} is moved by
 * {@code ReleaseSbomJustificationService} on every write that set or cleared a key, and every write
 * files one insert-only {@link ReleaseSbomJustificationAudit}. {@code org} and {@code release} are copied
 * from the head at the first write so the row reads alone; the head stays the authority at every write.
 * No {@code @Version}: a write holds the row with {@code SELECT ... FOR UPDATE}.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sbom_justifications")
public class ReleaseSbomJustification implements Serializable {
	private static final long serialVersionUID = 20261106L;

	@Id
	@Column(name = "release_sbom")
	private UUID releaseSbom;

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID release;

	@Column(nullable = false)
	private int revision = 0;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(name = "last_updated_date", nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(name = "schema_version", nullable = false)
	private int schemaVersion = 0;

	@Type(JsonBinaryType.class)
	@Column(name = "record_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private ReleaseSbomJustificationData recordData = ReleaseSbomJustificationData.empty();

	public UUID getReleaseSbom() { return releaseSbom; }
	public void setReleaseSbom(UUID releaseSbom) { this.releaseSbom = releaseSbom; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getRelease() { return release; }
	public void setRelease(UUID release) { this.release = release; }

	public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }

	public ZonedDateTime getCreatedDate() { return createdDate; }

	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

	public int getSchemaVersion() { return schemaVersion; }

	public ReleaseSbomJustificationData getRecordData() { return recordData; }
	public void setRecordData(ReleaseSbomJustificationData recordData) { this.recordData = recordData; }
}

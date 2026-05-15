/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

/**
 * Per (canonical artifact, sbom_component) row. The artifact-keyed SBOM
 * storage introduced by V37 — replaces the prior release-keyed
 * {@code release_sbom_components} table.
 *
 * <p>One row per component this canonical artifact's BOM lists.
 * {@link #exactPurl} is the qualifier-bearing PURL as declared in the
 * BOM; {@link #parents} is the in-edges declared in this BOM where the
 * row's {@link #sbomComponentUuid} is the target.
 *
 * <p>Read-time merge for a release union-aggregates the rows of every
 * canonical artifact the release references (resolved through
 * {@link ArtifactCanonicalMap}). Per-release rows aren't persisted.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "artifact_sbom_components")
public class ArtifactSbomComponent implements Serializable {
	private static final long serialVersionUID = 234742L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID canonicalArtifactUuid;

	@Column(nullable = false)
	private UUID sbomComponentUuid;

	@Column(nullable = false)
	private String exactPurl;

	/**
	 * In-edges this canonical artifact declared for the target component.
	 * Each entry:
	 * <pre>{@code
	 * {
	 *   "sourceSbomComponentUuid": "<canonical sbom_component uuid>",
	 *   "sourceCanonicalPurl":     "<canonical PURL>",
	 *   "relationshipType":        "DEPENDS_ON",
	 *   "sourceExactPurl":         "<as declared in this BOM>",
	 *   "targetExactPurl":         "<as declared in this BOM>"
	 * }
	 * }</pre>
	 * The {@code declaringArtifacts} wrapper from the public GraphQL shape
	 * collapses — the row IS the declaration. Read-time merge across
	 * canonical artifacts in a release synthesizes the wrapper back.
	 */
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private List<Map<String, Object>> parents;

	@Column(nullable = false)
	private ZonedDateTime parsedAt = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getCanonicalArtifactUuid() { return canonicalArtifactUuid; }
	public void setCanonicalArtifactUuid(UUID canonicalArtifactUuid) { this.canonicalArtifactUuid = canonicalArtifactUuid; }

	public UUID getSbomComponentUuid() { return sbomComponentUuid; }
	public void setSbomComponentUuid(UUID sbomComponentUuid) { this.sbomComponentUuid = sbomComponentUuid; }

	public String getExactPurl() { return exactPurl; }
	public void setExactPurl(String exactPurl) { this.exactPurl = exactPurl; }

	public List<Map<String, Object>> getParents() { return parents; }
	public void setParents(List<Map<String, Object>> parents) { this.parents = parents; }

	public ZonedDateTime getParsedAt() { return parsedAt; }
	public void setParsedAt(ZonedDateTime parsedAt) { this.parsedAt = parsedAt; }
}

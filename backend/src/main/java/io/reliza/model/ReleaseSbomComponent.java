/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-time view of "the SBOM-component aggregation for one release, one
 * canonical sbom_component". Not persisted — no @Entity annotation.
 *
 * <p>Since V37 (artifact-keyed shape), the underlying storage lives in
 * {@code artifact_sbom_components} keyed by canonical artifact, not per
 * (release, sbom_component). This class is the synthesized per-(release,
 * sbom_component) shape the GraphQL surface still exposes — built by
 * {@code SbomComponentService.listReleaseSbomComponents} from one or more
 * artifact rows that participate in the release's BOM set.
 *
 * <p>The {@code uuid} field is a deterministic v5 derivation from
 * (release_uuid, sbom_component_uuid) — stable across reconciles so UI
 * deep links survive without needing a persisted anchor row.
 *
 * <p>{@code artifactParticipations} and {@code parents} preserve the
 * pre-V37 GraphQL shape: lists of map-shaped entries that the
 * {@link io.reliza.ws.SbomComponentDataFetcher} resolvers read directly.
 */
public class ReleaseSbomComponent implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234737L;

	private UUID uuid;
	private ZonedDateTime createdDate;
	private ZonedDateTime lastUpdatedDate;
	private UUID org;
	private UUID releaseUuid;
	private UUID sbomComponentUuid;
	private List<Map<String, Object>> artifactParticipations;
	private List<Map<String, Object>> parents;

	@Override
	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	@Override
	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }

	@Override
	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getReleaseUuid() { return releaseUuid; }
	public void setReleaseUuid(UUID releaseUuid) { this.releaseUuid = releaseUuid; }

	public UUID getSbomComponentUuid() { return sbomComponentUuid; }
	public void setSbomComponentUuid(UUID sbomComponentUuid) { this.sbomComponentUuid = sbomComponentUuid; }

	public List<Map<String, Object>> getArtifactParticipations() { return artifactParticipations; }
	public void setArtifactParticipations(List<Map<String, Object>> artifactParticipations) {
		this.artifactParticipations = artifactParticipations;
	}

	public List<Map<String, Object>> getParents() { return parents; }
	public void setParents(List<Map<String, Object>> parents) { this.parents = parents; }

	@Override
	public Map<String, Object> getRecordData() { return null; }

	@Override
	public void setRecordData(Map<String, Object> recordData) { /* no-op: not persisted */ }

	@Override
	public int getRevision() { return 0; }

	@Override
	public int getSchemaVersion() { return 0; }
}

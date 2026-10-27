/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The TEA publication head of one release (task TEA-5), as the service and the API read it. The
 * uuid is the TEA release and collection id; {@code latestVersion} is the newest collection
 * version, 0 before the first.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeaPublicationData extends RelizaDataParent implements RelizaObject {

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	@JsonProperty
	private UUID component;
	/** Fixes {@code belongsTo} of every collection of this publication. */
	@JsonProperty
	private ComponentType componentType;
	@JsonProperty
	private TeaPublicationState state;
	@JsonProperty
	private int latestVersion;
	/** The profile row applied at the last publish, and its revision then. */
	@JsonProperty
	private UUID profile;
	@JsonProperty
	private int profileRevision;
	/** Where the applied profile resolved from, for the page. */
	@JsonProperty
	private TeaProfileSource profileSource;
	@JsonProperty
	private UUID profileSourceObject;
	/** The product publication that published this release through its cascade, or null. */
	@JsonProperty
	private UUID cascadeOf;
	/**
	 * The generated aggregated SBOMs, one per SBOM profile name, in the order the last publish
	 * published the names (task TEA-28). A record without the key reads an empty list.
	 */
	@JsonProperty
	private List<TeaAggregateArtifact> aggregateArtifacts = new ArrayList<>();
	/** The stamped TEI, or null. */
	@JsonProperty
	private String tei;
	@JsonProperty
	private ZonedDateTime firstPublishedDate;
	@JsonProperty
	private ZonedDateTime lastPublishedDate;
	@JsonProperty
	private ZonedDateTime lastHiddenDate;
	/** The user or key of the last publish. */
	@JsonProperty
	private UUID lastPublishedBy;

	/** One named aggregate of a publication: the SBOM profile name and the generated artifact row (task TEA-28). */
	public record TeaAggregateArtifact(String profileName, UUID artifact) implements Serializable {}

	/** The head row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	/** Null reads as no aggregate, so a reader never meets a null list. */
	public void setAggregateArtifacts(List<TeaAggregateArtifact> aggregateArtifacts) {
		this.aggregateArtifacts = null == aggregateArtifacts ? new ArrayList<>() : new ArrayList<>(aggregateArtifacts);
	}

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	public static TeaPublicationData dataFromRecord(TeaPublication p) {
		if (p.getSchemaVersion() != 0) {
			throw new IllegalStateException("TEA publication schema version is " + p.getSchemaVersion()
					+ ", which is not currently supported");
		}
		TeaPublicationData d = Utils.OM.convertValue(p.getRecordData(), TeaPublicationData.class);
		d.setUuid(p.getUuid());
		d.setOrg(p.getOrg());
		d.setRelease(p.getRelease());
		d.setComponent(p.getComponent());
		d.setRevision(p.getRevision());
		d.setCreatedDate(p.getCreatedDate());
		d.setUpdatedDate(p.getLastUpdatedDate());
		return d;
	}

	@Override
	@JsonIgnore
	public UUID getResourceGroup() {
		return null;
	}
}

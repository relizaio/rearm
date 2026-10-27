/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.ComponentType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The head of a regulatory submission (task TEA-35): one per release and package kind, mutable by
 * design (state, latest version, last act, withdrawal), audited on every update. Its versions are
 * immutable rows of their own ({@link SubmissionVersionData}). {@code createdDate} is the first act's.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubmissionData extends RelizaDataParent implements RelizaObject {

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	@JsonProperty
	private UUID component;
	/** The component's type at the first create. */
	@JsonProperty
	private ComponentType componentType;
	@JsonProperty
	private PackageKind kind;
	@JsonProperty
	private SubmissionState state;
	/** 0 before the first version. */
	@JsonProperty
	private int latestVersion;
	@JsonProperty
	private UUID createdBy;
	@JsonProperty
	private ZonedDateTime lastActDate;
	@JsonProperty
	private UUID lastActBy;
	@JsonProperty
	private SubmissionActOutcome lastActKind;
	/** Set by a withdrawal, cleared by a resubmission that re-activates the head. */
	@JsonProperty
	private ZonedDateTime withdrawnDate;
	@JsonProperty
	private UUID withdrawnBy;
	@JsonProperty
	private String withdrawReason;
	/** The settings applied at the latest version. */
	@JsonProperty
	private SubmissionSettingsRef settings;

	/** The head row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	/**
	 * The head as data: the key and index columns ({@code uuid}, {@code org}, {@code release},
	 * {@code component}, {@code kind}, {@code state}, {@code latest_version}), the revision and the
	 * created date come from the row, overriding the record. The entity lives in a Pro package, so
	 * its columns are passed in.
	 */
	public static SubmissionData dataFromRecord(RelizaEntity row, UUID org, UUID release, UUID component, PackageKind kind,
			SubmissionState state, int latestVersion) {
		if (row.getSchemaVersion() != 0) {
			throw new IllegalStateException("Submission schema version is " + row.getSchemaVersion()
					+ ", which is not currently supported");
		}
		SubmissionData d = Utils.OM.convertValue(row.getRecordData(), SubmissionData.class);
		d.setUuid(row.getUuid());
		d.setOrg(org);
		d.setRelease(release);
		d.setComponent(component);
		d.setKind(kind);
		d.setState(state);
		d.setLatestVersion(latestVersion);
		d.setRevision(row.getRevision());
		d.setCreatedDate(row.getCreatedDate());
		d.setUpdatedDate(row.getLastUpdatedDate());
		return d;
	}

	@Override
	@JsonIgnore
	public UUID getResourceGroup() {
		return null;
	}
}

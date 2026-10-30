/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import lombok.Data;

/**
 * One immutable version of a regulatory submission (task TEA-35). {@code uuid} is the submission
 * (head) uuid. The manifest text, its sha256 and the signature slot are columns of their own and
 * never part of the JSONB record.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubmissionVersionData {

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private int version;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	@JsonProperty
	private PackageKind kind;
	@JsonProperty
	private ZonedDateTime createdDate;
	@JsonProperty
	private UUID createdBy;
	/** Null on a create without one. */
	@JsonProperty
	private String reason;
	@JsonProperty
	private SubmissionSettingsRef settings;
	@JsonProperty
	private SubmissionProfileRef sbomProfile;
	/** Manifest order; RAW_ARTIFACTS holds one record per referenced artifact. */
	@JsonProperty
	private List<SubmissionPartRecord> parts = new ArrayList<>();
	@JsonProperty
	private UUID manifestArtifact;
	@JsonProperty
	private String manifestArtifactVersion;

	@JsonIgnore
	private String manifestSha256;
	@JsonIgnore
	private String manifest;
	@JsonIgnore
	private String signature;

	/** The submission (head) this version belongs to: the wire name of {@code uuid}. */
	@JsonIgnore
	public UUID getSubmission() {
		return uuid;
	}

	public void setParts(List<SubmissionPartRecord> parts) {
		this.parts = null == parts ? new ArrayList<>() : new ArrayList<>(parts);
	}

	/** The JSONB record: everything but the manifest, its sha256 and the signature. */
	public Map<String, Object> toRecordData() {
		@SuppressWarnings("unchecked")
		Map<String, Object> m = Utils.OM.convertValue(this, java.util.LinkedHashMap.class);
		return m;
	}

	/**
	 * The version as data: the key and the other columns override the record; the manifest, its
	 * sha256 and the signature come from their columns only. The entity lives in a Pro package, so
	 * its columns are passed in.
	 */
	public static SubmissionVersionData dataFromRecord(Map<String, Object> recordData, int schemaVersion, UUID uuid,
			int version, UUID org, UUID release, ZonedDateTime createdDate, UUID createdBy, String manifest,
			String manifestSha256, String signature) {
		if (schemaVersion != 0) {
			throw new IllegalStateException("Submission version schema version is " + schemaVersion
					+ ", which is not currently supported");
		}
		SubmissionVersionData d = Utils.OM.convertValue(recordData, SubmissionVersionData.class);
		d.setUuid(uuid);
		d.setVersion(version);
		d.setOrg(org);
		d.setRelease(release);
		d.setCreatedDate(createdDate);
		d.setCreatedBy(createdBy);
		d.setManifest(manifest);
		d.setManifestSha256(manifestSha256);
		d.setSignature(signature);
		return d;
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The working copy of a submission's next version (FDAW-1 D2, table {@code rearm.submission_drafts}):
 * one per release and kind, mutable, saved whole with its revision. {@code submission} is null until
 * the first version exists. The draft verbs and the rest of the record come with FDAW-1 A3; nothing
 * writes a draft yet.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubmissionDraftData extends RelizaDataParent implements RelizaObject {

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	@JsonProperty
	private UUID component;
	@JsonProperty
	private PackageKind kind;
	@JsonProperty
	private UUID submission;
	@JsonProperty
	private SubmissionDraftState state;

	/** The draft row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	/**
	 * The draft as data: the key and index columns, the revision and the dates come from the row,
	 * overriding the record. The entity lives in a Pro package, so its columns are passed in.
	 */
	public static SubmissionDraftData dataFromRecord(RelizaEntity row, UUID org, UUID release, UUID component,
			PackageKind kind, UUID submission, SubmissionDraftState state) {
		if (row.getSchemaVersion() != 0) {
			throw new IllegalStateException("Submission draft schema version is " + row.getSchemaVersion()
					+ ", which is not currently supported");
		}
		SubmissionDraftData d = Utils.OM.convertValue(row.getRecordData(), SubmissionDraftData.class);
		d.setUuid(row.getUuid());
		d.setOrg(org);
		d.setRelease(release);
		d.setComponent(component);
		d.setKind(kind);
		d.setSubmission(submission);
		d.setState(state);
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

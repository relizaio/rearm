/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The working copy of a submission's next version (FDAW-1 D2, table {@code rearm.submission_drafts}):
 * one per release and kind, mutable, saved whole with its revision. {@code submission} is null until
 * the first version exists; {@code plannedSubmission} is the head's uuid, minted at Start for a first
 * draft, so a part can name its submission before it exists. A key caller reads none of this
 * (FDAW-29 3.3b).
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

	@JsonProperty
	private UUID plannedSubmission;
	@JsonProperty
	private int plannedVersion;
	@JsonProperty
	private ZonedDateTime startedAt;
	@JsonProperty
	private DraftActor startedBy;
	@JsonProperty
	private NewVersionReason reasonCategory;
	/** Null only on a FIRST_VERSION draft; bounded and stored trimmed. */
	@JsonProperty
	private String reasonText;
	@JsonProperty
	private ReplacesRef replaces;
	@JsonProperty
	private DraftJob job;
	/** The last successful build; a failed job leaves it as it was. */
	@JsonProperty
	private DraftBuild build;
	/** Set by Confirm, cleared by Return to draft and by any Rebuild. */
	@JsonProperty
	private HeldConfirmation confirmation;

	/** The value a Start or Discard release event names the draft by. */
	public static String eventLabel(PackageKind kind) {
		return kind.name() + " draft";
	}

	/** Whether the release event is a draft's own: the writer and the key-lane filter share the label. */
	public static boolean isDraftEvent(ReleaseUpdateEvent e) {
		if (null == e || ReleaseUpdateScope.SUBMISSION != e.rus()) return false;
		return Arrays.stream(PackageKind.values()).map(SubmissionDraftData::eventLabel)
				.anyMatch(label -> label.equals(e.oldValue()) || label.equals(e.newValue()));
	}

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
		if (null == d.getJob()) d.setJob(DraftJob.none());
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

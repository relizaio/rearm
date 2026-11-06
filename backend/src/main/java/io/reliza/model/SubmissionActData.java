/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import lombok.Data;

/**
 * One row of the append-only submission act record (FDAW-1 D2, table {@code rearm.submission_acts}):
 * who did what to which version, when and why. The key, target, kind, actor and cause are columns of
 * their own and never part of the JSONB record; the record holds the actor's snapshot (USER acts
 * only), the capacity, the reason, and the payload of the act's kind. A SYSTEM or MIGRATION act names
 * no person: its record carries no {@code actorName}, {@code actorEmail} or {@code capacity} key
 * (the record leaves out every null value). Boxed fields and unknown keys ignored, so a row written
 * by the migration's SQL reads through the same class.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubmissionActData {

	/** Whether the act's version carries a confirmation record; record-only, not on the wire. */
	public enum ConfirmationRecord {
		NONE, RECORDED
	}

	// ------------------------------------------------------------------ the columns

	@JsonIgnore
	private UUID uuid;
	@JsonIgnore
	private UUID org;
	/** The head; null on an act of a draft whose first version does not exist yet. */
	@JsonIgnore
	private UUID submission;
	/** The draft; null on an act on a version. */
	@JsonIgnore
	private UUID draft;
	@JsonIgnore
	private UUID release;
	@JsonIgnore
	private Integer version;
	@JsonIgnore
	private SubmissionActKind kind;
	/** The person who acted: set on a USER act only. */
	@JsonIgnore
	private UUID actor;
	@JsonIgnore
	private ActCause cause;
	/** The act a SYSTEM act follows. */
	@JsonIgnore
	private UUID causedBy;
	@JsonIgnore
	private ZonedDateTime actedAt;

	// ------------------------------------------------------------------ the record

	/** The actor's name when the act was written (USER acts only). */
	@JsonProperty
	private String actorName;
	/** The actor's e-mail in the organization when the act was written (USER acts only). */
	@JsonProperty
	private String actorEmail;
	@JsonProperty
	private ConfirmationCapacity capacity;
	@JsonProperty
	private String reasonText;
	@JsonProperty
	private String manifestSha256;
	@JsonProperty
	private ConfirmationRecord confirmationRecord;
	/** SUPERSEDED and AMENDED: the version that superseded or amended this one. */
	@JsonProperty
	private Integer byVersion;
	/** MIGRATED: the state before V107. */
	@JsonProperty
	private SubmissionLegacyState fromState;
	/** MIGRATED: the state V107 wrote. */
	@JsonProperty
	private SubmissionState toState;
	@JsonProperty
	private Integer latestVersion;
	/** MIGRATED: sha256 of the head row before V107 (the formula is in V107's header). */
	@JsonProperty
	private String beforeHash;
	/** MIGRATED: sha256 of the head row as V107 left it. */
	@JsonProperty
	private String afterHash;
	/** MIGRATED from WITHDRAWN: the withdrawal triple copied from the head record. */
	@JsonProperty
	private ZonedDateTime withdrawnDate;
	@JsonProperty
	private UUID withdrawnBy;
	@JsonProperty
	private String withdrawReason;

	/** The JSONB record: the record keys whose value is not null. */
	public Map<String, Object> toRecordData() {
		@SuppressWarnings("unchecked")
		Map<String, Object> m = Utils.OM.convertValue(this, LinkedHashMap.class);
		m.values().removeIf(v -> null == v);
		return m;
	}

	/**
	 * The act as data: the record, then the columns over it. The entity lives in a Pro package, so its
	 * columns are passed in.
	 */
	public static SubmissionActData dataFromRecord(Map<String, Object> recordData, int schemaVersion, UUID uuid,
			UUID org, UUID submission, UUID draft, UUID release, Integer version, SubmissionActKind kind, UUID actor,
			ActCause cause, UUID causedBy, ZonedDateTime actedAt) {
		if (schemaVersion != 0) {
			throw new IllegalStateException("Submission act schema version is " + schemaVersion
					+ ", which is not currently supported");
		}
		SubmissionActData d = Utils.OM.convertValue(null == recordData ? Map.of() : recordData,
				SubmissionActData.class);
		d.setUuid(uuid);
		d.setOrg(org);
		d.setSubmission(submission);
		d.setDraft(draft);
		d.setRelease(release);
		d.setVersion(version);
		d.setKind(kind);
		d.setActor(actor);
		d.setCause(cause);
		d.setCausedBy(causedBy);
		d.setActedAt(actedAt);
		return d;
	}
}

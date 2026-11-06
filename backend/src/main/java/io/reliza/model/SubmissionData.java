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
import io.reliza.model.ComponentData.ComponentType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The head of a regulatory submission (task TEA-35): one per release and package kind, mutable by
 * design (state, latest version, last act), audited on every update. Its versions are immutable rows
 * of their own ({@link SubmissionVersionData}); the append-only act record ({@link SubmissionActData})
 * is the record of who did what, and the head is its view (FDAW-1 D2): {@code versionStates} holds
 * each version's state and {@code state} the latest version's. {@code createdDate} is the first act's.
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
	/** The latest version's state (the column). */
	@JsonProperty
	private SubmissionState state;
	/** The state the head held before V107 migrated it; null on a head created since (FDAW-1 D6). */
	@JsonProperty
	private SubmissionLegacyState legacyState;
	/**
	 * Each version's state, in version order, as the acts left it; null on a head V107 migrated and
	 * no act has saved since ({@link #versionStatesOrDerived()} answers it then).
	 */
	@JsonProperty
	private List<VersionState> versionStates;
	/** The version recorded as sent to the agency; null while none is (no act writes SENT yet). */
	@JsonProperty
	private Integer currentSentVersion;
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
	/** Read-only since FDAW-8; written by no act (the withdrawal of a head from before V107). */
	@JsonProperty
	private ZonedDateTime withdrawnDate;
	/** Read-only since FDAW-8; written by no act. */
	@JsonProperty
	private UUID withdrawnBy;
	/** Read-only since FDAW-8; written by no act. */
	@JsonProperty
	private String withdrawReason;
	/** The settings applied at the latest version. */
	@JsonProperty
	private SubmissionSettingsRef settings;

	/** The head row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	/**
	 * One version's state. {@code byVersion} is the version that superseded or amended this one, null
	 * otherwise.
	 */
	public record VersionState(int version, SubmissionState state, Integer byVersion) {}

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	/**
	 * The stored {@code versionStates}, or, on a migrated head that no act has saved since (null list,
	 * FDAW-1 D6 item 5), the view the migration implies: every earlier version SUPERSEDED by the
	 * latest, the latest in the head's state. A new list each call; empty before the first version.
	 */
	public List<VersionState> versionStatesOrDerived() {
		if (null != versionStates) return new ArrayList<>(versionStates);
		List<VersionState> derived = new ArrayList<>();
		for (int v = 1; v < latestVersion; v++) {
			derived.add(new VersionState(v, SubmissionState.SUPERSEDED, latestVersion));
		}
		if (latestVersion > 0) derived.add(new VersionState(latestVersion, state, null));
		return derived;
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

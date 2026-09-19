/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import lombok.Data;

/**
 * An accountable statement by a user or an agent about something.
 *
 * <p>Append-only: a correction is a new row plus a revocation of the old one, never an edit. That
 * is the whole point -- an attestation is the record somebody stood behind a claim, and a record
 * that can be edited afterwards is not one.
 *
 * <p>One table for several types. A commit acknowledgement is what turns an unaccountable commit
 * into a claimed one, and a lock release is who released a lock and why. Key ownership and review
 * are the next two and need no schema change.
 *
 * <p>Enums are nested rather than top-level because the names are generic enough to collide --
 * {@code AttestationStatus} is already taken by the mitigation attestations, which are a separate
 * table and stay that way for now.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttestationData extends RelizaDataParent implements RelizaObject {

	/** What kind of statement this is. */
	public enum Type {
		/** A principal claims or disowns a commit or a build. */
		COMMIT_ACKNOWLEDGEMENT,
		/** Who released a lock, why, and whether it was an administrator override. */
		LOCK_RELEASE
	}

	public enum SubjectType { SCE, RELEASE, LOCK }

	public enum ActorType { USER, AGENT }

	/** Verdicts of a {@link Type#COMMIT_ACKNOWLEDGEMENT}. */
	public enum Verdict {
		/**
		 * "This is mine." Makes the commit recognized. It does not certify the content, does not
		 * touch any lifecycle, and is not a review -- whether the claim means "fixed later" or
		 * "fine as it is" belongs in the note.
		 */
		MINE,
		/**
		 * "This is not mine." Leaves the commit unrecognized and escalates every lock it is a
		 * cause of to an administrator's call.
		 */
		NOT_MINE
	}

	/** Whether the row still counts. Revoked rows stay for the audit trail. */
	public enum RecordStatus { ACTIVE, REVOKED }

	@JsonProperty private UUID uuid;
	@JsonProperty private UUID org;
	@JsonProperty private Type type;
	@JsonProperty private SubjectType subjectType;
	@JsonProperty private UUID subjectUuid;
	/** Display only: what the subject was called when the statement was made. */
	@JsonProperty private SubjectRef subjectRef;
	@JsonProperty private ActorType actorType;
	/** User uuid or agent uuid, per {@link #actorType}. */
	@JsonProperty private UUID actorUuid;
	/** AGENT: the session the statement was filed under, when the key had one open. */
	@JsonProperty private UUID agentSession;
	/** Type-specific; {@link Verdict} for a commit acknowledgement, unset for a lock release. */
	@JsonProperty private Verdict verdict;
	@JsonProperty private String note;
	/** LOCK_RELEASE only: released without the requirement being met. Never silent. */
	@JsonProperty private boolean override;
	@JsonProperty private RecordStatus status = RecordStatus.ACTIVE;
	@JsonProperty private UUID revokedBy;
	@JsonProperty private ZonedDateTime revokedAt;
	@JsonProperty private String revokeReason;
	/** Reserved: a signed statement over this record, verified through the enrolled key. */
	@JsonProperty private UUID signatureArtifact;
	@JsonProperty private ZonedDateTime createdDate;

	/** What the subject was called, so a list reads without resolving every subject. */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SubjectRef {
		@JsonProperty private UUID component;
		@JsonProperty private String componentName;
		@JsonProperty private String commit;
		@JsonProperty private String version;

		public static SubjectRef of(UUID component, String componentName, String commit, String version) {
			SubjectRef r = new SubjectRef();
			r.component = component;
			r.componentName = componentName;
			r.commit = commit;
			r.version = version;
			return r;
		}
	}

	public boolean isActive() {
		return status == null || status == RecordStatus.ACTIVE;
	}

	/** True when this row is an active MINE claim, which is what recognition turns on. */
	public boolean claimsSubject() {
		return isActive() && type == Type.COMMIT_ACKNOWLEDGEMENT && verdict == Verdict.MINE;
	}

	public boolean disownsSubject() {
		return isActive() && type == Type.COMMIT_ACKNOWLEDGEMENT && verdict == Verdict.NOT_MINE;
	}

	public static AttestationData create(UUID org, Type type, SubjectType subjectType, UUID subjectUuid,
			SubjectRef subjectRef, ActorType actorType, UUID actorUuid, UUID agentSession,
			Verdict verdict, String note, boolean override) {
		AttestationData d = new AttestationData();
		d.uuid = UUID.randomUUID();
		d.org = org;
		d.type = type;
		d.subjectType = subjectType;
		d.subjectUuid = subjectUuid;
		d.subjectRef = subjectRef;
		d.actorType = actorType;
		d.actorUuid = actorUuid;
		d.agentSession = agentSession;
		d.verdict = verdict;
		d.note = note;
		d.override = override;
		d.status = RecordStatus.ACTIVE;
		d.createdDate = ZonedDateTime.now();
		return d;
	}

	public static AttestationData dataFromRecord(Attestation a) {
		AttestationData d = Utils.OM.convertValue(a.getRecordData(), new TypeReference<AttestationData>() {});
		d.setUuid(a.getUuid());
		return d;
	}

	public Map<String, Object> toRecordData() {
		return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
	}

	@Override
	public UUID getResourceGroup() {
		return null;
	}
}

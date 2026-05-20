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

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.SigningKeyData.SignatureFormat;
import io.reliza.model.SigningKeyData.SigningKeyOwnerType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * Verdict snapshot for a single (subject, signature-artifact) pair.
 * Refreshed when the verifier is re-run; historical record is the
 * source of truth for CEL policy decisions on a given release.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignatureVerificationData extends RelizaDataParent implements RelizaObject {

	public enum SignatureVerificationState {
		UNSIGNED,
		VERIFIED,
		INVALID_SIGNATURE,
		UNKNOWN_KEY,
		KEY_REVOKED,
		WRONG_SIGNER,
		PENDING,
		ERRORED
	}

	public enum SignatureSubjectType {
		SCE,
		ARTIFACT
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	@JsonProperty
	private SignatureSubjectType subjectType;

	@JsonProperty
	private UUID subjectUuid;

	/** Artifact uuid carrying the signature bytes. */
	@JsonProperty
	private UUID signatureArtifactUuid;

	/**
	 * Artifact uuid carrying the signed payload, when it isn't
	 * implicit from the subject. For git-commit signatures this is
	 * null — payload is derived from the SCE's commit object.
	 */
	@JsonProperty
	private UUID signedPayloadArtifactUuid;

	/**
	 * Artifact uuid for an ephemeral certificate (sigstore-style).
	 * Null for SSH / GPG.
	 */
	@JsonProperty
	private UUID publicKeyArtifactUuid;

	@JsonProperty
	private SignatureFormat format;

	@JsonProperty
	private SignatureVerificationState verdict = SignatureVerificationState.PENDING;

	/** Set on VERIFIED — the agent or committer the key belongs to. */
	@JsonProperty
	private SigningKeyOwnerType ownerType;

	@JsonProperty
	private UUID ownerUuid;

	/** Set on VERIFIED — fingerprint of the matched enrolled key. */
	@JsonProperty
	private String keyFingerprint;

	@JsonProperty
	private ZonedDateTime verifiedAt;

	/** Bump when verifier semantics change; lets us re-verify stale rows. */
	@JsonProperty
	private Integer verifierVersion;

	/** Free-form diagnostic for the UI / debugging. */
	@JsonProperty
	private String details;

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static SignatureVerificationData dataFromRecord(SignatureVerification v) {
		if (v.getSchemaVersion() != 0) {
			throw new IllegalStateException("SignatureVerification schema version is " + v.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = v.getRecordData();
		SignatureVerificationData vd = Utils.OM.convertValue(recordData, SignatureVerificationData.class);
		vd.setUuid(v.getUuid());
		vd.setCreatedDate(v.getCreatedDate());
		return vd;
	}
}

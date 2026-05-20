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
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * Polymorphic enrolled public key. ownerType + ownerUuid point at the
 * agent or committer that holds the corresponding private key. The
 * verifier never trusts the key embedded in a signature blob — it
 * matches signatures to this table by {@link #fingerprint} and refuses
 * anything else.
 *
 * Identity is (org, fingerprint) while {@link #revokedAt} is null. A
 * revoked key keeps its row for historical-verdict re-checks; a fresh
 * enrolment of the same material requires a new row.
 *
 * v1 formats: SSH and GPG. X.509 lands in v2 via a separate trust
 * anchors table (see ai-plans/agentic/README §11.4).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SigningKeyData extends RelizaDataParent implements RelizaObject {

	public enum SignatureFormat {
		SSH,
		GPG,
		X509
	}

	public enum SigningKeyOwnerType {
		AGENT,
		COMMITTER
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	@JsonProperty
	private SignatureFormat format;

	@JsonProperty
	private SigningKeyOwnerType ownerType;

	@JsonProperty
	private UUID ownerUuid;

	/**
	 * Normalised fingerprint for fast lookup. Format-specific:
	 * <ul>
	 *   <li>SSH: {@code SHA256:<base64-no-padding>} (the output of
	 *       {@code ssh-keygen -lf})</li>
	 *   <li>GPG: long key id (16 hex chars uppercase)</li>
	 *   <li>X.509 (v2): hex sha256 of the leaf cert DER</li>
	 * </ul>
	 */
	@JsonProperty
	private String fingerprint;

	/**
	 * Principal string the verifier checks against:
	 * <ul>
	 *   <li>SSH: email or principal that {@code ssh-keygen -Y verify}
	 *       gets passed via {@code -I} (and that the allowed_signers
	 *       file maps to this pubkey).</li>
	 *   <li>GPG: optional — informational user-id.</li>
	 * </ul>
	 */
	@JsonProperty
	private String identity;

	/**
	 * Wire-format public key (single line for SSH, ASCII-armoured for
	 * GPG). Persisted so the verifier can rebuild its allowed_signers
	 * / keyring on demand without round-tripping to anything external.
	 */
	@JsonProperty
	private String pubKey;

	@JsonProperty
	private ZonedDateTime revokedAt;

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static SigningKeyData dataFromRecord(SigningKey k) {
		if (k.getSchemaVersion() != 0) {
			throw new IllegalStateException("SigningKey schema version is " + k.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = k.getRecordData();
		SigningKeyData kd = Utils.OM.convertValue(recordData, SigningKeyData.class);
		kd.setUuid(k.getUuid());
		kd.setCreatedDate(k.getCreatedDate());
		return kd;
	}
}

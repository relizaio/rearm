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
import lombok.ToString;

/**
 * An organization's SBOM signing key (SCORE-32; SCORE-31 ADR-3). One key per org is
 * {@link SbomSigningKeyStatus#CURRENT} and signs merged SBOM exports; a rotation makes it
 * {@link SbomSigningKeyStatus#RETIRED} (still published, verifies documents signed before
 * {@link #retiredAt}); {@link SbomSigningKeyStatus#REVOKED} is a key the org no longer vouches for.
 *
 * <p>{@link #encryptedPrivateKey} is the only private material and lives in the record only: the
 * GraphQL type has no such field, the public JWKS entry is built from the public fields, and it is
 * left out of {@link #toString()}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SbomSigningKeyData extends RelizaDataParent implements RelizaObject {

	/**
	 * The signing algorithm. A constant's name is the JSF algorithm name, the JWK {@code crv} and
	 * {@code alg}, and the JCA algorithm name, all {@code Ed25519}.
	 */
	public enum SbomSigningKeyAlgorithm {
		Ed25519
	}

	public enum SbomSigningKeySource {
		GENERATED,
		IMPORTED
	}

	public enum SbomSigningKeyStatus {
		CURRENT,
		RETIRED,
		REVOKED
	}

	/** The JWK members of {@link #publicKeyJwk}, in the order they are written. */
	public static final String JWK_KTY = "kty";
	public static final String JWK_CRV = "crv";
	public static final String JWK_X = "x";
	public static final String JWK_KTY_OKP = "OKP";

	/** The JWKS document's one member, and the members a JWKS entry carries beyond the public JWK. */
	public static final String JWKS_KEYS = "keys";
	public static final String JWK_KID = "kid";
	public static final String JWK_USE = "use";
	public static final String JWK_ALG = "alg";
	public static final String JWK_USE_SIG = "sig";
	public static final String JWK_STATUS = "rearm:status";
	public static final String JWK_NOT_BEFORE = "rearm:notBefore";
	public static final String JWK_NOT_AFTER = "rearm:notAfter";
	public static final String JWK_REVOKED_AT = "rearm:revokedAt";

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/** Equal to the uuid; the value that goes into the signature and the JWKS {@code kid}. */
	@JsonProperty
	private String keyId;

	@JsonProperty
	private SbomSigningKeyAlgorithm algorithm;

	@JsonProperty
	private SbomSigningKeySource source;

	@JsonProperty
	private SbomSigningKeyStatus status;

	@JsonProperty
	private ZonedDateTime retiredAt;

	@JsonProperty
	private ZonedDateTime revokedAt;

	/** {@code kty} = OKP, {@code crv} = Ed25519, {@code x} = base64url (no padding) of the 32 raw public key bytes. */
	@JsonProperty
	private Map<String, String> publicKeyJwk;

	/** SubjectPublicKeyInfo of the same key, PEM armoured, for people. */
	@JsonProperty
	private String publicKeyPem;

	/** EncryptionService ciphertext of the base64 PKCS#8 DER. Never serialised outside the record. */
	@JsonProperty
	@ToString.Exclude
	private String encryptedPrivateKey;

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static SbomSigningKeyData dataFromRecord(SbomSigningKey k) {
		if (k.getSchemaVersion() != 0) {
			throw new IllegalStateException("SbomSigningKey schema version is " + k.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = k.getRecordData();
		SbomSigningKeyData kd = Utils.OM.convertValue(recordData, SbomSigningKeyData.class);
		kd.setUuid(k.getUuid());
		kd.setCreatedDate(k.getCreatedDate());
		return kd;
	}
}

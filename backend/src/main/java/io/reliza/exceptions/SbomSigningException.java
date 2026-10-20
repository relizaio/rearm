/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

/**
 * An SBOM could not be signed with an organization's signing key, for the reason
 * {@link #getReason()} names (SCORE-32).
 *
 * <p>The message names the key by its {@code keyId} and never carries key material, ciphertext or the
 * cause's text. Unchecked, the shape of {@link SbomScoreException}: the egress path that signs
 * (SCORE-31b) decides what a failure means for the export.
 */
public class SbomSigningException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final String ERROR_CODE = "SBOM_SIGNING_ERROR";

	public enum SbomSigningErrorReason {
		/** The stored private key does not decrypt, decode or parse as an Ed25519 PKCS#8 key. */
		KEY_UNREADABLE,
		/** The key names an algorithm this signer does not implement. */
		UNSUPPORTED_ALGORITHM,
		/** The JDK signature engine failed. */
		SIGNING_FAILED,
		/**
		 * The document has no RFC 8785 canonical form: not an object, a lone surrogate, a duplicate
		 * member name or a non-finite number (SCORE-33, {@code JsfCanonicalizer}).
		 */
		NOT_CANONICALIZABLE
	}

	private final SbomSigningErrorReason reason;

	public SbomSigningException(SbomSigningErrorReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public SbomSigningErrorReason getReason() {
		return reason;
	}
}

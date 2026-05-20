/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.SigningKeyData.SignatureFormat;

/**
 * Tag-key conventions for {@link ArtifactData.ArtifactType#SIGNATURE}
 * artifacts, and the canonical entry point for parsing the two
 * value-side enums those tags carry. Centralised so the CLI, the
 * rearm-actions extractor, and the verifier all agree on what to look
 * for.
 *
 * <p>Tag values are uppercase enum names — see
 * {@link SigningKeyData.SignatureFormat} (canonical home) and
 * {@link SignatureSubject}. Prefer the {@code fromTagValue} parsers
 * here over raw {@code Enum.valueOf} so unknown values land as a
 * caller-readable {@link RelizaException} rather than an
 * {@link IllegalArgumentException}.
 *
 * <p>Example tags on a commit-signature artifact:
 * <pre>
 * [
 *   { "key": "signatureSubject", "value": "COMMIT" },
 *   { "key": "signatureFormat",  "value": "SSH" },
 *   { "key": "signedCommitSha",  "value": "deadbeef..." }
 * ]
 * </pre>
 */
public final class SignatureArtifactTags {

	private SignatureArtifactTags() {}

	/** Discriminator — what was signed. v1 only: COMMIT. v2 may add IMAGE / SBOM / in-toto subjects. */
	public static final String SIGNATURE_SUBJECT = "signatureSubject";

	/** Discriminator — how was it signed. SSH / GPG / X509. */
	public static final String SIGNATURE_FORMAT = "signatureFormat";

	/** Quick-lookup binding for the commit case. */
	public static final String SIGNED_COMMIT_SHA = "signedCommitSha";

	public enum SignatureSubject {
		COMMIT,
		// reserved for v2
		IMAGE,
		SBOM;

		/**
		 * Parse the tag-value string into a {@link SignatureSubject}.
		 * Throws {@link RelizaException} (not {@link IllegalArgumentException})
		 * on unknown values so callers can surface a clean error.
		 */
		public static SignatureSubject fromTagValue(String tagValue) throws RelizaException {
			if (tagValue == null) {
				throw new RelizaException("Missing " + SIGNATURE_SUBJECT + " tag value");
			}
			try {
				return SignatureSubject.valueOf(tagValue);
			} catch (IllegalArgumentException e) {
				throw new RelizaException("Unknown " + SIGNATURE_SUBJECT + " tag value: " + tagValue);
			}
		}
	}

	/**
	 * Adapter — parse the {@code signatureFormat} tag value into the
	 * canonical {@link SignatureFormat} enum on {@link SigningKeyData}.
	 * Lives here (not on {@code SignatureFormat}) to keep the enum file
	 * free of tag-key knowledge; this class is the one-stop registry
	 * for everything about signature-artifact tag wire format.
	 */
	public static SignatureFormat formatFromTagValue(String tagValue) throws RelizaException {
		if (tagValue == null) {
			throw new RelizaException("Missing " + SIGNATURE_FORMAT + " tag value");
		}
		try {
			return SignatureFormat.valueOf(tagValue);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unknown " + SIGNATURE_FORMAT + " tag value: " + tagValue);
		}
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import io.reliza.exceptions.SbomSigningException;
import io.reliza.model.SbomSigningKeyData;

/**
 * Signs canonical SBOM bytes with an organization's SBOM signing key (SCORE-31 ADR-3). The seam a
 * KMS or HSM signer can implement later; {@link LocalKeySigner} is the implementation that holds the
 * key in this server's database, encrypted with {@link EncryptionService}.
 */
public interface SbomSigner {

	/**
	 * The raw signature over {@code canonical}: 64 bytes for Ed25519.
	 *
	 * @throws SbomSigningException when the key cannot be used
	 */
	byte[] sign(byte[] canonical, SbomSigningKeyData key);

	/** The JSF algorithm name of the signature {@link #sign} makes with this key: {@code Ed25519}. */
	String algorithm(SbomSigningKeyData key);
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import org.springframework.stereotype.Service;

import io.reliza.exceptions.SbomSigningException;
import io.reliza.exceptions.SbomSigningException.SbomSigningErrorReason;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;

/**
 * The {@link SbomSigner} over a key held in this server's database (SCORE-32): the PKCS#8 private key
 * is decrypted with {@link EncryptionService}, used for one JDK {@code Ed25519} signature and not
 * cached. Logs nothing: the caller that signs reports a failure, and no path here can put key
 * material or ciphertext into a message.
 */
@Service
public class LocalKeySigner implements SbomSigner {

	/** The JCA and JSF name of the one algorithm, from the enum so they cannot drift apart. */
	private static final String ED25519 = SbomSigningKeyAlgorithm.Ed25519.name();

	private final EncryptionService encryptionService;

	LocalKeySigner(EncryptionService encryptionService) {
		this.encryptionService = encryptionService;
	}

	@Override
	public String algorithm(SbomSigningKeyData key) {
		requireEd25519(key);
		return ED25519;
	}

	@Override
	public byte[] sign(byte[] canonical, SbomSigningKeyData key) {
		requireEd25519(key);
		PrivateKey privateKey = privateKey(key);
		try {
			Signature signature = Signature.getInstance(ED25519);
			signature.initSign(privateKey);
			signature.update(canonical);
			return signature.sign();
		} catch (SignatureException e) {
			throw new SbomSigningException(SbomSigningErrorReason.SIGNING_FAILED,
					"Signing with SBOM signing key " + key.getKeyId() + " failed");
		} catch (GeneralSecurityException e) {
			throw unreadable(key);
		}
	}

	private static void requireEd25519(SbomSigningKeyData key) {
		if (null == key || SbomSigningKeyAlgorithm.Ed25519 != key.getAlgorithm()) {
			throw new SbomSigningException(SbomSigningErrorReason.UNSUPPORTED_ALGORITHM,
					"SBOM signing key " + (null == key ? null : key.getKeyId())
					+ " is not an Ed25519 key; this signer implements Ed25519 only");
		}
	}

	private PrivateKey privateKey(SbomSigningKeyData key) {
		if (null == key.getEncryptedPrivateKey()) throw unreadable(key);
		String pkcs8Base64 = encryptionService.decrypt(key.getEncryptedPrivateKey());
		if (null == pkcs8Base64) throw unreadable(key);
		byte[] der = null;
		try {
			der = Base64.getDecoder().decode(pkcs8Base64);
			return KeyFactory.getInstance(ED25519).generatePrivate(new PKCS8EncodedKeySpec(der));
		} catch (IllegalArgumentException | GeneralSecurityException e) {
			throw unreadable(key);
		} finally {
			if (null != der) Arrays.fill(der, (byte) 0);
		}
	}

	private static SbomSigningException unreadable(SbomSigningKeyData key) {
		return new SbomSigningException(SbomSigningErrorReason.KEY_UNREADABLE,
				"SBOM signing key " + key.getKeyId() + " could not be read as an Ed25519 private key");
	}
}

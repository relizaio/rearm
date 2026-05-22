/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Iterator;

import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;

import io.reliza.exceptions.RelizaException;

/**
 * Derives the canonical fingerprint a signing key would carry once
 * imported by ssh-keygen / gpg. Used at enrol time so the UI never
 * has to compute or paste it.
 */
public final class SigningKeyFingerprintUtil {

	private SigningKeyFingerprintUtil() {}

	public static String deriveSshFingerprint(String pubKey) throws RelizaException {
		if (StringUtils.isBlank(pubKey)) throw new RelizaException("Cannot derive SSH fingerprint: empty pubKey");
		String[] parts = pubKey.trim().split("\\s+");
		if (parts.length < 2) throw new RelizaException("SSH pubKey must be 'algo base64key [comment]'");
		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(parts[1]);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("SSH pubKey body is not valid base64");
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
			return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new RelizaException("SHA-256 not available: " + e.getMessage());
		}
	}

	/**
	 * Long key id (16 hex chars upper-case) of the primary key in an
	 * ASCII-armoured GPG public key block.
	 */
	public static String deriveGpgLongKeyId(String pubKey) throws RelizaException {
		if (StringUtils.isBlank(pubKey)) throw new RelizaException("Cannot derive GPG fingerprint: empty pubKey");
		try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(pubKey.getBytes()))) {
			PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
			Iterator<PGPPublicKeyRing> ringIt = rings.getKeyRings();
			if (!ringIt.hasNext()) throw new RelizaException("GPG pubKey contained no key rings");
			PGPPublicKeyRing ring = ringIt.next();
			PGPPublicKey primary = ring.getPublicKey();
			return String.format("%016X", primary.getKeyID());
		} catch (RelizaException e) {
			throw e;
		} catch (Exception e) {
			throw new RelizaException("Failed to parse GPG public key: " + e.getMessage());
		}
	}
}

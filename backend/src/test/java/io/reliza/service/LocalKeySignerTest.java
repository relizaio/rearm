/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.SbomSigningException;
import io.reliza.exceptions.SbomSigningException.SbomSigningErrorReason;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;
import io.reliza.service.SbomSigningTestKeys.LogCapture;
import io.reliza.service.SbomSigningTestKeys.Rfc8032Vector;

/**
 * The local Ed25519 signer (SCORE-32, T-8 and T-9): RFC 8032 section 7.1 vectors through the stored,
 * encrypted form of the key, and a typed failure that names the key and carries no key material.
 */
class LocalKeySignerTest {

	private final EncryptionService encryption = SbomSigningTestKeys.encryptionService(SbomSigningTestKeys.PASSWORD);
	private final LocalKeySigner signer = new LocalKeySigner(encryption);

	private static SbomSigningKeyData key(String encryptedPrivateKey) {
		SbomSigningKeyData k = new SbomSigningKeyData();
		k.setKeyId(UUID.randomUUID().toString());
		k.setAlgorithm(SbomSigningKeyAlgorithm.Ed25519);
		k.setEncryptedPrivateKey(encryptedPrivateKey);
		return k;
	}

	private SbomSigningKeyData storedKey(byte[] pkcs8, EncryptionService es) {
		return key(es.encrypt(Base64.getEncoder().encodeToString(pkcs8)));
	}

	// T-8

	@Test
	void sign_matchesRfc8032Test1To3() throws Exception {
		for (Rfc8032Vector v : SbomSigningTestKeys.rfc8032Vectors()) {
			SbomSigningKeyData k = storedKey(SbomSigningTestKeys.pkcs8FromSeed(v.secretKey()), encryption);
			byte[] sig = signer.sign(v.message(), k);
			assertEquals(64, sig.length, v.name());
			assertArrayEquals(v.signature(), sig, v.name() + ": " + HexFormat.of().formatHex(sig));
		}
	}

	@Test
	void sign_isDeterministic() throws Exception {
		SbomSigningKeyData k = storedKey(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate()
				.getEncoded(), encryption);
		byte[] doc = "{\"bomFormat\":\"CycloneDX\"}".getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(signer.sign(doc, k), signer.sign(doc, k));
	}

	@Test
	void algorithm_isEd25519() {
		assertEquals("Ed25519", signer.algorithm(key("x")));
	}

	// T-9

	@Test
	void sign_throwsTypedExceptionWhenKeyUnreadable() throws Exception {
		byte[] pkcs8 = SbomSigningTestKeys.pkcs8FromSeed(SbomSigningTestKeys.rfc8032Vectors().get(0).secretKey());
		EncryptionService other = SbomSigningTestKeys.encryptionService(SbomSigningTestKeys.OTHER_PASSWORD);
		SbomSigningKeyData k = storedKey(pkcs8, other);
		SbomSigningException e;
		try (LogCapture logs = new LogCapture(LocalKeySigner.class, EncryptionService.class)) {
			e = assertThrows(SbomSigningException.class, () -> signer.sign(new byte[] {1}, k));
			assertNoKeyMaterial(logs.text(), pkcs8, k.getEncryptedPrivateKey());
		}
		assertEquals(SbomSigningErrorReason.KEY_UNREADABLE, e.getReason());
		assertTrue(e.getMessage().contains(k.getKeyId()), e.getMessage());
		assertNoKeyMaterial(e.getMessage(), pkcs8, k.getEncryptedPrivateKey());
		assertEquals(null, e.getCause());
	}

	@Test
	void sign_throwsOnGarbageCiphertext() {
		SbomSigningKeyData k = key("not-a-ciphertext");
		SbomSigningException e = assertThrows(SbomSigningException.class, () -> signer.sign(new byte[0], k));
		assertEquals(SbomSigningErrorReason.KEY_UNREADABLE, e.getReason());
		assertFalse(e.getMessage().contains("not-a-ciphertext"), e.getMessage());

		SbomSigningException missing = assertThrows(SbomSigningException.class, () -> signer.sign(new byte[0], key(null)));
		assertEquals(SbomSigningErrorReason.KEY_UNREADABLE, missing.getReason());
	}

	@Test
	void sign_refusesACiphertextThatIsNotAnEd25519Pkcs8() throws Exception {
		// decrypts, but to text that is not base64, then to an EC key: both unreadable as Ed25519
		SbomSigningException notBase64 = assertThrows(SbomSigningException.class,
				() -> signer.sign(new byte[0], key(encryption.encrypt("%%% not base64 %%%"))));
		assertEquals(SbomSigningErrorReason.KEY_UNREADABLE, notBase64.getReason());
		byte[] ec = KeyPairGenerator.getInstance("EC").generateKeyPair().getPrivate().getEncoded();
		SbomSigningException notEd25519 = assertThrows(SbomSigningException.class,
				() -> signer.sign(new byte[0], storedKey(ec, encryption)));
		assertEquals(SbomSigningErrorReason.KEY_UNREADABLE, notEd25519.getReason());
	}

	@Test
	void sign_refusesNonEd25519Algorithm() throws Exception {
		SbomSigningKeyData k = storedKey(SbomSigningTestKeys.pkcs8FromSeed(new byte[32]), encryption);
		k.setAlgorithm(null);
		SbomSigningException e = assertThrows(SbomSigningException.class, () -> signer.sign(new byte[0], k));
		assertEquals(SbomSigningErrorReason.UNSUPPORTED_ALGORITHM, e.getReason());
		assertTrue(e.getMessage().contains(k.getKeyId()), e.getMessage());
		assertEquals(SbomSigningErrorReason.UNSUPPORTED_ALGORITHM,
				assertThrows(SbomSigningException.class, () -> signer.algorithm(k)).getReason());
	}

	static void assertNoKeyMaterial(String text, byte[] pkcs8, String ciphertext) {
		String b64 = Base64.getEncoder().encodeToString(pkcs8);
		assertFalse(text.contains(b64), "base64 PKCS#8 in: " + text.length() + " chars");
		assertFalse(text.contains(HexFormat.of().formatHex(pkcs8)), "hex PKCS#8 in the text");
		assertFalse(text.contains("PRIVATE KEY"), "PEM armour in the text");
		if (null != ciphertext) assertFalse(text.contains(ciphertext), "ciphertext in the text");
	}
}

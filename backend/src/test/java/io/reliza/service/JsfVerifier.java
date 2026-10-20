/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

import io.reliza.common.Utils;
import io.reliza.exceptions.SbomSigningException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Java verifier of the root JSF signature, for tests only (SCORE-33 ADR-15). It mirrors
 * rearm-cli's rule (SCORE-34 ADR-2, ADR-10) so the Java tests can say a document verifies without a
 * Go binary: signing input = the RFC 8785 canonical form of the whole document with only
 * {@code signature.value} removed (a leading byte order mark stripped first, nothing else); pure
 * Ed25519, not prehashed; ES256 as the raw {@code r || s}; {@code value} strict base64url without
 * padding; an embedded {@code publicKey} must be the key given. Nothing in {@code src/main} verifies.
 *
 * <p>The JSF and JWK member names and algorithm names are written out here as the wire spells them,
 * not taken from the production constants, on purpose: a verifier that shared the signer's constants
 * would follow a renamed constant and keep passing against documents no other verifier accepts.
 */
public final class JsfVerifier {

	public enum Outcome { VERIFIED, FAILED, MALFORMED, KEY_MISMATCH, UNSUPPORTED_ALGORITHM }

	private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
	private static final Pattern BASE64URL_NO_PADDING = Pattern.compile("[A-Za-z0-9_-]*");

	private JsfVerifier() {}

	/** Verifies the JSON text: refused text (duplicate names, lone surrogates, invalid JSON) is MALFORMED. */
	public static Outcome verify(String json, PublicKey key) {
		String text = json.startsWith("\uFEFF") ? json.substring(1) : json;
		try {
			JsfCanonicalizer.canonicalize(text);
		} catch (SbomSigningException e) {
			return Outcome.MALFORMED;
		}
		return verify(Utils.OM.readTree(text), key);
	}

	public static Outcome verify(JsonNode document, PublicKey key) {
		return verify(document, key, false);
	}

	/** Verifies under the {@code publicKey} the signature embeds; none embedded is MALFORMED. */
	public static Outcome verifyEmbedded(JsonNode document) {
		return verify(document, null, true);
	}

	public static Outcome verifyEmbedded(String json) {
		return verifyEmbedded(Utils.OM.readTree(json));
	}

	private static Outcome verify(JsonNode document, PublicKey given, boolean embedded) {
		if (null == document || !document.isObject()) return Outcome.MALFORMED;
		JsonNode sig = document.get("signature");
		if (null == sig || !sig.isObject()) return Outcome.MALFORMED;
		JsonNode alg = sig.get("algorithm");
		JsonNode value = sig.get("value");
		if (null == alg || !alg.isString() || alg.asString().isEmpty()) return Outcome.MALFORMED;
		if (null == value || !value.isString() || value.asString().isEmpty()) return Outcome.MALFORMED;
		byte[] signatureBytes = strictBase64Url(value.asString());
		if (null == signatureBytes) return Outcome.MALFORMED;

		PublicKey key = given;
		JsonNode embeddedJwk = sig.get("publicKey");
		if (embedded) {
			if (null == embeddedJwk || !embeddedJwk.isObject()) return Outcome.MALFORMED;
			try {
				key = keyFromJwk(embeddedJwk);
			} catch (RuntimeException e) {
				return Outcome.MALFORMED;
			}
		} else if (null != embeddedJwk) {
			// only the embedded JWK against the key given; the signature itself is checked below, and
			// the tests that call a document signed assert VERIFIED, never just the absence of a mismatch
			PublicKey stated;
			try {
				stated = keyFromJwk(embeddedJwk);
			} catch (RuntimeException e) {
				return Outcome.MALFORMED;
			}
			if (!Arrays.equals(stated.getEncoded(), key.getEncoded())) return Outcome.KEY_MISMATCH;
		}

		String engine = switch (alg.asString()) {
			case "Ed25519" -> "Ed25519";
			case "ES256" -> "SHA256withECDSAinP1363Format";
			default -> null;
		};
		if (null == engine) return Outcome.UNSUPPORTED_ALGORITHM;

		ObjectNode withoutValue = (ObjectNode) document.deepCopy();
		((ObjectNode) withoutValue.get("signature")).remove("value");
		byte[] canonical;
		try {
			canonical = JsfCanonicalizer.canonicalize(withoutValue);
		} catch (SbomSigningException e) {
			return Outcome.MALFORMED;
		}
		try {
			Signature s = Signature.getInstance(engine);
			s.initVerify(key);
			s.update(canonical);
			return s.verify(signatureBytes) ? Outcome.VERIFIED : Outcome.FAILED;
		} catch (GeneralSecurityException e) {
			return Outcome.FAILED;
		}
	}

	/**
	 * base64url without padding and nothing else, or null. Strict like Go's RawURLEncoding: the JDK
	 * decoder ignores non-zero bits after the last whole byte, so the decoded bytes must encode back
	 * to the same text.
	 */
	private static byte[] strictBase64Url(String s) {
		if (!BASE64URL_NO_PADDING.matcher(s).matches() || 1 == s.length() % 4) return null;
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(s);
			return s.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)) ? decoded : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// ---- keys ------------------------------------------------------------------------------------

	public static PublicKey keyFromJwk(Map<String, String> jwk) {
		JsonNode node = Utils.OM.valueToTree(jwk);
		return keyFromJwk(node);
	}

	/** An OKP Ed25519 key from {@code x}, or an EC P-256 key from {@code x} and {@code y}. */
	public static PublicKey keyFromJwk(JsonNode jwk) {
		String kty = text(jwk, "kty");
		String crv = text(jwk, "crv");
		try {
			if ("OKP".equals(kty) && "Ed25519".equals(crv)) {
				byte[] x = Base64.getUrlDecoder().decode(text(jwk, "x"));
				if (32 != x.length) throw new IllegalArgumentException("Ed25519 x is not 32 bytes");
				byte[] spki = Arrays.copyOf(ED25519_SPKI_PREFIX, ED25519_SPKI_PREFIX.length + 32);
				System.arraycopy(x, 0, spki, ED25519_SPKI_PREFIX.length, 32);
				return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
			}
			if ("EC".equals(kty) && "P-256".equals(crv)) {
				AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
				params.init(new ECGenParameterSpec("secp256r1"));
				ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
				ECPoint point = new ECPoint(new BigInteger(1, Base64.getUrlDecoder().decode(text(jwk, "x"))),
						new BigInteger(1, Base64.getUrlDecoder().decode(text(jwk, "y"))));
				return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
			}
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("unusable JWK", e);
		}
		throw new IllegalArgumentException("unsupported JWK " + kty + "/" + crv);
	}

	/** A SubjectPublicKeyInfo PEM, Ed25519 or EC. */
	public static PublicKey keyFromPem(String pem) {
		String b64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(b64);
		for (String algorithm : new String[] {"Ed25519", "EC"}) {
			try {
				return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
			} catch (GeneralSecurityException e) {
				// try the next
			}
		}
		throw new IllegalArgumentException("not an Ed25519 or EC public key");
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return null == v || !v.isString() ? null : v.asString();
	}
}

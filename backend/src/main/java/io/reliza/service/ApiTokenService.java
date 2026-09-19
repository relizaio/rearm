/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.SystemInfoData.EncProps;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues and verifies ReARM access tokens for API keys (the OAuth 2.0 client_credentials
 * exchange on /api/programmatic/token). Tokens are compact JWS, HS256, opaque to clients:
 *
 * <ul>
 * <li>signing key derived with HKDF-SHA256 (the JDK KDF API) from the installation's
 *     encryption password and salt under a fixed label, so no new secret is configured and rotating the encryption
 *     secret rotates token signing; the previous password (already kept for re-encryption)
 *     derives the previous signing key. The {@code kid} header is a digest of the key
 *     material, so a token always names the exact key that signed it and in-flight tokens
 *     survive a rotation;</li>
 * <li>claims: sub = api key uuid, org, aud (one per surface, strict), iat, exp (one hour),
 *     jti, slot and fp = fingerprint of the secret the key was exchanged with, so retiring or
 *     regenerating that secret invalidates every token issued through it while the key's
 *     other secret keeps working;</li>
 * <li>no permissions inside: they are read from the key row on every use.</li>
 * </ul>
 */
@Service
@Slf4j
public class ApiTokenService {

	public static final String ISSUER = "rearm";
	public static final String AUD_PROGRAMMATIC = "programmatic";
	public static final String AUD_TEA = "tea";
	public static final long TTL_SECONDS = 3600L;
	static final long CLOCK_SKEW_SECONDS = 60L;
	private static final byte[] LABEL = "rearm-api-token-signing-v1".getBytes(StandardCharsets.UTF_8);

	private final EncProps enc;
	private final String issuer;
	private final Supplier<String> pepperSupplier;

	/** Derived on first use, not in the constructor: the pepper lives in the database. */
	private volatile Keys derived;

	/** Signing keys by key id; the id is a digest of the key material, so a token always names the exact key that signed it. */
	private record Keys(Map<String, byte[]> byKid, String currentKid) {}

	@Autowired
	public ApiTokenService(RelizaConfigProps props, @Lazy SystemInfoService systemInfoService,
			@Value("${relizaprops.baseuri:}") String baseUri,
			@Value("${relizaprops.encryption.shippedDefault:}") String shippedDefaultPassword) {
		this(props.getEncryption(), issuerFor(baseUri), systemInfoService::getOrCreateApiTokenPepper,
				shippedDefaultPassword);
	}

	ApiTokenService(EncProps enc, String issuer, Supplier<String> pepperSupplier) {
		this(enc, issuer, pepperSupplier, null);
	}

	ApiTokenService(EncProps enc, String issuer, Supplier<String> pepperSupplier, String shippedDefaultPassword) {
		if (enc == null || StringUtils.isEmpty(enc.password())) {
			throw new IllegalStateException("relizaprops.encryption.password is required to sign API tokens");
		}
		this.enc = enc;
		this.issuer = issuer;
		this.pepperSupplier = pepperSupplier;
		// Compared here and not kept: the shipped default is read from the same file that sets the
		// password, rather than copied into this class, because this file syncs between editions
		// and a literal here would mean each shipping the other's default and both going stale.
		// Comparing the RESOLVED password is exact under every override Spring offers -- env var,
		// system property, command line, profile, external yaml, SPRING_APPLICATION_JSON -- since
		// it never asks how the value was set.
		if (StringUtils.isNotEmpty(shippedDefaultPassword) && shippedDefaultPassword.equals(enc.password())) {
			// Error, not warn: this is the one line an operator must not scroll past. It names the
			// property and never the value, and it does not refuse to start -- local development
			// runs on the default on purpose.
			log.error("relizaprops.encryption.password is the value shipped with this repository."
					+ " It is public, so every installation left on it derives the same encryption"
					+ " key. Set RELIZAPROP_PASS (and RELIZAPROP_SALT) to values of your own.");
		}
	}

	/**
	 * Who the token says issued it.
	 *
	 * <p>A constant here meant every installation issued tokens under the same name, so the issuer
	 * check could not tell one from another -- the thing it exists to do. The base URI is what
	 * distinguishes two installations that otherwise share their configuration, including two
	 * clones of one database on different hosts, which is the case the pepper cannot help with.
	 */
	static String issuerFor(String baseUri) {
		return StringUtils.isEmpty(baseUri) ? ISSUER : baseUri;
	}

	/**
	 * The signing keys, derived once, on first use.
	 *
	 * <p>Deriving in the constructor was not possible once the pepper moved into the database: the
	 * service is constructed before a query can be run. Failure here is deliberately not caught --
	 * signing with a key derived from a missing pepper would produce tokens that stop verifying the
	 * moment the pepper is readable again.
	 */
	private Keys keys() {
		Keys local = derived;
		if (local != null) return local;
		synchronized (this) {
			if (derived != null) return derived;
			String pepper;
			try {
				pepper = pepperSupplier.get();
			} catch (RuntimeException e) {
				// Logged here, at error, because the only other place it surfaces is verify()
				// turning every failure into an empty Optional -- so a database outage would
				// arrive in the log as a stream of rejected tokens, which reads as an attack.
				log.error("Could not read the API token pepper; tokens cannot be signed or verified"
						+ " until it is readable again", e);
				throw e;
			}
			Map<String, byte[]> byKid = new LinkedHashMap<>();
			byte[] current = hkdfSha256(enc.password(), enc.salt(), pepper);
			String currentKid = kidOf(current);
			byKid.put(currentKid, current);
			if (StringUtils.isNotEmpty(enc.oldPassword())) {
				byte[] previous = hkdfSha256(enc.oldPassword(), enc.oldSalt(), pepper);
				byKid.putIfAbsent(kidOf(previous), previous);
			}
			derived = new Keys(byKid, currentKid);
			return derived;
		}
	}

	/** Key id = first 16 hex chars of SHA-256 over the derived key; reveals nothing about the key. */
	static String kidOf(byte[] key) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
			return sb.toString();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Verified token claims that matter to authentication. */
	public record TokenClaims(UUID keyUuid, UUID org, String audience, String fingerprint, Integer slot, String jti, Instant expiresAt,
			UUID sessionUuid, UUID actorUser, List<UUID> ruleUuids, Map<String, String> federation) {
		public TokenClaims(UUID keyUuid, UUID org, String audience, String fingerprint, Integer slot, String jti, Instant expiresAt) {
			this(keyUuid, org, audience, fingerprint, slot, jti, expiresAt, null, null, null, null);
		}
		public TokenClaims(UUID keyUuid, UUID org, String audience, String fingerprint, Integer slot, String jti, Instant expiresAt,
				UUID sessionUuid, UUID actorUser) {
			this(keyUuid, org, audience, fingerprint, slot, jti, expiresAt, sessionUuid, actorUser, null, null);
		}
	}

	/**
	 * Tokens from a federated exchange are bound to the rules that admitted the identity, at their
	 * current versions: an edit, a disable or a delete of any of them invalidates the token.
	 */
	public static String fingerprint(List<io.reliza.model.FederatedTrustRule> rules) {
		String material = rules.stream()
				.sorted(java.util.Comparator.comparing(r -> r.getUuid().toString()))
				.map(r -> r.getUuid() + "@" + r.getVersion())
				.collect(java.util.stream.Collectors.joining(","));
		return sha256Hex("federated-rules:" + material).substring(0, 16);
	}

	/**
	 * Access token for a federated identity: the key it acts as (a FEDERATED identity row or a
	 * bound FREEFORM key), the rules it came through, and the provider claims kept as {@code fed}.
	 * {@code clip} caps the expiry (an expiring rule); null for the plain TTL.
	 */
	public String issueForFederation(ApiKey ak, List<io.reliza.model.FederatedTrustRule> rules, Map<String, String> federation,
			String audience, Instant clip) {
		try {
			Instant now = Instant.now();
			Instant exp = now.plusSeconds(TTL_SECONDS);
			if (clip != null && clip.isBefore(exp)) exp = clip;
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.issuer(issuer)
					.subject(ak.getUuid().toString())
					.audience(audience)
					.issueTime(Date.from(now))
					.expirationTime(Date.from(exp))
					.jwtID(UUID.randomUUID().toString())
					.claim("org", ak.getOrg() == null ? null : ak.getOrg().toString())
					.claim("fp", fingerprint(rules))
					.claim("rules", rules.stream().map(r -> r.getUuid().toString()).toList())
					.claim("fed", federation)
					.build();
			// One lookup: the header and the signer must name the same key, and reading it twice
			// reads as though they could differ.
			Keys signing = keys();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signing.currentKid()).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
			jwt.sign(new MACSigner(signing.byKid().get(signing.currentKid())));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException("cannot sign federated access token", e);
		}
	}

	/** Fingerprint of a CLI session: revoking the session (or rotating its refresh token) invalidates its access tokens. */
	public static String fingerprint(io.reliza.model.CliSession session) {
		return io.reliza.common.CliSessionCodes.sha256Hex("cli-session:" + session.getUuid() + ":" + session.getRefreshTokenHash()).substring(0, 16);
	}

	/**
	 * Access token for a CLI session: same shape as the key-secret token (sub = key), plus {@code sid}
	 * (the session, whose fingerprint is {@code fp}) and {@code act} (the user who logged in), so audit
	 * can say "key X operated by user Y" and revoking the session kills the token at once.
	 */
	public String issueForSession(ApiKey ak, io.reliza.model.CliSession session, String audience) {
		try {
			Instant now = Instant.now();
			Instant exp = now.plusSeconds(TTL_SECONDS);
			if (session.getExpiresDate() != null && session.getExpiresDate().toInstant().isBefore(exp)) exp = session.getExpiresDate().toInstant();
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.issuer(issuer)
					.subject(ak.getUuid().toString())
					.audience(audience)
					.issueTime(Date.from(now))
					.expirationTime(Date.from(exp))
					.jwtID(UUID.randomUUID().toString())
					.claim("org", ak.getOrg() == null ? null : ak.getOrg().toString())
					.claim("fp", fingerprint(session))
					.claim("sid", session.getUuid().toString())
					.claim("act", session.getUser() == null ? null : session.getUser().toString())
					.build();
			// One lookup: the header and the signer must name the same key, and reading it twice
			// reads as though they could differ.
			Keys signing = keys();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signing.currentKid()).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
			jwt.sign(new MACSigner(signing.byKid().get(signing.currentKid())));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException("cannot sign session access token", e);
		}
	}

	public String issue(ApiKey ak, ApiKeySecret secret, String audience) {
		try {
			Instant now = Instant.now();
			Instant exp = now.plusSeconds(TTL_SECONDS);
			if (secret.getExpiresDate() != null && secret.getExpiresDate().toInstant().isBefore(exp)) exp = secret.getExpiresDate().toInstant();
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.issuer(issuer)
					.subject(ak.getUuid().toString())
					.audience(audience)
					.issueTime(Date.from(now))
					.expirationTime(Date.from(exp))
					.jwtID(UUID.randomUUID().toString())
					.claim("org", ak.getOrg() == null ? null : ak.getOrg().toString())
					.claim("fp", fingerprint(secret))
					.claim("slot", secret.getSlot())
					.build();
			// One lookup: the header and the signer must name the same key, and reading it twice
			// reads as though they could differ.
			Keys signing = keys();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signing.currentKid()).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
			jwt.sign(new MACSigner(signing.byKid().get(signing.currentKid())));
			return jwt.serialize();
		} catch (JOSEException e) {
			throw new IllegalStateException("cannot sign API token", e);
		}
	}

	/**
	 * Verify signature, issuer, audience and expiry. Returns empty for anything invalid; the
	 * caller still has to load the key row and compare {@link TokenClaims#fingerprint()}.
	 */
	public Optional<TokenClaims> verify(String token, String expectedAudience) {
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			String kid = jwt.getHeader().getKeyID();
			byte[] key = kid == null ? null : keys().byKid().get(kid);
			if (key == null || !JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) return Optional.empty();
			if (!jwt.verify(new MACVerifier(key))) return Optional.empty();
			JWTClaimsSet c = jwt.getJWTClaimsSet();
			if (!issuer.equals(c.getIssuer())) return Optional.empty();
			if (c.getAudience() == null || !c.getAudience().contains(expectedAudience)) return Optional.empty();
			if (c.getExpirationTime() == null || c.getExpirationTime().toInstant().plusSeconds(CLOCK_SKEW_SECONDS).isBefore(Instant.now())) return Optional.empty();
			UUID keyUuid = UUID.fromString(c.getSubject());
			String org = c.getStringClaim("org");
			Integer slot = c.getIntegerClaim("slot");
			String sid = c.getStringClaim("sid");
			String act = c.getStringClaim("act");
			List<String> ruleStrs = c.getStringListClaim("rules");
			List<UUID> rules = ruleStrs == null ? null : ruleStrs.stream().map(UUID::fromString).toList();
			Map<String, String> fed = null;
			Map<String, Object> fedRaw = c.getJSONObjectClaim("fed");
			if (fedRaw != null) {
				fed = new java.util.LinkedHashMap<>();
				for (Map.Entry<String, Object> e : fedRaw.entrySet()) {
					if (e.getValue() != null) fed.put(e.getKey(), String.valueOf(e.getValue()));
				}
			}
			return Optional.of(new TokenClaims(keyUuid, org == null ? null : UUID.fromString(org), expectedAudience,
					c.getStringClaim("fp"), slot, c.getJWTID(), c.getExpirationTime().toInstant(),
					sid == null ? null : UUID.fromString(sid), act == null ? null : UUID.fromString(act), rules, fed));
		} catch (Exception e) {
			log.debug("API token rejected: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/** Short, stable digest of one secret's stored (Argon2) hash; changes when that secret is regenerated. */
	public static String fingerprint(ApiKeySecret secret) {
		return sha256Hex(StringUtils.defaultString(secret.getHash())).substring(0, 16);
	}

	// ---- HKDF-SHA256 (RFC 5869) through the JDK KDF API (JEP 510): extract with the salt,
	// expand under the label to the 32 bytes HS256 needs ----
	/**
	 * Input keying material is the password and the installation's pepper, separated by a byte that
	 * cannot occur in either, so no two different pairs can produce the same input.
	 */
	static byte[] hkdfSha256(String password, String salt, String pepper) {
		byte[] pw = password.getBytes(StandardCharsets.UTF_8);
		byte[] pep = StringUtils.defaultString(pepper).getBytes(StandardCharsets.UTF_8);
		byte[] ikm = new byte[pw.length + 1 + pep.length];
		System.arraycopy(pw, 0, ikm, 0, pw.length);
		ikm[pw.length] = 0;
		System.arraycopy(pep, 0, ikm, pw.length + 1, pep.length);
		return hkdfSha256(ikm, StringUtils.defaultString(salt).getBytes(StandardCharsets.UTF_8), LABEL, 32);
	}

	static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
		try {
			javax.crypto.KDF kdf = javax.crypto.KDF.getInstance("HKDF-SHA256");
			javax.crypto.spec.HKDFParameterSpec.Builder b = javax.crypto.spec.HKDFParameterSpec.ofExtract().addIKM(ikm);
			if (salt != null && salt.length > 0) b.addSalt(salt); // RFC 5869: absent salt = zero-filled
			return kdf.deriveKey("Generic", b.thenExpand(info, length)).getEncoded();
		} catch (Exception e) {
			throw new IllegalStateException("HKDF-SHA256 unavailable", e);
		}
	}

	private static String sha256Hex(String s) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : h) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}

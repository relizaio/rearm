/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.reliza.common.LogSafe;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.service.FederatedMatching.IdentityClaims;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies an identity token (RFC 7523 assertion) from an external issuer: signature against the
 * issuer's published keys (OIDC discovery, cached per issuer), issuer, expiry, audience equal to
 * this instance, and single use of the token id. Only issuers some active rule names are ever
 * contacted, so an unknown token cannot make the server fetch arbitrary URLs.
 */
@Service
@Slf4j
public class FederatedAssertionService {

	static final Duration MAX_ASSERTION_LIFETIME = Duration.ofHours(24);

	/**
	 * Why a federated assertion or its exchange was refused, one name per refusal site (task TPF-31). The token
	 * endpoint answers every refusal before a rule admits the verified identity with one text and writes this name,
	 * a fixed vocabulary, to its one log line: the name is the distinction the wire no longer carries.
	 */
	public enum Refusal {
		/** {@link #peekIssuer}: the assertion does not parse. */
		MALFORMED,
		/** The exchange: the assertion parses but names no issuer. */
		NO_ISSUER,
		/** The exchange: no active, usable rule names the presented issuer text. */
		UNTRUSTED_ISSUER,
		/** {@link #verify}: this instance has no base URI, so no audience can be required. */
		AUDIENCE_NOT_CONFIGURED,
		/** {@link #verify}: key discovery for a stored issuer failed. */
		KEYS_UNAVAILABLE,
		/** {@link #verify}: the decoder refused the token for anything but a claim validator (signature, key, header, format, a JWK fetch inside decode, a token whose Jwt cannot be built). */
		NOT_VERIFIED,
		/** {@link #verify}: the decoder's claim validators (expiry, not-before, issuer) refused the token after its signature. */
		CLAIMS_INVALID,
		/** {@link #verify}: the decoded issuer is not the trusted one. */
		ISSUER_MISMATCH,
		/** {@link #verify}: the audience is not this instance's base URI. */
		AUDIENCE,
		/** {@link #verify}: no expiry. */
		NO_EXPIRY,
		/** {@link #verify}: the expiry lies further ahead than the longest lifetime accepted. */
		LIFETIME,
		/** {@link #verify}: no token id. */
		NO_ID,
		/** {@link #verify}: no repository or owner. */
		NO_REPOSITORY,
		/** {@link #verify}: no repository or owner ids. */
		NO_REPOSITORY_IDS,
		/** {@link #verify}: called without an assertion or an issuer (not reachable from the exchange). */
		ARGUMENTS,
		/** The exchange: the verified identity matches no rule naming the issuer. */
		IDENTITY_UNMATCHED,
		/** The exchange: the verified identity matches rules, but none of the organization client_id names. */
		ORG_NOT_NAMED,
		/** The exchange: client_id is not an organization uuid. */
		CLIENT_ID_NOT_UUID,
		/** The exchange: several organizations trust the identity and client_id names none. */
		SEVERAL_ORGS,
		/** The exchange: the owner id differs from a rule's pin. */
		OWNER_PIN,
		/** The exchange: several key-bound rules bind different keys. */
		SEVERAL_KEYS,
		/** The exchange: the key a rule binds is gone. */
		KEY_GONE,
		/** The exchange: the key a rule binds is not an active Free Form key of the organization. */
		KEY_NOT_USABLE,
		/** The exchange: the identity row is disabled. */
		IDENTITY_DISABLED,
		/** The exchange: the repository id differs from the identity row's pin. */
		REPOSITORY_PIN,
		/** The exchange: the assertion id was already used. */
		REPLAY
	}

	public static class AssertionException extends Exception {
		private static final long serialVersionUID = 1L;
		private final Refusal reason;
		public AssertionException(Refusal reason, String message) { super(message); this.reason = reason; }
		public Refusal reason() { return reason; }
	}

	public record VerifiedAssertion(IdentityClaims identity, Map<String, Object> claims, Instant expiresAt, String jti) {}

	private final Cache<String, JwtDecoder> decoders = Caffeine.newBuilder().maximumSize(64).expireAfterWrite(Duration.ofHours(12)).build();

	private Function<String, JwtDecoder> decoderFactory = JwtDecoders::fromIssuerLocation;

	@Autowired
	private RelizaConfigProps relizaConfigProps;

	public FederatedAssertionService() {}

	/** Test hook: decoders without discovery, an explicit audience. */
	FederatedAssertionService(Function<String, JwtDecoder> decoderFactory, RelizaConfigProps props) {
		this.decoderFactory = decoderFactory;
		this.relizaConfigProps = props;
	}

	/** The audience an assertion must name: this instance's base URI (with or without a trailing slash). */
	public String expectedAudience() {
		String base = relizaConfigProps == null ? null : relizaConfigProps.getBaseuri();
		return base == null ? null : base.replaceAll("/+$", "");
	}

	/** The issuer claim, read without verification, so the caller can find the rules that name it before anything is fetched. */
	public String peekIssuer(String assertion) throws AssertionException {
		try {
			return com.nimbusds.jwt.JWTParser.parse(assertion).getJWTClaimsSet().getIssuer();
		} catch (Exception e) {
			throw new AssertionException(Refusal.MALFORMED, "malformed assertion");
		}
	}

	/**
	 * The wire and log text of a decoder refusal, from a fixed vocabulary: a validator refusal (expiry, issuer or
	 * audience claims) or anything else (signature, key, header or format). The library's own message is built from
	 * the token before its signature is checked, so it is never passed on.
	 */
	static String reasonOf(JwtException e) {
		return e instanceof JwtValidationException
				? "assertion rejected: claims not valid (expiry, issuer or audience)"
				: "assertion rejected: signature, key or format not accepted";
	}

	/** The fixed-vocabulary reason of a decoder refusal, split as {@link #reasonOf} splits the sentence: by the class the decoder throws. */
	static Refusal refusalOf(JwtException e) {
		return e instanceof JwtValidationException ? Refusal.CLAIMS_INVALID : Refusal.NOT_VERIFIED;
	}

	/**
	 * Full verification against a trusted issuer. {@code provider} decides how the claims are read.
	 */
	public VerifiedAssertion verify(String assertion, String issuer, Provider provider) throws AssertionException {
		if (StringUtils.isBlank(assertion) || StringUtils.isBlank(issuer)) throw new AssertionException(Refusal.ARGUMENTS, "assertion and issuer are required");
		String audience = expectedAudience();
		if (StringUtils.isBlank(audience)) {
			log.error("federated identity exchange refused: relizaprops.baseuri is not configured, so no audience can be required");
			throw new AssertionException(Refusal.AUDIENCE_NOT_CONFIGURED, "this instance has no base URI configured for federated identities");
		}
		JwtDecoder decoder;
		try {
			decoder = decoders.get(issuer, decoderFactory);
		} catch (RuntimeException e) {
			log.error("cannot set up key discovery for federated issuer {}", LogSafe.text(issuer), e);
			throw new AssertionException(Refusal.KEYS_UNAVAILABLE, "issuer keys unavailable");
		}
		Jwt jwt;
		try {
			jwt = decoder.decode(assertion);
		} catch (JwtException e) {
			// the library's message is built from the token (its header, before the signature): never logged, never sent;
			// the token endpoint's one refusal line writes the reason (TPF-31)
			throw new AssertionException(refusalOf(e), reasonOf(e));
		}
		if (!issuer.equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())) throw new AssertionException(Refusal.ISSUER_MISMATCH, "issuer mismatch");
		List<String> aud = jwt.getAudience();
		if (aud == null || aud.stream().noneMatch(a -> a != null && a.replaceAll("/+$", "").equalsIgnoreCase(audience))) {
			throw new AssertionException(Refusal.AUDIENCE, "audience must be " + audience);
		}
		Instant exp = jwt.getExpiresAt();
		if (exp == null) throw new AssertionException(Refusal.NO_EXPIRY, "assertion has no expiry");
		if (exp.isAfter(Instant.now().plus(MAX_ASSERTION_LIFETIME))) throw new AssertionException(Refusal.LIFETIME, "assertion lifetime is implausibly long");
		String jti = jwt.getId();
		if (StringUtils.isBlank(jti)) throw new AssertionException(Refusal.NO_ID, "assertion has no id");
		Map<String, Object> claims = jwt.getClaims();
		IdentityClaims identity = switch (provider) {
			case GITHUB_ACTIONS -> FederatedMatching.fromGitHub(issuer, claims);
		};
		if (identity.repository() == null || identity.owner() == null) throw new AssertionException(Refusal.NO_REPOSITORY, "assertion names no repository");
		// the ids are what the pins compare; a token without them cannot be pinned or checked, so it is not accepted
		if (StringUtils.isBlank(identity.repositoryId()) || StringUtils.isBlank(identity.ownerId())) throw new AssertionException(Refusal.NO_REPOSITORY_IDS, "assertion carries no repository and owner ids");
		return new VerifiedAssertion(identity, claims, exp, jti);
	}

	/**
	 * A custom issuer (GitHub Enterprise Server) must be https on a public host name: the issuer
	 * drives key discovery, so a rule must not be able to point the server at itself or at the
	 * network around it. Returns the reason it is refused, or null when acceptable.
	 */
	public static String issuerProblem(String issuer) {
		if (StringUtils.isBlank(issuer)) return "issuer is required";
		java.net.URI u;
		try {
			u = java.net.URI.create(issuer.trim());
		} catch (RuntimeException e) {
			return "issuer is not a valid URL";
		}
		if (!"https".equalsIgnoreCase(u.getScheme())) return "issuer must use https";
		String host = u.getHost();
		if (StringUtils.isBlank(host)) return "issuer has no host";
		if (u.getUserInfo() != null) return "issuer must not carry credentials";
		String h = host.toLowerCase();
		if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || !h.contains(".")) return "issuer must be a public host name";
		if (h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || h.startsWith("[") || h.contains(":")) return "issuer must be a host name, not an IP address";
		return null;
	}
}

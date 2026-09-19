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
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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

	public static class AssertionException extends Exception {
		private static final long serialVersionUID = 1L;
		public AssertionException(String message) { super(message); }
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
			throw new AssertionException("malformed assertion");
		}
	}

	/**
	 * Full verification against a trusted issuer. {@code provider} decides how the claims are read.
	 */
	public VerifiedAssertion verify(String assertion, String issuer, Provider provider) throws AssertionException {
		if (StringUtils.isBlank(assertion) || StringUtils.isBlank(issuer)) throw new AssertionException("assertion and issuer are required");
		String audience = expectedAudience();
		if (StringUtils.isBlank(audience)) {
			log.error("federated identity exchange refused: relizaprops.baseuri is not configured, so no audience can be required");
			throw new AssertionException("this instance has no base URI configured for federated identities");
		}
		JwtDecoder decoder;
		try {
			decoder = decoders.get(issuer, decoderFactory);
		} catch (RuntimeException e) {
			log.error("cannot set up key discovery for federated issuer {}", issuer, e);
			throw new AssertionException("issuer keys unavailable");
		}
		Jwt jwt;
		try {
			jwt = decoder.decode(assertion);
		} catch (JwtException e) {
			log.warn("SECURITY: federated assertion from issuer {} rejected: {}", issuer, e.getMessage());
			throw new AssertionException("assertion rejected: " + e.getMessage());
		}
		if (!issuer.equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())) throw new AssertionException("issuer mismatch");
		List<String> aud = jwt.getAudience();
		if (aud == null || aud.stream().noneMatch(a -> a != null && a.replaceAll("/+$", "").equalsIgnoreCase(audience))) {
			throw new AssertionException("audience must be " + audience);
		}
		Instant exp = jwt.getExpiresAt();
		if (exp == null) throw new AssertionException("assertion has no expiry");
		if (exp.isAfter(Instant.now().plus(MAX_ASSERTION_LIFETIME))) throw new AssertionException("assertion lifetime is implausibly long");
		String jti = jwt.getId();
		if (StringUtils.isBlank(jti)) throw new AssertionException("assertion has no id");
		Map<String, Object> claims = jwt.getClaims();
		IdentityClaims identity = switch (provider) {
			case GITHUB_ACTIONS -> FederatedMatching.fromGitHub(issuer, claims);
		};
		if (identity.repository() == null || identity.owner() == null) throw new AssertionException("assertion names no repository");
		// the ids are what the pins compare; a token without them cannot be pinned or checked, so it is not accepted
		if (StringUtils.isBlank(identity.repositoryId()) || StringUtils.isBlank(identity.ownerId())) throw new AssertionException("assertion carries no repository and owner ids");
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

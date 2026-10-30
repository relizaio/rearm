/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;

import io.reliza.common.LogSafe;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.service.FederatedMatching.IdentityClaims;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies an identity token (RFC 7523 assertion) from an external issuer: signature against the
 * issuer's published keys (OIDC discovery, cached per issuer), issuer, expiry, audience equal to
 * this instance, and single use of the token id. Only issuers some active rule names are ever
 * contacted, so an unknown token cannot make the server fetch arbitrary URLs. A failed discovery is
 * remembered per issuer for {@link #DISCOVERY_RETRY_AFTER}, so an issuer outage costs one attempt and one
 * ERROR per window rather than one per exchange, and each issuer's decoder makes at most
 * {@link #KEY_SET_FETCHES_PER_WINDOW} key-set requests per {@link #KEY_SET_FETCH_WINDOW} after its build
 * (task TPF-53).
 */
@Service
@Slf4j
public class FederatedAssertionService {

	static final Duration MAX_ASSERTION_LIFETIME = Duration.ofHours(24);

	/** How long a failed key discovery for an issuer refuses its exchanges without a new attempt, counted from the end of the failed attempt (TPF-53). */
	static final Duration DISCOVERY_RETRY_AFTER = Duration.ofSeconds(60);

	/** The key-set refetch window of each issuer's decoder: Nimbus's own default interval, which Spring turns off (TPF-53). */
	static final Duration KEY_SET_FETCH_WINDOW = Duration.ofMillis(JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL);

	/** Key-set requests one decoder may make per {@link #KEY_SET_FETCH_WINDOW} after its build, on Nimbus's terms. */
	static final int KEY_SET_FETCHES_PER_WINDOW = 2;

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
		/** {@link #verify}: key discovery for a stored issuer failed, now or less than {@link #DISCOVERY_RETRY_AFTER} ago. */
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

	/** Issuers whose last discovery failed less than {@link #DISCOVERY_RETRY_AFTER} ago; written only by {@link #discover}'s failure branch. */
	private final Cache<String, Boolean> failedDiscoveries;

	private final Function<String, JwtDecoder> decoderFactory;

	@Autowired
	private RelizaConfigProps relizaConfigProps;

	public FederatedAssertionService() {
		this(Ticker.systemTicker());
	}

	private FederatedAssertionService(Ticker ticker) {
		this.failedDiscoveries = failureCache(ticker);
		this.decoderFactory = issuer -> discoveringDecoder(issuer, libraryTimeouts(), ticker);
	}

	/** Test hook: decoders without discovery, an explicit audience. */
	FederatedAssertionService(Function<String, JwtDecoder> decoderFactory, RelizaConfigProps props) {
		this(decoderFactory, props, Ticker.systemTicker());
	}

	/** Test hook: as above, with the clock of the failed-discovery window. */
	FederatedAssertionService(Function<String, JwtDecoder> decoderFactory, RelizaConfigProps props, Ticker ticker) {
		this.failedDiscoveries = failureCache(ticker);
		this.decoderFactory = decoderFactory;
		this.relizaConfigProps = props;
	}

	private static Cache<String, Boolean> failureCache(Ticker ticker) {
		return Caffeine.newBuilder().maximumSize(64).expireAfterWrite(DISCOVERY_RETRY_AFTER).ticker(ticker).build();
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
			decoder = decoders.get(issuer, this::discover);
		} catch (RuntimeException e) {
			// a live failure was logged once by discover; a refusal from the window writes no line (TPF-53)
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
	 * The success cache's loader (TPF-53). While the issuer's last discovery failed less than
	 * {@link #DISCOVERY_RETRY_AFTER} ago it refuses at once, with nothing sent or logged; otherwise it runs the
	 * factory, and a failure opens the window and writes the one ERROR of that window. Exchanges waiting behind an
	 * in-flight attempt for the same issuer run this in turn once it fails, and find the entry.
	 */
	private JwtDecoder discover(String issuer) {
		if (failedDiscoveries.getIfPresent(issuer) != null) throw new RecentDiscoveryFailure();
		try {
			return decoderFactory.apply(issuer);
		} catch (RuntimeException e) {
			failedDiscoveries.put(issuer, Boolean.TRUE);
			log.error("cannot set up key discovery for federated issuer {}; exchanges naming it are refused without a new discovery for the next {} seconds", LogSafe.text(issuer), DISCOVERY_RETRY_AFTER.toSeconds(), e);
			throw e;
		}
	}

	/** The window's refusal inside the loader: no stack, never logged or sent ({@link #verify} maps it to {@link Refusal#KEYS_UNAVAILABLE}). */
	private static final class RecentDiscoveryFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;
		RecentDiscoveryFailure() { super("key discovery failed recently", null, false, false); }
	}

	/**
	 * The decoder {@code JwtDecoders.fromIssuerLocation} builds (discovery, validators, key source and its cache),
	 * apart from its HTTP client: a {@link RestTemplate} over {@code http} carrying a {@link KeySetFetchLimit}, armed
	 * once the build's own requests (discovery and the first key set) are done (TPF-53).
	 */
	static JwtDecoder discoveringDecoder(String issuer, ClientHttpRequestFactory http, Ticker ticker) {
		KeySetFetchLimit limit = new KeySetFetchLimit(ticker);
		RestTemplate rest = new RestTemplate(http);
		rest.getInterceptors().add(limit);
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).restOperations(rest).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
		limit.arm();
		return decoder;
	}

	/** A new client per build with the connect and read timeouts Spring's own discovery client reads (JwtDecoderProviderConfigurationUtils, package-private there). */
	static ClientHttpRequestFactory libraryTimeouts() {
		SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
		f.setConnectTimeout(Integer.parseInt(System.getProperty("sun.net.client.defaultConnectTimeout", "30000")));
		f.setReadTimeout(Integer.parseInt(System.getProperty("sun.net.client.defaultReadTimeout", "30000")));
		return f;
	}

	/**
	 * Nimbus's default key-set refetch limit (RateLimitedJWKSetSource), on one decoder's HTTP client and on an
	 * injectable clock: once armed, the first request opens a window of {@link #KEY_SET_FETCH_WINDOW}, one more is
	 * allowed in it, and any further request in it is refused before it is sent. A refusal writes no log line: the
	 * decoder reports it as a failed key fetch, which the token endpoint's one line records.
	 */
	static final class KeySetFetchLimit implements ClientHttpRequestInterceptor {
		private final Ticker ticker;
		private boolean armed;
		private boolean windowOpen;
		private long windowEnd;
		private int left;

		KeySetFetchLimit(Ticker ticker) { this.ticker = ticker; }

		synchronized void arm() { armed = true; }

		private synchronized boolean admit() {
			if (!armed) return true;
			long now = ticker.read();
			if (!windowOpen || now - windowEnd >= 0) {
				windowOpen = true;
				windowEnd = now + KEY_SET_FETCH_WINDOW.toNanos();
				left = KEY_SET_FETCHES_PER_WINDOW - 1;
				return true;
			}
			if (left <= 0) return false;
			left--;
			return true;
		}

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
			if (!admit()) throw new IOException("key set refetch limit reached");
			return execution.execute(request, body);
		}
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

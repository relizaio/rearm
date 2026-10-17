/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.model.CliSession;
import io.reliza.model.UserData;
import io.reliza.service.CliSessionService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.ApiTokenService.TokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs on the stateless programmatic chain, in front of the GraphQL endpoint and the programmatic
 * artifact downloads ({@link ArtifactWs#PROGRAMMATIC_ARTIFACT_PATTERN}, TPF-13); the other paths of
 * the chain (token, schema, device, revoke, webhook) are not filtered. The filter decides its path set on the parsed,
 * decoded request path with the matcher type the chain is built from, from the same constants, so the chain's claim is
 * a superset of the filter's by construction: every spelling of these two paths the chain claims is filtered (TPF-24).
 *
 * <ul>
 * <li>{@code Authorization: Bearer <access token>}: verifies the token (signature, issuer,
 *     audience, expiry), loads the key row, checks the secret fingerprint, and exposes the
 *     resulting principal as a request attribute that
 *     {@code AuthorizationService.authenticateProgrammatic} picks up, so every programmatic
 *     resolver works unchanged. Invalid tokens are answered with 401 and
 *     {@code WWW-Authenticate: Bearer ... error="invalid_token"} (RFC 6750). A verified token bumps the
 *     key's last access ({@link ApiKeyAccessService#touchLastAccess}).</li>
 * <li>{@code Authorization: Basic}: verified here, once, for every key type (TPF-27), by the
 *     {@link BasicKeyVerifier} both chains share (TPF-29): {@link ApiKeyService#isMatchingApiKey} (the same
 *     check the token endpoint runs, behind the {@link RateLimitingFilter} in front of this filter), then the
 *     key row, whose last access the verifier bumps; the principal stored is {@link AuthHeaderParse#fromVerifiedKey}
 *     of that row, the shape the
 *     bearer branch stores, so the secret and any org named in the id text never travel past this filter. An
 *     admitted Basic keeps a {@code Deprecation} header pointing at the token endpoint. Presenting the key
 *     secret directly is deprecated in favour of the exchange; no sunset is scheduled yet.</li>
 * <li>Any credential that does not verify (a bearer, a Basic with a wrong secret, an unknown id, a
 *     malformed header, an unrecognised type prefix, or any other scheme) gets one answer: 401 with
 *     {@link #INVALID_CREDENTIAL_CHALLENGE} and {@link #INVALID_CREDENTIAL_BODY}, no deprecation header.</li>
 * </ul>
 * The legacy {@code /graphql} endpoint is on the browser chain, where this filter does not run: on the browser chain
 * {@link LegacyGraphqlBasicFilter} runs the same verification on {@code /graphql} (TPF-29).
 * Not a Spring component on purpose: it is registered on the programmatic chain only.
 */
@Slf4j
public class ProgrammaticAuthenticationFilter extends OncePerRequestFilter {

	public static final String PRINCIPAL_ATTRIBUTE = "io.reliza.programmatic.principal";
	static final String LANE = "programmatic";
	/**
	 * The two 401 shapes of the programmatic lane: no credential at all, and a credential that does not
	 * verify. Shared with the artifact download handlers ({@link ArtifactWs}), which authenticate in the
	 * handler, so the filter and the handlers answer with one text.
	 */
	public static final String NO_CREDENTIAL_CHALLENGE = "Bearer realm=\"rearm\"";
	public static final String NO_CREDENTIAL_BODY = "{\"error\":\"invalid_request\",\"error_description\":\"authentication required: a bearer access token from "
			+ ProgrammaticTokenController.PATH + ", or the API key as HTTP Basic\"}";
	public static final String INVALID_CREDENTIAL_CHALLENGE = "Bearer realm=\"rearm\", error=\"invalid_token\", error_description=\"The access token is invalid, expired or revoked\"";
	public static final String INVALID_CREDENTIAL_BODY = "{\"error\":\"invalid_token\",\"error_description\":\"The access token is invalid, expired or revoked\"}";

	private final ApiTokenService apiTokenService;
	private final ApiKeyService apiKeyService;
	private final ApiKeyAccessService apiKeyAccessService;
	private final CliSessionService cliSessionService;
	private final FederatedTrustRuleService federatedTrustRuleService;
	private final BasicKeyVerifier verifier;

	public ProgrammaticAuthenticationFilter(ApiTokenService apiTokenService, ApiKeyService apiKeyService, ApiKeyAccessService apiKeyAccessService,
			CliSessionService cliSessionService, FederatedTrustRuleService federatedTrustRuleService) {
		this.apiTokenService = apiTokenService;
		this.apiKeyService = apiKeyService;
		this.apiKeyAccessService = apiKeyAccessService;
		this.cliSessionService = cliSessionService;
		this.federatedTrustRuleService = federatedTrustRuleService;
		this.verifier = new BasicKeyVerifier(apiKeyService, apiKeyAccessService, LANE);
	}

	/**
	 * The GraphQL path and the artifact download paths, both matched with the matcher type
	 * {@code securityMatcher(String...)} builds for the chain, from the same constants, on the parsed and decoded
	 * request path, so every spelling of these two patterns the chain claims (a percent-encoded letter included), this
	 * filter claims. The GraphQL matcher takes no method, so any method on the path is filtered.
	 */
	private static final RequestMatcher FILTERED_PATHS = new OrRequestMatcher(
			PathPatternRequestMatcher.withDefaults().matcher(ProgrammaticGraphQlConfig.PATH),
			PathPatternRequestMatcher.withDefaults().matcher(ArtifactWs.PROGRAMMATIC_ARTIFACT_PATTERN));

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !FILTERED_PATHS.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null) {
			// no credential at all: the challenge is the runtime signal that a token is required here, the same
			// way an open-or-protected TEA server tells a client which endpoints need one
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, NO_CREDENTIAL_CHALLENGE);
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write(NO_CREDENTIAL_BODY);
			return;
		}
		if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String token = authorization.substring(7).trim();
			Optional<TokenClaims> claims = apiTokenService.verify(token, ApiTokenService.AUD_PROGRAMMATIC);
			Optional<ApiKey> oak = claims.flatMap(c -> apiKeyService.getApiKey(c.keyUuid()));
			String reason = null;
			if (claims.isEmpty()) {
				reason = "invalid or expired";
			} else if (oak.isEmpty()) {
				reason = "key not found";
			} else {
				ApiKeyData akd = ApiKeyData.dataFromRecord(oak.get());
				if (akd.getStatus() != ApiKeyStatus.ACTIVE) {
					reason = "key inactive";
				} else if (claims.get().ruleUuids() != null) {
					// federated exchange: every admitting rule must still be usable at the version the token was minted with
					if (federatedTrustRuleService.usable(claims.get().ruleUuids(), claims.get().fingerprint()).isEmpty()) reason = "trust rule changed, disabled or removed";
				} else if (claims.get().sessionUuid() != null) {
					// CLI browser login: the token is bound to a session, not a secret
					Optional<CliSession> session = cliSessionService.usable(claims.get().sessionUuid(), claims.get().fingerprint());
					Optional<UserData> owner = session.flatMap(cliSessionService::owner);
					if (session.isEmpty()) reason = "CLI session revoked or expired";
					else if (owner.isEmpty()) reason = "CLI session user inactive";
					else if (!CliSessionService.backs(owner.get(), oak.get(), akd)) reason = "CLI session user left the org or no longer holds the key";
					else cliSessionService.touchLastUsed(session.get().getUuid());
				} else {
					// the secret the token was exchanged with must still exist, be active and be unchanged
					boolean live = akd.effectiveSecrets(oak.get().getApiKey()).stream()
							.anyMatch(sec -> sec.isUsable() && ApiTokenService.fingerprint(sec).equals(claims.get().fingerprint()));
					if (!live) reason = "secret retired, expired or regenerated";
				}
			}
			if (reason != null) {
				log.warn("SECURITY: programmatic access token rejected from {} ({})", request.getRemoteAddr(), reason);
				refuse(response);
				return;
			}
			AuthHeaderParse principal;
			if (claims.get().ruleUuids() != null) {
				principal = AuthHeaderParse.fromVerifiedFederation(oak.get(),
						FederatedTrustRuleService.contextOf(claims.get().ruleUuids(), claims.get().federation()), request.getRemoteAddr());
			} else if (claims.get().sessionUuid() != null) {
				principal = AuthHeaderParse.fromVerifiedSession(oak.get(), claims.get().actorUser(),
						claims.get().sessionUuid(), request.getRemoteAddr());
			} else {
				principal = AuthHeaderParse.fromVerifiedKey(oak.get(), claims.get().slot(), request.getRemoteAddr());
			}
			request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
			// every verified credential is a use of the key; the resolvers behind it do not all record access
			apiKeyAccessService.touchLastAccess(oak.get().getUuid());
			chain.doFilter(request, response);
			return;
		}
		// Basic, or any other scheme (Digest, Token, garbage, a bare "Basic" or "Bearer"): the shared verifier decides
		Optional<AuthHeaderParse> basic = verifier.verify(authorization, request.getRemoteAddr());
		if (basic.isEmpty()) {
			refuse(response);
			return;
		}
		BasicKeyVerifier.deprecationHeaders(response);
		request.setAttribute(PRINCIPAL_ATTRIBUTE, basic.get());
		chain.doFilter(request, response);
	}

	/** The one invalid-credential answer, {@link BasicKeyVerifier#refuse}: the bearer and the Basic refusals are byte-identical. */
	private static void refuse(HttpServletResponse response) throws IOException {
		BasicKeyVerifier.refuse(response);
	}

	/** The identity a refusal logs: see {@link AuthHeaderParse#canonicalId()}; empty for a null parse. */
	static String canonicalIdOf(AuthHeaderParse parsed) {
		return parsed == null ? "" : parsed.canonicalId();
	}
}

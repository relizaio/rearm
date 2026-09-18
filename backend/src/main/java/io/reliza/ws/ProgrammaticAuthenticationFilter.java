/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.model.CliSession;
import io.reliza.service.CliSessionService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.ApiTokenService.TokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs on the stateless programmatic chain, in front of the GraphQL endpoint.
 *
 * <ul>
 * <li>{@code Authorization: Bearer <access token>}: verifies the token (signature, issuer,
 *     audience, expiry), loads the key row, checks the secret fingerprint, and exposes the
 *     resulting principal as a request attribute that
 *     {@code AuthorizationService.authenticateProgrammatic} picks up, so every programmatic
 *     resolver works unchanged. Invalid tokens are answered with 401 and
 *     {@code WWW-Authenticate: Bearer ... error="invalid_token"} (RFC 6750).</li>
 * <li>{@code Authorization: Basic}: passes through as today (resolvers verify the secret),
 *     with a {@code Deprecation} header pointing at the token endpoint. Presenting the key
 *     secret directly on the GraphQL endpoint is deprecated in favour of the exchange; no
 *     sunset is scheduled yet.</li>
 * </ul>
 * Not a Spring component on purpose: it is registered on the programmatic chain only.
 */
@Slf4j
public class ProgrammaticAuthenticationFilter extends OncePerRequestFilter {

	public static final String PRINCIPAL_ATTRIBUTE = "io.reliza.programmatic.principal";
	private static final String DEPRECATION_NOTE = "API key secrets presented directly are deprecated; exchange the key for an access token at "
			+ ProgrammaticTokenController.PATH + " (grant_type=client_credentials) and send it as a bearer token";

	private final ApiTokenService apiTokenService;
	private final ApiKeyService apiKeyService;
	private final CliSessionService cliSessionService;
	private final FederatedTrustRuleService federatedTrustRuleService;

	public ProgrammaticAuthenticationFilter(ApiTokenService apiTokenService, ApiKeyService apiKeyService, CliSessionService cliSessionService,
			FederatedTrustRuleService federatedTrustRuleService) {
		this.apiTokenService = apiTokenService;
		this.apiKeyService = apiKeyService;
		this.cliSessionService = cliSessionService;
		this.federatedTrustRuleService = federatedTrustRuleService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !ProgrammaticGraphQlConfig.PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null) {
			// no credential at all: the challenge is the runtime signal that a token is required here, the same
			// way an open-or-protected TEA server tells a client which endpoints need one
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"rearm\"");
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"error\":\"invalid_request\",\"error_description\":\"authentication required: a bearer access token from "
					+ ProgrammaticTokenController.PATH + ", or the API key as HTTP Basic\"}");
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
					if (session.isEmpty()) reason = "CLI session revoked or expired";
					else if (cliSessionService.owner(session.get()).isEmpty()) reason = "CLI session user inactive";
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
				response.setStatus(HttpStatus.UNAUTHORIZED.value());
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"rearm\", error=\"invalid_token\", error_description=\"The access token is invalid, expired or revoked\"");
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.getWriter().write("{\"error\":\"invalid_token\",\"error_description\":\"The access token is invalid, expired or revoked\"}");
				return;
			}
			AuthHeaderParse principal;
			if (claims.get().ruleUuids() != null) {
				principal = AuthHeaderParse.fromVerifiedFederation(oak.get(),
						FederatedTrustRuleService.contextOf(claims.get().ruleUuids(), claims.get().federation()), request.getRemoteAddr());
			} else if (claims.get().sessionUuid() != null) {
				principal = AuthHeaderParse.fromVerifiedSession(oak.get(), claims.get().actorUser(), request.getRemoteAddr());
			} else {
				principal = AuthHeaderParse.fromVerifiedKey(oak.get(), claims.get().slot(), request.getRemoteAddr());
			}
			request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
			chain.doFilter(request, response);
			return;
		}
		if (authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
			response.setHeader("Deprecation", "true");
			response.setHeader("Link", "<" + ProgrammaticTokenController.PATH + ">; rel=\"alternate\"; title=\"token endpoint\"");
			response.setHeader("X-ReARM-Deprecation", DEPRECATION_NOTE);
		}
		chain.doFilter(request, response);
	}
}

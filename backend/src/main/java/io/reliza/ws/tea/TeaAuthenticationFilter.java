/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;

import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ApiTokenService.TokenClaims;
import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.ws.App;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Classifies every request to the TEA API, {@code /tea/**} as the chain reads it (task TEA-3; task TEA-6 round 4): anonymous, an invalid token, or a valid access token
 * of an EXTERNAL key, issued for the {@code tea} audience by the TEA token endpoint. It never answers: a PUBLIC
 * object is served whatever token was presented, so the object decides ({@link TeaAuthorizationService}). The
 * outcome is left in {@link #CALLER_ATTRIBUTE}. Not a Spring component: registered on the TEA chain only.
 */
@Slf4j
public class TeaAuthenticationFilter extends OncePerRequestFilter {

	public static final String CALLER_ATTRIBUTE = TeaAuthorizationService.CALLER_ATTRIBUTE;

	private final ApiTokenService apiTokenService;
	private final ApiKeyService apiKeyService;

	public TeaAuthenticationFilter(ApiTokenService apiTokenService, ApiKeyService apiKeyService) {
		this.apiTokenService = apiTokenService;
		this.apiKeyService = apiKeyService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// the TEA chain's own reading of the path (task TEA-6 round 4): a percent-encoded spelling of a TEA API path is
		// served as that path, so its bearer is classified as on the plain spelling
		Optional<PathContainer> path = App.chainPathOf(request.getRequestURI(), request.getContextPath());
		return path.isEmpty() || !App.isTeaApiPath(path.get()) || TeaTokenController.isTokenPath(path.get());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		request.setAttribute(CALLER_ATTRIBUTE, classify(request));
		chain.doFilter(request, response);
	}

	TeaCaller classify(HttpServletRequest request) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return TeaCaller.anonymous();
		String token = authorization.substring(7).trim();
		String remoteIp = request.getRemoteAddr();
		Optional<TokenClaims> claims = apiTokenService.verify(token, ApiTokenService.AUD_TEA);
		if (claims.isEmpty()) return invalid(remoteIp, "invalid or expired");
		Optional<ApiKey> oak = apiKeyService.getApiKey(claims.get().keyUuid());
		if (oak.isEmpty()) return invalid(remoteIp, "key not found");
		ApiKey ak = oak.get();
		if (ak.getObjectType() != ApiTypeEnum.EXTERNAL) return invalid(remoteIp, "not an external key");
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (akd.getStatus() != ApiKeyStatus.ACTIVE) return invalid(remoteIp, "key inactive");
		if (claims.get().sessionUuid() != null || claims.get().ruleUuids() != null) return invalid(remoteIp, "not a key token");
		if (!ApiKeyService.isTokenSecretLive(ak, akd, claims.get())) return invalid(remoteIp, "secret retired, expired or regenerated");
		return TeaCaller.authenticated(new TeaPrincipal(ak.getUuid(), ak.getOrg(), ApiKeyService.keyIdOf(ak, akd),
				claims.get().slot(), remoteIp));
	}

	private static TeaCaller invalid(String remoteIp, String reason) {
		log.warn("SECURITY: TEA access token rejected from {} ({})", remoteIp, reason);
		return TeaCaller.invalidToken(reason);
	}
}

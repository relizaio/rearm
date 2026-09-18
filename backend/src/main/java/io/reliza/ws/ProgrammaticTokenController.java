/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.common.OAuthErrors;
import io.reliza.service.CliSessionService;
import io.reliza.service.FederatedTrustRuleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * OAuth 2.0 token endpoint for API keys (RFC 6749 section 4.4, the shape the TEA
 * specification mandates): {@code POST /api/programmatic/token} with
 * {@code grant_type=client_credentials}, the key id and secret as HTTP Basic (or as
 * {@code client_id} / {@code client_secret} form fields for clients that cannot set the
 * header). Returns a one-hour bearer access token for the programmatic GraphQL endpoint.
 * No refresh tokens: a client holding its key simply exchanges again.
 */
@RestController
@Slf4j
public class ProgrammaticTokenController {

	@org.springframework.beans.factory.annotation.Autowired
	private CliSessionService cliSessionService;

	public static final String PATH = "/api/programmatic/token";

	private final ApiKeyService apiKeyService;
	private final ApiTokenService apiTokenService;

	public ProgrammaticTokenController(ApiKeyService apiKeyService, ApiTokenService apiTokenService) {
		this.apiKeyService = apiKeyService;
		this.apiTokenService = apiTokenService;
	}

	@PostMapping(value = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> token(HttpServletRequest request,
			@RequestParam(value = "grant_type", required = false) String grantType,
			@RequestParam(value = "client_id", required = false) String clientId,
			@RequestParam(value = "client_secret", required = false) String clientSecret,
			@RequestParam(value = "device_code", required = false) String deviceCode,
			@RequestParam(value = "refresh_token", required = false) String refreshToken,
			@RequestParam(value = "assertion", required = false) String assertion) {
		if (DEVICE_CODE_GRANT.equals(grantType)) return deviceCodeGrant(request, deviceCode);
		if (OAuthErrors.GRANT_REFRESH_TOKEN.equals(grantType)) return refreshGrant(request, refreshToken);
		if (JWT_BEARER_GRANT.equals(grantType)) return jwtBearerGrant(request, assertion, clientId);
		if (!OAuthErrors.GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
			return error(HttpStatus.BAD_REQUEST, OAuthErrors.UNSUPPORTED_GRANT_TYPE, "supported: " + OAuthErrors.GRANT_CLIENT_CREDENTIALS + ", " + DEVICE_CODE_GRANT + ", "
					+ OAuthErrors.GRANT_REFRESH_TOKEN + ", " + JWT_BEARER_GRANT);
		}
		HttpHeaders headers = new HttpHeaders();
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (StringUtils.isEmpty(authorization) && StringUtils.isNotEmpty(clientId) && StringUtils.isNotEmpty(clientSecret)) {
			authorization = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		if (StringUtils.isEmpty(authorization)) {
			return invalidClient("client authentication required (HTTP Basic with the API key id and secret)");
		}
		headers.set(HttpHeaders.AUTHORIZATION, authorization);
		AuthHeaderParse ahp = AuthHeaderParse.parseAuthHeader(headers, request.getRemoteAddr());
		if (ahp == null || ahp.getType() == null || StringUtils.isEmpty(ahp.getApiKey())) {
			return invalidClient("malformed API key credentials");
		}
		UUID keyUuid = apiKeyService.isMatchingApiKey(ahp);
		if (keyUuid == null) {
			return invalidClient("unknown API key or wrong secret");
		}
		Optional<ApiKey> oak = apiKeyService.getApiKey(keyUuid);
		if (oak.isEmpty()) {
			return invalidClient("unknown API key");
		}
		ApiKeyData akd = ApiKeyData.dataFromRecord(oak.get());
		int slot = ahp.getMatchedSecretSlot() == null ? 1 : ahp.getMatchedSecretSlot();
		ApiKeySecret secret = akd.effectiveSecrets(oak.get().getApiKey()).stream().filter(x -> x.getSlot() == slot).findFirst()
				.orElse(null);
		if (secret == null) {
			return invalidClient("unknown API key secret");
		}
		String token = apiTokenService.issue(oak.get(), secret, ApiTokenService.AUD_PROGRAMMATIC);
		log.info("Issued programmatic access token for key {} (org {}) from {}", keyUuid, oak.get().getOrg(), request.getRemoteAddr());
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header(HttpHeaders.PRAGMA, "no-cache")
				.body(Map.of("access_token", token, "token_type", "Bearer", "expires_in", ApiTokenService.TTL_SECONDS));
	}

	public static final String DEVICE_CODE_GRANT = OAuthErrors.GRANT_DEVICE_CODE;
	public static final String JWT_BEARER_GRANT = OAuthErrors.GRANT_JWT_BEARER;

	@org.springframework.beans.factory.annotation.Autowired
	private FederatedTrustRuleService federatedTrustRuleService;

	/**
	 * RFC 7523: an identity token from a trusted external issuer (GitHub Actions) is exchanged for
	 * the usual one-hour access token. {@code client_id} is the ReARM organization uuid, needed only
	 * when several organizations trust the same identity. Outcomes are 200 with an error member,
	 * like the device flow, because ingresses in front of ReARM rewrite 4xx bodies.
	 */
	private ResponseEntity<Map<String, Object>> jwtBearerGrant(HttpServletRequest request, String assertion, String clientId) {
		if (StringUtils.isEmpty(assertion)) return softError(OAuthErrors.INVALID_REQUEST, "assertion is required");
		FederatedTrustRuleService.Exchange ex;
		try {
			ex = federatedTrustRuleService.exchange(assertion, clientId);
		} catch (FederatedTrustRuleService.ExchangeException e) {
			log.warn("SECURITY: federated identity exchange refused from {}: {}", request.getRemoteAddr(), e.getMessage());
			return softError(e.code(), e.getMessage());
		}
		String token = apiTokenService.issueForFederation(ex.principal(), ex.rules(), FederatedTrustRuleService.tokenClaimsOf(ex.context()),
				ApiTokenService.AUD_PROGRAMMATIC, ex.clip());
		log.info("Issued federated access token for {} ({} run {}) as key {} in org {} from {}", ex.context().repository(), ex.context().provider(),
				ex.context().runId(), ex.principal().getUuid(), ex.principal().getOrg(), request.getRemoteAddr());
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("access_token", token);
		body.put("token_type", "Bearer");
		body.put("expires_in", ApiTokenService.TTL_SECONDS);
		body.put("api_key_id", ApiKeyService.keyIdOf(ex.principal()));
		body.put("api_key_uuid", ex.principal().getUuid().toString());
		body.put("org", String.valueOf(ex.principal().getOrg()));
		body.put("identity", ex.context().repository());
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache").body(body);
	}

	/** RFC 8628 §3.4: the CLI polls with its device code until the user has approved the login in the browser. */
	private ResponseEntity<Map<String, Object>> deviceCodeGrant(HttpServletRequest request, String deviceCode) {
		CliSessionService.PollResult r = cliSessionService.poll(deviceCode);
		switch (r.outcome()) {
			case AUTHORIZATION_PENDING: return softError(OAuthErrors.AUTHORIZATION_PENDING, "the user has not approved this login yet");
			case ACCESS_DENIED: return softError(OAuthErrors.ACCESS_DENIED, "the user denied this login");
			case EXPIRED: return softError(OAuthErrors.EXPIRED_TOKEN, "this login request expired; start again");
			case INVALID: return softError(OAuthErrors.INVALID_GRANT, "unknown or already used device code");
			case DELIVERED:
			default:
				CliSession s = r.delivery().session();
				Optional<ApiKey> oak = apiKeyService.getApiKey(s.getApiKey());
				if (oak.isEmpty()) return softError(OAuthErrors.INVALID_GRANT, "the key behind this login is gone");
				String token = apiTokenService.issueForSession(oak.get(), s, ApiTokenService.AUD_PROGRAMMATIC);
				log.info("CLI session {} delivered to {} (key {}, org {})", s.getUuid(), request.getRemoteAddr(), s.getApiKey(), s.getOrg());
				return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
						.body(Map.of("access_token", token, "token_type", "Bearer", "expires_in", ApiTokenService.TTL_SECONDS,
								"refresh_token", r.delivery().refreshToken(), "api_key_id", ApiKeyService.keyIdOf(oak.get()),
								"api_key_uuid", oak.get().getUuid().toString(), "org", String.valueOf(oak.get().getOrg()),
								"session", s.getUuid().toString(), "session_expires_at", s.getExpiresDate().toInstant().toString()));
		}
	}

	/** RFC 6749 §6: a live session's refresh token buys the next one-hour access token and slides the session expiry. */
	private ResponseEntity<Map<String, Object>> refreshGrant(HttpServletRequest request, String refreshToken) {
		Optional<CliSessionService.Refreshed> or = cliSessionService.refresh(refreshToken, request.getRemoteAddr());
		if (or.isEmpty()) return softError(OAuthErrors.INVALID_GRANT, "unknown, expired or revoked refresh token, or the key behind the session is inactive; log in again");
		CliSession s = or.get().session();
		Optional<ApiKey> oak = apiKeyService.getApiKey(s.getApiKey());
		if (oak.isEmpty() || ApiKeyData.dataFromRecord(oak.get()).getStatus() != ApiKeyStatus.ACTIVE) {
			return softError(OAuthErrors.INVALID_GRANT, "the key behind this session is inactive or gone");
		}
		String token = apiTokenService.issueForSession(oak.get(), s, ApiTokenService.AUD_PROGRAMMATIC);
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
				.body(Map.of("access_token", token, "token_type", "Bearer", "expires_in", ApiTokenService.TTL_SECONDS,
						"refresh_token", or.get().refreshToken(), "session_expires_at", s.getExpiresDate().toInstant().toString()));
	}

	/**
	 * Device-flow and refresh outcomes are reported with HTTP 200 and the RFC error member in the
	 * body. RFC 8628 / 6749 say 400, but the ingress in front of ReARM deployments replaces every
	 * 4xx body with its own text error page, which would leave the CLI unable to tell
	 * authorization_pending from access_denied. These grants are spoken only by our CLI, which
	 * reads the {@code error} member regardless of status; the client_credentials path is unchanged.
	 */
	private static ResponseEntity<Map<String, Object>> softError(String code, String description) {
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(Map.of("error", code, "error_description", description));
	}

	private static ResponseEntity<Map<String, Object>> invalidClient(String description) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"rearm\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(Map.of("error", OAuthErrors.INVALID_CLIENT, "error_description", description));
	}

	private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String description) {
		return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(Map.of("error", code, "error_description", description));
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.common.LogSafe;
import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
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
 * No refresh tokens: a client holding its key simply exchanges again. Request parameters in the
 * URL query string are refused with 400 invalid_request ({@link OAuthQueryGuard}), and so is a
 * request that carries one of them more than once. The parameters are read by exact name through
 * {@link OAuthQueryGuard#single}, the read the rate limiter makes, never through Spring's binder
 * (task TPF-26): do not put a {@code @RequestParam} back on {@link #token}.
 */
@RestController
@Slf4j
public class ProgrammaticTokenController {

	@org.springframework.beans.factory.annotation.Autowired
	private CliSessionService cliSessionService;

	public static final String PATH = "/api/programmatic/token";

	private final ApiKeyService apiKeyService;
	private final ApiTokenService apiTokenService;
	private final ApiKeyAccessService apiKeyAccessService;

	public ProgrammaticTokenController(ApiKeyService apiKeyService, ApiTokenService apiTokenService,
			ApiKeyAccessService apiKeyAccessService) {
		this.apiKeyService = apiKeyService;
		this.apiTokenService = apiTokenService;
		this.apiKeyAccessService = apiKeyAccessService;
	}

	@PostMapping(value = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
		// TPF-3: before anything else is read, refuse OAuth parameters in the URL query string (names only are logged and answered)
		List<String> inQuery = OAuthQueryGuard.namesInQuery(request.getQueryString(), OAuthQueryGuard.TOKEN_REQUEST_PARAMETERS);
		if (!inQuery.isEmpty()) {
			log.warn("SECURITY: token request refused from {}: {} in the URL query string", request.getRemoteAddr(), inQuery);
			return error(HttpStatus.BAD_REQUEST, OAuthErrors.INVALID_REQUEST, OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: " + String.join(", ", inQuery));
		}
		// TPF-26: a parameter sent more than once is refused before anything is parsed (RFC 6749 section 3.2; names only)
		List<String> repeated = OAuthQueryGuard.namesRepeated(request.getParameterMap(), OAuthQueryGuard.TOKEN_REQUEST_PARAMETERS);
		if (!repeated.isEmpty()) {
			log.warn("SECURITY: token request refused from {}: {} sent more than once", request.getRemoteAddr(), repeated);
			return error(HttpStatus.BAD_REQUEST, OAuthErrors.INVALID_REQUEST, OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: " + String.join(", ", repeated));
		}
		// TPF-26: read by exact name through the helper the rate limiter reads client_id with, so both read the same text
		Map<String, String[]> params = request.getParameterMap();
		String grantType = OAuthQueryGuard.single(params, "grant_type");
		String clientId = OAuthQueryGuard.single(params, "client_id");
		String clientSecret = OAuthQueryGuard.single(params, "client_secret");
		String deviceCode = OAuthQueryGuard.single(params, "device_code");
		String refreshToken = OAuthQueryGuard.single(params, "refresh_token");
		String assertion = OAuthQueryGuard.single(params, "assertion");
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
		// the exchange verified the key's secret: a use of the key, like a Basic credential in the filters
		apiKeyAccessService.touchLastAccess(keyUuid);
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
	 * like the device flow, because ingresses in front of ReARM rewrite 4xx bodies. Every refusal
	 * before a rule admits the verified identity reads {@link FederatedTrustRuleService#NOT_ACCEPTED}
	 * (task TPF-31); the reason is written to the log line below, never to the wire.
	 */
	private ResponseEntity<Map<String, Object>> jwtBearerGrant(HttpServletRequest request, String assertion, String clientId) {
		if (StringUtils.isEmpty(assertion)) return softError(OAuthErrors.INVALID_REQUEST, "assertion is required");
		FederatedTrustRuleService.Exchange ex;
		try {
			ex = federatedTrustRuleService.exchange(assertion, clientId);
		} catch (FederatedTrustRuleService.ExchangeException e) {
			// TPF-31: the one line of a refused exchange; the reason is the distinction the wire text no longer carries
			// the address is request-derived too: forward-headers-strategy native makes it the X-Forwarded-For entry for an internal-range peer
			log.warn("SECURITY: federated identity exchange refused from {}: {} (issuer {}, identity {})", LogSafe.text(request.getRemoteAddr()), e.reason(),
					LogSafe.text(e.issuer()), LogSafe.text(e.identity()));
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
				Map<String, Object> body = sessionTokenBody(token, s, r.delivery().refreshToken());
				body.put("api_key_id", ApiKeyService.keyIdOf(oak.get()));
				body.put("api_key_uuid", oak.get().getUuid().toString());
				body.put("org", String.valueOf(oak.get().getOrg()));
				body.put("session", s.getUuid().toString());
				return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache").body(body);
		}
	}

	/** RFC 6749 §6: a live session's refresh token buys the next one-hour access token and slides the session expiry. */
	private ResponseEntity<Map<String, Object>> refreshGrant(HttpServletRequest request, String refreshToken) {
		Optional<CliSessionService.Refreshed> or;
		try {
			or = cliSessionService.refresh(refreshToken, request.getRemoteAddr());
		} catch (CliSessionService.SessionEnded e) {
			return softError(OAuthErrors.INVALID_GRANT, e.getMessage());
		}
		if (or.isEmpty()) return softError(OAuthErrors.INVALID_GRANT, "unknown, expired or revoked refresh token, the key behind the session is inactive, or its user left the org or no longer owns or holds the key; log in again");
		CliSession s = or.get().session();
		Optional<ApiKey> oak = apiKeyService.getApiKey(s.getApiKey());
		if (oak.isEmpty() || ApiKeyData.dataFromRecord(oak.get()).getStatus() != ApiKeyStatus.ACTIVE) {
			return softError(OAuthErrors.INVALID_GRANT, "the key behind this session is inactive or gone");
		}
		String token = apiTokenService.issueForSession(oak.get(), s, ApiTokenService.AUD_PROGRAMMATIC);
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
				.body(sessionTokenBody(token, s, or.get().refreshToken()));
	}

	/**
	 * The token response of the device and refresh grants: expires_in is the token's real life (an
	 * hour, or less when the session ends first), the refresh token only when there is one worth
	 * having, and session_hard_expiry when the key bounds its sessions (task RD3-7).
	 */
	static Map<String, Object> sessionTokenBody(String token, CliSession s, String refreshToken) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("access_token", token);
		body.put("token_type", "Bearer");
		body.put("expires_in", io.reliza.common.CliSessionCodes.expiresInSeconds(java.time.ZonedDateTime.now(), s.getExpiresDate()));
		if (refreshToken != null) body.put("refresh_token", refreshToken);
		body.put("session_expires_at", s.getExpiresDate().toInstant().toString());
		if (s.getHardExpiresDate() != null) body.put("session_hard_expiry", s.getHardExpiresDate().toInstant().toString());
		return body;
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

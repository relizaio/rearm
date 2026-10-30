/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.reliza.common.LogSafe;
import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.model.Organization;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaTokenErrorResponse.ErrorEnum;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ExternalApiKeyService;
import io.reliza.service.ExternalApiKeyService.ExternalKeyMatch;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * The TEA token endpoint (task TEA-3): the {@code POST /token} of the TEA 1.0.0 authentication chapter, mounted on
 * the organization's API base ({@code /tea/<org TEA id>}). RFC 6749 client_credentials only, with the EXTERNAL
 * key id and secret as HTTP Basic (each part form-urlencoded before Base64) or as {@code client_id} /
 * {@code client_secret} form fields. Answers a one-hour bearer token for the {@code tea} audience, with the real
 * RFC 6749 statuses: a TEA client is third-party code. No refresh token, no other grant.
 *
 * <p>The request shape is checked by the guard the programmatic token endpoint runs ({@link OAuthQueryGuard}, task
 * TEA-24): a token-request parameter in the URL query string, a token-request parameter sent more than once, and an
 * Authorization header together with a client_id or client_secret form field are each refused with 400
 * invalid_request. The parameters are read by exact name through {@link OAuthQueryGuard#single}, never through
 * Spring's binder (task TPF-26): do not put a {@code @RequestParam} back on {@code token}.
 */
@RestController
@Slf4j
public class TeaTokenController {

	public static final String TOKEN_PATH_TEMPLATE = TeaPaths.ORG_BASE + "/token";
	private static final PathPattern TOKEN_PATH = PathPatternParser.defaultInstance.parse(TOKEN_PATH_TEMPLATE);
	static final String CHALLENGE = "Basic realm=\"tea\"";
	static final String UNDECODABLE_DESCRIPTION = "the request parameters could not be decoded";

	private final ExternalApiKeyService externalApiKeyService;
	private final OrganizationRepository organizationRepository;
	private final ApiTokenService apiTokenService;
	private final ApiKeyAccessService apiKeyAccessService;

	public TeaTokenController(ExternalApiKeyService externalApiKeyService, OrganizationRepository organizationRepository,
			ApiTokenService apiTokenService, ApiKeyAccessService apiKeyAccessService) {
		this.externalApiKeyService = externalApiKeyService;
		this.organizationRepository = organizationRepository;
		this.apiTokenService = apiTokenService;
		this.apiKeyAccessService = apiKeyAccessService;
	}

	/**
	 * Whether a path, read as the TEA chain reads it ({@link io.reliza.ws.App#chainPathOf}), is the token endpoint
	 * of some organization, the path this controller serves (the TEA filter skips it).
	 */
	public static boolean isTokenPath(PathContainer path) {
		return null != path && TOKEN_PATH.matches(path);
	}

	@PostMapping(value = TOKEN_PATH_TEMPLATE, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> token(HttpServletRequest request, @PathVariable("org") String orgTeaId) {
		// 1. the organization of the base path
		Optional<UUID> org = organizationOf(orgTeaId);
		if (org.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).header(HttpHeaders.CACHE_CONTROL, "no-store")
					.contentType(MediaType.APPLICATION_JSON).body(new TeaErrorResponse(TeaUnknownErrorType.OBJECT_UNKNOWN));
		}
		// 2. the request shape: every token-request parameter comes from the form body (RFC 6749 2.3.1, 3.2), checked on
		// the raw query string before any parameter parse; names only are logged and answered, never the query string
		List<String> inQuery = OAuthQueryGuard.namesInQuery(request.getQueryString(), OAuthQueryGuard.TOKEN_REQUEST_PARAMETERS);
		if (!inQuery.isEmpty()) {
			log.warn("SECURITY: TEA token request refused from {}: {} in the URL query string", LogSafe.text(request.getRemoteAddr()), inQuery);
			return error(HttpStatus.BAD_REQUEST, ErrorEnum.INVALID_REQUEST, OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: " + String.join(", ", inQuery));
		}
		// 3. a parameter sent more than once is refused before anything is read (RFC 6749 3.2; names only); this is the
		// first parameter parse of the request, so an undecodable form or query throws here (invalidParameter answers it)
		List<String> repeated = OAuthQueryGuard.namesRepeated(request.getParameterMap(), OAuthQueryGuard.TOKEN_REQUEST_PARAMETERS);
		if (!repeated.isEmpty()) {
			log.warn("SECURITY: TEA token request refused from {}: {} sent more than once", LogSafe.text(request.getRemoteAddr()), repeated);
			return error(HttpStatus.BAD_REQUEST, ErrorEnum.INVALID_REQUEST, OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: " + String.join(", ", repeated));
		}
		// 4. the reads, by exact name
		Map<String, String[]> params = request.getParameterMap();
		String grantType = OAuthQueryGuard.single(params, "grant_type");
		String clientId = OAuthQueryGuard.single(params, "client_id");
		String clientSecret = OAuthQueryGuard.single(params, "client_secret");
		// 5. the grant; a scope parameter is ignored
		if (StringUtils.isEmpty(grantType)) return error(HttpStatus.BAD_REQUEST, ErrorEnum.INVALID_REQUEST, "grant_type is required");
		if (!OAuthErrors.GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
			return error(HttpStatus.BAD_REQUEST, ErrorEnum.UNSUPPORTED_GRANT_TYPE, "supported: " + OAuthErrors.GRANT_CLIENT_CREDENTIALS);
		}
		// 6. the credentials: one method per request (RFC 6749 2.3)
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (OAuthQueryGuard.usesTwoClientAuthenticationMethods(authorization, params)) {
			log.warn("SECURITY: TEA token request refused from {}: an Authorization header and a client_id or client_secret form field", LogSafe.text(request.getRemoteAddr()));
			return error(HttpStatus.BAD_REQUEST, ErrorEnum.INVALID_REQUEST, OAuthQueryGuard.ONE_METHOD_DESCRIPTION);
		}
		boolean header = StringUtils.isNotBlank(authorization);
		Optional<BasicCredentials> creds = header ? BasicCredentials.parse(authorization)
				: (StringUtils.isEmpty(clientId) || StringUtils.isEmpty(clientSecret) ? Optional.empty()
						: Optional.of(new BasicCredentials(clientId, clientSecret)));
		if (creds.isEmpty()) return invalidClient("client authentication required: HTTP Basic with the external key id and secret");
		// 7. the key
		String ip = request.getRemoteAddr();
		Optional<ExternalKeyMatch> match = externalApiKeyService.authenticate(creds.get().id(), creds.get().secret(), org.get(), ip);
		if (match.isEmpty()) return invalidClient("unknown API key or wrong secret");
		// 8. the token
		ExternalKeyMatch m = match.get();
		String token = apiTokenService.issue(m.key(), m.secret(), ApiTokenService.AUD_TEA);
		String keyId = ApiKeyService.keyIdOf(m.key(), m.data());
		log.info("Issued TEA access token for key {} (org {}) from {}", m.key().getUuid(), m.key().getOrg(), ip);
		try {
			apiKeyAccessService.recordApiKeyAccess(m.key().getUuid(), ip, m.key().getOrg(), keyId);
		} catch (RuntimeException e) {
			log.error("could not record the TEA token exchange of key {}", m.key().getUuid(), e);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("access_token", token);
		out.put("token_type", "Bearer");
		out.put("expires_in", expiresIn(m.secret().getExpiresDate() == null ? null : m.secret().getExpiresDate().toInstant(), Instant.now()));
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
				.contentType(MediaType.APPLICATION_JSON).body(out);
	}

	/**
	 * Task TEA-21: a form body or query string whose parameters Tomcat could not decode (raised at the first parameter
	 * parse, {@code request.getParameterMap()} in step 3 of {@link #token}, after the raw query-string check, task
	 * TEA-24) is the RFC 6749 {@code invalid_request}, with a fixed description and one DEBUG line
	 * ({@link TeaErrorAdvice#logUndecodable}; the name is read from the query string only, the consumed form body
	 * cannot be re-read), never the container's ERROR line with a stack trace. Depends on the embedded Tomcat the pom
	 * pins.
	 */
	@ExceptionHandler(InvalidParameterException.class)
	public ResponseEntity<Map<String, Object>> invalidParameter(InvalidParameterException e, HttpServletRequest request) {
		TeaErrorAdvice.logUndecodable(log, e, request);
		return error(HttpStatus.BAD_REQUEST, ErrorEnum.INVALID_REQUEST, UNDECODABLE_DESCRIPTION);
	}

	/** Seconds until the token's exp: the one-hour lifetime, or less when the secret expires first, as issue clips it. */
	static long expiresIn(Instant secretExpiry, Instant now) {
		long ttl = ApiTokenService.TTL_SECONDS;
		if (secretExpiry == null) return ttl;
		long left = secretExpiry.getEpochSecond() - now.getEpochSecond();
		return Math.max(0, Math.min(ttl, left));
	}

	/** The live organization of a TEA base segment: an archived or unknown one is empty, through the repository rule (TEA-22). */
	private Optional<UUID> organizationOf(String orgTeaId) {
		UUID teaOrg;
		try {
			teaOrg = UUID.fromString(orgTeaId);
		} catch (IllegalArgumentException | NullPointerException malformed) {
			return Optional.empty();
		}
		return organizationRepository.findByTeaUuid(teaOrg.toString()).map(Organization::getUuid);
	}

	private static ResponseEntity<Map<String, Object>> invalidClient(String description) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE)
				.header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("error", ErrorEnum.INVALID_CLIENT.getValue(), "error_description", description));
	}

	/** The RFC 6749 5.2 body; the code is the spec's token error code, written as its string. */
	private static ResponseEntity<Map<String, Object>> error(HttpStatus status, ErrorEnum code, String description) {
		return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("error", code.getValue(), "error_description", description));
	}

	/** The key id and secret of an HTTP Basic credential, each form-urlencoded part decoded (RFC 6749 2.3.1). */
	record BasicCredentials(String id, String secret) {
		static Optional<BasicCredentials> parse(String authorization) {
			if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return Optional.empty();
			try {
				String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
				int colon = decoded.indexOf(':');
				if (colon < 0) return Optional.empty();
				String id = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
				String secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
				if (id.isEmpty() || secret.isEmpty()) return Optional.empty();
				return Optional.of(new BasicCredentials(id, secret));
			} catch (IllegalArgumentException malformed) {
				return Optional.empty();
			}
		}
	}
}

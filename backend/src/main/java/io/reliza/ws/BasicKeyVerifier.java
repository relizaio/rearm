/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * The one HTTP Basic key verification of both security chains (TPF-27, TPF-29): {@link ProgrammaticAuthenticationFilter}
 * on the programmatic chain and {@link LegacyGraphqlBasicFilter} on the browser chain's {@code /graphql} delegate to it,
 * so the two lanes verify one credential with one rule set and answer every refusal with one 401.
 *
 * <p>{@link #verify} parses the header, refuses a malformed one without a service call, runs
 * {@link ApiKeyService#isMatchingApiKey} (behind the {@link RateLimitingFilter} on either chain), loads the key row and
 * bumps its last access ({@link ApiKeyAccessService#touchLastAccess});
 * the principal it returns is {@link AuthHeaderParse#fromVerifiedKey} of that row, so the secret and any org named in the
 * id text never travel past the calling filter. An empty result means refused: the verifier has written the one warn line
 * and the caller writes {@link #refuse}. Not a Spring bean: each filter builds its own with a fixed lane label.
 */
@Slf4j
final class BasicKeyVerifier {

	static final String DEPRECATION_NOTE = "API key secrets presented directly are deprecated; exchange the key for an access token at "
			+ ProgrammaticTokenController.PATH + " (grant_type=client_credentials) and send it as a bearer token";

	private final ApiKeyService apiKeyService;
	private final ApiKeyAccessService apiKeyAccessService;
	/** A fixed label naming the chain in the refusal log line; never request-derived. */
	private final String lane;

	BasicKeyVerifier(ApiKeyService apiKeyService, ApiKeyAccessService apiKeyAccessService, String lane) {
		this.apiKeyService = apiKeyService;
		this.apiKeyAccessService = apiKeyAccessService;
		this.lane = lane;
	}

	/**
	 * Verifies an {@code Authorization} value that is expected to carry an HTTP Basic key credential.
	 *
	 * @return the verified principal of the key row, or empty when the value is refused (a foreign scheme, a malformed
	 *         header, an unknown key, a wrong secret, a missing row or a lookup failure); a refusal is logged here
	 */
	Optional<AuthHeaderParse> verify(String authorization, String remoteAddr) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
			// any other scheme (Digest, Token, garbage, a bare "Basic" or "Bearer"): not a credential this verifier takes
			rejected(remoteAddr, "unsupported scheme", null);
			return Optional.empty();
		}
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, authorization);
		AuthHeaderParse parsed = AuthHeaderParse.parseAuthHeader(headers, remoteAddr);
		if (parsed.getType() == null || parsed.getObjUuid() == null || parsed.getApiKey() == null || parsed.getApiKey().isEmpty()) {
			// malformed header, unrecognised type prefix, no colon, empty id or empty secret: no service call
			rejected(remoteAddr, "malformed", parsed);
			return Optional.empty();
		}
		ApiKey row;
		try {
			UUID keyUuid = apiKeyService.isMatchingApiKey(parsed);
			if (keyUuid == null) {
				rejected(remoteAddr, "unknown key or wrong secret", parsed);
				return Optional.empty();
			}
			Optional<ApiKey> oak = apiKeyService.getApiKey(keyUuid);
			if (oak.isEmpty()) {
				rejected(remoteAddr, "key row not found", parsed);
				return Optional.empty();
			}
			row = oak.get();
		} catch (RuntimeException e) {
			// a failing lookup (a database outage, say): at the authentication boundary that must be the same answer
			// as every other refusal. An id of an ordered type without its __ord__ no longer gets here: isMatchingApiKey
			// refuses it before any lookup (ApiTypeEnum.requiresKeyOrder)
			// the class name only: the message is the data layer's, raised before the secret check
			log.warn("SECURITY: {} Basic credential rejected from {} (lookup failed) {}: {}", lane, remoteAddr,
					ProgrammaticAuthenticationFilter.canonicalIdOf(parsed), e.getClass().getName());
			return Optional.empty();
		}
		// a verified credential is a use of the key, on both lanes, whatever the resolver behind it records
		apiKeyAccessService.touchLastAccess(row.getUuid());
		return Optional.of(AuthHeaderParse.fromVerifiedKey(row, parsed.getMatchedSecretSlot(), remoteAddr));
	}

	private void rejected(String remoteAddr, String reason, AuthHeaderParse parsed) {
		log.warn("SECURITY: {} Basic credential rejected from {} ({}) {}", lane, remoteAddr, reason,
				ProgrammaticAuthenticationFilter.canonicalIdOf(parsed));
	}

	/**
	 * The one invalid-credential answer: the same headers as the no-credential answer and the download handlers'
	 * 401s, so neither the headers nor the body say which layer or which chain refused, which scheme was sent or why.
	 */
	static void refuse(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY);
	}

	/** The headers an admitted Basic carries on either chain: presenting the secret directly is deprecated, no sunset yet. */
	static void deprecationHeaders(HttpServletResponse response) {
		response.setHeader("Deprecation", "true");
		response.setHeader("Link", "<" + ProgrammaticTokenController.PATH + ">; rel=\"alternate\"; title=\"token endpoint\"");
		response.setHeader("X-ReARM-Deprecation", DEPRECATION_NOTE);
	}
}

/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.common.LogSafe;
import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.service.CliSessionService;
import io.reliza.service.CliSessionService.DeviceAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * CLI browser login, RFC 8628 shapes on the stateless programmatic chain.
 * {@code POST /api/programmatic/device/code} starts a login (no credentials: the user approves it
 * in the browser); the CLI then polls the token endpoint with the device-code grant.
 * {@code POST /api/programmatic/revoke} (RFC 7009) ends a session by its refresh token: logout.
 * The refresh token is read from the form body only ({@link OAuthQueryGuard}).
 */
@RestController
@Slf4j
public class DeviceAuthorizationController {

	public static final String DEVICE_CODE_PATH = "/api/programmatic/device/code";
	public static final String REVOKE_PATH = "/api/programmatic/revoke";

	@Autowired private CliSessionService cliSessionService;

	@PostMapping(value = DEVICE_CODE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> deviceCode(HttpServletRequest request,
			@RequestParam(value = "requested_from", required = false) String requestedFrom,
			@RequestParam(value = "requested_os", required = false) String requestedOs,
			@RequestParam(value = "requested_tz", required = false) String requestedTz,
			@RequestParam(value = "requested_client", required = false) String requestedClient) {
		// what the CLI says about itself is reported by the requester; the address is what this server saw
		DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo(requestedFrom, requestedOs, requestedTz, requestedClient, clientIp(request)));
		// the user code approves the login for the next ten minutes: it is a secret while it lives and stays out of the log
		log.info("CLI login started from {} ({})", request.getRemoteAddr(), LogSafe.text(requestedFrom));
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of(
				"device_code", d.deviceCode(),
				"user_code", d.userCode(),
				"verification_uri", d.verificationUri(),
				"verification_uri_complete", d.verificationUriComplete(),
				"expires_in", d.expiresIn(),
				"interval", d.interval()));
	}

	/**
	 * The client address as this server saw it: the first hop of X-Forwarded-For, else X-Real-IP,
	 * else the socket peer. Behind an ingress that does not forward the client address this is the
	 * ingress hop, which the approval page then shows as such; it is still what the server observed.
	 */
	static String clientIp(HttpServletRequest request) {
		String fwd = request.getHeader("X-Forwarded-For");
		if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].strip();
		String real = request.getHeader("X-Real-IP");
		if (real != null && !real.isBlank()) return real.strip();
		return request.getRemoteAddr();
	}

	@PostMapping(value = REVOKE_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> revoke(HttpServletRequest request, @RequestParam(value = "token", required = false) String token) {
		// TPF-3: the refresh token travels in the form body only; in the URL query string it is refused before any lookup
		List<String> inQuery = OAuthQueryGuard.namesInQuery(request.getQueryString(), OAuthQueryGuard.REVOKE_REQUEST_PARAMETERS);
		if (!inQuery.isEmpty()) {
			log.warn("SECURITY: revoke request refused from {}: {} in the URL query string", request.getRemoteAddr(), inQuery);
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store")
					.body(Map.of("error", OAuthErrors.INVALID_REQUEST, "error_description",
							OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: " + String.join(", ", inQuery)));
		}
		// RFC 7009: 200 whether or not the token was live, so a caller cannot probe for valid tokens
		boolean revoked = cliSessionService.revokeByRefreshToken(token, request.getRemoteAddr());
		if (revoked) log.info("CLI session logged out from {}", request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}
}

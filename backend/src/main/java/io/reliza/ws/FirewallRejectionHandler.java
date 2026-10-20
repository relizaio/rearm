/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.reliza.common.LogSafe;

/**
 * Answers every request Spring Security's StrictHttpFirewall refuses (a path parameter, an encoded slash,
 * percent or dot, a line break, "//", "/../", an unknown method, and the lazy header and parameter-name
 * rules) with a fixed 400, written inside FilterChainProxy (task TPF-45).
 *
 * <p>The stock handler calls {@code sendError(400)}, which makes the container dispatch the request to
 * /error, where the browser chain answers 401 with a Bearer challenge and stores the request in a new HTTP
 * session, uncounted by the rate limiter. This handler sets the status and writes the body instead, so no
 * error dispatch follows, no chain runs and no session is created. It reads nothing from the request.
 *
 * <p>The answer is the TEA chain's bytes for the same rejection: status 400, application/json, no-store
 * and {@code {"message":"Bad Request"}}, so one answer serves every path before and after the TEA merge.
 *
 * <p>It must stay the only RequestRejectedHandler bean: WebSecurity looks it up by type and silently falls
 * back to the stock handler when there are two (FirewallRejectionChainTest goes red on the body then).
 */
@Component
public class FirewallRejectionHandler implements RequestRejectedHandler {

	private static final Logger log = LoggerFactory.getLogger(FirewallRejectionHandler.class);

	/** The fixed answer, 25 bytes. Nothing of the request is echoed. */
	static final byte[] BODY = "{\"message\":\"Bad Request\"}".getBytes(StandardCharsets.US_ASCII);

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException rejected)
			throws IOException {
		log.debug("Request refused by the firewall: {}", LogSafe.text(rejected.getMessage()));
		if (response.isCommitted()) return;
		response.reset();
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentLength(BODY.length);
		response.getOutputStream().write(BODY);
		response.flushBuffer();
	}
}

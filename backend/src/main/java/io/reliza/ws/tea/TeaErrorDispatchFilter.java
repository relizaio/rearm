/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers the error dispatch of a TEA request (task TEA-6, design round 2, decision 16). On the TEA
 * chain a JSON body carries a string {@code error} property only as a TEA error-response or as the
 * token endpoint's RFC 6749 body; Spring Boot's /error body has one ("error":"Not Found"), which a
 * 1.0.0 client reads as a TEA error with an unknown value and stops at this authority. So every
 * status that reaches the error dispatch of a TEA URI (an unmounted path, the removed 0.4.0 base, a
 * firewall rejection, an unsupported method, an unacceptable Accept, an unhandled exception) is
 * answered here with {@code {"message":"<reason phrase>"}}, {@code no-store}, and nothing of the
 * request echoed: the readme's own example of a non-TEA body, which a client treats as a wrong base.
 * It runs on the error dispatch only and never continues the chain, so Boot's error controller never
 * renders a TEA URI. Not a Spring component: registered on the TEA chain only.
 */
public class TeaErrorDispatchFilter extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getDispatcherType() != DispatcherType.ERROR;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		int status = statusOf(request);
		HttpStatus known = HttpStatus.resolve(status);
		byte[] body = TeaJson.MAPPER.writeValueAsBytes(Map.of("message", null == known ? "Error" : known.getReasonPhrase()));
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
		response.flushBuffer();
	}

	private static int statusOf(HttpServletRequest request) {
		Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		return status instanceof Integer i ? i : HttpStatus.INTERNAL_SERVER_ERROR.value();
	}
}

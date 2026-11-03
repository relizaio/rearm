/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import io.reliza.service.TeaHostAssignmentService;
import io.reliza.ws.App;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Custom hosts serve TEA only (TPF-142, architecture D12). On a listed custom host, every request
 * that reaches the backend outside /.well-known/tea and /tea/** (read by the gate's own normalised
 * test, {@link App#isNormalizedTeaUri}, so a dot-segment spelling under /tea is refused too) is
 * answered here with 404 {@code {"message":"Not Found"}}, {@code no-store}, before any security
 * chain: GraphQL, REST, the programmatic token endpoint and /error never run on a customer's domain.
 * Inert on every other host (the baseuri host, an unlisted host, the empty host): no database read,
 * the custom set is the parsed property. The UI's files and /kauth never reach the backend; on a
 * custom host the TPF-143 ingress match refuses them. Not a Spring component: registered by
 * {@code App.teaHostGateFilter} at the highest precedence.
 */
public class TeaHostGateFilter extends OncePerRequestFilter {

	static final byte[] NOT_FOUND_BODY = TeaJson.MAPPER.writeValueAsBytes(Map.of("message", HttpStatus.NOT_FOUND.getReasonPhrase()));

	private final TeaHostAssignmentService teaHostAssignmentService;

	public TeaHostGateFilter(TeaHostAssignmentService teaHostAssignmentService) {
		this.teaHostAssignmentService = teaHostAssignmentService;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!teaHostAssignmentService.isCustomHost(TeaHosts.hostOf(request))) {
			chain.doFilter(request, response);
			return;
		}
		boolean teaPath = DispatcherType.ERROR == request.getDispatcherType() ? App.isNormalizedTeaErrorDispatch(request)
				: App.isNormalizedTeaUri(request);
		if (teaPath) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpStatus.NOT_FOUND.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentLength(NOT_FOUND_BODY.length);
		response.getOutputStream().write(NOT_FOUND_BODY);
		response.flushBuffer();
	}
}

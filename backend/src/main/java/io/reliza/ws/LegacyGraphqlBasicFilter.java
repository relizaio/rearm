/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs on the browser chain, on the legacy GraphQL endpoint {@link #PATH} only (TPF-29), after the
 * {@link RateLimitingFilter} and before authorization, and verifies an HTTP Basic key credential there exactly as
 * {@link ProgrammaticAuthenticationFilter} does on the programmatic chain, through the same {@link BasicKeyVerifier}:
 * an admitted Basic stores the key row's principal as {@link ProgrammaticAuthenticationFilter#PRINCIPAL_ATTRIBUTE} and
 * carries the deprecation headers; any other non-bearer value (a wrong secret, an unknown id, a malformed header, an
 * unrecognised type prefix, a foreign scheme) is the one invalid-credential 401, so the resolvers on this lane see only
 * verified keys.
 *
 * <p>A request with no {@code Authorization} header (anonymous) and a bearer-prefixed one (the UI's IdP JWT, which the
 * resource server in front of this filter has already authenticated or refused) are passed on untouched: the bearer test
 * is the one {@code DefaultBearerTokenResolver} claims a header by, so the resource server and this filter partition the
 * header values. The path is matched on the parsed, decoded request path with the matcher type the browser chain's
 * {@code permitAll} for the same constant uses, so the authorization rule and this filter cover one path set; every other
 * spelling is {@code authenticated()} on that chain and never reaches the handler without an IdP session.
 * Not a Spring component on purpose: it is registered on the browser chain only.
 */
public class LegacyGraphqlBasicFilter extends OncePerRequestFilter {

	/** The legacy GraphQL endpoint; the browser chain's {@code permitAll} reads this constant too. */
	public static final String PATH = "/graphql";
	static final String LANE = "legacy /graphql";

	private static final RequestMatcher GRAPHQL = PathPatternRequestMatcher.withDefaults().matcher(PATH);

	private final BasicKeyVerifier verifier;

	public LegacyGraphqlBasicFilter(ApiKeyService apiKeyService, ApiKeyAccessService apiKeyAccessService) {
		this.verifier = new BasicKeyVerifier(apiKeyService, apiKeyAccessService, LANE);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !GRAPHQL.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || StringUtils.startsWithIgnoreCase(authorization, "bearer")) {
			// anonymous: the resolvers answer per field; bearer-prefixed: the resource server's, already decided
			chain.doFilter(request, response);
			return;
		}
		Optional<AuthHeaderParse> principal = verifier.verify(authorization, request.getRemoteAddr());
		if (principal.isEmpty()) {
			BasicKeyVerifier.refuse(response);
			return;
		}
		BasicKeyVerifier.deprecationHeaders(response);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal.get());
		chain.doFilter(request, response);
	}
}

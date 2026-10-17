/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The branding permit in the other branch of the browser chain (WL-2 design 4.5, test 17b):
 * {@code App} builds a different {@code authorizeHttpRequests} when beta TEA is on, and the UI reads
 * the branding before login there too. The branch is picked by a property when the chain is built,
 * so this class needs its own context with that property; {@link BrandingWiringIntegrationTest}
 * covers the shipped ({@code false}) branch.
 */
@SpringBootTest(classes = {App.class}, properties = "relizaprops.enableBetaTea=true")
class BrandingWiringBetaTeaIntegrationTest {

	@Autowired
	@Qualifier("filterChain")
	private SecurityFilterChain filterChain;

	private static final Supplier<Authentication> ANONYMOUS = () -> new AnonymousAuthenticationToken("key",
			"anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	@Test
	void withBetaTeaOnAnAnonymousCallerMayStillReadTheBranding() {
		AuthorizationManager<HttpServletRequest> authz = filterChain.getFilters().stream()
				.filter(AuthorizationFilter.class::isInstance).map(AuthorizationFilter.class::cast)
				.findFirst().orElseThrow().getAuthorizationManager();

		// The marker that the beta-TEA branch was built: the other branch denies /tea/**.
		assertTrue(authz.authorize(ANONYMOUS, get("/tea/x")).isGranted());

		assertTrue(authz.authorize(ANONYMOUS, get("/api/branding/v1/settings")).isGranted());
		assertTrue(authz.authorize(ANONYMOUS, get("/api/branding/v1/asset/logo")).isGranted());
		// The control: this branch still refuses an anonymous caller elsewhere.
		assertFalse(authz.authorize(ANONYMOUS, get("/api/manual/v1/artifact/x/download")).isGranted());
	}

	private static MockHttpServletRequest get(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setServletPath(path);
		return request;
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import io.reliza.service.BrandingService;
import io.reliza.service.BrandingService.ResolvedBranding;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The branding endpoint as wired into the application (WL-2 design 4.5, tests 16-17): the shipped
 * configuration resolves to {@code default}, and the browser security chain lets an anonymous
 * caller read the branding, which the UI needs before anyone has logged in.
 */
@SpringBootTest(classes = {App.class})
class BrandingWiringIntegrationTest {

	@Autowired
	private BrandingService brandingService;

	@Autowired
	@Qualifier("filterChain")
	private SecurityFilterChain filterChain;

	private static final Supplier<Authentication> ANONYMOUS = () -> new AnonymousAuthenticationToken("key",
			"anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	@Test
	void theShippedConfigurationResolvesToDefault() {
		assertSame(ResolvedBranding.DEFAULT, brandingService.current());
	}

	@Test
	void anAnonymousCallerMayReadTheBrandingButNothingElse() {
		AuthorizationManager<HttpServletRequest> authz = filterChain.getFilters().stream()
				.filter(AuthorizationFilter.class::isInstance).map(AuthorizationFilter.class::cast)
				.findFirst().orElseThrow().getAuthorizationManager();

		// The marker that the shipped (beta TEA off) branch was built; BrandingWiringBetaTeaIntegrationTest
		// covers the other one.
		assertFalse(authz.authorize(ANONYMOUS, get("/tea/x")).isGranted());

		assertTrue(authz.authorize(ANONYMOUS, get("/api/branding/v1/settings")).isGranted());
		assertTrue(authz.authorize(ANONYMOUS, get("/api/branding/v1/asset/logo")).isGranted());
		// The control: the same check refuses an anonymous caller elsewhere, so the grants above
		// are the branding matcher's doing, not a chain that permits everything.
		assertFalse(authz.authorize(ANONYMOUS, get("/api/branding-other/settings")).isGranted());
		assertFalse(authz.authorize(ANONYMOUS, get("/api/manual/v1/artifact/x/download")).isGranted());
	}

	private static MockHttpServletRequest get(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setServletPath(path);
		return request;
	}
}

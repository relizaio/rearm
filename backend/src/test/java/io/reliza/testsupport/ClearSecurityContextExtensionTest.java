/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The global extension clears what a test left in the security context (task df51a6fe). No
 * annotation here on purpose: it applies through auto-detection, as it does to every other test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClearSecurityContextExtensionTest {

	private static SecurityContext leaked;

	@Test
	@Order(1)
	void aTestLeavesAMockedContextBehind() {
		leaked = mock(SecurityContext.class);
		when(leaked.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(leaked);
		// and does not clear it, as the six leaky unit tests did
	}

	@Test
	@Order(2)
	void theNextTestStartsWithAnEmptyHolder() {
		assertNotSame(leaked, SecurityContextHolder.getContext(), "the mocked context was cleared");
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}
}

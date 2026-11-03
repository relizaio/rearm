/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.service.TeaHostAssignmentService;
import io.reliza.ws.tea.TeaHostGateFilter;
import jakarta.servlet.DispatcherType;

/**
 * TPF-142 mutation round: the custom-host gate's own path test refuses a decoded backslash as it refuses a decoded
 * slash, and the gate is registered ahead of every security chain, for every URL, on the request and the error
 * dispatch (architecture D12, F-11).
 */
class AppTeaHostGateEdgesTest {

	@Test
	void aDecodedBackslashIsNotATeaPath() {
		assertTrue(App.isNormalizedTeaUri("/tea/v1.0.0/products", ""));
		for (String uri : List.of("/tea/x%5c..%5c..%5cgraphql", "/tea/v1.0.0/x%5Cy")) {
			assertFalse(App.isNormalizedTeaUri(uri, ""), uri);
		}
	}

	@Test
	void theGateIsRegisteredFirstForEveryUrlOnTheRequestAndTheErrorDispatch() {
		FilterRegistrationBean<TeaHostGateFilter> reg = new App().teaHostGateFilter(mock(TeaHostAssignmentService.class));
		assertEquals(Ordered.HIGHEST_PRECEDENCE, reg.getOrder());
		assertEquals(List.of("/*"), List.copyOf(reg.getUrlPatterns()));
		assertEquals(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR), ReflectionTestUtils.getField(reg, "dispatcherTypes"));
	}
}

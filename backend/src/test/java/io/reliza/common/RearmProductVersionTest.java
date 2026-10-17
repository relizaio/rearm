/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The version on ReARM's tool entry: the deployment's REARM_PRODUCT_VERSION (the chart's
 * appVersion), or the fixed fallback when the deployment does not pass one -- never absent.
 */
class RearmProductVersionTest {

	@Test
	void theDeploymentsVersionWins() {
		assertEquals("26.10.28", Utils.rearmProductVersion("26.10.28"));
		assertEquals("26.10.28", Utils.rearmProductVersion("  26.10.28\n"));
	}

	@Test
	void anUnsetOrBlankVersionFallsBackToTheFixedOne() {
		assertEquals("26.08.95", Utils.rearmProductVersion(null));
		assertEquals("26.08.95", Utils.rearmProductVersion(""));
		assertEquals("26.08.95", Utils.rearmProductVersion("   "));
		assertEquals(Utils.REARM_PRODUCT_VERSION_FALLBACK, Utils.rearmProductVersion(null));
	}
}

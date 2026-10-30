/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import io.reliza.model.SystemInfoData.EncProps;

/**
 * A real {@link ApiTokenService} for unit tests outside this package (task TEA-3): the test constructor is
 * package-private, and the TEA filter and token controller tests live in io.reliza.ws.tea.
 */
public final class ApiTokenServiceTestSupport {
	private ApiTokenServiceTestSupport() {}

	public static ApiTokenService create() {
		return new ApiTokenService(new EncProps("pw-tea3", "salt-tea3", null, null), "https://rearm.test", () -> "pepper-tea3");
	}
}

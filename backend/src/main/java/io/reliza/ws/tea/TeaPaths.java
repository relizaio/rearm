/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import io.reliza.service.TeaProfileService;

/** The TEA 1.0.0 base every TEA controller is mounted under (task TEA-6). */
public final class TeaPaths {

	/** The organization's TEA id, then the API version: clients append /v1.0.0 to the advertised base. */
	public static final String ORG_BASE = "/tea/{org}/v1.0.0";

	/** The template variable that carries the organization's TEA id. */
	static final String ORG_VARIABLE = "org";

	static final String API_VERSION = TeaProfileService.TEA_API_VERSION;

	private TeaPaths() {}
}

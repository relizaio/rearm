/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import io.reliza.service.TeaHostAssignmentService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The request host of a TEA request (TPF-142, architecture D11): {@code getServerName()}, which
 * under {@code server.forward-headers-strategy: native} the container rewrites from
 * X-Forwarded-Host only for an internal-proxy peer, normalised by
 * {@link TeaHostAssignmentService#normalizeHost}. No header is read here or anywhere else.
 */
public final class TeaHosts {

	private TeaHosts() {}

	/** The normalised server name, or the empty string (not a custom host, no mapping). */
	public static String hostOf(HttpServletRequest r) {
		return null == r ? "" : TeaHostAssignmentService.normalizeHost(r.getServerName()).orElse("");
	}
}

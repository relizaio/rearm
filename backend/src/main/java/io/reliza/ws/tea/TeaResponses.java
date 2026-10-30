/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;

/**
 * The headers of a served TEA JSON response (task TEA-6). Visibility is live, so a cache must
 * revalidate every time; content read with a key is private; the token changes the content.
 */
public final class TeaResponses {

	private TeaResponses() {}

	public static HttpHeaders headersFor(TeaRequestContext ctx) {
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.APPLICATION_JSON);
		boolean keyed = null != ctx && null != ctx.caller() && TeaAuthState.AUTHENTICATED == ctx.caller().state();
		h.setCacheControl(keyed ? "private, no-cache" : "no-cache");
		h.set(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
		return h;
	}

	/** The strong entity tag of a stored document: its sha256, quoted. */
	public static String etagOf(String sha256) {
		return "\"" + sha256 + "\"";
	}

	/**
	 * Whether an If-None-Match header names this tag, or is {@code *}, under the weak comparison: a
	 * {@code W/} prefix is ignored and the opaque tags compared.
	 */
	public static boolean ifNoneMatchMatches(String ifNoneMatch, String etag) {
		if (null == ifNoneMatch || null == etag) return false;
		String opaque = opaque(etag);
		return Arrays.stream(ifNoneMatch.split(",")).map(String::trim)
				.anyMatch(t -> "*".equals(t) || opaque.equals(opaque(t)));
	}

	private static String opaque(String tag) {
		String t = tag.trim();
		if (t.startsWith("W/")) t = t.substring(2);
		return t;
	}
}

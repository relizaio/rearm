/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The token and revoke endpoints take their OAuth request parameters from the form body only
 * (RFC 6749 sections 2.3.1 and 3.2, RFC 7009 section 2.1): a parameter in the URL query string
 * lands in every access log and proxy on the way, so a request that names one there is refused
 * before anything else is read (task TPF-3). Spring's {@code @RequestParam} merges the query
 * string with the body, so the check reads the raw query string instead.
 *
 * <p>Matching is on parameter names only: each {@code &}-separated part of the query string,
 * the text before the first {@code =}, percent-decoded as UTF-8 when it decodes and compared as
 * sent when it does not, case-sensitive. Values are never looked at.
 *
 * <p>The token endpoint also refuses a request that carries one of its parameters more than once
 * ({@link #namesRepeated}, RFC 6749 section 3.2), and reads its parameters through {@link #single}
 * by exact name, never through Spring's binder, which accepts names and shapes an exact-name read
 * does not ({@code client_id[]}, a comma join of repeated values); the rate limiter reads the token
 * request's {@code client_id} through the same {@link #single}, so the two always read the same text
 * (task TPF-26).
 */
public final class OAuthQueryGuard {
	private OAuthQueryGuard() {}

	/** Every parameter {@code POST /api/programmatic/token} reads. */
	public static final Set<String> TOKEN_REQUEST_PARAMETERS = Set.of("grant_type", "client_id", "client_secret", "device_code",
			"refresh_token", "assertion");

	/** The one parameter {@code POST /api/programmatic/revoke} binds: the refresh token. */
	public static final Set<String> REVOKE_REQUEST_PARAMETERS = Set.of("token");

	public static final String BODY_ONLY_DESCRIPTION = "request parameters are accepted in the form body only (RFC 6749 section 2.3.1 and 3.2); "
			+ "send the API key as HTTP Basic or as client_id and client_secret form fields";

	public static final String ONCE_ONLY_DESCRIPTION = "each request parameter is accepted at most once (RFC 6749 section 3.2)";

	/** A name URLDecoder decodes without throwing: every % starts a two-digit hex escape. */
	private static final Pattern DECODABLE = Pattern.compile("(?:[^%]|%[0-9A-Fa-f]{2})*");

	/**
	 * The names from {@code names} that the query string carries, in query order, each once.
	 * The returned strings are the constants of {@code names}, never the caller's text, so they
	 * are safe to log and to answer with.
	 */
	public static List<String> namesInQuery(String queryString, Set<String> names) {
		List<String> found = new ArrayList<>();
		if (queryString == null || queryString.isEmpty()) return found;
		for (String part : queryString.split("&")) {
			int eq = part.indexOf('=');
			String name = eq < 0 ? part : part.substring(0, eq);
			String decoded = DECODABLE.matcher(name).matches() ? URLDecoder.decode(name, StandardCharsets.UTF_8) : name;
			for (String known : names) {
				if (known.equals(decoded) && !found.contains(known)) found.add(known);
			}
		}
		return found;
	}

	/**
	 * The names from {@code names} whose array in {@code parameterMap} has more than one element,
	 * sorted by name. The returned strings are the constants of {@code names}, never the caller's
	 * values, so they are safe to log and to answer with.
	 */
	public static List<String> namesRepeated(Map<String, String[]> parameterMap, Set<String> names) {
		List<String> found = new ArrayList<>();
		if (parameterMap == null || parameterMap.isEmpty()) return found;
		for (String known : names) {
			String[] values = parameterMap.get(known);
			if (values != null && values.length > 1) found.add(known);
		}
		found.sort(null);
		return found;
	}

	/**
	 * The first value of the parameter named exactly {@code name}, or null when the map has no such
	 * entry or its array is empty: what {@code request.getParameter(name)} returns, read from the map.
	 */
	public static String single(Map<String, String[]> parameterMap, String name) {
		if (parameterMap == null) return null;
		String[] values = parameterMap.get(name);
		return values == null || values.length == 0 ? null : values[0];
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import io.reliza.model.TeaDownloadObject;
import io.reliza.service.tea.TeaArtifactSurfaceService.ArtifactRevision;
import io.reliza.service.tea.TeaExternalUrl;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * The rules of the TEA artifact downloads (task TEA-10): {@code mediaType} is the one selector of a
 * format, and a served download carries a strong ETag of its bytes, their Repr-Digest, the absolute
 * versioned Content-Location, and a Cache-Control that is immutable only for a PUBLIC versioned read.
 */
@Slf4j
public final class TeaArtifactResponses {

	private TeaArtifactResponses() {}

	static final String NO_FORMAT_MESSAGE = "mediaType is required and must name one of the formats of this artifact revision";
	static final String NOT_A_MEDIA_TYPE_MESSAGE = "mediaType is not a media type";
	/**
	 * A versioned download a PUBLIC publication serves (content and signature, the 200 and its 304): shared
	 * caches may keep it, unrevalidated, for one day and no longer (design round 7 decision 25, operator
	 * decision B). The day bounds how long a copy outlives a withdrawal (a hide, the release archived or
	 * rejected, the profile narrowed to PRIVATE, the organization archived), which the origin refuses at once
	 * but cannot recall; after it a conforming cache revalidates with the strong ETag.
	 */
	static final String VERSIONED_PUBLIC = "public, max-age=86400, immutable";
	static final String LATEST_PUBLIC = "no-cache";
	static final String NOT_SHARED = "private, no-cache";
	static final String REPR_DIGEST = "Repr-Digest";

	/**
	 * Round 4 decision 16: every header built from stored data is bounded before it is written, so the
	 * container's response-header buffer (8 KB by default) never decides an answer of these endpoints.
	 * Constants, not properties: the same answer on every deployment. The Location bound is the TEA-19 url
	 * rule's, one number (design round 5 decision 17).
	 */
	public static final int MAX_LOCATION_OCTETS = TeaExternalUrl.MAX_OCTETS;
	public static final int MAX_FILE_NAME_OCTETS = 255;
	public static final int MAX_MEDIA_TYPE_CHARS = 1024;
	public static final String LOCATION_TOO_LONG_MESSAGE = "the published location is longer than this server sends as a redirect Location (at most "
			+ MAX_LOCATION_OCTETS + " octets as a URI)";

	/**
	 * The stored media type of the format {@code requestedMediaType} selects: absent, blank, a wildcard
	 * or no match is 406 NO_ACCEPTABLE_FORMAT; a value that is not a media type is 400. Type, subtype
	 * and parameter names compare case-insensitively, parameter values exactly (a charset as a charset).
	 */
	public static String selectFormat(ArtifactRevision r, String requestedMediaType) {
		if (null == requestedMediaType || requestedMediaType.isBlank()) throw TeaRefusal.noAcceptableFormat(NO_FORMAT_MESSAGE);
		MediaType requested = mediaTypeOf(requestedMediaType).orElse(null);
		if (null == requested) throw TeaRefusal.invalidRequest(NOT_A_MEDIA_TYPE_MESSAGE);
		if (requested.isWildcardType() || requested.isWildcardSubtype()) throw TeaRefusal.noAcceptableFormat(NO_FORMAT_MESSAGE);
		JsonNode formats = r.artifactObject().get("formats");
		if (null != formats && formats.isArray()) {
			for (JsonNode f : formats) {
				JsonNode mt = f.get("mediaType");
				String stored = null == mt ? null : mt.asString();
				if (null == stored) continue;
				MediaType candidate = mediaTypeOf(stored).orElse(null);
				if (null == candidate) {
					log.error("TEA artifact {} revision {}: the stored format media type is not a media type", r.teaUuid(), r.revision());
					continue;
				}
				if (sameFormat(requested, candidate)) return stored;
			}
		}
		throw TeaRefusal.noAcceptableFormat(NO_FORMAT_MESSAGE);
	}

	/**
	 * Type and subtype case-insensitively, parameter names case-insensitively, parameter values exactly
	 * except a charset, compared as a charset name (case-insensitively). Spring's MimeType.equals
	 * compares an upper-case CHARSET value exactly, so it is not used.
	 */
	static boolean sameFormat(MediaType a, MediaType b) {
		if (!a.getType().equalsIgnoreCase(b.getType()) || !a.getSubtype().equalsIgnoreCase(b.getSubtype())) return false;
		Map<String, String> pa = lowerKeys(a.getParameters());
		Map<String, String> pb = lowerKeys(b.getParameters());
		if (!pa.keySet().equals(pb.keySet())) return false;
		for (Map.Entry<String, String> e : pa.entrySet()) {
			String other = pb.get(e.getKey());
			boolean equal = "charset".equals(e.getKey()) ? e.getValue().equalsIgnoreCase(other) : e.getValue().equals(other);
			if (!equal) return false;
		}
		return true;
	}

	private static Map<String, String> lowerKeys(Map<String, String> parameters) {
		Map<String, String> out = new HashMap<>();
		parameters.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
		return out;
	}

	/**
	 * The parsed media type, or empty when the value is null, longer than {@link #MAX_MEDIA_TYPE_CHARS}
	 * (round 4 decision 16c), does not parse, has an empty type or subtype, or renders
	 * ({@link MediaType#toString()}) with a UTF-16 unit outside U+0020 to U+007E: a quoted parameter value
	 * keeps controls and non-ASCII characters through the parser, and such a rendering is no header value
	 * (design round 6 decision 24). The rendering is what a served download's Content-Type carries, never
	 * the stored string. Logs nothing (round 3 decision 15): for a requested value the 400 is the record, a
	 * refusal the client earned; for a stored value the caller writes the one ERROR naming the revision.
	 */
	public static Optional<MediaType> mediaTypeOf(String value) {
		if (null == value || value.length() > MAX_MEDIA_TYPE_CHARS) return Optional.empty();
		MediaType m;
		try {
			m = MediaType.parseMediaType(value);
		} catch (InvalidMediaTypeException notAMediaType) {
			return Optional.empty();
		}
		if (m.getType().isEmpty() || m.getSubtype().isEmpty()) return Optional.empty();
		boolean printable = m.toString().chars().allMatch(c -> c >= 0x20 && c <= 0x7e);
		return printable ? Optional.of(m) : Optional.empty();
	}

	/**
	 * The headers a 304 repeats (and the 200 carries): ETag, Repr-Digest, Content-Location,
	 * Cache-Control and Vary; never Content-Encoding (no content coding is applied).
	 */
	public static HttpHeaders validatorHeaders(TeaRequestContext ctx, ArtifactRevision r, String mediaType, String sha256,
			TeaDownloadObject kind, boolean latest) {
		HttpHeaders h = new HttpHeaders();
		h.setETag(TeaResponses.etagOf(sha256));
		h.set(REPR_DIGEST, reprDigest(sha256));
		h.set(HttpHeaders.CONTENT_LOCATION, contentLocation(ctx.org().apiBase(), r, mediaType, kind));
		h.setCacheControl(cacheControl(r.publiclyAccessible(), latest));
		h.set(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
		return h;
	}

	/** The headers of a served download: the validator headers plus Content-Type, Content-Length and Content-Disposition. */
	public static HttpHeaders downloadHeaders(TeaRequestContext ctx, ArtifactRevision r, String mediaType, String sha256,
			TeaDownloadObject kind, boolean latest, String contentType, String fileName, long length) {
		HttpHeaders h = validatorHeaders(ctx, r, mediaType, sha256, kind, latest);
		h.set(HttpHeaders.CONTENT_TYPE, contentType);
		h.setContentLength(length);
		h.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName));
		return h;
	}

	/**
	 * The headers of a 302 to bytes that live outside ReARM (decision 11): Location the frozen url in
	 * URI form ({@link #locationOf}, round 3 decision 14), Cache-Control by exposure and Vary; no
	 * validator and no representation header. The endpoint does not change the answer: a redirect
	 * carries no validator, so neither endpoint is immutable. A Location longer than
	 * {@link #MAX_LOCATION_OCTETS} as a URI is not sent: 404 NOT_IMPLEMENTED, thrown here, before the
	 * caller sets any status (round 4 decision 16a); nothing above DEBUG.
	 */
	public static HttpHeaders redirectHeaders(TeaRequestContext ctx, ArtifactRevision r, boolean latest, String location) {
		String mapped = locationOf(location);
		if (null != mapped && mapped.length() > MAX_LOCATION_OCTETS) {
			// the mapped form is printable US-ASCII, so its characters are its octets
			log.debug("TEA artifact {} revision {}: the published location is {} octets as a URI, over {}; 404 NOT_IMPLEMENTED",
					r.teaUuid(), r.revision(), mapped.length(), MAX_LOCATION_OCTETS);
			throw TeaRefusal.notImplemented(LOCATION_TOO_LONG_MESSAGE);
		}
		HttpHeaders h = new HttpHeaders();
		h.set(HttpHeaders.LOCATION, mapped);
		h.setCacheControl(r.publiclyAccessible() ? LATEST_PUBLIC : NOT_SHARED);
		h.set(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
		return h;
	}

	private static final HexFormat UPPER_HEX = HexFormat.of().withUpperCase();

	/**
	 * The frozen url mapped to a URI, so it is always a header value (round 3 decision 14): a code
	 * point in U+0020 to U+007E is copied as is ({@code %} included, so an escape the publisher wrote
	 * is never encoded twice); every other code point is encoded as UTF-8 and each octet written as
	 * {@code %} and two uppercase hexadecimal digits (RFC 3986 section 2.1, RFC 3987 section 3.1, applied
	 * to the controls too). The identity for every url that already is a URI; one percent-decoding
	 * gives back the frozen string. No refusal and no log line.
	 */
	public static String locationOf(String frozenUrl) {
		if (null == frozenUrl) return null;
		StringBuilder out = new StringBuilder(frozenUrl.length());
		frozenUrl.codePoints().forEach(cp -> {
			if (cp >= 0x20 && cp <= 0x7e) {
				out.append((char) cp);
			} else {
				for (byte octet : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
					out.append('%').append(UPPER_HEX.toHexDigits(octet));
				}
			}
		});
		return out.toString();
	}

	/** {@code <apiBase>/v1.0.0/artifact/<uuid>/<revision>/download?mediaType=<encoded>}, or {@code .../signature/download?...}. */
	static String contentLocation(String apiBase, ArtifactRevision r, String mediaType, TeaDownloadObject kind) {
		return apiBase + "/v" + TeaPaths.API_VERSION + "/artifact/" + r.teaUuid() + "/" + r.revision()
				+ (TeaDownloadObject.SIGNATURE == kind ? "/signature/download" : "/download")
				+ "?mediaType=" + URLEncoder.encode(mediaType, StandardCharsets.UTF_8);
	}

	/** RFC 9530 dictionary form: {@code sha-256=:<base64 of the 32 digest bytes>:}. */
	static String reprDigest(String sha256) {
		return "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)) + ":";
	}

	static String cacheControl(boolean publiclyAccessible, boolean latest) {
		if (!publiclyAccessible) return NOT_SHARED;
		return latest ? LATEST_PUBLIC : VERSIONED_PUBLIC;
	}

	/**
	 * An RFC 6266 attachment: a plain quoted filename, or {@code filename*} UTF-8 for anything else; the
	 * name is first cut to {@link #MAX_FILE_NAME_OCTETS} of UTF-8 on a code point boundary (round 4
	 * decision 16b; the parameter is a hint, so a cut and no refusal).
	 */
	static String contentDisposition(String name) {
		String fileName = cutToOctets(name, MAX_FILE_NAME_OCTETS);
		boolean plain = fileName.chars().allMatch(c -> c >= 0x20 && c < 0x7f && c != '"' && c != '\\');
		ContentDisposition.Builder b = ContentDisposition.attachment();
		return (plain ? b.filename(fileName) : b.filename(fileName, StandardCharsets.UTF_8)).build().toString();
	}

	/** The longest prefix of whole code points whose UTF-8 encoding is at most {@code maxOctets}. */
	static String cutToOctets(String value, int maxOctets) {
		int octets = 0;
		int end = 0;
		while (end < value.length()) {
			int cp = value.codePointAt(end);
			int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
			if (octets + width > maxOctets) break;
			octets += width;
			end += Character.charCount(cp);
		}
		return value.substring(0, end);
	}
}

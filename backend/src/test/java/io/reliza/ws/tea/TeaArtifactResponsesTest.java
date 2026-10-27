/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.tea.TeaArtifactSurfaceService.ArtifactRevision;
import io.reliza.service.tea.TeaExternalUrl;
import io.reliza.service.tea.TeaLogCapture;
import io.reliza.service.tea.TeaSurfaceService.TeaOrg;
import io.reliza.model.TeaDownloadObject;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task TEA-10, design 4.2 cases 6 and 7, round 2 case 22 and round 3 case 25 (6 and 22 amended): format
 * selection by mediaType only and silent on a client's malformed value, the download and redirect
 * headers, and the Location in URI form.
 */
class TeaArtifactResponsesTest {

	private static final String CDX = "application/vnd.cyclonedx+json";
	private static final UUID ORG_TEA = UUID.randomUUID();
	private static final TeaRequestContext CTX = new TeaRequestContext(new TeaOrg(UUID.randomUUID(), ORG_TEA,
			"https://h/tea/" + ORG_TEA), TeaCaller.anonymous(), null);

	private static ArtifactRevision revision(UUID uuid, int revision, String mediaType, boolean publiclyAccessible) {
		ObjectNode o = TeaJson.MAPPER.createObjectNode();
		o.put("uuid", uuid.toString());
		o.put("version", revision);
		o.putArray("formats").addObject().put("mediaType", mediaType);
		return new ArtifactRevision(uuid, revision, null, 1, null, o, publiclyAccessible);
	}

	private static void assertRefused(int status, TeaUnknownErrorType error, ArtifactRevision r, String requested) {
		TeaRefusal e = assertThrows(TeaRefusal.class, () -> TeaArtifactResponses.selectFormat(r, requested), requested);
		assertEquals(status, e.response().getStatusCode().value(), requested);
		assertEquals(error, e.response().getBody().getError(), requested);
		assertEquals("no-store", e.response().getHeaders().getCacheControl(), requested);
		if (TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT == error) {
			assertEquals(TeaArtifactResponses.NO_FORMAT_MESSAGE, e.response().getBody().getMessage());
		} else {
			assertTrue(e.response().getBody().getMessage().contains("mediaType"), requested);
		}
	}

	// 6
	@Test
	void mediaTypeIsTheOnlySelector() {
		ArtifactRevision r = revision(UUID.randomUUID(), 1, CDX, true);
		for (String requested : new String[] { null, "", "  ", "*/*", "application/*", "application/json",
				CDX + "; version=1.6", "text/plain" }) {
			assertRefused(406, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, r, requested);
		}
		// round 3 (T-7), round 4 (T-9, 16c): a value the client sent that is not a media type, or is longer
		// than 1024 characters, is a 400 that writes nothing above DEBUG and no throwable at any level
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			for (String requested : new String[] { "foo", "/json", "text/plain; charset=nope", "a/b, c/d", TOO_LONG }) {
				assertRefused(400, TeaUnknownErrorType.INVALID_REQUEST, r, requested);
			}
			assertEquals(List.of(), logs.aboveDebug());
			assertEquals(0, logs.withThrowable());
		}
		assertEquals(CDX, TeaArtifactResponses.selectFormat(r, "Application/VND.CycloneDX+JSON"), "the stored spelling");
		assertEquals(CDX, TeaArtifactResponses.selectFormat(r, CDX));

		ArtifactRevision text = revision(UUID.randomUUID(), 1, "text/plain; charset=utf-8", true);
		assertEquals("text/plain; charset=utf-8", TeaArtifactResponses.selectFormat(text, "text/plain; CHARSET=UTF-8"));
		assertRefused(406, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, text, "text/plain; charset=latin1");
		assertRefused(406, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, text, "text/plain");
		assertRefused(406, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, text, "text/plain; charset=utf-8; x=1");
		ArtifactRevision param = revision(UUID.randomUUID(), 1, CDX + "; version=1.6", true);
		assertEquals(CDX + "; version=1.6", TeaArtifactResponses.selectFormat(param, "application/vnd.cyclonedx+json; VERSION=1.6"));
		assertRefused(406, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, param, CDX + "; version=1.6a");

		// round 3 (T-7): a stored format media type that does not parse stays one ERROR naming the revision, no stack trace
		UUID broken = UUID.randomUUID();
		ObjectNode o = TeaJson.MAPPER.createObjectNode();
		o.put("uuid", broken.toString());
		o.put("version", 4);
		o.putArray("formats").add(TeaJson.MAPPER.createObjectNode().put("mediaType", "not a type"))
				.add(TeaJson.MAPPER.createObjectNode().put("mediaType", CDX));
		ArtifactRevision stored = new ArtifactRevision(broken, 4, null, 1, null, o, true);
		assertStoredFormatSkipped(stored, broken);

		// round 4 (16c): a stored media type longer than 1024 characters is not one, though it would parse
		assertTrue(TOO_LONG.length() == 1025 && EXACT.length() == 1024);
		UUID longTea = UUID.randomUUID();
		ObjectNode lo = TeaJson.MAPPER.createObjectNode();
		lo.put("uuid", longTea.toString());
		lo.put("version", 4);
		lo.putArray("formats").add(TeaJson.MAPPER.createObjectNode().put("mediaType", TOO_LONG))
				.add(TeaJson.MAPPER.createObjectNode().put("mediaType", CDX));
		assertStoredFormatSkipped(new ArtifactRevision(longTea, 4, null, 1, null, lo, true), longTea);
		// the boundary: exactly 1024 characters is a media type, requested and stored
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			assertEquals(EXACT, TeaArtifactResponses.selectFormat(revision(UUID.randomUUID(), 1, EXACT, true), EXACT),
					"the stored spelling");
			assertEquals(List.of(), logs.aboveDebug());
		}
	}

	// round 6 row 41 (decision 24): the one parse, whose rendering is a header value or nothing
	@Test
	void aMediaTypeIsItsParsedRenderingOrNothing() {
		Map<String, String> rendered = new LinkedHashMap<>();
		rendered.put("text/plain;\r\ncharset", "text/plain");
		rendered.put(" text/plain ", "text/plain");
		rendered.put("text/plain;\tcharset=utf-8", "text/plain;charset=utf-8");
		for (Map.Entry<String, String> c : rendered.entrySet()) {
			assertEquals(c.getValue(), TeaArtifactResponses.mediaTypeOf(c.getKey()).map(MediaType::toString).orElse(null), c.getValue());
		}
		for (String none : new String[] { "text/plain;\r\ncharset=nope", "text/pl\r\nain", "text/plain;a=\"b\r\nc\"",
				"text/plain;a=\"\u00e9\"", TOO_LONG, "/", null }) {
			assertEquals(Optional.empty(), TeaArtifactResponses.mediaTypeOf(none), String.valueOf(none).length() + " characters");
		}
		// a stored spelling that parses selects and is answered as stored, silently
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			ArtifactRevision crlf = revision(UUID.randomUUID(), 1, "text/plain;\r\ncharset", true);
			assertEquals("text/plain;\r\ncharset", TeaArtifactResponses.selectFormat(crlf, "text/plain"));
			assertRefused(400, TeaUnknownErrorType.INVALID_REQUEST, crlf, "text/plain;a=\"b\r\nc\"");
			assertEquals(List.of(), logs.aboveDebug());
			assertEquals(0, logs.withThrowable());
		}
		// a stored quoted value carrying CR LF is not a media type: skipped with the one ERROR
		UUID quoted = UUID.randomUUID();
		ObjectNode o = TeaJson.MAPPER.createObjectNode();
		o.put("uuid", quoted.toString());
		o.put("version", 4);
		o.putArray("formats").add(TeaJson.MAPPER.createObjectNode().put("mediaType", "text/plain;a=\"b\r\nc\""))
				.add(TeaJson.MAPPER.createObjectNode().put("mediaType", CDX));
		assertStoredFormatSkipped(new ArtifactRevision(quoted, 4, null, 1, null, o, true), quoted);
	}

	// round 5 row 32: the Location bound is the url rule's, one number
	@Test
	void theLocationBoundIsTheUrlRulesBound() {
		assertEquals(TeaExternalUrl.MAX_OCTETS, TeaArtifactResponses.MAX_LOCATION_OCTETS);
	}

	private static final String TOO_LONG = "a".repeat(1020) + "/json";
	private static final String EXACT = "a".repeat(1019) + "/json";

	/** Selecting CDX answers it and text/plain is 406, each with exactly one ERROR naming revision 4 and no throwable. */
	private static void assertStoredFormatSkipped(ArtifactRevision stored, UUID tea) {
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			assertEquals(CDX, TeaArtifactResponses.selectFormat(stored, CDX));
			assertStoredError(logs, tea);
		}
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			assertRefused(406, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, stored, "text/plain");
			assertStoredError(logs, tea);
		}
	}

	private static void assertStoredError(TeaLogCapture logs, UUID tea) {
		assertEquals(1, logs.aboveDebug().size(), logs.aboveDebug().toString());
		String line = logs.aboveDebug().get(0);
		assertTrue(line.startsWith("ERROR: ") && line.contains(tea.toString()) && line.contains("revision 4"), line);
		assertEquals(0, logs.withThrowable(), "no stack trace");
	}

	// 7
	@Test
	void theDownloadHeaders() {
		UUID u = UUID.randomUUID();
		String sha = "ab".repeat(32);
		String versioned = "https://h/tea/" + ORG_TEA + "/v1.0.0/artifact/" + u + "/3/download?mediaType=application%2Fvnd.cyclonedx%2Bjson";
		String signature = "https://h/tea/" + ORG_TEA + "/v1.0.0/artifact/" + u + "/3/signature/download?mediaType=application%2Fvnd.cyclonedx%2Bjson";

		HttpHeaders h = TeaArtifactResponses.downloadHeaders(CTX, revision(u, 3, CDX, true), CDX, sha, TeaDownloadObject.CONTENT, false,
				CDX, "sbom.cdx.json", 42);
		assertEquals(versioned, h.getFirst(HttpHeaders.CONTENT_LOCATION));
		assertEquals("\"" + sha + "\"", h.getETag());
		assertFalse(h.getETag().startsWith("W/"));
		assertEquals("sha-256=:q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=:", h.getFirst("Repr-Digest"));
		assertEquals("public, max-age=86400, immutable", h.getCacheControl(), "one day (round 7 decision 25)");
		assertEquals("Authorization", h.getFirst(HttpHeaders.VARY));
		assertEquals(CDX, h.getFirst(HttpHeaders.CONTENT_TYPE));
		assertEquals(42L, h.getContentLength());
		assertEquals("attachment; filename=\"sbom.cdx.json\"", h.getFirst(HttpHeaders.CONTENT_DISPOSITION));
		assertNull(h.getFirst(HttpHeaders.CONTENT_ENCODING));

		assertEquals(signature, TeaArtifactResponses.downloadHeaders(CTX, revision(u, 3, CDX, true), CDX, sha, TeaDownloadObject.SIGNATURE,
				false, "application/octet-stream", "x.sig", 1).getFirst(HttpHeaders.CONTENT_LOCATION));
		assertEquals("private, no-cache", TeaArtifactResponses.downloadHeaders(CTX, revision(u, 3, CDX, false), CDX, sha,
				TeaDownloadObject.CONTENT, false, CDX, "a", 1).getCacheControl());
		assertEquals("no-cache", TeaArtifactResponses.downloadHeaders(CTX, revision(u, 3, CDX, true), CDX, sha,
				TeaDownloadObject.CONTENT, true, CDX, "a", 1).getCacheControl());
		assertEquals("private, no-cache", TeaArtifactResponses.downloadHeaders(CTX, revision(u, 3, CDX, false), CDX, sha,
				TeaDownloadObject.CONTENT, true, CDX, "a", 1).getCacheControl());

		for (String name : new String[] { "lic\u00e9nse.txt", "a\"b.txt" }) {
			String cd = TeaArtifactResponses.downloadHeaders(CTX, revision(u, 3, CDX, true), CDX, sha, TeaDownloadObject.CONTENT, false,
					CDX, name, 1).getFirst(HttpHeaders.CONTENT_DISPOSITION);
			assertTrue(cd.startsWith("attachment; ") && cd.contains("filename*=UTF-8''"), cd);
		}
		assertEquals("text%2Fplain%3B+charset%3Dutf-8", TeaArtifactResponses.contentLocation("b", revision(u, 3, CDX, true),
				"text/plain; charset=utf-8", TeaDownloadObject.CONTENT).replaceAll(".*mediaType=", ""));

		// the 304 set is the 200's minus the representation headers
		HttpHeaders validators = TeaArtifactResponses.validatorHeaders(CTX, revision(u, 3, CDX, true), CDX, sha, TeaDownloadObject.CONTENT, false);
		Set<String> full = new HashSet<>(h.headerNames());
		full.removeAll(Set.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_DISPOSITION));
		assertEquals(full, validators.headerNames());
		for (String name : validators.headerNames()) assertEquals(h.get(name), validators.get(name), name);
	}

	// 22 (round 2, amended by round 3): a redirect carries Location in URI form, Cache-Control by exposure and Vary, nothing else
	@Test
	void theRedirectHeaders() {
		UUID u = UUID.randomUUID();
		String url = "https://files.example/a+b/notes-1.md?sig=x%2By&v=1";
		for (boolean latest : new boolean[] { true, false }) {
			HttpHeaders pub = TeaArtifactResponses.redirectHeaders(CTX, revision(u, 3, CDX, true), latest, url);
			assertEquals(Set.of(HttpHeaders.LOCATION, HttpHeaders.CACHE_CONTROL, HttpHeaders.VARY), pub.headerNames(), "latest " + latest);
			assertEquals(url, pub.getFirst(HttpHeaders.LOCATION), "a url that is a URI is unchanged");
			assertEquals("https://files.example/na%C3%AFve-%E2%9C%93.md", TeaArtifactResponses.redirectHeaders(CTX,
					revision(u, 3, CDX, true), latest, "https://files.example/na\u00efve-\u2713.md").getFirst(HttpHeaders.LOCATION),
					"an IRI in URI form");
			assertEquals("no-cache", pub.getCacheControl(), "latest " + latest + ": never immutable");
			assertEquals("Authorization", pub.getFirst(HttpHeaders.VARY));
			for (String absent : new String[] { HttpHeaders.ETAG, "Repr-Digest", HttpHeaders.CONTENT_LOCATION,
					HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_ENCODING }) {
				assertNull(pub.getFirst(absent), absent);
			}
			HttpHeaders priv = TeaArtifactResponses.redirectHeaders(CTX, revision(u, 3, CDX, false), latest, url);
			assertEquals("private, no-cache", priv.getCacheControl(), "latest " + latest);
		}
	}

	// 25 (round 3): Location is the frozen url in URI form: printable US-ASCII copied, the rest UTF-8 percent-encoded
	@Test
	void theLocationIsTheFrozenUrlInUriForm() {
		Map<String, String> cases = new LinkedHashMap<>();
		for (String identity : new String[] { "https://files.example/a+b/notes-1.md?sig=x%2By&v=1", "/relative/x.txt",
				"javascript:alert(1)", "https://example.com/a b.txt", "100%", "" }) {
			cases.put(identity, identity);
		}
		cases.put("https://files.example/na\u00efve-\u2713.md", "https://files.example/na%C3%AFve-%E2%9C%93.md");
		cases.put("https://e.example/x.md\r\nSet-Cookie: tea10=1", "https://e.example/x.md%0D%0ASet-Cookie: tea10=1");
		cases.put("caf\u00e9.md", "caf%C3%A9.md");
		cases.put("a\tb", "a%09b");
		cases.put("a\u007fb", "a%7Fb");
		cases.put("\u0000", "%00");
		cases.put("\uD83D\uDE00", "%F0%9F%98%80");
		// round 4 (T-10): every edge of the encoder's five shapes, the C1 controls included
		cases.put("\u001f", "%1F");
		cases.put("\u0080", "%C2%80");
		cases.put("\u0085", "%C2%85");
		cases.put("\u009f", "%C2%9F");
		cases.put("\u00a0", "%C2%A0");
		cases.put("\u00ff", "%C3%BF");
		cases.put("\u0100", "%C4%80");
		cases.put("\u07ff", "%DF%BF");
		cases.put("\u0800", "%E0%A0%80");
		cases.put("\uffff", "%EF%BF%BF");
		cases.put("\uD800\uDC00", "%F0%90%80%80");
		cases.put("\uDBFF\uDFFF", "%F4%8F%BF%BF");
		cases.put("a\u0080b\u0085c\u009f", "a%C2%80b%C2%85c%C2%9F");
		for (String identity : new String[] { " ", "~", " a " }) {
			cases.put(identity, identity); // printable US-ASCII at both ends, and no strip
		}
		for (Map.Entry<String, String> c : cases.entrySet()) {
			String location = TeaArtifactResponses.locationOf(c.getKey());
			assertEquals(c.getValue(), location, c.getValue());
			assertTrue(location.chars().allMatch(ch -> ch >= 0x20 && ch <= 0x7e), "printable US-ASCII only: " + location);
			if (!c.getKey().contains("%")) {
				assertEquals(c.getKey(), URLDecoder.decode(location.replace("+", "%2B"), StandardCharsets.UTF_8),
						"one percent-decoding gives the frozen string back");
			}
		}
		assertNull(TeaArtifactResponses.locationOf(null));
		// hexadecimal digits are uppercase: no escape the mapping wrote has a lowercase digit, over every input
		for (String in : cases.keySet()) {
			String out = TeaArtifactResponses.locationOf(in);
			assertTrue(java.util.regex.Pattern.compile("%[0-9A-F]{2}").matcher(out).replaceAll("").equals(
					java.util.regex.Pattern.compile("%[0-9a-fA-F]{2}").matcher(out).replaceAll("")), "uppercase escapes: " + out);
		}
	}

	// 26 (round 4, T-8): a Location over 4096 octets as a URI is never built: 404 NOT_IMPLEMENTED, nothing above DEBUG
	@Test
	void theLocationIsBoundedBeforeItIsAHeader() {
		assertEquals(4096, TeaArtifactResponses.MAX_LOCATION_OCTETS);
		String prefix = "https://files.example/";
		String fits = prefix + "a".repeat(4074);
		String over = prefix + "a".repeat(4075);
		String iriFits = prefix + "\u00e9".repeat(679);
		String iriOver = prefix + "\u00e9".repeat(680);
		assertTrue(fits.length() == 4096 && over.length() == 4097 && iriFits.length() == 701);
		UUID u = UUID.randomUUID();
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			for (boolean latest : new boolean[] { false, true }) {
				for (boolean publiclyAccessible : new boolean[] { true, false }) {
					String what = (latest ? "latest" : "versioned") + (publiclyAccessible ? " public" : " private");
					ArtifactRevision r = revision(u, 3, CDX, publiclyAccessible);
					HttpHeaders h = TeaArtifactResponses.redirectHeaders(CTX, r, latest, fits);
					assertEquals(fits, h.getFirst(HttpHeaders.LOCATION), what + ": 4096 octets go out");
					assertEquals(Set.of(HttpHeaders.LOCATION, HttpHeaders.CACHE_CONTROL, HttpHeaders.VARY), h.headerNames(), what);
					assertEquals(publiclyAccessible ? "no-cache" : "private, no-cache", h.getCacheControl(), what);
					String mapped = TeaArtifactResponses.redirectHeaders(CTX, r, latest, iriFits).getFirst(HttpHeaders.LOCATION);
					assertEquals(prefix + "%C3%A9".repeat(679), mapped, what + ": the IRI that is 4096 octets as a URI");
					assertEquals(4096, mapped.length());
					assertLocationRefused(r, latest, over, what + " 4097");
					assertLocationRefused(r, latest, iriOver, what + " IRI 4102 as a URI");
				}
			}
			assertEquals(List.of(), logs.aboveDebug());
			assertEquals(0, logs.withThrowable());
			assertFalse(logs.lines(Level.DEBUG).isEmpty(), "the capture is open at DEBUG");
			for (String line : logs.lines(Level.DEBUG)) assertFalse(line.contains("files.example"), "no url in the line: " + line);
		}
	}

	private static void assertLocationRefused(ArtifactRevision r, boolean latest, String location, String what) {
		TeaRefusal e = assertThrows(TeaRefusal.class, () -> TeaArtifactResponses.redirectHeaders(CTX, r, latest, location), what);
		assertEquals(404, e.response().getStatusCode().value(), what);
		assertEquals(TeaUnknownErrorType.NOT_IMPLEMENTED, e.response().getBody().getError(), what);
		assertEquals(TeaArtifactResponses.LOCATION_TOO_LONG_MESSAGE, e.response().getBody().getMessage(), what);
		assertEquals("no-store", e.response().getHeaders().getCacheControl(), what);
		assertEquals("application/json", e.response().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE), what);
		assertNull(e.response().getHeaders().getFirst(HttpHeaders.LOCATION), what);
		assertNull(e.response().getHeaders().getFirst(HttpHeaders.VARY), what);
	}

	// 28 (round 4, T-8): the Content-Disposition file name is cut to 255 octets of UTF-8 on a code point boundary
	@Test
	void theFileNameIsCutToWhatAFileSystemTakes() {
		assertEquals(255, TeaArtifactResponses.MAX_FILE_NAME_OCTETS);
		UUID u = UUID.randomUUID();
		String sha = "ab".repeat(32);
		String e127 = URLEncoder.encode("\u00e9".repeat(127), StandardCharsets.UTF_8);
		for (TeaDownloadObject kind : TeaDownloadObject.values()) {
			java.util.function.Function<String, String> cd = name -> TeaArtifactResponses.downloadHeaders(CTX,
					revision(u, 3, CDX, true), CDX, sha, kind, false, CDX, name, 1).getFirst(HttpHeaders.CONTENT_DISPOSITION);
			String n300 = "n".repeat(300);
			assertEquals("attachment; filename=\"" + "n".repeat(255) + "\"", cd.apply(n300), kind + " 300 ASCII");
			assertEquals("attachment; filename=\"" + "m".repeat(255) + "\"", cd.apply("m".repeat(255)), kind + " 255 unchanged");
			String accented = cd.apply("\u00e9".repeat(200) + ".md");
			// Spring writes its ASCII fallback (one e per e-acute) before filename*; both carry the 127 kept code points
			assertEquals("attachment; filename=\"" + "e".repeat(127) + "\"; filename*=UTF-8''" + e127, accented,
					kind + " 254 octets of e-acute, nothing of .md");
			assertEquals("attachment; filename=\"" + "a".repeat(253) + "\"", cd.apply("a".repeat(253) + "\uD83D\uDE00"),
					kind + " the emoji dropped whole");
		}
	}
}

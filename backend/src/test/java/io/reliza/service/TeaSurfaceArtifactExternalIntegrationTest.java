/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.Link;
import io.reliza.service.tea.TeaArtifactSurfaceService;
import io.reliza.service.tea.TeaLogCapture;
import io.reliza.ws.tea.ArtifactApiController;
import io.reliza.ws.tea.TeaArtifactResponses;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-10, design round 2 case 24 (amended by round 3): a format published with an external url (a
 * row stored EXTERNALLY) is answered by a 302 to the frozen url in URI form after the access decision
 * and the format, with no validator, no log row, no WARN and no ERROR; an externally stored signature
 * likewise, after the format check, or SIGNATURE_NOT_FOUND with one WARN when it has no link; hosted
 * bytes that cannot be read stay a 500. Round 5 (rows 36 and 37): a signature published by url is a 302
 * to the frozen signatureUrl; an entry frozen before TEA-20 answers from the row's live link only when the
 * TEA-19 url rule admits it, else SIGNATURE_NOT_FOUND with one WARN naming the rule's problem.
 */
public class TeaSurfaceArtifactExternalIntegrationTest extends TeaSurfaceArtifactIntegrationTestBase {

	private static final List<String> NEVER_ON_A_REDIRECT = List.of("ETag", "Repr-Digest", "Content-Location",
			"Content-Disposition", "Content-Type", "Content-Encoding");

	static void assertRedirect(HttpResponse<byte[]> r, String location, String cacheControl, String what) {
		assertEquals(302, r.statusCode(), what);
		assertEquals(location, header(r, "Location"), what + ": the url byte for byte");
		assertEquals(cacheControl, header(r, "Cache-Control"), what);
		assertEquals("Authorization", header(r, "Vary"), what);
		assertEquals(0, r.body().length, what + ": no body");
		for (String h : NEVER_ON_A_REDIRECT) assertNull(header(r, h), what + ": " + h);
	}

	/** Exactly {@code count} lines above DEBUG, the last one the WARN naming the signature row and the url rule's problem. */
	private static void assertNewWarn(TeaLogCapture service, int count, java.util.UUID row, String problem) {
		List<String> lines = service.aboveDebug();
		assertEquals(count, lines.size(), lines.toString());
		String last = lines.get(count - 1);
		assertTrue(last.startsWith("WARN: ") && last.contains(row.toString()) && last.contains("(" + problem + ")"), last);
	}

	private JsonNode format(ArtWorld w, Art a, String rev) throws Exception {
		HttpResponse<String> r = get(url(w, a, rev, ""), null);
		assertEquals(200, r.statusCode(), rev + ": " + r.body());
		assertConforms(r.body(), "artifact");
		return Utils.OM.readTree(r.body()).get("formats").get(0);
	}

	// 24
	@Test
	void aFormatPublishedWithAnExternalUrlIsARedirectToIt() throws Exception {
		ArtWorld w = artifactWorld();
		// run 4 (T-13, design 4.3): the pre-rule freezes rewrote pub's version 1, so its stored document_sha256 (the
		// collection's ETag) must still be the digest of the document it serves
		for (String version : List.of("1", "latest")) {
			HttpResponse<byte[]> c = getBytes(w.base() + "/componentRelease/" + w.pub().head() + "/collection/" + version, null, null);
			assertEquals(200, c.statusCode(), "collection " + version + " of pub after the freezes");
			assertEquals("\"" + Utils.bytesToHexSha256(c.body()) + "\"", header(c, "ETag"),
					"collection " + version + " of pub after the freezes");
		}
		Art ext = w.pub().ext();
		String extUrl = w.pub().extUrl();
		ZonedDateTime since = ZonedDateTime.now();
		// round 4 (T-9): the captures open at DEBUG, so silence means nothing above DEBUG and no throwable
		try (TeaLogCapture service = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG);
				TeaLogCapture controller = TeaLogCapture.of(ArtifactApiController.class, Level.DEBUG);
				TeaLogCapture responses = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			for (String rev : List.of("1", "latest")) {
				JsonNode f = format(w, ext, rev);
				assertEquals(extUrl, f.get("url").asString(), rev);
				assertEquals(ext.sha256(), f.get("checksums").get(0).get("algValue").asString(), rev);
			}

			// PUBLIC: a 302 for everyone, on both endpoints, GET and HEAD, If-None-Match never evaluated
			for (String rev : List.of("1", "latest")) {
				String u = url(w, ext, rev, "/download");
				for (String bearer : new String[] { null, w.cover() }) {
					String what = rev + (null == bearer ? " anonymous" : " cover");
					assertRedirect(getBytes(u, bearer, null), extUrl, "no-cache", what);
					assertRedirect(head(u, bearer, null), extUrl, "no-cache", what + " HEAD");
					for (String inm : List.of("*", "\"" + ext.sha256() + "\"")) {
						assertRedirect(getBytes(u, bearer, Map.of("If-None-Match", inm)), extUrl, "no-cache", what + " " + inm);
					}
				}
			}

			// PRIVATE: the access decision and the format come first
			Art pext = w.priv().ext();
			String purl = url(w, pext, "1", "/download");
			assertRedirect(getBytes(purl, w.cover(), null), w.priv().extUrl(), "private, no-cache", "priv for cover");
			assertRedirect(getBytes(url(w, pext, "latest", "/download"), w.cover(), null), w.priv().extUrl(),
					"private, no-cache", "priv latest for cover");
			assertNoStore401(get(purl, null), false, "priv anonymous");
			assertObjectUnknown(get(purl, w.other()), "priv for other");
			// TEA-18 (decision 21, row 38): a valid token of another organization is anonymous here, so PRIVATE is the challenge
			assertNoStore401(get(purl, w.far()), false, "priv for the foreign key");
			String unmatched = w.base() + "/artifact/" + pext.tea() + "/1/download?mediaType=" + enc("text/plain");
			assertTeaError(get(unmatched, w.cover()), 406, "NO_ACCEPTABLE_FORMAT", "priv text/plain for cover");
			assertNoStore401(get(w.base() + "/artifact/" + pext.tea() + "/1/download", null), false, "priv no mediaType anonymous");

			// the signature: published by url, a 302 to the frozen signatureUrl (TEA-20); none published is SIGNATURE_NOT_FOUND
			for (String rev : List.of("1", "latest")) {
				String sig = url(w, ext, rev, "/signature/download");
				assertRedirect(getBytes(sig, null, null), w.pub().extSigUrl(), "no-cache", "signature " + rev);
				assertRedirect(head(sig, null, null), w.pub().extSigUrl(), "no-cache", "signature " + rev + " HEAD");
			}
			HttpResponse<String> meta = get(url(w, ext, "1", ""), null);
			assertEquals(200, meta.statusCode(), meta.body());
			assertConforms(meta.body(), "artifact");
			assertEquals(w.pub().extSigUrl(), Utils.OM.readTree(meta.body()).get("formats").get(0).get("signatureUrl").asString());
			assertTeaError(get(url(w, pext, "1", "/signature/download"), w.cover()), 404, "SIGNATURE_NOT_FOUND", "priv ext signature");

			// round 3 (T-5): the format check comes before the 302 of an externally stored signature
			for (String rev : List.of("1", "latest")) {
				String sig = w.base() + "/artifact/" + ext.tea() + "/" + rev + "/signature/download";
				for (String q : List.of("?mediaType=" + enc("text/plain"), "", "?mediaType=")) {
					HttpResponse<String> r = get(sig + q, null);
					assertTeaError(r, 406, "NO_ACCEPTABLE_FORMAT", "ext signature " + rev + " " + q);
					assertNull(header(r, "Location"), "ext signature " + rev + " " + q + ": no Location");
				}
			}

			// round 3 (T-6): a frozen url that is not a URI goes out in URI form, the IRI and the controls alike
			Art iri = w.pub().extIri();
			Art ctl = w.pub().extCtl();
			String iriLocation = w.pub().extIriUrl().replace("na\u00efve-\u2713.md", "na%C3%AFve-%E2%9C%93.md");
			String ctlLocation = w.pub().extCtlUrl().replace("\r\n", "%0D%0A");
			assertTrue(iriLocation.endsWith("/na%C3%AFve-%E2%9C%93.md") && ctlLocation.endsWith("/x.md%0D%0ASet-Cookie: tea10=1"));
			for (String rev : List.of("1", "latest")) {
				for (Art a : List.of(iri, ctl)) {
					String location = a == iri ? iriLocation : ctlLocation;
					String what = (a == iri ? "extIri " : "extCtl ") + rev;
					String u = url(w, a, rev, "/download");
					HttpResponse<byte[]> got = getBytes(u, null, null);
					assertRedirect(got, location, "no-cache", what);
					assertNull(header(got, "Set-Cookie"), what + ": no header injected");
					HttpResponse<byte[]> head = head(u, null, null);
					assertRedirect(head, location, "no-cache", what + " HEAD");
					assertNull(header(head, "Set-Cookie"), what + " HEAD: no header injected");
				}
			}
			// the metadata serves the frozen string as stored (read for its url only)
			for (Art a : List.of(iri, ctl)) {
				HttpResponse<String> m = get(url(w, a, "1", ""), null);
				assertEquals(200, m.statusCode(), m.body());
				assertEquals(a == iri ? w.pub().extIriUrl() : w.pub().extCtlUrl(),
						Utils.OM.readTree(m.body()).get("formats").get(0).get("url").asString());
			}

			assertEquals(List.of(), service.aboveDebug());
			assertEquals(List.of(), controller.aboveDebug());
			assertEquals(List.of(), responses.aboveDebug());

			// round 5 row 36: the pre-rule signature entries. extIri's link is an IRI, which the url rule refuses
			ArtObj p = w.pub();
			int warns = 0;
			for (String rev : List.of("1", "latest")) {
				assertTeaError(get(url(w, iri, rev, "/signature/download"), null), 404, "SIGNATURE_NOT_FOUND", "extIri signature " + rev);
				assertNewWarn(service, ++warns, p.extIriSigRow(), "NOT_PRINTABLE_ASCII");
			}
			for (Art a : List.of(iri, ctl, p.extLong(), p.extOver(), p.extIriOver(), p.extNoSig())) {
				HttpResponse<String> m = get(url(w, a, "1", ""), null);
				assertEquals(200, m.statusCode(), m.body());
				assertFalse(Utils.OM.readTree(m.body()).get("formats").get(0).has("signatureUrl"), a.tea() + ": no signatureUrl");
			}
			// extNoSig: the engine skipped its signature, so nothing was published
			for (String rev : List.of("1", "latest")) {
				assertTeaError(get(url(w, p.extNoSig(), rev, "/signature/download"), null), 404, "SIGNATURE_NOT_FOUND",
						"extNoSig signature " + rev);
			}
			assertEquals(warns, service.aboveDebug().size(), service.aboveDebug().toString());
			// extPre: a pre-rule entry whose live link the url rule admits is a 302 to it, byte for byte
			for (String rev : List.of("1", "latest")) {
				assertRedirect(getBytes(url(w, p.extPre(), rev, "/signature/download"), null, null), p.extPreSigUrl(), "no-cache",
						"extPre signature " + rev);
			}
			assertEquals(warns, service.aboveDebug().size(), service.aboveDebug().toString());
			// live and admitted: the link edited to ftp is refused by the rule
			setDownloadLinks(p.extPreSigRow(), List.of(new Link("ftp://files.example/moved.sig", Link.ContentEnum.OCTET_STREAM)));
			for (String rev : List.of("1", "latest")) {
				assertTeaError(get(url(w, p.extPre(), rev, "/signature/download"), null), 404, "SIGNATURE_NOT_FOUND",
						"extPre moved to ftp " + rev);
				assertNewWarn(service, ++warns, p.extPreSigRow(), "SCHEME_NOT_HTTP");
			}
			// frozen, not live (TEA-20 row 14): the signature row's links emptied, the frozen signatureUrl still answers
			setDownloadLinks(p.extSigRow(), List.of());
			for (String rev : List.of("1", "latest")) {
				assertRedirect(getBytes(url(w, ext, rev, "/signature/download"), null, null), p.extSigUrl(), "no-cache",
						"signature after its links were emptied " + rev);
			}
			assertEquals(warns, service.aboveDebug().size(), service.aboveDebug().toString());
			// the same entry frozen before TEA-20 (no signatureUrl): the linkless row is SIGNATURE_NOT_FOUND with one WARN
			freezeSignature(p.head(), 1, ext.tea(), p.extSigRow(), 1, null);
			assertTeaError(get(url(w, ext, "1", "/signature/download"), null), 404, "SIGNATURE_NOT_FOUND", "a linkless signature");
			assertNewWarn(service, ++warns, p.extSigRow(), "EMPTY");
			assertEquals(List.of(), controller.aboveDebug());
			assertEquals(List.of(), responses.aboveDebug());

			// frozen, not live: the row's link moves, the published revision does not
			setDownloadLinks(ext.row(), List.of(new Link("https://elsewhere.example/moved.md", Link.ContentEnum.OCTET_STREAM)));
			assertRedirect(getBytes(url(w, ext, "1", "/download"), null, null), extUrl, "no-cache", "after the live link moved");
			assertEquals(extUrl, format(w, ext, "1").get("url").asString(), "the metadata still names the frozen url");

			assertEquals(List.of(), awaitTeaDownloads(since, 1), "a redirect serves no bytes, so no row");
			assertEquals(warns, service.aboveDebug().size(), "only the WARNs above: " + service.aboveDebug());
			assertTrue(controller.lines(Level.DEBUG).stream().anyMatch(l -> l.endsWith("redirected")), "the captures are open at DEBUG");
			assertEquals(List.of(), controller.aboveDebug());
			assertEquals(List.of(), responses.aboveDebug());
			for (TeaLogCapture c : List.of(service, controller, responses)) assertEquals(0, c.withThrowable());
		}

		// the control: hosted bytes that cannot be read stay a 500 with one ERROR and no row
		Art nosig = w.pub().nosig();
		retained.remove(nosig.sha256());
		ZonedDateTime control = ZonedDateTime.now();
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertNotTeaError(get(url(w, nosig, String.valueOf(nosig.revision()), "/download"), null), 500, "Internal Server Error",
					"a hosted blob that is gone");
			assertEquals(1, logs.errors().size(), logs.errors().toString());
		}
		assertEquals(List.of(), awaitTeaDownloads(control, 1));
	}

	/** Round 4 decision 16a: 404 NOT_IMPLEMENTED with its message, no-store, JSON, and neither Location nor Vary. */
	private static void assertLocationRefused(HttpResponse<String> r, String what) {
		assertTeaError(r, 404, "NOT_IMPLEMENTED", what);
		assertEquals(TeaArtifactResponses.LOCATION_TOO_LONG_MESSAGE, json(r).get("message"), what);
		assertEquals("application/json", header(r, "Content-Type"), what);
		assertNull(header(r, "Location"), what + ": no Location");
		assertNull(header(r, "Vary"), what + ": no Vary");
	}

	private static void assertLocationRefusedHead(HttpResponse<byte[]> r, String what) {
		assertEquals(404, r.statusCode(), what);
		assertEquals(0, r.body().length, what + ": no body");
		assertEquals("no-store", header(r, "Cache-Control"), what);
		assertEquals("application/json", header(r, "Content-Type"), what);
		assertNull(header(r, "Location"), what + ": no Location");
		assertNull(header(r, "Vary"), what + ": no Vary");
	}

	// 27 (round 4, T-8)
	@Test
	void aLocationTheContainerCannotSendIsDeclinedBeforeItIsBuilt() throws Exception {
		ArtWorld w = artifactWorld();
		ArtObj p = w.pub();
		assertEquals(4096, p.extLongUrl().length());
		ZonedDateTime since = ZonedDateTime.now();
		int warns = 0;
		try (TeaLogCapture service = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG);
				TeaLogCapture controller = TeaLogCapture.of(ArtifactApiController.class, Level.DEBUG);
				TeaLogCapture responses = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG)) {
			for (String rev : List.of("1", "latest")) {
				// 4096 octets: the 302 goes out through the real container, byte for byte
				String u = url(w, p.extLong(), rev, "/download");
				assertRedirect(getBytes(u, null, null), p.extLongUrl(), "no-cache", "extLong " + rev);
				assertRedirect(head(u, null, null), p.extLongUrl(), "no-cache", "extLong " + rev + " HEAD");
				// its signature's link is 4100 octets: a pre-rule entry whose live link the url rule refuses (round 5 row 37)
				assertTeaError(get(url(w, p.extLong(), rev, "/signature/download"), null), 404, "SIGNATURE_NOT_FOUND",
						"extLong signature " + rev);
				assertNewWarn(service, ++warns, p.extLongSigRow(), "TOO_LONG");

				// 4097 octets, and an IRI over 4096 as a URI: declined, for every caller, GET and HEAD
				for (Art a : List.of(p.extOver(), p.extIriOver())) {
					String what = (a == p.extOver() ? "extOver " : "extIriOver ") + rev;
					String d = url(w, a, rev, "/download");
					assertLocationRefused(get(d, null), what);
					assertLocationRefused(get(d, w.cover()), what + " cover");
					assertLocationRefusedHead(head(d, null, null), what + " HEAD");
					assertTeaError(get(url(w, a, rev, "/signature/download"), null), 404, "SIGNATURE_NOT_FOUND", what + " signature");
					boolean over = a == p.extOver();
					assertNewWarn(service, ++warns, over ? p.extOverSigRow() : p.extIriOverSigRow(), over ? "TOO_LONG" : "NOT_PRINTABLE_ASCII");
				}
				// the format check precedes the bound
				String over = w.base() + "/artifact/" + p.extOver().tea() + "/" + rev + "/download";
				assertTeaError(get(over + "?mediaType=" + enc("text/plain"), null), 406, "NO_ACCEPTABLE_FORMAT", "extOver text/plain " + rev);
				assertTeaError(get(over + "?mediaType=foo", null), 400, "INVALID_REQUEST", "extOver foo " + rev);
			}
			assertEquals(p.extLongUrl(), format(w, p.extLong(), "1").get("url").asString(), "the metadata serves the url");
			// read for its url only: a frozen IRI is not an RFC 3986 uri (the TEA-5 admission follow-up), as for extIri
			HttpResponse<String> iriMeta = get(url(w, p.extIriOver(), "1", ""), null);
			assertEquals(200, iriMeta.statusCode(), iriMeta.body());
			assertEquals(p.extIriOverUrl(), Utils.OM.readTree(iriMeta.body()).get("formats").get(0).get("url").asString(),
					"the metadata serves the frozen IRI as stored; the bound is on the URI form");

			// the file name is cut to 255 octets
			Art ln = p.longName();
			assertEquals(304, p.longNameFile().length());
			HttpResponse<byte[]> got = getBytes(url(w, ln, String.valueOf(ln.revision()), "/download"), null, null);
			assertDownload(got, ln.bytes(), LIC_MEDIA_TYPE, contentLocation(w, ln, false), "public, max-age=86400, immutable",
					"longName");
			assertEquals("attachment; filename=\"" + "n".repeat(255) + "\"", header(got, "Content-Disposition"));

			assertEquals(1, awaitTeaDownloads(since, 1).size(), "only the longName 200 is logged");
			assertFalse(controller.lines(Level.DEBUG).isEmpty(), "the captures are open at DEBUG");
			assertEquals(6, warns, "three signature WARNs per revision loop");
			assertEquals(warns, service.aboveDebug().size(), "only those WARNs: " + service.aboveDebug());
			for (TeaLogCapture c : List.of(controller, responses)) assertEquals(List.of(), c.aboveDebug());
			for (TeaLogCapture c : List.of(service, controller, responses)) assertEquals(0, c.withThrowable());
		}
	}
}

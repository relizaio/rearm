/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.Utils;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.service.tea.TeaArtifactSurfaceService;
import io.reliza.service.tea.TeaLogCapture;
import io.reliza.ws.tea.ArtifactApiController;
import io.reliza.ws.tea.TeaArtifactResponses;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task TEA-10, design 4.3 cases 13, 14, 18 and 19 (14 amended by rounds 2 and 3): the conditional GET,
 * the format refusals after the access decision and without a log line, HEAD, and the failures that must
 * never read as another answer.
 */
public class TeaSurfaceArtifactCachingIntegrationTest extends TeaSurfaceArtifactIntegrationTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager txManager;

	private static final List<String> VALIDATORS = List.of("ETag", "Content-Location", "Cache-Control", "Vary", "Repr-Digest");

	private void assertNotModified(String url, String bearer, String inm, HttpResponse<byte[]> ok, String what) throws Exception {
		HttpResponse<byte[]> nm = getBytes(url, bearer, Map.of("If-None-Match", inm));
		assertEquals(304, nm.statusCode(), what + " " + inm);
		assertEquals(0, nm.body().length, what);
		for (String h : VALIDATORS) assertEquals(header(ok, h), header(nm, h), what + " " + inm + ": " + h);
		assertNull(header(nm, "Content-Type"), what + ": no Content-Type on a 304");
		assertNull(header(nm, "Content-Disposition"), what);
	}

	// 13
	@Test
	void ifNoneMatchAnswersNotModifiedUnderTheWeakComparison() throws Exception {
		ArtWorld w = artifactWorld();
		Art lic = w.pub().lic();
		for (String url : List.of(url(w, lic, "latest", "/download"), url(w, lic, String.valueOf(lic.revision()), "/download"),
				url(w, lic, "latest", "/signature/download"), url(w, lic, String.valueOf(lic.revision()), "/signature/download"))) {
			HttpResponse<byte[]> ok = getBytes(url, null, null);
			assertEquals(200, ok.statusCode(), url);
			String tag = header(ok, "ETag");
			clearInvocations(sharedArtifactSpy);
			for (String inm : List.of(tag, "W/" + tag, "*", "\"x\", " + tag)) assertNotModified(url, null, inm, ok, url);
			verify(sharedArtifactSpy, never()).downloadRawArtifact(any());
			assertEquals(200, getBytes(url, null, Map.of("If-None-Match", "\"another\"")).statusCode(), url);
		}
		// no stored SHA-256: the bytes are read to compute the validator, then compared
		Art s512 = w.pub().s512();
		String url = url(w, s512, String.valueOf(s512.revision()), "/download");
		HttpResponse<byte[]> ok = getBytes(url, null, null);
		clearInvocations(sharedArtifactSpy);
		assertNotModified(url, null, header(ok, "ETag"), ok, "s512");
		verify(sharedArtifactSpy, atLeastOnce()).downloadRawArtifact(any());
		// a PRIVATE revision for a covering key repeats its private Cache-Control
		Art priv = w.priv().lic();
		String purl = url(w, priv, String.valueOf(priv.revision()), "/download");
		HttpResponse<byte[]> pok = getBytes(purl, w.cover(), null);
		assertEquals("private, no-cache", header(pok, "Cache-Control"));
		assertNotModified(purl, w.cover(), header(pok, "ETag"), pok, "priv");
	}

	// 14 (amended: TEA 1.0.0 makes mediaType optional)
	@Test
	void mediaTypeWildcardOrUnmatchedIsNotAcceptable() throws Exception {
		ArtWorld w = artifactWorld();
		Art agg = w.pub().agg();
		Art lic = w.pub().lic();
		for (String suffix : List.of("/download", "/signature/download")) {
			Art a = "/download".equals(suffix) ? agg : lic;
			String base = w.base() + "/artifact/" + a.tea() + "/" + a.revision() + suffix;
			for (String q : List.of("?mediaType=" + enc("application/json"), "?mediaType=" + enc("*/*"),
					"?mediaType=" + enc("application/*"))) {
				HttpResponse<String> r = get(base + q, null);
				assertTeaError(r, 406, "NO_ACCEPTABLE_FORMAT", suffix + q);
				assertTrue(json(r).get("message") instanceof String, suffix + q);
			}
			assertTeaError(get(base + "?mediaType=foo", null), 400, "INVALID_REQUEST", suffix + " foo");
			// absent or empty: the server's choice, named by Content-Location; content varies on Accept
			for (String q : List.of("", "?mediaType=")) {
				HttpResponse<byte[]> r = getBytes(base + q, null, null);
				assertEquals(200, r.statusCode(), suffix + q + ": " + new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
				assertTrue(header(r, "Content-Location").endsWith("?mediaType=" + enc(a.mediaType())), suffix + q);
				assertEquals("/download".equals(suffix) ? "Authorization, Accept" : "Authorization", header(r, "Vary"), suffix + q);
			}
		}
		// without mediaType a content download negotiates on Accept
		String content = w.base() + "/artifact/" + agg.tea() + "/" + agg.revision() + "/download";
		HttpResponse<byte[]> accepted = getBytes(content, null, Map.of("Accept", "text/html;q=0.5, " + agg.mediaType()));
		assertEquals(200, accepted.statusCode());
		assertArrayEquals(agg.bytes(), accepted.body());
		assertEquals(agg.mediaType(), header(accepted, "Content-Type"));
		assertEquals(200, getBytes(content, null, Map.of("Accept", "*/*")).statusCode());
		HttpResponse<String> refused = get(content, null, Map.of("Accept", "text/html"));
		assertTeaError(refused, 406, "NO_ACCEPTABLE_FORMAT", "Accept text/html");
		// an explicit mediaType decides alone
		assertEquals(200, getBytes(content + "?mediaType=" + enc(agg.mediaType()), null, Map.of("Accept", "text/html")).statusCode());
		// round 3 (T-7), round 4 (T-9): a mediaType that is not a media type is a 400 the client earned, so
		// nothing above DEBUG and no throwable at any level, read from captures open at DEBUG
		try (TeaLogCapture responses = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG);
				TeaLogCapture controller = TeaLogCapture.of(ArtifactApiController.class, Level.DEBUG)) {
			String licDownload = w.base() + "/artifact/" + lic.tea() + "/" + lic.revision() + "/download";
			for (String q : List.of("foo", "%2Fjson", "text%2Fplain%3B%20charset%3Dnope", "a%2Fb%2C%20c%2Fd")) {
				assertTeaError(get(licDownload + "?mediaType=" + q, null), 400, "INVALID_REQUEST", "lic " + q);
			}
			assertEquals(List.of(), responses.aboveDebug());
			assertEquals(0, responses.withThrowable());
			assertEquals(List.of(), controller.aboveDebug());
			assertEquals(0, controller.withThrowable());
		}
		HttpResponse<byte[]> mixed = getBytes(w.base() + "/artifact/" + agg.tea() + "/1/download?mediaType="
				+ enc("Application/VND.CycloneDX+JSON"), null, null);
		assertEquals(200, mixed.statusCode());
		assertEquals("application/vnd.cyclonedx+json", header(mixed, "Content-Type"));
		assertEquals(contentLocation(w, agg, false), header(mixed, "Content-Location"), "the stored spelling");

		Art priv = w.priv().lic();
		String wrong = w.base() + "/artifact/" + priv.tea() + "/" + priv.revision() + "/download?mediaType=" + enc("text/html");
		assertTeaError(get(wrong, w.cover()), 406, "NO_ACCEPTABLE_FORMAT", "priv for cover");
		assertNoStore401(get(wrong, null), false, "priv anonymous: the access decision comes first");
		assertObjectUnknown(get(wrong, w.other()), "priv for other");

		// round 2 (T-2): with mediaType absent or blank the access decision still comes before the format check;
		// run 4 (T-12): so it does for a mediaType that is not a media type, which only a served caller sees as 400
		for (String suffix : List.of("/download", "/signature/download")) {
			for (String rev : List.of("1", "latest")) {
				for (String q : List.of("", "?mediaType=", "?mediaType=foo")) {
					String tail = "/" + rev + suffix + q;
					String label = tail.isEmpty() ? suffix : tail;
					String privLic = w.base() + "/artifact/" + priv.tea() + tail;
					assertNoStore401(get(privLic, null), false, "priv anonymous " + label);
					assertObjectUnknown(get(privLic, w.other()), "priv for other " + label);
					assertObjectUnknown(get(w.base() + "/artifact/" + UUID.randomUUID() + tail, null), "a random id " + label);
					assertObjectUnknown(get(w.base() + "/artifact/" + w.hid().lic().tea() + tail, null), "hid anonymous " + label);
					assertObjectUnknown(get(w.base() + "/artifact/" + w.foreignAgg().tea() + tail, null), "the foreign id " + label);
					if (q.endsWith("foo")) {
						assertTeaError(get(privLic, w.cover()), 400, "INVALID_REQUEST", "priv for cover " + label);
					} else if ("/download".equals(suffix)) {
						// TEA 1.0.0: without mediaType the covering caller is served the server's choice
						assertEquals(200, getBytes(privLic, w.cover(), null).statusCode(), "priv for cover " + label);
					} else {
						assertNotEquals(406, get(privLic, w.cover()).statusCode(), "priv for cover " + label);
					}
				}
			}
		}
	}

	// 18
	@Test
	void headAnswersTheGetsHeadersWithoutABodyOrALogRow() throws Exception {
		ArtWorld w = artifactWorld();
		Art lic = w.pub().lic();
		ZonedDateTime since = ZonedDateTime.now();
		for (String rev : List.of("latest", String.valueOf(lic.revision()))) {
			for (String suffix : List.of("/download", "/signature/download")) {
				String url = url(w, lic, rev, suffix);
				HttpResponse<byte[]> g = getBytes(url, null, null);
				HttpResponse<byte[]> h = head(url, null, null);
				assertEquals(200, h.statusCode(), url);
				assertEquals(0, h.body().length, url);
				byte[] served = url.contains("/signature/") ? w.pub().sigBytes() : lic.bytes();
				assertEquals(String.valueOf(served.length), header(h, "Content-Length"), url + ": the exact length");
				for (String name : List.of("Content-Type", "Content-Length", "Content-Disposition", "ETag", "Repr-Digest",
						"Content-Location", "Cache-Control", "Vary")) {
					assertEquals(header(g, name), header(h, name), url + ": " + name);
				}
				assertEquals(304, head(url, null, Map.of("If-None-Match", header(g, "ETag"))).statusCode(), url);
			}
		}
		assertEquals(4, awaitTeaDownloads(since, 4).size(), "the four GETs only");
		Art priv = w.priv().lic();
		HttpResponse<byte[]> anon = head(url(w, priv, "latest", "/download"), null, null);
		assertEquals(401, anon.statusCode());
		assertEquals("Bearer realm=\"tea\"", header(anon, "WWW-Authenticate"));
	}

	// 19
	@Test
	void aMissingBlobIsA500AndABrokenDocumentIsUnknown() throws Exception {
		ArtWorld w = artifactWorld();
		Art nosig = w.pub().nosig();
		retained.remove(nosig.sha256());
		ZonedDateTime since = ZonedDateTime.now();
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			HttpResponse<String> r = get(url(w, nosig, String.valueOf(nosig.revision()), "/download"), null);
			assertNotTeaError(r, 500, "Internal Server Error", "a blob that is gone");
			assertEquals(1, logs.errors().size(), logs.errors().toString());
		}
		assertEquals(200, get(url(w, nosig, "latest", ""), null).statusCode(), "the metadata still serves");

		Art lic = w.pub().lic();
		HttpResponse<byte[]> plain = getBytes(url(w, lic, "latest", "/download"), null, Map.of("Accept", "text/html"));
		assertEquals(200, plain.statusCode(), "Accept does not negotiate a download");
		assertEquals(lic.mediaType(), header(plain, "Content-Type"));
		assertEquals(1, awaitTeaDownloads(since, 1).size(), "only the served download is logged");

		TeaPublishedCollectionData v1 = teaPublicationService.getLatestCollection(w.pub().head()).orElseThrow();
		ObjectNode doc = (ObjectNode) Utils.OM.readTree(v1.getDocument());
		doc.putArray("artifacts");
		String broken = Utils.OM.writeValueAsString(doc);
		new TransactionTemplate(txManager).execute(s -> jdbc.update("UPDATE rearm.tea_collections SET document = ? WHERE uuid = ? AND version = ?",
				broken, w.pub().head(), v1.getVersion()));
		for (String suffix : List.of("", "/download", "/signature/download")) {
			for (String rev : List.of("latest", String.valueOf(lic.revision()))) {
				try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
					assertObjectUnknown(get(url(w, lic, rev, suffix), null), rev + suffix);
					assertEquals(1, logs.errors().size(), rev + suffix + ": " + logs.errors());
				}
			}
		}
		assertEquals(new String(plain.body(), StandardCharsets.UTF_8), new String(lic.bytes(), StandardCharsets.UTF_8));
	}
}

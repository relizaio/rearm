/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.DownloadLogData;
import io.reliza.model.DownloadLogData.DownloadConfig;
import io.reliza.model.DownloadLogData.DownloadSubjectType;
import io.reliza.model.TeaDownloadObject;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.tea.TeaLogCapture;
import io.reliza.ws.tea.ArtifactApiController;

/**
 * Task TEA-10, design 4.3 cases 15, 16 and 17 (16 and 17 amended by round 2): signatures only when
 * published alongside, the auth matrix on all six operations with Cache-Control judged per revision,
 * and one download log row per served download, keyed only by a key of this organization. Round 6 row
 * 42: a signature row's non-integer version reaches the log through LogSafe; round 7 row 43: a
 * conditional request for a revision the caller is no longer served is the refusal, never 304.
 */
public class TeaSurfaceArtifactAccessIntegrationTest extends TeaSurfaceArtifactIntegrationTestBase {

	/** The six operations of one artifact, by name; the downloads select its format. */
	private static Map<String, String> operations(ArtWorld w, Art a) {
		Map<String, String> m = new LinkedHashMap<>();
		String rev = String.valueOf(a.revision());
		m.put("latest", url(w, a, "latest", ""));
		m.put("version", url(w, a, rev, ""));
		m.put("latest/download", url(w, a, "latest", "/download"));
		m.put("version/download", url(w, a, rev, "/download"));
		m.put("latest/signature", url(w, a, "latest", "/signature/download"));
		m.put("version/signature", url(w, a, rev, "/signature/download"));
		return m;
	}

	/** Each caller's expected status on all six operations: 200, 401 (challenge), 401i (invalid_token) or 404. */
	private void assertMatrix(ArtWorld w, Art a, String anon, String bad, String other, String cover, String what) throws Exception {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("anonymous", anon);
		expected.put("bad", bad);
		expected.put("other", other);
		expected.put("cover", cover);
		Map<String, String> bearers = new LinkedHashMap<>();
		bearers.put("anonymous", null);
		bearers.put("bad", BAD);
		bearers.put("other", w.other());
		bearers.put("cover", w.cover());
		for (Map.Entry<String, String> op : operations(w, a).entrySet()) {
			for (Map.Entry<String, String> c : expected.entrySet()) {
				String label = what + " " + op.getKey() + " " + c.getKey();
				HttpResponse<String> r = get(op.getValue(), bearers.get(c.getKey()));
				switch (c.getValue()) {
					case "200" -> assertEquals(200, r.statusCode(), label + ": " + r.body());
					case "401" -> assertNoStore401(r, false, label);
					case "401i" -> assertNoStore401(r, true, label);
					default -> assertObjectUnknown(r, label);
				}
			}
		}
	}

	// 16
	@Test
	void theAuthMatrixOnEveryOperation() throws Exception {
		ArtWorld w = artifactWorld();
		assertMatrix(w, w.pub().lic(), "200", "200", "200", "200", "pub");
		assertMatrix(w, w.priv().lic(), "401", "401i", "404", "200", "priv");
		assertMatrix(w, w.hid().lic(), "404", "404", "404", "404", "hid");
		assertMatrix(w, w.casc().lic(), "401", "401i", "404", "200", "casc");
		teaPublicationService.hide(w.prodRelease(), null, WU);
		assertMatrix(w, w.casc().lic(), "404", "404", "404", "404", "casc after the product is hidden");
		publish(w.prodRelease(), TeaPublishMode.PUBLISH_OR_REPUBLISH);
		assertEquals(200, get(url(w, w.casc().lic(), "latest", "/download"), w.cover()).statusCode(), "casc again for cover");

		for (String suffix : List.of("/latest", "/1", "/latest/download?mediaType=text%2Fplain", "/1/signature/download?mediaType=text%2Fplain")) {
			assertObjectUnknown(get(w.base() + "/artifact/" + UUID.randomUUID() + suffix, w.cover()), "random id " + suffix);
		}
		Art far = w.foreignAgg();
		assertObjectUnknown(get(w.base() + "/artifact/" + far.tea() + "/latest", null), "the foreign id under this base");
		assertObjectUnknown(get(url(w, far, "1", "/download"), w.cover()), "the foreign id download under this base");
		HttpResponse<byte[]> home = getBytes(w.foreignBase() + "/artifact/" + far.tea() + "/1/download?mediaType=" + enc(far.mediaType()), null, null);
		assertEquals(200, home.statusCode(), "the foreign id under its own base");
		assertArrayEquals(far.bytes(), home.body());
		assertObjectUnknown(get(url(w, w.hid().lic(), "1", "/download"), null, Map.of("Accept", "application/vnd.cyclonedx+json")),
				"a 404 under a download's Accept keeps the TEA body");

		// latest is judged over what the caller is served: one row named by a PUBLIC and a PRIVATE publication
		UUID both = seedFile(org, "both-" + tag() + ".txt", "both v1".getBytes(StandardCharsets.UTF_8), ArtifactType.LICENSE,
				LIC_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_256));
		releaseService.addArtifact(both, w.pub().release(), WU);
		releaseService.addArtifact(both, w.priv().release(), WU);
		publish(w.pub().release(), TeaPublishMode.REPUBLISH);
		publish(w.priv().release(), TeaPublishMode.REPUBLISH);
		Artifact row = sharedArtifactService.getArtifact(both).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(row);
		ad.setVersion("2");
		sharedArtifactService.saveArtifact(row, ad, WU);
		publish(w.priv().release(), TeaPublishMode.REPUBLISH);
		String bothTea = String.valueOf(artifactService.getArtifactData(both).orElseThrow().getTeaUuid());
		String latest = w.base() + "/artifact/" + bothTea + "/latest";
		assertEquals(1, Utils.OM.readTree(get(latest, null).body()).get("version").asInt(), "anonymous sees the PUBLIC revision");
		assertEquals(2, Utils.OM.readTree(get(latest, w.cover()).body()).get("version").asInt(), "the covering key sees revision 2");
		assertNoStore401(get(w.base() + "/artifact/" + bothTea + "/2", null), false, "revision 2 anonymously");
		assertObjectUnknown(get(w.base() + "/artifact/" + bothTea + "/2", w.other()), "revision 2 for other");

		// round 2 (T-1): Cache-Control is judged over the revision answered, not over everything served
		String q = "/download?mediaType=" + enc(LIC_MEDIA_TYPE);
		String bothBase = w.base() + "/artifact/" + bothTea;
		String bothLocation = w.apiBase() + "/v1.0.0/artifact/" + bothTea;
		HttpResponse<byte[]> coverLatest = getBytes(bothBase + "/latest" + q, w.cover(), null);
		assertEquals(200, coverLatest.statusCode());
		assertEquals("private, no-cache", header(coverLatest, "Cache-Control"), "revision 2 is named by priv only");
		assertEquals(bothLocation + "/2" + q, header(coverLatest, "Content-Location"));
		HttpResponse<byte[]> coverTwo = getBytes(bothBase + "/2" + q, w.cover(), null);
		assertEquals(200, coverTwo.statusCode());
		assertEquals("private, no-cache", header(coverTwo, "Cache-Control"));
		HttpResponse<byte[]> coverOne = getBytes(bothBase + "/1" + q, w.cover(), null);
		assertEquals(200, coverOne.statusCode());
		assertEquals("public, max-age=86400, immutable", header(coverOne, "Cache-Control"), "revision 1 is named by pub too");
		HttpResponse<byte[]> anonLatest = getBytes(bothBase + "/latest" + q, null, null);
		assertEquals(200, anonLatest.statusCode());
		assertEquals("no-cache", header(anonLatest, "Cache-Control"));
		assertEquals(bothLocation + "/1" + q, header(anonLatest, "Content-Location"));
	}

	// 15
	@Test
	void aSignatureIsServedOnlyWhenPublishedAlongside() throws Exception {
		ArtWorld w = artifactWorld();
		Art lic = w.pub().lic();
		ArtifactData sig = artifactService.getArtifactData(w.pub().sigRow()).orElseThrow();
		String fileName = sig.getTags().stream().filter(t -> CommonVariables.FILE_NAME_FIELD.equals(t.key())).findFirst().orElseThrow().value();
		String location = contentLocation(w, lic, true);
		for (String rev : List.of("latest", String.valueOf(lic.revision()))) {
			HttpResponse<byte[]> r = getBytes(url(w, lic, rev, "/signature/download"), null, null);
			assertEquals(200, r.statusCode(), rev);
			assertArrayEquals(w.pub().sigBytes(), r.body(), rev);
			assertHeaders(r, w.pub().sigBytes(), SIG_MEDIA_TYPE, location, "latest".equals(rev) ? "no-cache"
					: "public, max-age=86400, immutable", rev);
			assertEquals("attachment; filename=\"" + fileName + "\"", header(r, "Content-Disposition"), rev);
		}
		HttpResponse<byte[]> priv = getBytes(url(w, w.priv().lic(), "1", "/signature/download"), w.cover(), null);
		assertEquals(200, priv.statusCode());
		assertEquals("private, no-cache", header(priv, "Cache-Control"));

		for (Art a : List.of(w.pub().nosig(), w.pub().agg(), w.pub().bom())) {
			for (String rev : List.of("latest", String.valueOf(a.revision()))) {
				HttpResponse<String> r = get(url(w, a, rev, "/signature/download"), null);
				assertTeaError(r, 404, "SIGNATURE_NOT_FOUND", a.mediaType() + " " + rev);
				assertNull(json(r).get("message"));
			}
		}
		assertObjectUnknown(get(url(w, w.hid().lic(), "1", "/signature/download"), null), "hidden: concealment wins");
		teaPublicationService.hide(w.prodRelease(), null, WU);
		assertObjectUnknown(get(url(w, w.casc().lic(), "1", "/signature/download"), w.cover()), "a hidden product's child");

		artifactService.archiveArtifact(w.pub().sigRow(), WU);
		HttpResponse<byte[]> archived = getBytes(url(w, lic, "1", "/signature/download"), null, null);
		assertEquals(200, archived.statusCode(), "a published revision stays retrievable");
		assertArrayEquals(w.pub().sigBytes(), archived.body());
	}

	// 17
	@Test
	void oneLogRowPerServedDownload() throws Exception {
		ArtWorld w = artifactWorld();
		Art priv = w.priv().lic();
		Art pub = w.pub().lic();
		ZonedDateTime quiet = ZonedDateTime.now();
		String ok = url(w, priv, "1", "/download");
		String tag = getBytes(ok, w.cover(), null).headers().firstValue("ETag").orElseThrow();
		// the writer is @Async: let the first row land before the window that must stay empty opens
		assertEquals(1, awaitTeaDownloads(quiet, 1).size(), "the first GET");
		ZonedDateTime since = ZonedDateTime.now();
		assertEquals(304, getBytes(ok, w.cover(), Map.of("If-None-Match", tag)).statusCode());
		assertEquals(200, head(ok, w.cover(), null).statusCode());
		assertEquals(406, get(w.base() + "/artifact/" + priv.tea() + "/1/download?mediaType=" + enc("application/x-no-such-format"), w.cover()).statusCode());
		assertEquals(404, get(ok, w.other()).statusCode());
		assertEquals(200, get(url(w, priv, "1", ""), w.cover()).statusCode());
		assertEquals(200, get(url(w, priv, "latest", ""), w.cover()).statusCode());
		assertEquals(1, awaitTeaDownloads(quiet, 1).size(), "only the first GET");
		assertEquals(List.of(), awaitTeaDownloads(since, 1), "no row for a 304, a HEAD, a 406, a 404 or a metadata read");

		ZonedDateTime content = ZonedDateTime.now();
		assertEquals(200, getBytes(ok, w.cover(), null).statusCode());
		List<DownloadLogData> rows = awaitTeaDownloads(content, 1);
		assertEquals(1, rows.size());
		DownloadLogData row = rows.get(0);
		assertEquals(DownloadSubjectType.ARTIFACT, row.getSubjectType());
		assertEquals(priv.row(), row.getSubjectUuid());
		assertEquals(ProgrammaticType.API, row.getCreatedType());
		assertEquals(w.coverKey(), row.getDownloadedBy());
		DownloadConfig c = row.getDownloadConfig();
		assertEquals(w.coverKeyId(), c.getTeaKeyId());
		assertEquals(w.priv().head(), c.getTeaPublication());
		assertEquals(1, c.getTeaCollectionVersion());
		assertEquals(priv.tea(), c.getTeaArtifactUuid());
		assertEquals(1, c.getTeaArtifactVersion());
		assertEquals(TeaDownloadObject.CONTENT, c.getTeaObject());
		assertEquals(priv.mediaType(), c.getMediaType());
		assertEquals(priv.row(), c.getArtifactUuid());
		assertEquals(1, c.getArtifactVersion());

		ZonedDateTime signature = ZonedDateTime.now();
		assertEquals(200, getBytes(url(w, priv, "latest", "/signature/download"), w.cover(), null).statusCode());
		List<DownloadLogData> sig = awaitTeaDownloads(signature, 1);
		assertEquals(1, sig.size());
		assertEquals(TeaDownloadObject.SIGNATURE, sig.get(0).getDownloadConfig().getTeaObject());
		assertEquals(priv.row(), sig.get(0).getSubjectUuid(), "the subject is the published artifact row");
		assertEquals(w.priv().sigRow(), sig.get(0).getDownloadConfig().getArtifactUuid());

		ZonedDateTime anonymous = ZonedDateTime.now();
		assertEquals(200, getBytes(url(w, pub, "1", "/download"), null, null).statusCode());
		List<DownloadLogData> anon = awaitTeaDownloads(anonymous, 1);
		assertEquals(1, anon.size());
		assertNull(anon.get(0).getDownloadConfig().getTeaKeyId());
		assertNull(anon.get(0).getDownloadedBy());
		assertEquals(ProgrammaticType.API, anon.get(0).getCreatedType());
		ZonedDateTime garbage = ZonedDateTime.now();
		assertEquals(200, getBytes(url(w, pub, "1", "/download"), BAD, null).statusCode());
		assertNull(awaitTeaDownloads(garbage, 1).get(0).getDownloadConfig().getTeaKeyId(), "a garbage bearer on PUBLIC is anonymous");

		// round 2 (T-4): a valid key of another organization is recorded as anonymous, its own organization's log keeps it
		ZonedDateTime foreignKey = ZonedDateTime.now();
		HttpResponse<byte[]> farDownload = getBytes(url(w, pub, "1", "/download"), w.far(), null);
		assertEquals(200, farDownload.statusCode());
		// row 38 (decision 25, unchanged by the TEA-18 rule): the anonymous headers, so a PUBLIC versioned download is shared-cacheable
		assertEquals("public, max-age=86400, immutable", header(farDownload, "Cache-Control"), "the foreign key's PUBLIC download");
		HttpResponse<String> farMetadata = get(url(w, pub, "1", ""), w.far());
		assertEquals(200, farMetadata.statusCode(), farMetadata.body());
		assertEquals("no-cache", header(farMetadata, "Cache-Control"), "the foreign key's metadata read of pub is the anonymous one");
		List<DownloadLogData> farRows = awaitTeaDownloads(foreignKey, 1);
		assertEquals(1, farRows.size());
		DownloadLogData farRow = farRows.get(0);
		assertNull(farRow.getDownloadConfig().getTeaKeyId(), "no foreign key id in this organization's log");
		assertNull(farRow.getDownloadedBy());
		assertNull(farRow.getLastUpdatedBy());
		assertEquals(ProgrammaticType.API, farRow.getCreatedType());
		assertNotNull(farRow.getIpAddress());
		DownloadLogData anonRow = anon.get(0);
		assertEquals(anonRow.getSubjectType(), farRow.getSubjectType());
		assertEquals(anonRow.getSubjectUuid(), farRow.getSubjectUuid());
		DownloadConfig fc = farRow.getDownloadConfig();
		DownloadConfig ac = anonRow.getDownloadConfig();
		assertEquals(ac.getTeaPublication(), fc.getTeaPublication());
		assertEquals(ac.getTeaCollectionVersion(), fc.getTeaCollectionVersion());
		assertEquals(ac.getTeaArtifactUuid(), fc.getTeaArtifactUuid());
		assertEquals(ac.getTeaArtifactVersion(), fc.getTeaArtifactVersion());
		assertEquals(ac.getTeaObject(), fc.getTeaObject());
		assertEquals(ac.getMediaType(), fc.getMediaType());
		assertEquals(ac.getArtifactUuid(), fc.getArtifactUuid());
		assertEquals(ac.getArtifactVersion(), fc.getArtifactVersion());
		ZonedDateTime foreignPrivate = ZonedDateTime.now();
		// TEA-18 (decision 21, row 38): a valid token of another organization is anonymous here, so PRIVATE is the challenge
		assertNoStore401(get(url(w, priv, "1", "/download"), w.far()), false, "the foreign key on PRIVATE");
		assertEquals(List.of(), awaitTeaDownloads(foreignPrivate, 1), "no row for the refusal");
		ZonedDateTime home = ZonedDateTime.now();
		Art fagg = w.foreignAgg();
		assertEquals(200, getBytes(w.foreignBase() + "/artifact/" + fagg.tea() + "/1/download?mediaType=" + enc(fagg.mediaType()),
				w.far(), null).statusCode());
		List<DownloadLogData> own = awaitTeaDownloads(w.foreignOrg(), home, 1);
		assertEquals(1, own.size(), "the key's own organization logs it");
		assertEquals(w.farKeyId(), own.get(0).getDownloadConfig().getTeaKeyId());
		assertEquals(w.farKey(), own.get(0).getDownloadedBy());
	}
	// round 6 row 42 (decision 22, F-3): a published signature whose row version is not an integer
	@Test
	void aSignatureRowsVersionReachesTheLogThroughLogSafe() throws Exception {
		ArtWorld w = artifactWorld();
		String t = tag();
		byte[] licBytes = ("row 42 license " + t).getBytes(StandardCharsets.UTF_8);
		UUID lic = seedFile(org, "row42-" + t + ".txt", licBytes, ArtifactType.LICENSE, LIC_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_256));
		byte[] sigBytes = ("row 42 signature " + t).getBytes(StandardCharsets.UTF_8);
		UUID sig = seedFile(org, "row42-" + t + ".sig", sigBytes, ArtifactType.SIGNATURE, SIG_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_256));
		Artifact sigRow = sharedArtifactService.getArtifact(sig).orElseThrow();
		ArtifactData sd = ArtifactData.dataFromRecord(sigRow);
		sd.setVersion("1\n2");
		sharedArtifactService.saveArtifact(sigRow, sd, WU);
		attachChild(lic, sig);
		releaseService.addArtifact(lic, w.pub().release(), WU);
		publish(w.pub().release(), TeaPublishMode.REPUBLISH);
		TeaPublishedEntry e = teaPublicationService.getLatestCollection(w.pub().head()).orElseThrow().getEntries().stream()
				.filter(x -> lic.equals(x.artifact())).findFirst().orElseThrow();
		assertEquals(sig, e.signatureArtifact());
		assertNull(e.signatureRevision(), "a non-integer version is published without a revision");
		Art a = new Art(lic, e.teaUuid(), e.revision(), e.mediaType(), licBytes);
		String rev = String.valueOf(a.revision());
		ZonedDateTime since = ZonedDateTime.now();
		try (TeaLogCapture controller = TeaLogCapture.of(ArtifactApiController.class, Level.DEBUG)) {
			HttpResponse<byte[]> r = getBytes(url(w, a, rev, "/signature/download"), null, null);
			assertEquals(200, r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
			assertArrayEquals(sigBytes, r.body(), "the live row's bytes");
			List<DownloadLogData> rows = awaitTeaDownloads(since, 1);
			assertEquals(1, rows.size());
			assertEquals(sig, rows.get(0).getDownloadConfig().getArtifactUuid());
			assertNull(rows.get(0).getDownloadConfig().getArtifactVersion());
			assertEquals(1, controller.aboveDebug().size(), controller.aboveDebug().toString());
			String line = controller.aboveDebug().get(0);
			assertTrue(line.startsWith("ERROR: ") && line.contains("1?2") && !line.contains("\n") && !line.contains("\r"), line);
			assertEquals(0, controller.withThrowable());
		}
		assertEquals(200, get(url(w, a, rev, ""), null).statusCode(), "the metadata of the entry");
	}

	/** The ETag of each of the four downloads of {@code a} for {@code bearer}, by url; each a 200. */
	private Map<String, String> tagsOf(ArtWorld w, Art a, String bearer) throws Exception {
		Map<String, String> tags = new LinkedHashMap<>();
		for (String rev : List.of("1", "latest")) {
			for (String suffix : List.of("/download", "/signature/download")) {
				String u = url(w, a, rev, suffix);
				HttpResponse<byte[]> ok = getBytes(u, bearer, null);
				assertEquals(200, ok.statusCode(), u);
				tags.put(u, header(ok, "ETag"));
			}
		}
		return tags;
	}

	private static void assertNoValidator(HttpResponse<?> r, String what) {
		assertNull(header(r, "ETag"), what + ": no ETag");
		assertNull(header(r, "Repr-Digest"), what + ": no Repr-Digest");
	}

	/**
	 * Round 7 row 43 (F-5): the access decision comes before the validator, so a conditional request for a
	 * revision the caller is no longer served is refused, never 304. The manual hide (TeaPublicationDataFetcher)
	 * and TEA-7's TEA_HIDE (TeaOutputEventService) both go through TeaPublicationService.hide, so this one case
	 * covers both routes; casc is concealed through the hidden product (the cascade), pub by its own release.
	 */
	@Test
	void aConditionalRequestAfterAHideOrWithoutAGrantIsRefused() throws Exception {
		ArtWorld w = artifactWorld();
		ZonedDateTime quiet = ZonedDateTime.now();
		Map<String, Map<String, String>> tags = new LinkedHashMap<>();
		tags.put(w.cover(), tagsOf(w, w.casc().lic(), w.cover()));
		Map<String, String> anonymous = tagsOf(w, w.pub().lic(), null);
		String privUrl = url(w, w.priv().lic(), "1", "/download");
		HttpResponse<byte[]> privOk = getBytes(privUrl, w.cover(), null);
		assertEquals(200, privOk.statusCode());
		String privTag = header(privOk, "ETag");
		assertEquals(9, awaitTeaDownloads(quiet, 9).size(), "the nine 200s land before the quiet window");
		ZonedDateTime since = ZonedDateTime.now();

		teaPublicationService.hide(w.prodRelease(), null, WU);
		teaPublicationService.hide(w.pub().release(), null, WU);
		for (Map.Entry<String, String> c : tags.get(w.cover()).entrySet()) assertHiddenRefusal(c.getKey(), w.cover(), c.getValue());
		for (Map.Entry<String, String> c : anonymous.entrySet()) assertHiddenRefusal(c.getKey(), null, c.getValue());

		// PRIVATE, served to cover: the tag is the challenge anonymously and OBJECT_UNKNOWN for other
		Map<String, String> inm = Map.of("If-None-Match", privTag);
		HttpResponse<String> anon = get(privUrl, null, inm);
		assertNoStore401(anon, false, "priv anonymous with a validator");
		assertNoValidator(anon, "priv anonymous");
		HttpResponse<byte[]> anonHead = head(privUrl, null, inm);
		assertEquals(401, anonHead.statusCode());
		assertEquals("Bearer realm=\"tea\"", header(anonHead, "WWW-Authenticate"));
		assertEquals("no-store", header(anonHead, "Cache-Control"));
		assertEquals(0, anonHead.body().length);
		assertNoValidator(anonHead, "priv anonymous HEAD");
		HttpResponse<String> other = get(privUrl, w.other(), inm);
		assertObjectUnknown(other, "priv for other with a validator");
		assertNoValidator(other, "priv for other");
		HttpResponse<byte[]> otherHead = head(privUrl, w.other(), inm);
		assertEquals(404, otherHead.statusCode());
		assertEquals("no-store", header(otherHead, "Cache-Control"));
		assertEquals(0, otherHead.body().length);
		assertNoValidator(otherHead, "priv for other HEAD");
		assertEquals(List.of(), awaitTeaDownloads(since, 1), "no row for a refusal");

		// republished: the same validators are 304 again, so the case refuses no validator on a served revision
		publish(w.prodRelease(), TeaPublishMode.PUBLISH_OR_REPUBLISH);
		publish(w.pub().release(), TeaPublishMode.PUBLISH_OR_REPUBLISH);
		for (Map.Entry<String, String> c : tags.get(w.cover()).entrySet()) assertNotModifiedAgain(c.getKey(), w.cover(), c.getValue());
		for (Map.Entry<String, String> c : anonymous.entrySet()) assertNotModifiedAgain(c.getKey(), null, c.getValue());
		assertNotModifiedAgain(privUrl, w.cover(), privTag);
		assertEquals(List.of(), awaitTeaDownloads(since, 1), "no row for a 304 either");
	}

	private void assertHiddenRefusal(String url, String bearer, String tag) throws Exception {
		String what = url + (null == bearer ? " anonymous" : " cover") + " after the hide";
		HttpResponse<String> r = get(url, bearer, Map.of("If-None-Match", tag));
		assertObjectUnknown(r, what);
		assertNoValidator(r, what);
		HttpResponse<byte[]> h = head(url, bearer, Map.of("If-None-Match", tag));
		assertEquals(404, h.statusCode(), what + " HEAD");
		assertEquals("no-store", header(h, "Cache-Control"), what + " HEAD");
		assertEquals(0, h.body().length, what + " HEAD");
		assertNoValidator(h, what + " HEAD");
	}

	private void assertNotModifiedAgain(String url, String bearer, String tag) throws Exception {
		HttpResponse<byte[]> r = getBytes(url, bearer, Map.of("If-None-Match", tag));
		assertEquals(304, r.statusCode(), url);
		assertEquals(tag, header(r, "ETag"), url);
	}
}

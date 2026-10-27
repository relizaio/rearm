/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.DownloadLogData;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.tea.TeaArtifactSurfaceService;
import io.reliza.service.tea.TeaLogCapture;
import io.reliza.ws.tea.ArtifactApiController;
import io.reliza.ws.tea.TeaArtifactResponses;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-10, design 4.3 cases 10, 11, 12 and 20: the metadata is the frozen object of the stored
 * document; a download is the stored bytes with every header the spec names; a revision's bytes and
 * object survive a re-publish; the lookup reads the organization's collection rows. Round 6 rows 39 and
 * 41: a mistyped declared digest is no validator, and the Content-Type is the parsed media type's rendering.
 */
public class TeaSurfaceArtifactIntegrationTest extends TeaSurfaceArtifactIntegrationTestBase {

	private JsonNode storedElement(UUID head, Art a) {
		JsonNode doc = Utils.OM.readTree(teaPublicationService.getLatestCollection(head).orElseThrow().getDocument());
		for (JsonNode n : doc.get("artifacts")) {
			if (a.tea().toString().equals(n.get("uuid").asString()) && n.get("version").asInt() == a.revision()) return n;
		}
		throw new AssertionError("no element for " + a.tea());
	}

	// 10
	@Test
	void theMetadataIsTheFrozenObject() throws Exception {
		ArtWorld w = artifactWorld();
		for (Art a : List.of(w.pub().agg(), w.pub().bom(), w.pub().lic(), w.pub().nosig(), w.pub().s512())) {
			for (String rev : List.of("latest", String.valueOf(a.revision()))) {
				String what = a.mediaType() + " " + rev;
				HttpResponse<String> r = get(url(w, a, rev, ""), null);
				assertEquals(200, r.statusCode(), what + ": " + r.body());
				assertConforms(r.body(), "artifact");
				assertEquals(storedElement(w.pub().head(), a), Utils.OM.readTree(r.body()), what);
				assertServedHeaders(r, false, what);
				assertNull(header(r, "ETag"), what + ": no ETag on metadata");
				assertServedHeaders(get(url(w, a, rev, ""), w.cover()), true, what + " keyed");
			}
		}
		Art lic = w.pub().lic();
		String latestBefore = get(url(w, lic, "latest", ""), null).body();
		String versionedBefore = get(url(w, lic, String.valueOf(lic.revision()), ""), null).body();
		Artifact row = sharedArtifactService.getArtifact(lic.row()).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(row);
		ad.setDisplayIdentifier("renamed-" + tag());
		List<TagRecord> tags = new ArrayList<>(ad.getTags());
		tags.add(new TagRecord("team", "renamed", Removable.YES));
		ad.setTags(tags);
		sharedArtifactService.saveArtifact(row, ad, WU);
		assertEquals(latestBefore, get(url(w, lic, "latest", ""), null).body(), "the live row is not read");
		assertEquals(versionedBefore, get(url(w, lic, String.valueOf(lic.revision()), ""), null).body());

		HttpResponse<String> abc = get(w.base() + "/artifact/" + lic.tea() + "/abc", null);
		assertTeaError(abc, 400, "INVALID_REQUEST", "abc");
		for (String rev : List.of("0", "-1", String.valueOf(lic.revision() + 7))) {
			assertObjectUnknown(get(w.base() + "/artifact/" + lic.tea() + "/" + rev, null), "revision " + rev);
		}
	}

	// 11
	@Test
	void aDownloadCarriesEveryHeaderNamed() throws Exception {
		ArtWorld w = artifactWorld();
		for (Art a : List.of(w.pub().agg(), w.pub().bom(), w.pub().lic(), w.pub().nosig(), w.pub().s512())) {
			String name = Utils.OM.readTree(get(url(w, a, "latest", ""), null).body()).get("name").asString();
			HttpResponse<byte[]> v = getBytes(url(w, a, String.valueOf(a.revision()), "/download"), null, null);
			assertDownload(v, a.bytes(), a.mediaType(), contentLocation(w, a, false), "public, max-age=86400, immutable", a.mediaType());
			// task TEA-28, D10: the aggregate's frozen name is its bare profile name, served as <name>.cdx.json
			String fileName = a == w.pub().agg() ? name + ".cdx.json" : name;
			assertEquals("attachment; filename=\"" + fileName + "\"", header(v, "Content-Disposition"), a.mediaType());
			HttpResponse<byte[]> l = getBytes(url(w, a, "latest", "/download"), null, null);
			assertDownload(l, a.bytes(), a.mediaType(), contentLocation(w, a, false), "no-cache", a.mediaType() + " latest");
		}
		for (Art a : List.of(w.priv().agg(), w.priv().lic(), w.priv().s512())) {
			for (String rev : List.of("latest", String.valueOf(a.revision()))) {
				assertDownload(getBytes(url(w, a, rev, "/download"), w.cover(), null), a.bytes(), a.mediaType(),
						contentLocation(w, a, false), "private, no-cache", "priv " + a.mediaType() + " " + rev);
			}
		}
		Art s512 = w.pub().s512();
		JsonNode checksums = Utils.OM.readTree(get(url(w, s512, "latest", ""), null).body()).get("formats").get(0).get("checksums");
		assertEquals(1, checksums.size());
		assertEquals("SHA-512", checksums.get(0).get("algType").asString());
		assertEquals("\"" + s512.sha256() + "\"", header(getBytes(url(w, s512, "latest", "/download"), null, null), "ETag"),
				"computed from the bytes when the format publishes no SHA-256");

		// round 6 row 39 (decision 23): a declared SHA-256 of 63 characters is no validator; the bytes' own digest is served
		String t = tag();
		byte[] typoBytes = ("declared typo " + t).getBytes(StandardCharsets.UTF_8);
		UUID typo = seedFile(org, "typo-" + t + ".txt", typoBytes, ArtifactType.LICENSE, LIC_MEDIA_TYPE, Set.of());
		String declared63 = Utils.bytesToHexSha256(typoBytes).substring(0, 63);
		Artifact row = sharedArtifactService.getArtifact(typo).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(row);
		Set<DigestRecord> digests = new LinkedHashSet<>(ad.getDigestRecords());
		digests.add(new DigestRecord(DigestAlgorithm.SHA_256, declared63, DigestScope.ORIGINAL_FILE));
		ad.setDigestRecords(digests);
		sharedArtifactService.saveArtifact(row, ad, WU);
		retained.put(declared63, typoBytes); // the rig's raw download reads by the declared digest
		Art ta = publishedInPub(w, typo, typoBytes, LIC_MEDIA_TYPE);
		String rev = String.valueOf(ta.revision());
		try (TeaLogCapture service = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			HttpResponse<byte[]> got = getBytes(url(w, ta, rev, "/download"), null, null);
			assertDownload(got, typoBytes, LIC_MEDIA_TYPE, contentLocation(w, ta, false), "public, max-age=86400, immutable",
					"a mistyped declared digest: the ETag and Repr-Digest of the bytes");
			assertEquals(1, service.aboveDebug().size(), service.aboveDebug().toString());
			String line = service.aboveDebug().get(0);
			assertTrue(line.startsWith("ERROR: ") && line.contains(ta.tea().toString()) && line.contains("63 characters")
					&& !line.contains(declared63), line);
			assertEquals(0, service.withThrowable());
		}
		JsonNode declared = Utils.OM.readTree(get(url(w, ta, rev, ""), null).body()).get("formats").get(0).get("checksums").get(0);
		assertEquals(declared63, declared.get("algValue").asString(), "the metadata keeps the declared string as frozen");
	}

	/** {@code row} added to pub's release and republished: its entry as an Art, with {@code mediaType} as the selector. */
	private Art publishedInPub(ArtWorld w, UUID row, byte[] bytes, String mediaType) throws Exception {
		releaseService.addArtifact(row, w.pub().release(), WU);
		publish(w.pub().release(), TeaPublishMode.REPUBLISH);
		TeaPublishedEntry e = teaPublicationService.getLatestCollection(w.pub().head()).orElseThrow().getEntries().stream()
				.filter(x -> row.equals(x.artifact())).findFirst().orElseThrow();
		return new Art(row, e.teaUuid(), e.revision(), mediaType, bytes);
	}

	// round 6 row 41 (decision 24): a stored media type with CR LF after its separator is served as its rendering
	@Test
	void aStoredMediaTypeThatParsesIsServedAsItsRendering() throws Exception {
		ArtWorld w = artifactWorld();
		String stored = "text/plain;\r\ncharset";
		String t = tag();
		byte[] bytes = ("crlf media type " + t).getBytes(StandardCharsets.UTF_8);
		UUID row = seedFile(org, "crlf-" + t + ".txt", bytes, ArtifactType.LICENSE, stored, Set.of(DigestAlgorithm.SHA_256));
		Art a = publishedInPub(w, row, bytes, LIC_MEDIA_TYPE);
		String rev = String.valueOf(a.revision());
		String location = w.apiBase() + "/v1.0.0/artifact/" + a.tea() + "/" + rev + "/download?mediaType=text%2Fplain%3B%0D%0Acharset";
		ZonedDateTime since = ZonedDateTime.now();
		try (TeaLogCapture service = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG);
				TeaLogCapture responses = TeaLogCapture.of(TeaArtifactResponses.class, Level.DEBUG);
				TeaLogCapture controller = TeaLogCapture.of(ArtifactApiController.class, Level.DEBUG)) {
			String u = url(w, a, rev, "/download");
			assertDownload(getBytes(u, null, null), bytes, "text/plain", location, "public, max-age=86400, immutable", "GET");
			HttpResponse<byte[]> head = head(u, null, null);
			assertEquals(200, head.statusCode());
			assertEquals(0, head.body().length);
			assertHeaders(head, bytes, "text/plain", location, "public, max-age=86400, immutable", "HEAD");
			HttpResponse<String> meta = get(url(w, a, rev, ""), null);
			assertEquals(200, meta.statusCode(), meta.body());
			assertEquals(stored, Utils.OM.readTree(meta.body()).get("formats").get(0).get("mediaType").asString(), "the stored spelling");
			List<DownloadLogData> rows = awaitTeaDownloads(since, 1);
			assertEquals(1, rows.size(), "the GET only");
			assertEquals(stored, rows.get(0).getDownloadConfig().getMediaType());
			for (TeaLogCapture c : List.of(service, responses, controller)) {
				assertEquals(List.of(), c.aboveDebug());
				assertEquals(0, c.withThrowable());
			}
		}
	}

	// 12
	@Test
	void aRevisionIsByteStableAcrossARepublish() throws Exception {
		ArtWorld w = artifactWorld();
		Art agg = w.pub().agg();
		assertEquals(1, agg.revision());
		HttpResponse<byte[]> first = getBytes(url(w, agg, "1", "/download"), null, null);
		HttpResponse<byte[]> again = getBytes(url(w, agg, "1", "/download"), null, null);
		assertArrayEquals(first.body(), again.body());
		assertEquals(header(first, "ETag"), header(again, "ETag"));
		String objectBefore = get(url(w, agg, "1", ""), null).body();
		assertEquals(1, teaPublicationService.listCollectionsNamingArtifact(org.getUuid(), w.pub().lic().tea()).size());

		mergedPurls.get(w.pub().release()).add("pkg:generic/tea10-added@1.0.0");
		publish(w.pub().release(), TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v2 = teaPublicationService.getLatestCollection(w.pub().head()).orElseThrow();
		assertEquals(2, v2.getVersion());
		HttpResponse<String> latest = get(url(w, agg, "latest", ""), null);
		assertEquals(agg.tea().toString(), Utils.OM.readTree(latest.body()).get("uuid").asString(), "the TEA id is unchanged");
		assertEquals(2, Utils.OM.readTree(latest.body()).get("version").asInt());
		HttpResponse<byte[]> second = getBytes(url(w, agg, "2", "/download"), null, null);
		assertEquals(200, second.statusCode());
		assertNotEquals(new String(first.body(), StandardCharsets.UTF_8), new String(second.body(), StandardCharsets.UTF_8));
		assertNotEquals(header(first, "ETag"), header(second, "ETag"));
		assertEquals("\"" + Utils.bytesToHexSha256(second.body()) + "\"", header(second, "ETag"));
		HttpResponse<byte[]> firstAfter = getBytes(url(w, agg, "1", "/download"), null, null);
		assertArrayEquals(first.body(), firstAfter.body(), "revision 1 serves the bytes it was published with");
		assertEquals(header(first, "ETag"), header(firstAfter, "ETag"));
		assertEquals(objectBefore, get(url(w, agg, "1", ""), null).body(), "revision 1's object is unchanged");
		String c1 = get(w.base() + "/componentRelease/" + w.pub().head() + "/collection/1", null).body();
		assertTrue(c1.contains("\"uuid\":\"" + agg.tea() + "\",\"version\":1"), c1);

		// 20: the lookup names lic in both versions, oldest first, and nothing of another organization
		List<TeaPublishedCollectionData> naming = teaPublicationService.listCollectionsNamingArtifact(org.getUuid(), w.pub().lic().tea());
		assertEquals(List.of(1, 2), naming.stream().map(TeaPublishedCollectionData::getVersion).toList());
		assertTrue(naming.stream().allMatch(c -> w.pub().head().equals(c.getUuid())));
		assertEquals(List.of(), teaPublicationService.listCollectionsNamingArtifact(org.getUuid(), w.foreignAgg().tea()));
		assertEquals(List.of(), teaPublicationService.listCollectionsNamingArtifact(org.getUuid(), UUID.randomUUID()));
	}
}

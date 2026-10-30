/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-6, design 4.8 cases 27, 28 and 35: collections are the stored bytes with their sha256
 * as a strong ETag and conditional GET; the release objects conform to the 1.0.0 schemas and embed
 * the stored latest document; a published head without collection rows is never a 500.
 */
public class TeaSurfaceCollectionsIntegrationTest extends TeaSurfaceIntegrationTestBase {

	private TeaPublishedCollectionData stored(java.util.UUID head, int version) {
		return teaPublicationService.getCollection(head, version).orElseThrow();
	}

	// 28
	@Test
	void collectionsAreTheStoredBytesWithAStrongEtagAndConditionalGet() throws Exception {
		TeaWorld w = world();
		String path = w.base() + "/componentRelease/" + w.pub().head();
		TeaPublishedCollectionData v1 = stored(w.pub().head(), 1);
		HttpResponse<byte[]> one = getBytes(path + "/collection/1", null, null);
		assertEquals(200, one.statusCode());
		assertArrayEquals(v1.getDocument().getBytes(StandardCharsets.UTF_8), one.body(), "the stored bytes, verbatim");
		String etag = "\"" + v1.getDocumentSha256() + "\"";
		assertEquals(etag, one.headers().firstValue("ETag").orElse(null));
		assertServedHeaders(one, false, "collection 1");
		assertConforms(new String(one.body(), StandardCharsets.UTF_8), "collection");

		for (String inm : List.of(etag, "W/" + etag, "*", "\"other\", " + etag)) {
			HttpResponse<String> nm = get(path + "/collection/1", null, Map.of("If-None-Match", inm));
			assertEquals(304, nm.statusCode(), inm);
			assertEquals(etag, nm.headers().firstValue("ETag").orElse(null), inm);
			assertEquals("no-cache", nm.headers().firstValue("Cache-Control").orElse(null), inm);
			assertEquals("Authorization", nm.headers().firstValue("Vary").orElse(null), inm);
			assertTrue(nm.body().isEmpty(), inm);
		}
		assertEquals(200, get(path + "/collection/1", null, Map.of("If-None-Match", "\"another\"")).statusCode());

		HttpResponse<String> latest = get(path + "/collection/latest", null);
		assertEquals(v1.getDocument(), latest.body());
		assertObjectUnknown(get(path + "/collection/2", null), "version 2 before it exists");

		// a changed re-publish cuts version 2
		mergedPurls.get(w.pub().release()).add("pkg:generic/tea6-added@1.0.0");
		publish(w.pub().release(), TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v2 = stored(w.pub().head(), 2);
		HttpResponse<String> latest2 = get(path + "/collection/latest", null);
		assertEquals(v2.getDocument(), latest2.body());
		assertEquals("\"" + v2.getDocumentSha256() + "\"", latest2.headers().firstValue("ETag").orElse(null));
		assertNotEquals(etag, latest2.headers().firstValue("ETag").orElse(null));
		assertEquals(v1.getDocument(), get(path + "/collection/1", null).body(), "version 1 is immutable");

		for (String v : List.of("0", "-1", "3")) assertObjectUnknown(get(path + "/collection/" + v, null), "version " + v);
		HttpResponse<String> abc = get(path + "/collection/abc", null);
		assertEquals(400, abc.statusCode(), abc.body());
		assertEquals("INVALID_REQUEST", json(abc).get("error"));
		assertConforms(abc.body(), "error-response");

		HttpResponse<String> all = get(path + "/collections", null);
		assertEquals(200, all.statusCode(), all.body());
		assertEquals("{\"hasNext\":false,\"results\":[" + v1.getDocument() + "," + v2.getDocument() + "]}", all.body());
		assertConforms(all.body(), "paginated-collection-response");
		HttpResponse<String> desc = get(path + "/collections?sortOrder=desc", null);
		assertEquals("{\"hasNext\":false,\"results\":[" + v2.getDocument() + "," + v1.getDocument() + "]}", desc.body());
		assertEquals(400, get(path + "/collections?sortField=createdDate", null).statusCode());
	}

	// 27
	@Test
	void releaseObjectsConformAndEmbedTheStoredLatestCollection() throws Exception {
		TeaWorld w = world();
		HttpResponse<String> prod = get(w.base() + "/productRelease/" + w.prod().head(), w.cover());
		assertEquals(200, prod.statusCode(), prod.body());
		assertConforms(prod.body(), "product-release-with-collection");
		JsonNode node = Utils.OM.readTree(prod.body());
		assertEquals("PRODUCT_RELEASE", node.get("latestCollection").get("belongsTo").asString());
		JsonNode storedDoc = Utils.OM.readTree(teaPublicationService.getLatestCollection(w.prod().head()).orElseThrow().getDocument());
		assertEquals(storedDoc, node.get("latestCollection"));
		assertEquals(w.prod().teaComponent().toString(), node.get("product").asString());
		assertFalse(node.has("preRelease"), "preRelease is omitted");
		assertTrue(node.get("createdDate").asString().matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"), prod.body());
		assertEquals(w.prod().purl(), node.get("identifiers").get(0).get("idValue").asString());
		assertEquals(2, node.get("components").size(), prod.body());

		HttpResponse<String> comp = get(w.base() + "/componentRelease/" + w.pub().head(), null);
		assertEquals(200, comp.statusCode(), comp.body());
		assertConforms(comp.body(), "component-release-with-collection");
		JsonNode c = Utils.OM.readTree(comp.body());
		assertEquals(w.pub().teaComponent().toString(), c.get("component").asString());
		assertFalse(c.has("distributions"), "no outbound deliverables, no distributions");
		assertFalse(comp.body().contains(":null"), "no nulls on the wire: " + comp.body());

		HttpResponse<String> cle = get(w.base() + "/componentRelease/" + w.pub().head() + "/cle", null);
		assertConforms(cle.body(), "cle");
	}

	// 35
	@Test
	void aPublishedHeadWithoutCollectionRowsIsUnknownNotA500() throws Exception {
		TeaWorld w = world();
		int deleted = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s ->
				jdbc.update("DELETE FROM rearm.tea_collections WHERE uuid = ?", w.pub().head()));
		assertTrue(deleted > 0);
		String base = w.base() + "/componentRelease/" + w.pub().head();
		assertObjectUnknown(get(base, null), "with-collection object");
		assertObjectUnknown(get(base + "/collection/latest", null), "latest");
		HttpResponse<String> notUuid = get(w.base() + "/product/not-a-uuid", null);
		assertEquals(400, notUuid.statusCode(), notUuid.body());
		assertEquals("INVALID_REQUEST", json(notUuid).get("error"));
		assertObjectUnknown(get(root() + "/tea/not-a-uuid/v1.0.0/products", null), "malformed base");
		assertObjectUnknown(get(root() + "/tea/" + java.util.UUID.randomUUID() + "/v1.0.0/products", null), "unknown base");
	}

	@org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
	@org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager txManager;
}

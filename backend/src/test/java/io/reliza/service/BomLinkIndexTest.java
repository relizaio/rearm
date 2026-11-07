/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** FDAW-7 row 23 and security F-7: the purl index of a pinned document and the BOM-Link texts. */
class BomLinkIndexTest {

	static final String R = "pkg:generic/root@1";
	static final String P1 = "pkg:npm/left-pad@1.3.0";
	static final String P2 = "pkg:deb/debian/zlib@1.3?arch=amd64&distro=debian-13";
	static final String P3 = "pkg:maven/org.acme/core@2.0";
	static final String P4 = "pkg:npm/parent@4";
	static final String P5 = "pkg:npm/nested@5";
	static final String P7 = "pkg:npm/deep@7";
	static final String P8 = "pkg:npm/no-ref@8";
	static final String P9 = "pkg:npm/twice@9";
	static final String UUID_REF = "0e9f6c47-2d5c-4b9e-8a0f-36f1b2c4d5e6";

	static ObjectNode component(String bomRef, String purl) {
		ObjectNode c = Utils.OM.createObjectNode();
		c.put("type", "library");
		if (null != bomRef) c.put("bom-ref", bomRef);
		if (null != purl) c.put("purl", purl);
		return c;
	}

	static JsonNode fixture() {
		ObjectNode d = Utils.OM.createObjectNode();
		d.putObject("metadata").set("component", component("root", R));
		ArrayNode cs = d.putArray("components");
		cs.add(component(P1, P1));
		cs.add(component("pkg:deb/debian/zlib@1.3?package-id=abc", P2));
		cs.add(component(UUID_REF, P3));
		ObjectNode parent = component(P4, P4);
		ArrayNode nested = parent.putArray("components");
		nested.add(component("n5", P5));
		ObjectNode noPurl = component("n6", null);
		noPurl.putArray("components").add(component("n7", P7));
		nested.add(noPurl);
		cs.add(parent);
		cs.add(component(null, P8));
		cs.add(component("x", P9));
		cs.add(component("y", P9));
		cs.add(component("x", P9));
		cs.add(component("bad", "not a purl"));
		d.putArray("services").add(component("svc", "pkg:generic/service@1"));
		return d;
	}

	@Test
	void everyNodeWithABomRefAndAPurlIsIndexedByItsPurlIdentityInDocumentOrder() {
		BomLinkIndex idx = BomLinkIndex.of(fixture());
		assertEquals(List.of("root"), idx.bomRefsOf(R));
		assertEquals(List.of(P1), idx.bomRefsOf(P1));
		assertEquals(List.of("pkg:deb/debian/zlib@1.3?package-id=abc"), idx.bomRefsOf("pkg:deb/debian/zlib@1.3"));
		assertEquals(List.of("pkg:deb/debian/zlib@1.3?package-id=abc"),
				idx.bomRefsOf("pkg:deb/debian/zlib@1.3?arch=arm64"), "qualifiers do not take part");
		assertEquals(List.of(UUID_REF), idx.bomRefsOf(P3));
		assertEquals(List.of(P4), idx.bomRefsOf(P4));
		assertEquals(List.of("n5"), idx.bomRefsOf(P5));
		assertEquals(List.of("n7"), idx.bomRefsOf(P7), "a purl-less node's children are still walked");
		assertEquals(List.of(), idx.bomRefsOf("pkg:npm/unknown@6"));
		assertEquals(List.of(), idx.bomRefsOf(P8), "no bom-ref, nothing indexed");
		assertEquals(List.of("x", "y"), idx.bomRefsOf(P9), "document order, a repeated bom-ref once");
		assertEquals(List.of("bad"), idx.bomRefsOf("not a purl"), "an unparsable purl is keyed by its raw text");
		assertEquals(List.of(), idx.bomRefsOf("pkg:generic/service@1"), "services[] are not indexed");
		assertEquals(List.of(), idx.bomRefsOf(null));
		assertEquals(List.of(), idx.bomRefsOf(" "));
	}

	@Test
	void anEmptyOrMissingDocumentAnswersNothing() {
		assertEquals(List.of(), BomLinkIndex.of(null).bomRefsOf(P1));
		assertEquals(List.of(), BomLinkIndex.of(Utils.OM.createObjectNode()).bomRefsOf(P1));
		assertEquals(List.of(), BomLinkIndex.of(Utils.OM.createArrayNode()).bomRefsOf(P1));
	}

	@Test
	void aDeeplyNestedDocumentIsWalkedWithoutRecursion() {
		ObjectNode d = Utils.OM.createObjectNode();
		ObjectNode node = d.putArray("components").addObject();
		for (int i = 0; i < 20_000; i++) node = node.putArray("components").addObject();
		node.put("bom-ref", "leaf").put("purl", P1);
		assertEquals(List.of("leaf"), BomLinkIndex.of(d).bomRefsOf(P1));
	}

	@Test
	void theLinksPercentEncodeTheBomRefAsAnRfc3986Fragment() {
		UUID serial = UUID.fromString("3e671687-395b-41f5-a30f-a58921a69b79");
		assertEquals("urn:cdx:3e671687-395b-41f5-a30f-a58921a69b79/2", BomLinkIndex.bomLink(serial, 2));
		assertEquals("urn:cdx:3e671687-395b-41f5-a30f-a58921a69b79/2#pkg:npm/left-pad@1.3.0",
				BomLinkIndex.elementLink(serial, 2, P1), "a purl bom-ref keeps : / @");
		assertEquals("urn:cdx:3e671687-395b-41f5-a30f-a58921a69b79/1#pkg:deb/debian/zlib@1.3?package-id=abc",
				BomLinkIndex.elementLink(serial, 1, "pkg:deb/debian/zlib@1.3?package-id=abc"), "? and = are fragment-safe");
		assertEquals("a%23b%20c%25d", BomLinkIndex.encodeFragment("a#b c%d"), "#, space and % are encoded");
		assertEquals("caf%C3%A9", BomLinkIndex.encodeFragment("caf\u00e9"), "UTF-8 octets, upper-case hex");
		assertEquals("-._~!$&'()*+,;=:@/?", BomLinkIndex.encodeFragment("-._~!$&'()*+,;=:@/?"));
		assertEquals("%5B%5D%7B%7D%22%3C%3E%5C%5E%60%7C", BomLinkIndex.encodeFragment("[]{}\"<>\\^`|"));
		assertTrue(BomLinkIndex.MAX_LINKS_PER_FINDING == 100);
	}
}

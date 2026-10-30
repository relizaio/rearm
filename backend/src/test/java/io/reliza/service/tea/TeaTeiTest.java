/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Task TEA-6, design 4.1 cases 1 and 2: the TEI syntax of the TEA 1.0.0 discovery readme. */
class TeaTeiTest {

	private static final String ID = "47f51950-b0e2-11f1-adc2-1a52914d44b2";

	// 1
	@Test
	void aUuidTeiParsesIntoDomainTypeAndIdentifier() {
		TeaTei t = TeaTei.parse("tei://products.example.com/uuid/" + ID).orElseThrow();
		assertEquals("products.example.com", t.domain());
		assertEquals("uuid", t.type());
		assertEquals(ID, t.identifier());
		assertEquals(UUID.fromString(ID), t.uuidIdentifier().orElseThrow());
		assertNull(t.identifierProblem());
	}

	@Test
	void aPurlTeiCarriesItsPurlBase64UrlEncodedWithoutPadding() {
		String purl = "pkg:maven/org.apache.tomcat/tomcat@11.0.7?type=zip";
		String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(purl.getBytes(StandardCharsets.UTF_8));
		TeaTei t = TeaTei.parse("tei://tomcat.apache.org/purl/" + encoded).orElseThrow();
		assertEquals("purl", t.type());
		assertEquals(purl, t.purlIdentifier().orElseThrow());
		assertNull(t.identifierProblem());
	}

	@Test
	void aHyphenatedLabelAndAnUppercaseDomainParse() {
		assertTrue(TeaTei.parse("tei://my-products.example-vendor.com/uuid/" + ID).isPresent());
		TeaTei upper = TeaTei.parse("tei://Example.com/uuid/" + ID).orElseThrow();
		assertEquals("Example.com", upper.domain(), "the domain is kept as written");
	}

	/** One or more labels: a single label is a DNS name too, and is accepted. */
	@Test
	void aSingleLabelDomainIsAccepted() {
		assertTrue(TeaTei.parse("tei://localhost/uuid/" + ID).isPresent());
	}

	// 2
	@ParameterizedTest
	@ValueSource(strings = {
		"", "products.example.com/uuid/" + ID, "TEI://products.example.com/uuid/" + ID,
		"urn:tei:uuid:demo.rearmhq.com:" + ID, "tei://products.example.com:8443/uuid/" + ID,
		"tei://user@products.example.com/uuid/" + ID, "tei://products.example.com/1uuid/" + ID,
		"tei://products.example.com/Uuid/" + ID, "tei://products.example.com/uuid/",
		"tei://products.example.com/uuid/a/b", "tei://products.example.com/uuid/a%2Fb",
		"tei://products.example.com/uuid/a%2fb", "tei://products.example.com/uuid/" + ID + "/",
		"tei://products.example.com/uuid/" + ID + "?x=1", "tei://products.example.com/uuid/" + ID + "#f",
		"tei://products.example.com/uuid/ " + ID, "tei://products..example.com/uuid/" + ID,
		"tei:///uuid/" + ID, "tei://-bad.example.com/uuid/" + ID })
	void whatBreaksTheSyntaxIsRefusedWithTheRuleNamed(String value) {
		assertTrue(TeaTei.parse(value).isEmpty(), value);
		assertNotNull(TeaTei.problem(value), value);
	}

	@Test
	void aNullTeiIsRefused() {
		assertTrue(TeaTei.parse(null).isEmpty());
	}

	@Test
	void anIdentifierThatDoesNotFitItsTypeIsNamed() {
		assertNotNull(TeaTei.parse("tei://a.b/uuid/not-a-uuid").orElseThrow().identifierProblem());
		assertNotNull(TeaTei.parse("tei://a.b/purl/%%%").orElseThrow().identifierProblem());
		String notPkg = Base64.getUrlEncoder().withoutPadding().encodeToString("maven/x@1".getBytes(StandardCharsets.UTF_8));
		assertNotNull(TeaTei.parse("tei://a.b/purl/" + notPkg).orElseThrow().identifierProblem());
		// pkg:a/b@10 (10 bytes) and pkg:a/b@100 (11 bytes) encode with two and one padding characters; without the
		// padding the same purls are accepted, so the refusal is the padding's alone
		for (String purl : new String[] { "pkg:a/b@10", "pkg:a/b@100" }) {
			byte[] bytes = purl.getBytes(StandardCharsets.UTF_8);
			String padded = Base64.getUrlEncoder().encodeToString(bytes);
			assertTrue(padded.endsWith("="), padded);
			TeaTei paddedTei = TeaTei.parse("tei://a.b/purl/" + padded).orElseThrow();
			assertEquals("a purl TEI carries a Base64URL encoded purl, without padding", paddedTei.identifierProblem(), padded);
			assertTrue(paddedTei.purlIdentifier().isEmpty(), padded);
			TeaTei unpadded = TeaTei.parse("tei://a.b/purl/" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).orElseThrow();
			assertNull(unpadded.identifierProblem(), purl);
			assertEquals(purl, unpadded.purlIdentifier().orElseThrow());
		}
		assertNull(TeaTei.parse("tei://a.b/hash/abc").orElseThrow().identifierProblem(), "other types are not checked");
	}
}

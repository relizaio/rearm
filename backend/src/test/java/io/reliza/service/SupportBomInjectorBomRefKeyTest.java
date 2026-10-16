/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import io.reliza.common.Utils;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.service.SupportBomInjector.Attester;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import io.reliza.service.SupportBomInjector.DeclarationContext;

/**
 * A purl written only in a component's {@code bom-ref} identifies the component for the support
 * disclosure (SCORE-21, SCORE-12 ADR-10): precedence purl field, then purl-shaped bom-ref, then
 * cpe, with the bom-ref canonicalised exactly as the purl field is. Tests T-8 and T-9. Pure, no
 * Spring / DB.
 */
class SupportBomInjectorBomRefKeyTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final String ASSESSED_AT = "2026-08-26T12:00:00Z";
	private static final UUID ATTESTER = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final DeclarationContext CTX = new DeclarationContext("Example Devices Inc.",
			Map.of(ATTESTER, new Attester("Dana Rivera", "administrator")));

	private static JsonNode component(String json) {
		return Utils.OM.readTree(json);
	}

	private static String key(String json) {
		return SupportBomInjector.componentKey(component(json));
	}

	private static Map<String, String> propMap(JsonNode node) {
		Map<String, String> out = new HashMap<>();
		JsonNode props = node.get("properties");
		if (props != null && props.isArray()) {
			props.forEach(p -> out.put(p.get("name").asText(), p.get("value").asText()));
		}
		return out;
	}

	// T-8
	@Test
	void componentKeyUsesBomRefPurlWhenPurlAbsent() {
		assertEquals("pkg:golang/stdlib@v1.26.6",
				key("{\"type\":\"library\",\"name\":\"stdlib\",\"bom-ref\":\"pkg:golang/stdlib@v1.26.6\"}"));
		// A + in the bom-ref keys, and the key keeps the bytes the document has (the facts lookup
		// handles the %2B form of the row).
		assertEquals("pkg:deb/debian/g++@12", key("{\"bom-ref\":\"pkg:deb/debian/g++@12\"}"));
	}

	// T-8
	@Test
	void componentKeyCanonicalisesBomRefLikePurlField() {
		assertEquals("pkg:golang/stdlib@v1.26.6",
				key("{\"bom-ref\":\"pkg:golang/stdlib@v1.26.6?package-id=abc#sub\"}"));
		String deb = "pkg:deb/debian/curl@7.1?arch=amd64&distro=x";
		assertEquals("pkg:deb/debian/curl@7.1?distro=x", key("{\"bom-ref\":\"" + deb + "\"}"),
				"a preserved qualifier stays, the others go, as for the purl field");
		assertEquals(key("{\"purl\":\"" + deb + "\"}"), key("{\"bom-ref\":\"" + deb + "\"}"),
				"the same string keys the same in either field");
	}

	// T-8
	@Test
	void componentKeyIgnoresNonPurlBomRef() {
		List<String> notPurls = List.of("comp-12", "0b4e4a6c-5f0a-4c8e-9d36-1f2a3b4c5d6e",
				"https://x/y", "pkg:", "pkg:nope");
		for (String ref : notPurls) {
			assertNull(key("{\"name\":\"n\",\"bom-ref\":\"" + ref + "\"}"),
					"bom-ref " + ref + " is a label, not a key");
			assertEquals("cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*",
					key("{\"name\":\"n\",\"bom-ref\":\"" + ref
							+ "\",\"cpe\":\"cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*\"}"),
					"bom-ref " + ref + " falls through to the cpe");
		}
	}

	// T-8
	@Test
	void componentKeyPrecedence() {
		assertEquals("pkg:npm/a@1", key("{\"purl\":\"pkg:npm/a@1\",\"bom-ref\":\"pkg:npm/b@2\"}"),
				"the purl field wins over a different purl in the bom-ref");
		assertEquals("pkg:npm/b@2", key("{\"purl\":\"not-a-purl\",\"bom-ref\":\"pkg:npm/b@2\"}"),
				"an unusable purl field falls through to the bom-ref");
		assertEquals("pkg:npm/b@2", key("{\"bom-ref\":\"pkg:npm/b@2\",\"cpe\":\"cpe:2.3:a:v:b:2:*:*:*:*:*:*:*\"}"),
				"a purl-shaped bom-ref wins over the cpe");
		assertEquals("pkg:npm/b@2", key("{\"purl\":\" \",\"bom-ref\":\"pkg:npm/b@2\"}"),
				"a blank purl field falls through to the bom-ref");
	}

	// T-9
	@Test
	void injectAttachesFactsThroughBomRefPurl() {
		// Two nodes with no purl: one whose bom-ref is the purl, one whose bom-ref is the purl a
		// tool decorated. Both key the same row; each claim must target the node's OWN ref.
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "metadata":{"component":{"type":"application","name":"app","bom-ref":"app-root"}},
			 "components":[
			   {"type":"library","name":"stdlib","version":"v1.26.6","bom-ref":"pkg:golang/stdlib@v1.26.6"},
			   {"type":"library","name":"stdlib","version":"v1.26.6",
			    "bom-ref":"pkg:golang/stdlib@v1.26.6?package-id=abc"}]}""");
		assertTrue(SupportBomInjector.collectKeys(bom).contains("pkg:golang/stdlib@v1.26.6"),
				"the bom-ref key reaches the facts lookup");

		SupportData support = new SupportData(LevelOfSupport.ACTIVELY_MAINTAINED, SupportState.ATTESTED,
				SupportParty.THIRD_PARTY, SupportSource.MANUAL, ASSESSED_AT, ATTESTER, null, Map.of());
		SupportBomInjector.inject(bom, Map.of("pkg:golang/stdlib@v1.26.6",
				new ComponentSupportFacts(new SbomComponent(), support)), ASOF, null, CTX);

		List<String> refs = new ArrayList<>();
		for (JsonNode c : bom.get("components")) {
			assertEquals("actively maintained", propMap(c).get("reliza:support:levelOfSupport"),
					"the fact reached the node keyed by its bom-ref: " + c);
			refs.add(c.get("bom-ref").asText());
		}
		assertEquals(List.of("pkg:golang/stdlib@v1.26.6", "pkg:golang/stdlib@v1.26.6?package-id=abc"), refs,
				"the existing bom-refs are kept, none is replaced");
		assertFalse(bom.toString().contains("reliza:bomref:"), "no bom-ref was assigned: " + bom);

		JsonNode claims = bom.get("declarations").get("claims");
		assertNotNull(claims);
		List<String> targets = new ArrayList<>();
		claims.forEach(claim -> targets.add(claim.get("target").asText()));
		assertEquals(refs, targets, "each claim targets the node's own bom-ref");
	}
}

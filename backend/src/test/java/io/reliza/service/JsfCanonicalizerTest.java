/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.resource;
import static io.reliza.service.SbomExportSigningFixtures.resourceText;
import static io.reliza.service.SbomExportSigningFixtures.withoutValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.SbomSigningException;
import io.reliza.exceptions.SbomSigningException.SbomSigningErrorReason;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RFC 8785 canonicalisation of the JSF signing input (SCORE-33 T-3): rearm-cli's JCS vectors, the
 * committed canonical bytes of the signed fixtures, and the I-JSON refusals the Go side makes.
 */
class JsfCanonicalizerTest {

	private static void assertNotCanonicalizable(Runnable r, String what) {
		SbomSigningException e = assertThrows(SbomSigningException.class, r::run, what);
		assertEquals(SbomSigningErrorReason.NOT_CANONICALIZABLE, e.getReason(), what);
	}

	@Test
	void everyJcsVectorCanonicalisesToItsExpectedBytes() throws Exception {
		JsonNode vectors = Utils.OM.readTree(resourceText("jcs-vectors.json"));
		List<String> wrapped = new ArrayList<>();
		for (JsonNode v : vectors) {
			String name = v.get("name").asString();
			String input = v.get("input").asString();
			String expected = v.get("expected").asString();
			if (!input.stripLeading().startsWith("{")) {
				// the library takes an object root only; the root value is compared inside one
				input = "{\"v\":" + input + "}";
				expected = "{\"v\":" + expected + "}";
				wrapped.add(name);
			}
			assertEquals(expected, new String(JsfCanonicalizer.canonicalize(input), StandardCharsets.UTF_8),
					name + " (wrapped as {\"v\":...}: " + wrapped + ")");
		}
		assertEquals(11, vectors.size(), "the vector count changed; refresh from rearm-cli");
		assertEquals(List.of("rfc8785-3.2.2.3-numbers", "every-json-escape", "literals", "integer-2^53+1-rounds",
				"rfc8785-appendix-b-samples"), wrapped, "the five non-object roots");
	}

	@Test
	void signedFixturesMinusValueGiveTheCommittedCanonicalBytes() throws Exception {
		byte[] ed = resource("canonical/signed-ed25519.jcs");
		assertArrayEquals(ed, JsfCanonicalizer.canonicalize(withoutValue(resourceText("signed-ed25519.cdx.json"))));
		assertArrayEquals(ed, JsfCanonicalizer.canonicalize(withoutValue(resourceText("signed-ed25519.pretty.cdx.json"))));
		assertArrayEquals(resource("canonical/signed-es256.jcs"),
				JsfCanonicalizer.canonicalize(withoutValue(resourceText("signed-es256.cdx.json"))));
	}

	@Test
	void canonicalisationIsIdempotent() throws Exception {
		for (String f : List.of("canonical/signed-ed25519.jcs", "canonical/signed-es256.jcs")) {
			assertArrayEquals(resource(f), JsfCanonicalizer.canonicalize(resourceText(f)), f);
		}
	}

	@Test
	void aLoneSurrogateIsRefused() {
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("{\"a\":\"\\ud800\"}"), "escaped high");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("{\"a\":\"\\udc00x\"}"), "escaped low");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("{\"a\":\"\\ud83d\",\"b\":\"\\ude00\"}"),
				"halves in two strings");
		ObjectNode tree = Utils.OM.createObjectNode();
		tree.put("a", "x" + '\uD800' + "y");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize(tree), "raw high in a tree");
		ObjectNode low = Utils.OM.createObjectNode();
		low.put("a", String.valueOf('\uDC00'));
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize(low), "raw low in a tree");

		byte[] pair = JsfCanonicalizer.canonicalize("{\"a\":\"\\ud83d\\ude00\"}");
		byte[] grinning = "{\"a\":\"\uD83D\uDE00\"}".getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(grinning, pair);
		assertEquals(4, grinning.length - "{\"a\":\"\"}".length(), "U+1F600 is four UTF-8 bytes");
		ObjectNode pairTree = Utils.OM.createObjectNode();
		pairTree.put("a", "\uD83D\uDE00");
		assertArrayEquals(grinning, JsfCanonicalizer.canonicalize(pairTree));
		// an escaped backslash followed by u is not an escape
		assertArrayEquals("{\"a\":\"\\\\ud800\"}".getBytes(StandardCharsets.UTF_8),
				JsfCanonicalizer.canonicalize("{\"a\":\"\\\\ud800\"}"));
	}

	@Test
	void aNonObjectRootAndNonFiniteNumbersAreRefused() {
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("[1]"), "array root");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("\"x\""), "string root");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("{\"a\":1e400}"), "1e400");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize(Utils.OM.readTree("[1]")), "array node");
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize((JsonNode) null), "null node");
		// a non-finite double in a tree is written by Jackson as a string, so the served bytes and the
		// signing input agree on it: nothing to refuse
		ObjectNode inf = Utils.OM.createObjectNode();
		inf.put("a", Double.POSITIVE_INFINITY);
		assertEquals("{\"a\":\"Infinity\"}", new String(JsfCanonicalizer.canonicalize(inf), StandardCharsets.UTF_8));
		assertEquals(inf.toString(), new String(JsfCanonicalizer.canonicalize(inf), StandardCharsets.UTF_8));
	}

	@Test
	void duplicateNamesAreRefusedWithoutQuotingTheDocument() {
		SbomSigningException e = assertThrows(SbomSigningException.class,
				() -> JsfCanonicalizer.canonicalize("{\"secretName\":1,\"secretName\":2}"));
		assertEquals(SbomSigningErrorReason.NOT_CANONICALIZABLE, e.getReason());
		assertFalse(e.getMessage().contains("secretName"), e.getMessage());
		assertNotCanonicalizable(() -> JsfCanonicalizer.canonicalize("{\"a\":"), "truncated");
	}

	@Test
	void jacksonSerialisationDetailsDoNotLeakIntoTheCanonicalForm() {
		JsonNode tree = Utils.OM.readTree("{\"one\":1.0,\"big\":1e21,\"ctl\":\"\\u001f\",\"slash\":\"a/b\","
				+ "\"e\":\"\\u00e9\"}");
		String serialised = tree.toString();
		String canonical = new String(JsfCanonicalizer.canonicalize(tree), StandardCharsets.UTF_8);
		assertEquals("{\"big\":1e+21,\"ctl\":\"\\u001f\",\"e\":\"\u00e9\",\"one\":1,\"slash\":\"a/b\"}", canonical,
				"Jackson wrote " + serialised);
	}
}

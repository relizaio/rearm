/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-33 T-14 (section 3.5): the signing records, {@code reliza:export:signed},
 * {@code reliza:export:signatureKeyId} and {@code reliza:export:unsignedReason}, survive the
 * internal-metadata strip by their exact names, while ReARM's internal markers still go; and a copy
 * an uploader forged, with an uploaded root signature, goes in every egress sweep.
 */
class SupportBomInjectorSigningRecordTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"app\"},\"properties\":["
			+ "{\"name\":\"reliza:export:signed\",\"value\":\"true\"},"
			+ "{\"name\":\"reliza:export:bomRefsDeduplicated\",\"value\":\"3\"},"
			+ "{\"name\":\"reliza:export:signatureKeyId\",\"value\":\"00000000-0000-4000-8000-000000000001\"},"
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.2.3\"},"
			+ "{\"name\":\"reliza:export:unsignedReason\",\"value\":\"NO_KEY\"}]}}";

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		for (JsonNode p : properties) out.add(p.get("name").asString());
		return out;
	}

	@Test
	void theSigningRecordsSurviveTheInternalStrip() {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));
		assertEquals(List.of("reliza:export:signed", "reliza:export:signatureKeyId", "reliza:export:unsignedReason"),
				names(stripped.get("metadata").get("properties")));
	}

	/** An uploaded document carrying forged signing records and an uploader's root signature. */
	private static final String FORGED = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"properties\":["
			+ "{\"name\":\"reliza:export:signed\",\"value\":\"true\"},"
			+ "{\"name\":\"reliza:export:signatureKeyId\",\"value\":\"00000000-0000-4000-8000-000000000001\"},"
			+ "{\"name\":\"reliza:export:unsignedReason\",\"value\":\"forged\"},"
			+ "{\"name\":\"reliza:export:fileComponentsExcluded\",\"value\":\"1\"}]},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"properties\":["
			+ "{\"name\":\"reliza:export:signed\",\"value\":\"true\"},{\"name\":\"cdx:other\",\"value\":\"kept\"}]}],"
			+ "\"signature\":{\"algorithm\":\"Ed25519\",\"keyId\":\"00000000-0000-4000-8000-000000000001\","
			+ "\"publicKey\":{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw\"},"
			+ "\"value\":\"AAAA\"}}";

	// SCORE-33 security review M-1: the per-artifact download and every other egress sweep
	@Test
	void forgedSigningRecordsAndAnUploadedRootSignatureGoInEveryEgressSweep() {
		List<JsonNode> swept = List.of(SupportBomInjector.stripOnly(Utils.OM.readTree(FORGED)),
				SupportBomInjector.stripSilently(Utils.OM.readTree(FORGED)),
				SupportBomInjector.inject(Utils.OM.readTree(FORGED), Map.of(), LocalDate.of(2026, 10, 14)));
		for (JsonNode doc : swept) {
			String text = doc.toString();
			for (String name : SupportBomInjector.SIGNING_RECORD_PROPERTIES) {
				assertFalse(text.contains(name), name + " survived: " + text);
			}
			assertFalse(doc.has("signature"), "the uploaded root signature survived: " + text);
			assertTrue(text.contains("reliza:export:fileComponentsExcluded"), text);
			assertEquals(List.of("cdx:other"), names(doc.get("components").get(0).get("properties")));
		}
	}

	@Test
	void theRecordsAreNamedAsTheEngineReadsThem() {
		// rearm-cli cmd/exportReleaseBom.go (SCORE-34) reads these three names for its export notice
		assertEquals("reliza:export:signed", SupportBomInjector.SIGNED_PROPERTY);
		assertEquals("reliza:export:signatureKeyId", SupportBomInjector.SIGNATURE_KEY_ID_PROPERTY);
		assertEquals("reliza:export:unsignedReason", SupportBomInjector.UNSIGNED_REASON_PROPERTY);
	}
}

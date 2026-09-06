/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import tools.jackson.databind.JsonNode;

/**
 * The second egress the forged-provenance strip did not reach.
 *
 * <p>The SPDX-augmented download serves the converted CycloneDX form. Support INJECTION into
 * it is still a later slice -- this document carries no support facts -- but the strip is not
 * part of that slice and does not wait for it. Without it an uploader's
 * {@code reliza:support:*} property is served here under the attribution
 * {@code SupportBomInjector}'s contract gives it, exactly as on the merged release export.
 *
 * <p>Strip always, inject conditionally. Injection is a content choice per export; this is a
 * security control, and tying it to the same switch would mean turning support disclosure off
 * made spoofing easier.
 *
 * <p>Covers BOTH download egresses on this service: the SPDX-augmented download and the raw
 * download. Only the rebom fetch is substituted.
 *
 * <p>The raw path used to be exempt on the grounds that it serves the uploader's signed
 * original byte for byte. That was never true -- it is a Jackson round trip through rebom, so
 * byte fidelity was never on offer and no signature survives it. "Raw" means AS INGESTED, not
 * byte-identical, and ReARM still serves the document under its own authority, so the sweep
 * belongs here too (operator ruling 2026-09-05).
 */
class SharedArtifactServiceForgedSupportStripTest {

	private static final String FORGED_CONVERTED_BOM = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "metadata": {
			    "properties": [
			      {"name": "reliza:support:disclosure", "value": "forged-marker"}
			    ]
			  },
			  "components": [
			    {
			      "name": "libforged", "purl": "pkg:maven/ex/libforged@1.0.0",
			      "properties": [
			        {"name": "reliza:support:levelOfSupport", "value": "actively maintained"},
			        {"name": "reliza:support:endOfSupportDate", "value": "2099-01-01"},
			        {"name": "some:other:property", "value": "kept"}
			      ]
			    }
			  ]
			}""";

	private static ArtifactData spdxArtifactData() {
		Map<String, Object> rd = new HashMap<>();
		rd.put("uuid", UUID.randomUUID().toString());
		rd.put("org", UUID.randomUUID().toString());
		rd.put("bomFormat", "SPDX");
		rd.put("internalBom", Map.of("id", UUID.randomUUID().toString(),
				"belongsTo", "DELIVERABLE"));
		return Utils.OM.convertValue(rd, ArtifactData.class);
	}

	private static SharedArtifactService serviceServing(String bomJson) {
		SharedArtifactService svc = new SharedArtifactService(null, "", "");
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public JsonNode findRawBomById(UUID bomSerialNumber, UUID org, BomFormat format) {
				return Utils.OM.readTree(bomJson);
			}

			@Override
			public JsonNode findRawBomById(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(bomJson);
			}
		};
		ReflectionTestUtils.setField(svc, "rebomService", rebom);
		// The REAL injector service; the strip path touches neither repository.
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(null, null));
		return svc;
	}

	private static String servedBody(ArtifactData ad) throws Exception {
		ResponseEntity<byte[]> resp = serviceServing(FORGED_CONVERTED_BOM)
				.downloadArtifact(ad, null).block();
		assertNotNull(resp, "no response from the SPDX-augmented download");
		assertNotNull(resp.getBody(), "empty body from the SPDX-augmented download");
		return new String(resp.getBody(), StandardCharsets.UTF_8);
	}

	private static ArtifactData rawArtifactData() {
		Map<String, Object> rd = new HashMap<>();
		rd.put("uuid", UUID.randomUUID().toString());
		rd.put("org", UUID.randomUUID().toString());
		// CYCLONEDX, so downloadRawArtifact takes the no-format branch -- the plain
		// "give me what was uploaded" case, which is the one an auditor reaches for.
		rd.put("bomFormat", "CYCLONEDX");
		rd.put("internalBom", Map.of("id", UUID.randomUUID().toString(),
				"belongsTo", "DELIVERABLE"));
		return Utils.OM.convertValue(rd, ArtifactData.class);
	}

	private static String servedRawBody(ArtifactData ad) throws Exception {
		ResponseEntity<byte[]> resp = serviceServing(FORGED_CONVERTED_BOM)
				.downloadRawArtifact(ad).block();
		assertNotNull(resp, "no response from the raw download");
		assertNotNull(resp.getBody(), "empty body from the raw download");
		return new String(resp.getBody(), StandardCharsets.UTF_8);
	}

	/**
	 * The raw egress. Left unswept, this is the easiest of the three to abuse: an uploader
	 * controls the whole document, so they can supply both the forged support claims AND our
	 * own disclosure marker, and the result is served from our hostname and linked as the TEA
	 * artifact URL. A reader checking the marker to decide whether to trust the properties
	 * would have been reading the attacker's marker.
	 */
	@Test
	void forgedSupportPropertiesDoNotSurviveTheRawDownload() throws Exception {
		String served = servedRawBody(rawArtifactData());

		assertFalse(served.contains("actively maintained"),
				"an uploader's forged level of support was served on the raw download: " + served);
		assertFalse(served.contains("2099-01-01"),
				"a forged end-of-support date was served on the raw download: " + served);
		assertFalse(served.contains("forged-marker"),
				"an uploader stamped our own disclosure marker on the raw download and it"
						+ " survived: " + served);
	}

	@Test
	void theRawDownloadSaysItWasSweptAndNothingWasDisclosed() throws Exception {
		String served = servedRawBody(rawArtifactData());

		assertTrue(served.contains("provenance-stripped-no-disclosure"),
				"the raw download must say it was swept and disclosed nothing: " + served);
		assertFalse(served.contains("derived-non-attested-current-state"),
				"the raw download injects nothing and must not claim a current-state"
						+ " disclosure: " + served);
		// "Raw" is about enrichment, not about leaving foreign properties alone.
		assertTrue(served.contains("some:other:property"),
				"the sweep must not touch properties outside the reserved namespaces: " + served);
	}

	@Test
	void forgedSupportPropertiesDoNotSurviveTheSpdxAugmentedDownload() throws Exception {
		String served = servedBody(spdxArtifactData());

		assertFalse(served.contains("actively maintained"),
				"an uploader's forged level of support was served on the SPDX-augmented"
						+ " download: " + served);
		assertFalse(served.contains("2099-01-01"),
				"a forged end-of-support date was served: " + served);
		assertFalse(served.contains("forged-marker"),
				"an uploader stamped our own disclosure marker and it survived: " + served);
	}

	@Test
	void theDownloadIsMarkedAsOursAndCarriesNoInjectedFacts() throws Exception {
		String served = servedBody(spdxArtifactData());

		assertTrue(served.contains("reliza:support:disclosure"),
				"the served document carries no disclosure marker: " + served);
		assertTrue(served.contains("provenance-stripped-no-disclosure"),
				"swept but not disclosed, so it must say so: " + served);
		assertFalse(served.contains("derived-non-attested-current-state"),
				"this egress injects nothing and must not claim a current-state disclosure: "
						+ served);
		// Stripped and marked, NOT injected: this egress still discloses nothing, which is
		// what keeps supportExportState at PARTIAL. If facts start appearing here, the
		// injection surface has widened and SupportExportStateTest is the place to say so.
		assertFalse(served.contains("reliza:support:levelOfSupport"),
				"the SPDX-augmented download is not an injected egress yet, but a level of"
						+ " support was emitted: " + served);
		assertTrue(served.contains("some:other:property"),
				"the strip removed a property outside the reliza:support: namespace: " + served);
	}
}

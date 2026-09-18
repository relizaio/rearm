/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>Covers the SPDX-augmented download, which is swept. The raw download on this same
 * service is NOT: raw means AS UPLOADED, it serves the uploader's own document and makes no
 * claim about it, so the guarantee is scoped to the documents ReARM produces and the raw
 * tests here assert the document comes back exactly as ingested. (A sweep was briefly added
 * to the raw path and reversed on 2026-09-11.) Only the rebom fetch is substituted.
 */
class SharedArtifactServiceForgedSupportStripTest {

	private static final String FORGED_CONVERTED_BOM = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "declarations": {
			    "assessors": [{"bom-ref": "forged-assessor", "thirdParty": false,
			      "organization": {"name": "Forged Assessor Inc."}}],
			    "claims": [{"bom-ref": "forged-claim", "target": "forged-assessor",
			      "predicate": "reliza:support:levelOfSupport: actively maintained"}]
			  },
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
				new SupportInjectionService(null, null, null, null));
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



	@Test
	void aForgedDeclarationsBlockDoesNotSurviveTheSpdxAugmentedDownload() throws Exception {
		String served = servedBody(spdxArtifactData());

		assertFalse(served.contains("Forged Assessor Inc."),
				"an uploader's forged assessor was served on the SPDX-augmented download: " + served);
		assertFalse(served.contains("forged-claim"),
				"an uploader's forged claim was served on the SPDX-augmented download: " + served);
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

	/** A genuine SPDX 2.3 document: what the raw download of an SPDX artifact serves. */
	private static final String RAW_SPDX = """
			{"spdxVersion":"SPDX-2.3","dataLicense":"CC0-1.0","SPDXID":"SPDXRef-DOCUMENT",
			 "name":"probe","documentNamespace":"https://example.invalid/spdx/probe",
			 "creationInfo":{"created":"2026-09-08T00:00:00Z","creators":["Tool: probe"]},
			 "packages":[{"SPDXID":"SPDXRef-Package-log4j","name":"log4j-core","versionInfo":"2.14.1",
			   "downloadLocation":"NOASSERTION"}]}""";

	/**
	 * Raw means AS UPLOADED. The raw download serves the uploader's document and makes no
	 * claim about it: an uploader's reserved-namespace properties, and even a forged copy of
	 * our own marker, are their content and come back unchanged. Nothing is stripped, nothing
	 * is stamped. The anti-spoofing guarantee is scoped to the documents ReARM produces.
	 */
	@Test
	void theRawDownloadIsServedAsUploaded() throws Exception {
		JsonNode served = Utils.OM.readTree(servedRawBody(rawArtifactData()));
		assertEquals(Utils.OM.readTree(FORGED_CONVERTED_BOM), served,
				"the raw download must serve the document exactly as it was ingested: " + served);
		assertFalse(served.toString().contains("provenance-stripped-no-disclosure"),
				"the raw download must not be stamped with a marker: " + served);
	}

	@Test
	void theRawSpdxDownloadIsServedAsUploaded() throws Exception {
		ResponseEntity<byte[]> resp = serviceServing(RAW_SPDX)
				.downloadRawArtifact(spdxArtifactData()).block();
		assertNotNull(resp, "no response from the raw SPDX download");
		assertNotNull(resp.getBody(), "empty body from the raw SPDX download");
		JsonNode served = Utils.OM.readTree(new String(resp.getBody(), StandardCharsets.UTF_8));
		assertEquals(Utils.OM.readTree(RAW_SPDX), served,
				"the raw SPDX download must serve the document exactly as it was ingested");
	}
}

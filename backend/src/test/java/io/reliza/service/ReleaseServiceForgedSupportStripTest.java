/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.WhoUpdated;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import tools.jackson.databind.JsonNode;

/**
 * The merged release SBOM is the device-level document a manufacturer attaches to a
 * submission, and it is assembled out of component BOMs somebody else uploaded.
 *
 * <p>{@code SupportBomInjector}'s contract says any {@code reliza:support:*} property in a
 * served BOM was provably written by us. Until this fix that guarantee held on the CycloneDX
 * artifact download and nowhere else: {@code exportReleaseSbom} never went near the injector,
 * so a component BOM uploaded with {@code reliza:support:levelOfSupport} baked in reached the
 * merged export untouched -- served under our attribution, with our documentation vouching
 * for it. That is worse than no control, because the claim is what makes it credible.
 *
 * <p>The strip is therefore UNCONDITIONAL and separate from injection: injection is content
 * an operator opts into per export, this is a security control. If they shared one switch,
 * turning support disclosure off would make spoofing easier.
 *
 * <p>Drives the real public egress for {@code BomMediaType.JSON}. The merge is substituted
 * (it needs a database and a live rebom); everything downstream of it -- the strip, the
 * marker, the serialization -- is production code. The CSV and EXCEL media types of the same
 * export never route through the injector at all; they are rendered by rebom from a fixed
 * column list that excludes component properties, so nothing forged reaches them today.
 */
class ReleaseServiceForgedSupportStripTest {

	private static final UUID BOM_ID = UUID.randomUUID();

	/**
	 * A merged BOM as it arrives from rebom, carrying an uploader's forged claim in three
	 * places at once: on a component, on the root self-component, and as the document-level
	 * disclosure marker itself -- the last being the one that makes the whole file look
	 * server-issued.
	 */
	private static final String FORGED_MERGED_BOM = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "metadata": {
			    "component": {
			      "name": "device-root", "purl": "pkg:generic/device-root@1.0.0",
			      "properties": [
			        {"name": "reliza:support:status", "value": "ACTIVELY_SUPPORTED"}
			      ]
			    },
			    "properties": [
			      {"name": "reliza:support:disclosure", "value": "forged-marker"}
			    ]
			  },
			  "components": [
			    {
			      "name": "libforged", "purl": "pkg:maven/ex/libforged@1.0.0",
			      "properties": [
			        {"name": "reliza:support:levelOfSupport", "value": "actively maintained"},
			        {"name": "reliza:support:justification", "value": "vendor says so"},
			        {"name": "some:other:property", "value": "kept"}
			      ]
			    }
			  ]
			}""";

	private static ReleaseService serviceServing(String bomJson) {
		ReleaseService svc = new ReleaseService(null) {
			@Override
			UUID getReleaseBomId(UUID releaseUuid, Boolean tldOnly, Boolean ignoreDev,
					ArtifactBelongsTo belongsTo, BomStructureType structure, WhoUpdated wu,
					List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) {
				return BOM_ID;
			}
		};
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(bomJson);
			}
		};
		ReflectionTestUtils.setField(svc, "rebomService", rebom);
		// The REAL injector service. The strip path touches neither repository, so nulls are
		// safe here and the production tree edit is what runs.
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(null, null));
		return svc;
	}

	@Test
	void forgedSupportPropertiesDoNotSurviveTheMergedExport() throws Exception {
		String served = serviceServing(FORGED_MERGED_BOM).exportReleaseSbom(
				UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		assertFalse(served.contains("actively maintained"),
				"an uploader's forged level of support reached the merged release export, where"
						+ " SupportBomInjector's contract says every reliza:support:* property"
						+ " was written by us. Served BOM: " + served);
		assertFalse(served.contains("vendor says so"),
				"forged justification survived the merged export: " + served);
		assertFalse(served.contains("ACTIVELY_SUPPORTED"),
				"forged status survived on the root self-component: " + served);
		assertFalse(served.contains("forged-marker"),
				"an uploader stamped our own disclosure marker and it survived: " + served);
	}

	@Test
	void theExportIsMarkedAsOursAndKeepsPropertiesWeDoNotOwn() throws Exception {
		String served = serviceServing(FORGED_MERGED_BOM).exportReleaseSbom(
				UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		// Exactly one, ours. Two would mean the sweep ran after the stamp rather than before.
		assertTrue(served.contains("reliza:support:disclosure"),
				"the served document carries no disclosure marker at all: " + served);
		// The STRIPPED-ONLY value, not the current-state one. This egress injects nothing, and
		// the current-state marker would tell a reader that a component with no support
		// property has no recorded date -- across the whole document, including components we
		// hold an end-of-support date for.
		assertTrue(served.contains("provenance-stripped-no-disclosure"),
				"a swept-but-not-disclosed export must not claim a current-state disclosure: "
						+ served);
		assertFalse(served.contains("derived-non-attested-current-state"),
				"the merged export injects nothing, so it must not carry the current-state"
						+ " disclosure marker: " + served);
		assertFalse(served.replaceFirst("reliza:support:disclosure", "")
						.contains("reliza:support:disclosure"),
				"more than one disclosure marker in the served document: " + served);
		// The strip is scoped to the namespaces we own. It is not a general property purge.
		assertTrue(served.contains("some:other:property"),
				"the strip removed a property outside the reliza:support: namespace: " + served);
	}
}

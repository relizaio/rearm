/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cyclonedx.Version;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Component.Type;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.VdrMetadataProperty;

/**
 * Structural tests for {@code ReleaseService.transformVdrBomToCdxVex}.
 *
 * We don't run the full {@code generateCdxVex} pipeline (which would need a real Spring context
 * with mocked repositories) — instead we hand-build a VDR-shaped {@link Bom} that mirrors what
 * {@code buildVdrBom} produces and invoke the pure static transform directly. This tests the
 * VEX-specific contract: preserved components (VEX is a VDR extension), filtered vulnerabilities,
 * VEX_DOCUMENT marker, distinct serialNumber, and CDX 1.6 schema validity of the result.
 */
public class ReleaseServiceCdxVexTransformTest {

	private static final UUID FIXTURE_RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000042");

	private static Bom buildVdrLikeFixture() {
		Bom bom = new Bom();
		bom.setSerialNumber(ReleaseService.buildVdrSerialNumber(
				UUID.fromString("00000000-0000-0000-0000-000000000042"),
				null, null, null, null));
		Component root = new Component();
		root.setType(Type.APPLICATION);
		root.setName("demo-app");
		root.setVersion("1.0.0");
		Utils.augmentRootBomComponent("demo-org", root);
		Utils.setRearmBomMetadata(bom, root);

		// Components[] — preserved by the VEX transform (VEX is a VDR extension).
		Component lib = new Component();
		lib.setType(Type.LIBRARY);
		lib.setName("left-pad");
		lib.setPurl("pkg:npm/left-pad@1.0.0");
		lib.setBomRef("pkg:npm/left-pad@1.0.0");
		bom.setComponents(new ArrayList<>(List.of(lib)));

		// Vulnerabilities[]: one per analysis state, plus one with no analysis at all.
		List<Vulnerability> vulns = new ArrayList<>();
		vulns.add(vulnWithState("CVE-2024-0001", Vulnerability.Analysis.State.EXPLOITABLE));
		vulns.add(vulnWithState("CVE-2024-0002", Vulnerability.Analysis.State.NOT_AFFECTED));
		vulns.add(vulnWithState("CVE-2024-0003", Vulnerability.Analysis.State.RESOLVED));
		vulns.add(vulnWithState("CVE-2024-0004", Vulnerability.Analysis.State.FALSE_POSITIVE));
		vulns.add(vulnWithState("CVE-2024-0005", Vulnerability.Analysis.State.IN_TRIAGE));
		vulns.add(vulnWithState("CVE-2024-0006", null)); // no analysis block
		bom.setVulnerabilities(vulns);
		return bom;
	}

	private static Vulnerability vulnWithState(String id, Vulnerability.Analysis.State state) {
		Vulnerability v = new Vulnerability();
		v.setBomRef(UUID.randomUUID().toString());
		v.setId(id);
		Vulnerability.Source src = new Vulnerability.Source();
		src.setName("NVD");
		v.setSource(src);
		if (state != null) {
			Vulnerability.Analysis a = new Vulnerability.Analysis();
			a.setState(state);
			v.setAnalysis(a);
		}
		Vulnerability.Affect affect = new Vulnerability.Affect();
		affect.setRef("pkg:npm/left-pad@1.0.0");
		v.setAffects(List.of(affect));
		return v;
	}

	private static Bom invokeTransform(Bom bom, Boolean includeInTriage, Boolean includeSuppressed) {
		ReleaseService.transformVdrBomToCdxVex(bom, FIXTURE_RELEASE, includeInTriage,
				null, null, null, includeSuppressed);
		return bom;
	}

	private static Property findProperty(Bom bom, VdrMetadataProperty key) {
		if (bom.getMetadata() == null || bom.getMetadata().getProperties() == null) return null;
		return bom.getMetadata().getProperties().stream()
				.filter(p -> key.name().equals(p.getName()))
				.findFirst().orElse(null);
	}

	// ---- Structural contract ----

	@Test
	void transform_preservesComponentsArray() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		// VEX is a VDR extension — components[] must carry through so the document is self-contained.
		assertNotNull(bom.getComponents(), "VEX documents must retain components[]");
		assertEquals(1, bom.getComponents().size());
		assertEquals("left-pad", bom.getComponents().get(0).getName());
	}

	@Test
	void transform_filtersInTriageAndAnalysislessByDefault() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		List<String> kept = bom.getVulnerabilities().stream().map(Vulnerability::getId).toList();
		assertTrue(kept.contains("CVE-2024-0001"), "EXPLOITABLE must be kept");
		assertTrue(kept.contains("CVE-2024-0002"), "NOT_AFFECTED must be kept");
		assertTrue(kept.contains("CVE-2024-0003"), "RESOLVED must be kept");
		assertTrue(kept.contains("CVE-2024-0004"), "FALSE_POSITIVE must be kept");
		assertFalse(kept.contains("CVE-2024-0005"), "IN_TRIAGE must be filtered by default");
		assertFalse(kept.contains("CVE-2024-0006"), "Analysis-less entries must be filtered by default");
		assertEquals(4, kept.size());
	}

	@Test
	void transform_includesInTriageAndAnalysislessWhenOptedIn() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.TRUE, Boolean.FALSE);
		List<String> kept = bom.getVulnerabilities().stream().map(Vulnerability::getId).toList();
		assertTrue(kept.contains("CVE-2024-0005"), "IN_TRIAGE must be kept when includeInTriage=true");
		assertTrue(kept.contains("CVE-2024-0006"), "Analysis-less must be kept when includeInTriage=true");
		assertEquals(6, kept.size());

		// Analysis-less entries must be surfaced as explicit IN_TRIAGE statements so the VEX
		// document contains valid "under investigation" assertions rather than statement-less
		// findings that downstream consumers can't interpret.
		Vulnerability synthesized = bom.getVulnerabilities().stream()
				.filter(v -> "CVE-2024-0006".equals(v.getId()))
				.findFirst().orElseThrow();
		assertNotNull(synthesized.getAnalysis(),
				"Analysis-less vuln must be stamped with an explicit analysis block");
		assertEquals(Vulnerability.Analysis.State.IN_TRIAGE, synthesized.getAnalysis().getState(),
				"Synthesized analysis state must be IN_TRIAGE");
	}

	@Test
	void transform_nullIncludeInTriage_treatedAsFalse() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), null, Boolean.FALSE);
		List<String> kept = bom.getVulnerabilities().stream().map(Vulnerability::getId).toList();
		assertFalse(kept.contains("CVE-2024-0005"));
		assertFalse(kept.contains("CVE-2024-0006"));
	}

	@Test
	void transform_addsVexDocumentMarker() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		Property marker = findProperty(bom, VdrMetadataProperty.VEX_DOCUMENT);
		assertNotNull(marker, "VEX_DOCUMENT metadata property must be present");
		assertEquals("true", marker.getValue());
	}

	@Test
	void transform_replacesSerialWithVexSerial() throws Exception {
		Bom fixture = buildVdrLikeFixture();
		String vdrSerial = fixture.getSerialNumber();
		Bom bom = invokeTransform(fixture, Boolean.FALSE, Boolean.FALSE);
		assertTrue(bom.getSerialNumber().startsWith("urn:uuid:"));
		assertFalse(bom.getSerialNumber().equals(vdrSerial),
				"VEX must not reuse the VDR urn:uuid — two documents, two identities");
	}

	@Test
	void transform_outputIsSchemaValidAgainstCdx16() throws Exception {
		// Cover both default and include-in-triage modes so both code paths validate.
		for (Boolean triage : new Boolean[]{Boolean.FALSE, Boolean.TRUE}) {
			Bom bom = invokeTransform(buildVdrLikeFixture(), triage, Boolean.FALSE);
			BomJsonGenerator gen = BomGeneratorFactory.createJson(Version.VERSION_16, bom);
			String json = gen.toJsonString();
			List<ParseException> errors = new JsonParser().validate(
					json.getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
			assertTrue(errors.isEmpty(),
					() -> "VEX failed CDX 1.6 schema validation (includeInTriage=" + triage + "):\n"
							+ errors + "\n---\n" + json);
		}
	}

	@Test
	void transform_handlesEmptyVulnerabilitiesList() throws Exception {
		Bom bom = buildVdrLikeFixture();
		bom.setVulnerabilities(new ArrayList<>());
		Bom transformed = invokeTransform(bom, Boolean.FALSE, Boolean.FALSE);
		assertNotNull(transformed.getVulnerabilities());
		assertTrue(transformed.getVulnerabilities().isEmpty());
		assertNotNull(findProperty(transformed, VdrMetadataProperty.VEX_DOCUMENT));
	}

	@Test
	void transform_handlesNullVulnerabilitiesList() throws Exception {
		Bom bom = buildVdrLikeFixture();
		bom.setVulnerabilities(null);
		// Must not NPE on null vulns list.
		Bom transformed = invokeTransform(bom, Boolean.FALSE, Boolean.FALSE);
		// Components preserved, marker still added.
		assertNotNull(transformed.getComponents());
		assertEquals(1, transformed.getComponents().size());
		assertNotNull(findProperty(transformed, VdrMetadataProperty.VEX_DOCUMENT));
	}
}

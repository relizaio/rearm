/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cyclonedx.Version;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Component.Type;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.VdrMetadataProperty;
import io.reliza.model.VdrSnapshotType;

/**
 * Structural guardrail that every VDR document shape emitted by {@link ReleaseService#generateVdrInternal}
 * is valid against the bundled CycloneDX 1.6 JSON schema.
 *
 * Scope note (deliberate): this test operates on fixtures built with the same {@code cyclonedx-core-java}
 * builder patterns used inside {@code generateVdrInternal} (serial number via
 * {@link ReleaseService#buildVdrSerialNumber}, rearm metadata via {@link Utils#setRearmBomMetadata}, the
 * snapshot {@link VdrMetadataProperty} constants, and the {@link Vulnerability.Analysis} model). It does
 * not reach through the full service graph because that would require mocking 5+ collaborators and still
 * would not improve schema coverage. Each fixture here corresponds to a distinct structural path the
 * producer actually emits, so a breaking CDX change or a library upgrade that renames fields will still
 * fail loudly.
 *
 * The negative case ({@link #malformedBom_validatorReportsErrors}) proves the validator isn't silently
 * passing everything.
 */
public class ReleaseServiceVdrSchemaTest {

	private static final UUID FIXED_RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000042");

	private static void assertSchemaValid(Bom bom) throws Exception {
		BomJsonGenerator generator = BomGeneratorFactory.createJson(Version.VERSION_16, bom);
		String json = generator.toJsonString();
		List<ParseException> errors = new JsonParser().validate(
				json.getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
		assertTrue(errors.isEmpty(),
				() -> "CDX 1.6 schema validation failed:\n" + errors + "\n---\n" + json);
	}

	private static Bom newBaseBom(VdrSnapshotType snapshotType, String snapshotValue, ZonedDateTime cutOff) {
		Bom bom = new Bom();
		bom.setSerialNumber(ReleaseService.buildVdrSerialNumber(
				FIXED_RELEASE, snapshotType, snapshotValue, cutOff, Boolean.FALSE));
		Component rootComponent = new Component();
		rootComponent.setName("demo-app");
		rootComponent.setType(Type.APPLICATION);
		rootComponent.setVersion("1.0.0");
		Utils.augmentRootBomComponent("demo-org", rootComponent);
		Utils.setRearmBomMetadata(bom, rootComponent);
		return bom;
	}

	private static void attachSnapshotProperties(Bom bom, VdrSnapshotType snapshotType, String snapshotValue,
			ZonedDateTime cutOff) {
		Metadata metadata = bom.getMetadata();
		if (metadata.getProperties() == null) {
			metadata.setProperties(new ArrayList<>());
		}
		Property snap = new Property();
		snap.setName(VdrMetadataProperty.VDR_SNAPSHOT.toString());
		snap.setValue("true");
		metadata.getProperties().add(snap);

		Property cutoff = new Property();
		cutoff.setName(VdrMetadataProperty.VDR_CUTOFF_DATE.toString());
		cutoff.setValue(cutOff.toString());
		metadata.getProperties().add(cutoff);

		if (snapshotType != null) {
			Property type = new Property();
			type.setName(VdrMetadataProperty.VDR_SNAPSHOT_TYPE.toString());
			type.setValue(snapshotType.name());
			metadata.getProperties().add(type);
		}
		if (snapshotValue != null) {
			Property val = new Property();
			val.setName(VdrMetadataProperty.VDR_SNAPSHOT_VALUE.toString());
			val.setValue(snapshotValue);
			metadata.getProperties().add(val);
		}
	}

	private static Component libraryComponent(String purl) {
		Component c = new Component();
		c.setType(Type.LIBRARY);
		c.setName(purl.substring(purl.lastIndexOf('/') + 1).split("@")[0]);
		c.setPurl(purl);
		c.setBomRef(purl);
		return c;
	}

	private static Vulnerability vuln(String id, String bomRef, Vulnerability.Analysis analysis) {
		Vulnerability v = new Vulnerability();
		v.setBomRef(UUID.randomUUID().toString());
		v.setId(id);
		Vulnerability.Source src = new Vulnerability.Source();
		src.setName("NVD");
		src.setUrl("https://nvd.nist.gov/vuln/detail/" + id);
		v.setSource(src);
		if (analysis != null) {
			v.setAnalysis(analysis);
		}
		Vulnerability.Affect affect = new Vulnerability.Affect();
		affect.setRef(bomRef);
		v.setAffects(List.of(affect));
		return v;
	}

	// ---- Positive fixtures ----

	@Test
	void minimalLiveVdr_isSchemaValid() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		assertSchemaValid(bom);
	}

	@Test
	void historicalLifecycleSnapshot_withProperties_isSchemaValid() throws Exception {
		ZonedDateTime cutOff = ZonedDateTime.parse("2024-06-01T00:00:00Z");
		Bom bom = newBaseBom(VdrSnapshotType.LIFECYCLE, "release", cutOff);
		attachSnapshotProperties(bom, VdrSnapshotType.LIFECYCLE, "release", cutOff);
		assertSchemaValid(bom);
	}

	@Test
	void historicalDateSnapshot_withProperties_isSchemaValid() throws Exception {
		ZonedDateTime cutOff = ZonedDateTime.parse("2024-06-01T00:00:00Z");
		Bom bom = newBaseBom(VdrSnapshotType.DATE, null, cutOff);
		attachSnapshotProperties(bom, VdrSnapshotType.DATE, null, cutOff);
		assertSchemaValid(bom);
	}

	@Test
	void vdrWithExploitableAnalysis_responsesAndDetail_isSchemaValid() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:npm/left-pad@1.0.0";
		bom.setComponents(List.of(libraryComponent(purl)));

		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.EXPLOITABLE);
		analysis.setResponses(List.of(
				Vulnerability.Analysis.Response.UPDATE,
				Vulnerability.Analysis.Response.WORKAROUND_AVAILABLE));
		analysis.setDetail("Upstream patch available; deploy 1.0.1 on next release.");

		bom.setVulnerabilities(List.of(vuln("CVE-2024-0001", purl, analysis)));
		assertSchemaValid(bom);
	}

	@Test
	void vdrWithNotAffectedAnalysis_justificationAndDetail_isSchemaValid() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:maven/org.example/demo@2.3.4";
		bom.setComponents(List.of(libraryComponent(purl)));

		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.NOT_AFFECTED);
		analysis.setJustification(Vulnerability.Analysis.Justification.CODE_NOT_REACHABLE);
		analysis.setDetail("Vulnerable method is never invoked; entrypoint is disabled by config.");

		bom.setVulnerabilities(List.of(vuln("CVE-2024-0002", purl, analysis)));
		assertSchemaValid(bom);
	}

	@Test
	void vdrWithResolvedAnalysis_isSchemaValid() throws Exception {
		// Regression: RESOLVED replaces legacy FIXED. Must serialize to a valid CDX 1.6 state.
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:pypi/requests@2.28.0";
		bom.setComponents(List.of(libraryComponent(purl)));

		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.RESOLVED);
		analysis.setDetail("Fixed in 2.31.0; upgrade merged and deployed.");

		bom.setVulnerabilities(List.of(vuln("CVE-2024-0003", purl, analysis)));
		assertSchemaValid(bom);

		// And the serialized JSON must spell it "resolved" (lowercase per CDX), not "fixed".
		String json = BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
		assertTrue(json.contains("\"state\" : \"resolved\"") || json.contains("\"state\":\"resolved\""),
				() -> "Expected analysis.state=resolved in output, got:\n" + json);
		assertFalse(json.contains("\"fixed\""),
				() -> "Output still contains legacy 'fixed' value:\n" + json);
	}

	@Test
	void vdrWithMixedLibraryAndApplicationComponents_isSchemaValid() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		String libPurl = "pkg:npm/axios@0.21.1";

		Component libComp = libraryComponent(libPurl);
		Component appComp = new Component();
		appComp.setType(Type.APPLICATION);
		appComp.setName("dependent-service");
		appComp.setVersion("3.2.1");
		appComp.setBomRef("pkg:generic/dependent-service@3.2.1");
		appComp.setPurl("pkg:generic/dependent-service@3.2.1");

		bom.setComponents(List.of(libComp, appComp));

		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.IN_TRIAGE);

		Vulnerability v = new Vulnerability();
		v.setBomRef(UUID.randomUUID().toString());
		v.setId("CVE-2024-0004");
		Vulnerability.Source src = new Vulnerability.Source();
		src.setName("NVD");
		v.setSource(src);
		v.setAnalysis(analysis);
		// Multiple affects refs — one per consuming release / PURL.
		Vulnerability.Affect a1 = new Vulnerability.Affect();
		a1.setRef(libPurl);
		Vulnerability.Affect a2 = new Vulnerability.Affect();
		a2.setRef(appComp.getBomRef());
		v.setAffects(List.of(a1, a2));

		bom.setVulnerabilities(List.of(v));
		assertSchemaValid(bom);
	}

	@Test
	void vdrWithFalsePositiveAnalysis_isSchemaValid() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:golang/github.com/example/lib@1.2.3";
		bom.setComponents(List.of(libraryComponent(purl)));

		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.FALSE_POSITIVE);
		analysis.setDetail("Scanner matched on unrelated namespace; confirmed with upstream advisory.");

		bom.setVulnerabilities(List.of(vuln("CVE-2024-0005", purl, analysis)));
		assertSchemaValid(bom);
	}

	// ---- Negative fixture: prove the validator is real ----

	@Test
	void malformedBom_validatorReportsErrors() throws Exception {
		// Tamper with an otherwise-valid BOM to a non-UUID serial number. The CDX 1.6 schema
		// pins serialNumber to urn:uuid:<uuid>, so the validator MUST reject this.
		Bom bom = newBaseBom(null, null, null);
		String valid = BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
		String broken = valid.replaceFirst(
				"\"serialNumber\"\\s*:\\s*\"urn:uuid:[0-9a-f-]+\"",
				"\"serialNumber\" : \"not-a-urn\"");
		assertFalse(broken.equals(valid), "sanity: serialNumber replacement did not apply");

		List<ParseException> errors = new JsonParser().validate(
				broken.getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
		assertFalse(errors.isEmpty(),
				"Expected schema errors for invalid serialNumber; got none (validator is a no-op)");
	}

	@Test
	void buildVdrSerialNumber_outputSatisfiesCdxSerialPattern() {
		// The CDX 1.6 schema pins: ^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$
		String serial = ReleaseService.buildVdrSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.LIFECYCLE, "release",
				ZonedDateTime.parse("2024-06-01T00:00:00Z"), Boolean.TRUE);
		assertTrue(serial.matches("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
				() -> "serial violates CDX urn:uuid pattern: " + serial);
		assertEquals("urn:uuid:", serial.substring(0, 9));
	}
}

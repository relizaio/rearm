/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.cyclonedx.model.vulnerability.Vulnerability.Version.Status;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.VdrMetadataProperty;
import io.reliza.model.VdrSnapshotType;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

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
	void vdrWithAffectedVersionRangesAndFixedVersion_isSchemaValid() throws Exception {
		// affects[].versions[] as ReleaseService.affectedVersions writes them: vers ranges as
		// affected, the fixing version as unaffected; a Debian epoch and a bare * included.
		Bom bom = newBaseBom(null, null, null);
		String npm = "pkg:npm/handlebars@4.0.5";
		String deb = "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64&distro=debian-13";
		bom.setComponents(List.of(libraryComponent(npm), libraryComponent(deb)));
		Vulnerability v1 = vuln("GHSA-f2jv-r9rf-7988", npm, null);
		v1.getAffects().get(0).setVersions(List.of(
				version("vers:npm/<4.7.7", null, Status.AFFECTED),
				version(null, "4.7.7", Status.UNAFFECTED)));
		Vulnerability v2 = vuln("DEBIAN-CVE-2023-45853", deb, null);
		v2.getAffects().get(0).setVersions(List.of(
				version("vers:deb/<1:1.3.dfsg-2", null, Status.AFFECTED),
				version(null, "1:1.3.dfsg-2", Status.UNAFFECTED),
				version("vers:deb/*", null, Status.AFFECTED)));
		bom.setVulnerabilities(List.of(v1, v2));
		assertSchemaValid(bom);
	}

	@Test
	void aRangeOverTheSchemaLimitIsInvalid_whichIsWhyTheExportLeavesItOut() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		String npm = "pkg:npm/handlebars@4.0.5";
		bom.setComponents(List.of(libraryComponent(npm)));
		Vulnerability v = vuln("GHSA-f2jv-r9rf-7988", npm, null);
		String tooLong = "vers:npm/<" + "1".repeat(ReleaseService.CDX_VERSION_RANGE_MAX_LENGTH);
		v.getAffects().get(0).setVersions(List.of(version(tooLong, null, Status.AFFECTED)));
		bom.setVulnerabilities(List.of(v));
		String json = BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
		assertFalse(new JsonParser().validate(json.getBytes(StandardCharsets.UTF_8), Version.VERSION_16).isEmpty());
	}

	private static Vulnerability.Version version(String range, String version, Status status) {
		Vulnerability.Version v = new Vulnerability.Version();
		v.setRange(range);
		v.setVersion(version);
		v.setStatus(status);
		return v;
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

	// ---- Enriched entries, built by the exporter's own enrichment helpers ----

	/** A record as the drain stores it: DT's markdown references blob, aliases, cwes, dates. */
	private static VulnerabilityRecordData enrichedRecord() {
		VulnerabilityRecordData r = new VulnerabilityRecordData();
		r.setPrimaryVulnId("CVE-2021-23369");
		r.setAliases(new LinkedHashSet<>(List.of("CVE-2021-23369", "GHSA-f2jv-r9rf-7988", "SNYK-JS-HANDLEBARS-1056767")));
		r.setDescription("Remote code execution when compiling untrusted templates.");
		r.setCwes(new LinkedHashSet<>(List.of("CWE-94")));
		r.setReferences("* [https://nvd.nist.gov/vuln/detail/CVE-2021-23369](https://nvd.nist.gov/vuln/detail/CVE-2021-23369)\n"
				+ "* [Fix commit](https://github.com/handlebars-lang/handlebars.js/commit/b6d3de7)\n"
				+ "* [https://security-tracker.debian.org/tracker/CVE-2021-23369](https://security-tracker.debian.org/tracker/CVE-2021-23369)\n");
		r.setPublished(ZonedDateTime.parse("2021-04-12T14:15:00Z"));
		r.setUpdated(ZonedDateTime.parse("2024-11-21T05:51:00Z"));
		return r;
	}

	/** A finding's entry the way buildVdrVulnerabilityEntry assembles it, with PR I's affected ranges. */
	private static Vulnerability enrichedEntry(String vulnId, String purl, VulnerabilityRecordData record) throws Exception {
		Vulnerability v = new Vulnerability();
		v.setBomRef(UUID.randomUUID().toString());
		v.setId(vulnId);
		ReleaseService.setVulnerabilityCommonFields(v, new VulnerabilityDto(purl, vulnId, VulnerabilitySeverity.HIGH,
				Set.of(), Set.of(), Set.of(), null, null, null, null, null, null, null, null, null));
		ReleaseService.applyVulnerabilityEnrichmentFromRecord(v, record);
		Vulnerability.Affect affect = new Vulnerability.Affect();
		affect.setRef(purl);
		affect.setVersions(ReleaseService.affectedVersions(purl, ReleaseServiceVdrAffectedVersionsTest.rangesOf(
				ReleaseServiceVdrAffectedVersionsTest.DT5_SHAPES, "GITHUB/GHSA-f2jv-r9rf-7988"), vulnId));
		v.setAffects(List.of(affect));
		return v;
	}

	private static Bom enrichedVdr() throws Exception {
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:npm/handlebars@4.0.5";
		bom.setComponents(List.of(libraryComponent(purl)));
		VulnerabilityRecordData record = enrichedRecord();
		// the same record reached through the CVE and through its GHSA alias
		bom.setVulnerabilities(List.of(enrichedEntry("CVE-2021-23369", purl, record),
				enrichedEntry("GHSA-f2jv-r9rf-7988", purl, record)));
		return bom;
	}

	@Test
	void enrichedVdr_withAdvisoriesAliasesAndRanges_isSchemaValid() throws Exception {
		Bom bom = enrichedVdr();
		Vulnerability cve = bom.getVulnerabilities().get(0);
		assertEquals(3, cve.getAdvisories().size(), "every reference URL is an advisory");
		assertEquals(List.of("GHSA-f2jv-r9rf-7988", "SNYK-JS-HANDLEBARS-1056767"),
				cve.getReferences().stream().map(Vulnerability.Reference::getId).toList(),
				"references are the other ids, never the entry's own id");
		assertFalse(cve.getAffects().get(0).getVersions().isEmpty(), "sanity: the fixture carries ranges");
		assertSchemaValid(bom);
	}

	@Test
	void enrichedCdxVex_isSchemaValid() throws Exception {
		// the CycloneDX VEX is the VDR transformed in place: same advisories and references
		Bom bom = enrichedVdr();
		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.NOT_AFFECTED);
		analysis.setJustification(Vulnerability.Analysis.Justification.CODE_NOT_REACHABLE);
		bom.getVulnerabilities().forEach(v -> v.setAnalysis(analysis));
		ReleaseService.transformVdrBomToCdxVex(bom, FIXED_RELEASE, Boolean.FALSE, null, null, null, Boolean.FALSE);
		assertEquals(2, bom.getVulnerabilities().size());
		assertFalse(bom.getVulnerabilities().get(1).getAdvisories().isEmpty());
		assertSchemaValid(bom);
	}

	@Test
	void anAdvisoryIsTitledOnlyWhenItsLinkTextIsMoreThanTheUrl() {
		List<Vulnerability.Advisory> advisories = ReleaseService.advisoriesOf(enrichedRecord().getReferences());
		assertEquals("https://nvd.nist.gov/vuln/detail/CVE-2021-23369", advisories.get(0).getUrl());
		assertNull(advisories.get(0).getTitle());
		assertEquals("Fix commit", advisories.get(1).getTitle());
		assertTrue(ReleaseService.advisoriesOf(null).isEmpty());
	}

	@Test
	void aLinkThatIsNotAUriIsLeftOut_soOneBadLinkCannotInvalidateTheDocument() throws Exception {
		VulnerabilityRecordData record = enrichedRecord();
		record.setReferences(record.getReferences() + "* [notes](https://example.com/release notes)\n");
		assertEquals(3, ReleaseService.advisoriesOf(record.getReferences()).size(), "the link with a space is dropped");
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:npm/handlebars@4.0.5";
		bom.setComponents(List.of(libraryComponent(purl)));
		bom.setVulnerabilities(List.of(enrichedEntry("CVE-2021-23369", purl, record)));
		assertSchemaValid(bom);
	}

	@Test
	void anAliasReferenceNamesTheSourceThatPublishesIt() {
		List<Vulnerability.Reference> refs = ReleaseService.aliasReferences("GHSA-f2jv-r9rf-7988", enrichedRecord());
		assertEquals("CVE-2021-23369", refs.get(0).getId());
		assertEquals("NVD", refs.get(0).getSource().getName());
		assertEquals("https://nvd.nist.gov/vuln/detail/CVE-2021-23369", refs.get(0).getSource().getUrl());
		assertEquals("SNYK-JS-HANDLEBARS-1056767", refs.get(1).getId());
		assertEquals(2, refs.size(), "the entry's own GHSA is not a reference to itself");
		assertEquals(2, ReleaseService.aliasReferences("GHSA-F2JV-R9RF-7988", enrichedRecord()).size(),
				"nor under another casing");
	}

	@Test
	void aUrlAsAReferenceIsSchemaInvalid_whichIsWhyUrlsAreAdvisories() throws Exception {
		// the shape every enriched VDR had: references[] = {id: <url>}, no source
		Bom bom = newBaseBom(null, null, null);
		String purl = "pkg:npm/handlebars@4.0.5";
		bom.setComponents(List.of(libraryComponent(purl)));
		Vulnerability v = vuln("CVE-2021-23369", purl, null);
		Vulnerability.Reference urlRef = new Vulnerability.Reference();
		urlRef.setId("https://nvd.nist.gov/vuln/detail/CVE-2021-23369");
		v.setReferences(List.of(urlRef));
		bom.setVulnerabilities(List.of(v));
		String json = BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
		assertFalse(new JsonParser().validate(json.getBytes(StandardCharsets.UTF_8), Version.VERSION_16).isEmpty());
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

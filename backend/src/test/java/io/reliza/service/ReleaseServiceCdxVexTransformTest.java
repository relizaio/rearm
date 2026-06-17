/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import io.reliza.dto.HistoricallyResolvedFinding;
import io.reliza.model.VdrMetadataProperty;
import io.reliza.model.VulnAnalysisData.AnalysisHistory;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

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
		root.setBomRef("pkg:generic/demo-org/demo-app@1.0.0");
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

	// ---- Historical-resolved (Task 9) ----

	private static HistoricallyResolvedFinding hrf(String vulnId, String purl,
			UUID resolvingReleaseUuid, String resolvingVersion, String resolvingIso8601) {
		VulnerabilityDto vd =
				new VulnerabilityDto(
					purl, vulnId,
					VulnerabilitySeverity.HIGH,
					Set.of(), Set.of(), Set.of(),
					null, null, null, null, null, null, null, null, null);
		return new HistoricallyResolvedFinding(
				vd, resolvingReleaseUuid, resolvingVersion,
				ZonedDateTime.parse(resolvingIso8601));
	}

	@Test
	void appendHistoricallyResolved_emptyList_doesNotChangeBom() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		int before = bom.getVulnerabilities().size();

		ReleaseService.appendHistoricallyResolvedToBom(bom, List.of(), Map.of(), Map.of(), null);

		assertEquals(before, bom.getVulnerabilities().size(),
				"Empty historical-resolved list must not modify the bom");
	}

	@Test
	void appendHistoricallyResolved_addsEntriesWithCorrectShape() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		UUID resolvingUuid = UUID.fromString("00000000-0000-0000-0000-0000000000fa");

		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-9001", "pkg:npm/lodash@4.17.20", resolvingUuid, "1.5", "2026-02-01T00:00:00Z"));

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, Map.of(), Map.of(), null);

		Vulnerability appended = bom.getVulnerabilities().stream()
				.filter(v -> "CVE-2024-9001".equals(v.getId()))
				.findFirst().orElse(null);
		assertNotNull(appended, "Historical-resolved CVE must be appended to bom.vulnerabilities[]");
		assertNotNull(appended.getAnalysis());
		assertEquals(Vulnerability.Analysis.State.RESOLVED, appended.getAnalysis().getState());

		// affects[].ref must point at the product (metadata.component.bom-ref), not at the historical
		// transitive component which is not in current components[].
		String productBomRef = bom.getMetadata().getComponent().getBomRef();
		assertNotNull(productBomRef);
		assertTrue(appended.getAffects() != null && !appended.getAffects().isEmpty());
		assertEquals(productBomRef, appended.getAffects().get(0).getRef());

		// Provenance properties.
		Map<String, String> propMap = new HashMap<>();
		if (appended.getProperties() != null) {
			for (Property p : appended.getProperties()) propMap.put(p.getName(), p.getValue());
		}
		assertEquals(resolvingUuid.toString(), propMap.get("rearm:vex:resolvedInRelease"));
		assertEquals("1.5", propMap.get("rearm:vex:resolvedInVersion"));
	}

	@Test
	void appendHistoricallyResolved_skipsCveAlreadyInBom() throws Exception {
		// The fixture's CVE-2024-0001 is in the bom (state=EXPLOITABLE). Even if we pass it as
		// historically resolved (which would be a contract violation upstream), the static helper
		// is defensively idempotent against duplicates.
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		int before = bom.getVulnerabilities().size();

		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-0001", "pkg:npm/left-pad@1.0.0",
					UUID.fromString("00000000-0000-0000-0000-0000000000fb"), "1.5",
					"2026-02-01T00:00:00Z"));

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, Map.of(), Map.of(), null);

		assertEquals(before, bom.getVulnerabilities().size(),
				"Historical entry for a CVE id already in bom must be skipped");
	}

	@Test
	void appendHistoricallyResolved_withMatchingAnalysis_enrichesDetailAndLastUpdated() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);

		UUID resolvingUuid = UUID.fromString("00000000-0000-0000-0000-0000000000fc");
		ZonedDateTime resolveDate = ZonedDateTime.parse("2026-02-01T00:00:00Z");
		ZonedDateTime analysisDate = ZonedDateTime.parse("2026-02-15T12:34:56Z");

		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-9100", "pkg:npm/lodash@4.17.20", resolvingUuid, "1.5",
					resolveDate.toString()));

		// Pre-resolved AnalysisHistory entry carrying the details + timestamp the helper
		// should pull through. The orchestrator does the state=RESOLVED + cutoff filtering;
		// the static helper just consumes the resolved entry.
		AnalysisHistory latest = mock(AnalysisHistory.class);
		when(latest.getDetails()).thenReturn("Upgraded lodash to 4.17.21 in v1.5");
		when(latest.getCreatedDate()).thenReturn(analysisDate);

		String key = ReleaseService.computeAnalysisKey("pkg:npm/lodash@4.17.20", "CVE-2024-9100");
		Map<String, AnalysisHistory> resolvedHistoryByKey = Map.of(key, latest);

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, resolvedHistoryByKey, Map.of(), null);

		Vulnerability appended = bom.getVulnerabilities().stream()
				.filter(v -> "CVE-2024-9100".equals(v.getId())).findFirst().orElseThrow();
		assertEquals("Upgraded lodash to 4.17.21 in v1.5", appended.getAnalysis().getDetail());
		assertEquals(java.util.Date.from(analysisDate.toInstant()),
				appended.getAnalysis().getLastUpdated());
		assertEquals(java.util.Date.from(analysisDate.toInstant()),
				appended.getAnalysis().getFirstIssued());
	}

	@Test
	void appendHistoricallyResolved_noMatchingAnalysis_omitsDetailAndUsesResolvingDate() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);

		UUID resolvingUuid = UUID.fromString("00000000-0000-0000-0000-0000000000fd");
		ZonedDateTime resolveDate = ZonedDateTime.parse("2026-03-01T00:00:00Z");
		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-9101", "pkg:npm/lodash@4.17.20", resolvingUuid, "1.5",
					resolveDate.toString()));

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, Map.of(), Map.of(), null);

		Vulnerability appended = bom.getVulnerabilities().stream()
				.filter(v -> "CVE-2024-9101".equals(v.getId())).findFirst().orElseThrow();
		assertNull(appended.getAnalysis().getDetail(),
				"No matching VulnAnalysisData → analysis.detail must be omitted");
		assertEquals(java.util.Date.from(resolveDate.toInstant()),
				appended.getAnalysis().getLastUpdated());
	}

	@Test
	void appendHistoricallyResolved_passesCdx16SchemaValidation() throws Exception {
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);

		UUID resolvingUuid = UUID.fromString("00000000-0000-0000-0000-0000000000fe");
		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-9200", "pkg:npm/lodash@4.17.20", resolvingUuid, "1.5",
					"2026-02-01T00:00:00Z"),
				hrf("CVE-2024-9201", "pkg:maven/org.apache/log4j-core@2.20.0", resolvingUuid, "1.5",
					"2026-02-01T00:00:00Z"));

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, Map.of(), Map.of(), null);

		BomJsonGenerator gen = BomGeneratorFactory.createJson(Version.VERSION_16, bom);
		String json = gen.toJsonString();

		JsonParser parser = new JsonParser();
		java.util.List<ParseException> parseErrors =
				parser.validate(json.getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
		assertTrue(parseErrors.isEmpty(),
				"CDX 1.6 schema must accept bom with historical-resolved entries; got: " + parseErrors);
	}

	@Test
	void appendHistoricallyResolved_usesFallbackRefWhenMetadataBomRefMissing() throws Exception {
		// Real-world case: a release with no sid PURL produces a bom whose metadata.component
		// has no bom-ref. The orchestrator passes ReleaseData.getPreferredBomIdentifier() as
		// fallback (sid PURL → any PURL → release UUID); the static helper picks it up.
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		bom.getMetadata().getComponent().setBomRef(null);

		String fallback = FIXTURE_RELEASE.toString();
		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-9300", "pkg:npm/lodash@4.17.20",
					UUID.fromString("00000000-0000-0000-0000-0000000000ff"), "1.5",
					"2026-02-01T00:00:00Z"));

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, Map.of(), Map.of(), fallback);

		Vulnerability appended = bom.getVulnerabilities().stream()
				.filter(v -> "CVE-2024-9300".equals(v.getId())).findFirst().orElseThrow();
		assertNotNull(appended.getAffects(), "fallback ref must produce affects[]");
		assertFalse(appended.getAffects().isEmpty());
		assertEquals(fallback, appended.getAffects().get(0).getRef());
	}

	@Test
	void appendHistoricallyResolved_skipsAffectsWhenNoRefAvailable() throws Exception {
		// Defensive: if metadata.component has no bom-ref AND no fallback is supplied, skip
		// affects[] rather than emitting a malformed empty entry. CDX 1.6 schema permits
		// affects[] omission.
		Bom bom = invokeTransform(buildVdrLikeFixture(), Boolean.FALSE, Boolean.FALSE);
		bom.getMetadata().getComponent().setBomRef(null);

		List<HistoricallyResolvedFinding> findings = List.of(
				hrf("CVE-2024-9301", "pkg:npm/lodash@4.17.20",
					UUID.fromString("00000000-0000-0000-0000-0000000000fe"), "1.5",
					"2026-02-01T00:00:00Z"));

		ReleaseService.appendHistoricallyResolvedToBom(bom, findings, Map.of(), Map.of(), null);

		Vulnerability appended = bom.getVulnerabilities().stream()
				.filter(v -> "CVE-2024-9301".equals(v.getId())).findFirst().orElseThrow();
		assertTrue(appended.getAffects() == null || appended.getAffects().isEmpty(),
				"no fallback + no metadata bom-ref → affects[] must be omitted");
	}
}

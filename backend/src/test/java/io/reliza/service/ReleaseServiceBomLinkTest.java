/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.cyclonedx.Version;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Component.Type;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseSbomRef;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-7 row 11 (3.6): what a package VDR or CycloneDX VEX adds for its pinned release SBOM: one {@code bom}
 * external reference on the metadata component, and per finding one BOM-Link affect per component of the
 * pinned document carrying the finding's purl, beside the purl affect. The builder's own wiring is
 * covered over the test database by PackagePartGeneratorsIntegrationTest (row 12); a null pin keeps the
 * existing VDR suites' bytes.
 */
class ReleaseServiceBomLinkTest {

	static final UUID SERIAL = UUID.fromString("3e671687-395b-41f5-a30f-a58921a69b79");
	static final ReleaseSbomRef PIN = new ReleaseSbomRef(SERIAL, 2, "ab".repeat(32), UUID.randomUUID());
	static final String CDX = "pkg:npm/left-pad@1.3.0";
	static final String SYFT_REF = "pkg:deb/debian/zlib@1.3?package-id=abc";
	static final String SYFT_PURL = "pkg:deb/debian/zlib@1.3?arch=amd64&distro=debian-13";
	static final String UUID_PURL = "pkg:maven/org.acme/core@2.0";
	static final String UUID_REF = "0e9f6c47-2d5c-4b9e-8a0f-36f1b2c4d5e6";
	static final String NESTED = "pkg:npm/nested@5";

	/** The pinned document: cdxgen-, syft- and uuid-shaped components, a HIERARCHICAL parent with a nested one. */
	static JsonNode pinned() {
		return Utils.OM.readTree("""
				{"bomFormat": "CycloneDX", "specVersion": "1.6", "serialNumber": "urn:uuid:%s", "version": 2,
				 "metadata": {"component": {"type": "application", "name": "pump", "bom-ref": "root",
				   "purl": "pkg:generic/pump@1"}},
				 "components": [
				   {"type": "library", "name": "left-pad", "bom-ref": "%s", "purl": "%s"},
				   {"type": "library", "name": "zlib", "bom-ref": "%s", "purl": "%s"},
				   {"type": "library", "name": "core", "bom-ref": "%s", "purl": "%s"},
				   {"type": "library", "name": "parent", "bom-ref": "parent", "purl": "pkg:npm/parent@4",
				    "components": [{"type": "library", "name": "nested", "bom-ref": "n5", "purl": "%s"}]},
				   {"type": "library", "name": "twice-a", "bom-ref": "x", "purl": "pkg:npm/twice@9"},
				   {"type": "library", "name": "twice-b", "bom-ref": "y", "purl": "pkg:npm/twice@9"}]}"""
				.formatted(SERIAL, CDX, CDX, SYFT_REF, SYFT_PURL, UUID_REF, UUID_PURL, NESTED));
	}

	static List<Vulnerability.Version> versions() {
		Vulnerability.Version v = new Vulnerability.Version();
		v.setRange("vers:npm/<1.3.1");
		v.setStatus(Vulnerability.Version.Status.AFFECTED);
		return List.of(v);
	}

	static List<String> refs(List<Vulnerability.Affect> affects) {
		return affects.stream().map(Vulnerability.Affect::getRef).toList();
	}

	@Test
	void aFindingOnEachShapeGetsOneBomLinkToItsComponentWithThePurlAffectsVersions() {
		BomLinkIndex idx = BomLinkIndex.of(pinned());
		String base = "urn:cdx:" + SERIAL + "/2#";
		List<Vulnerability.Affect> cdx = ReleaseService.bomLinkAffects(PIN, idx, CDX, versions(), "CVE-1");
		assertEquals(List.of(base + CDX), refs(cdx));
		assertEquals("vers:npm/<1.3.1", cdx.get(0).getVersions().get(0).getRange());
		assertEquals(List.of(base + SYFT_REF), refs(ReleaseService.bomLinkAffects(PIN, idx, "pkg:deb/debian/zlib@1.3",
				versions(), "CVE-2")), "the qualifier-free finding purl");
		assertEquals(List.of(base + UUID_REF), refs(ReleaseService.bomLinkAffects(PIN, idx, UUID_PURL, List.of(),
				"CVE-3")));
		assertEquals(List.of(base + "n5"), refs(ReleaseService.bomLinkAffects(PIN, idx, NESTED, List.of(), "CVE-4")),
				"the nested component too");
		assertNull(ReleaseService.bomLinkAffects(PIN, idx, NESTED, List.of(), "CVE-4").get(0).getVersions(),
				"no versions when the purl affect has none");
		assertEquals(List.of(), ReleaseService.bomLinkAffects(PIN, idx, "pkg:npm/elsewhere@1", versions(), "CVE-5"),
				"a purl in no component: the purl affect only");
		assertEquals(List.of(base + "x", base + "y"),
				refs(ReleaseService.bomLinkAffects(PIN, idx, "pkg:npm/twice@9", List.of(), "CVE-6")), "document order");
		assertEquals(List.of(), ReleaseService.bomLinkAffects(null, null, CDX, versions(), "CVE-7"), "no pin, no link");
	}

	@Test
	void aPurlCarriedByMoreThanTheCapIsLinkedUpToTheCapWithOneWarn() {
		StringBuilder components = new StringBuilder();
		for (int i = 0; i < 130; i++) {
			if (i > 0) components.append(',');
			components.append("{\"type\":\"library\",\"name\":\"c\",\"bom-ref\":\"r").append(i)
					.append("\",\"purl\":\"pkg:npm/many@1\"}");
		}
		BomLinkIndex idx = BomLinkIndex.of(Utils.OM.readTree("{\"components\":[" + components + "]}"));
		List<LogEvent> warns = Collections.synchronizedList(new ArrayList<>());
		AbstractAppender appender = new AbstractAppender("fdaw7-cap-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (Level.WARN == event.getLevel()) warns.add(event.toImmutable());
			}
		};
		appender.start();
		var logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class);
		Level level = logger.getLevel();
		Configurator.setLevel(ReleaseService.class.getName(), Level.WARN);
		logger.addAppender(appender);
		try {
			List<Vulnerability.Affect> affects = ReleaseService.bomLinkAffects(PIN, idx, "pkg:npm/many@1", List.of(),
					"CVE-9");
			assertEquals(BomLinkIndex.MAX_LINKS_PER_FINDING, affects.size());
			assertEquals("urn:cdx:" + SERIAL + "/2#r0", affects.get(0).getRef());
		} finally {
			logger.removeAppender(appender);
			appender.stop();
			Configurator.setLevel(ReleaseService.class.getName(), level);
		}
		assertEquals(1, warns.size());
		assertTrue(warns.get(0).getMessage().getFormattedMessage().contains("130 components of the pinned release SBOM "
				+ SERIAL + " carry its purl; the first 100 are linked"), warns.get(0).getMessage().getFormattedMessage());
	}

	/** A VDR as buildVdrBom shapes it with a pin: the bom reference on the root, a finding with both affects. */
	static Bom vdrWithPin() {
		Bom bom = new Bom();
		bom.setSerialNumber("urn:uuid:" + UUID.randomUUID());
		bom.setVersion(1);
		Component root = new Component();
		root.setType(Type.APPLICATION);
		root.setName("pump");
		root.setVersion("1.2.3");
		Utils.augmentRootBomComponent("Acme", root);
		ReleaseService.addReleaseSbomReference(root, PIN);
		Utils.setRearmBomMetadata(bom, root);
		Vulnerability v = new Vulnerability();
		v.setBomRef(UUID.randomUUID().toString());
		v.setId("CVE-2026-0001");
		Vulnerability.Source src = new Vulnerability.Source();
		src.setName("NVD");
		v.setSource(src);
		Vulnerability.Analysis a = new Vulnerability.Analysis();
		a.setState(Vulnerability.Analysis.State.EXPLOITABLE);
		v.setAnalysis(a);
		Vulnerability.Affect purl = new Vulnerability.Affect();
		purl.setRef(CDX);
		purl.setVersions(versions());
		List<Vulnerability.Affect> affects = new ArrayList<>(List.of(purl));
		affects.addAll(ReleaseService.bomLinkAffects(PIN, BomLinkIndex.of(pinned()), CDX, versions(), v.getId()));
		v.setAffects(affects);
		bom.setVulnerabilities(new ArrayList<>(List.of(v)));
		return bom;
	}

	static void assertSchemaValid(Bom bom) throws Exception {
		String json = BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
		List<ParseException> errors = new JsonParser().validate(json.getBytes(StandardCharsets.UTF_8),
				Version.VERSION_16);
		assertTrue(errors.isEmpty(), () -> errors + "\n" + json);
	}

	@Test
	void theRootNamesThePinOnceAndTheDocumentIsCdx16Valid() throws Exception {
		Bom bom = vdrWithPin();
		List<ExternalReference> boms = bom.getMetadata().getComponent().getExternalReferences().stream()
				.filter(r -> ExternalReference.Type.BOM == r.getType()).toList();
		assertEquals(1, boms.size());
		assertEquals("urn:cdx:" + SERIAL + "/2", boms.get(0).getUrl());
		assertEquals("the release SBOM this document describes", boms.get(0).getComment());
		assertEquals(List.of(CDX, "urn:cdx:" + SERIAL + "/2#" + CDX),
				refs(bom.getVulnerabilities().get(0).getAffects()), "the purl affect first, then the BOM-Link");
		assertSchemaValid(bom);
	}

	@Test
	void theCycloneDxVexKeepsTheReferenceAndTheBomLinks() throws Exception {
		Bom bom = vdrWithPin();
		ReleaseService.transformVdrBomToCdxVex(bom, UUID.randomUUID(), Boolean.FALSE, null, null, null, Boolean.FALSE,
				"urn:uuid:" + UUID.randomUUID());
		assertEquals("urn:cdx:" + SERIAL + "/2", bom.getMetadata().getComponent().getExternalReferences().stream()
				.filter(r -> ExternalReference.Type.BOM == r.getType()).findFirst().orElseThrow().getUrl());
		assertEquals(List.of(CDX, "urn:cdx:" + SERIAL + "/2#" + CDX), refs(bom.getVulnerabilities().get(0).getAffects()));
		assertSchemaValid(bom);
	}

	@Test
	void anUnreadablePinnedDocumentIsARelizaExceptionNamingThePin() throws Exception {
		RebomService rebom = mock(RebomService.class);
		ReleaseService service = new ReleaseService(null);
		ReflectionTestUtils.setField(service, "rebomService", rebom);
		UUID org = UUID.randomUUID();

		when(rebom.findBomByVersion(any(), any(), any())).thenThrow(new IllegalStateException("rebom down"));
		RelizaException thrown = assertThrows(RelizaException.class, () -> service.pinnedDocument(PIN, org));
		assertEquals("the pinned release SBOM " + SERIAL + " version 2 could not be read: rebom down",
				thrown.getMessage());

		doReturn(null).when(rebom).findBomByVersion(any(), any(), any());
		assertEquals("the pinned release SBOM " + SERIAL + " version 2 could not be read: rebom answered no document",
				assertThrows(RelizaException.class, () -> service.pinnedDocument(PIN, org)).getMessage());
		doReturn(Utils.OM.createObjectNode()).when(rebom).findBomByVersion(any(), any(), any());
		assertThrows(RelizaException.class, () -> service.pinnedDocument(PIN, org));

		doReturn(pinned()).when(rebom).findBomByVersion(SERIAL, org, 2);
		assertEquals(pinned(), service.pinnedDocument(PIN, org));
	}
}

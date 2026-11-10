/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.Utils;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.OverlayKind;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentData;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.node.ObjectNode;

/** FDAW-15 row 6 (REQ-6): the vendored component row is born with its first attestation, in one transaction. */
class SbomComponentVendoredSupportIntegrationTest extends SbomComponentOverlayTestBase {

	@Autowired SupportInjectionService supportInjectionService;

	private static SupportAttestationRequest level() {
		return new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null, null, null, null, null, null,
				null, Set.of(), null);
	}

	private static SupportAttestationRequest empty() {
		return new SupportAttestationRequest(null, null, null, null, null, null, null, null, null, Set.of(), null);
	}

	private long components(String key) {
		return count("sbom_components", "org = ? AND canonical_purl = ?", org.getUuid(), key);
	}

	@Test
	void theFirstSaveBearsTheRowAndItsSupportAndTheNextPatchesThem() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libborn-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "2.3");
		overlayService.describeVendored(rd, name, "2.3", description("d"), null, random());

		UUID uuid = overlayService.saveVendoredSupport(rd, name, "2.3", level(), admin().getUuid());

		assertEquals(1, components(key));
		SbomComponent sc = sbomComponentRepository.findById(uuid).orElseThrow();
		assertEquals(key, sc.getCanonicalPurl());
		SbomComponentData data = SbomComponentData.dataFromRecord(sc);
		assertEquals("generic", data.type());
		assertEquals(name, data.name());
		assertEquals("2.3", data.version());
		assertEquals(List.of(new ComponentIdentity("purl", key)), sc.getIdentities());
		assertEquals("UNMATCHABLE_PURL_TYPE", sc.getFlowControl().enrichmentTerminalReason());
		assertEquals(1, count("sbom_component_support", "sbom_component_uuid = ?", uuid));
		assertEquals(1, count("sbom_component_support_audit", "sbom_component_uuid = ?", uuid));

		UUID again = overlayService.saveVendoredSupport(rd, name, "2.3", new SupportAttestationRequest(
				LevelOfSupport.NO_LONGER_MAINTAINED, null, null, "upstream stopped", null, null, null, null, null, Set.of(), null),
				admin().getUuid());
		assertEquals(uuid, again);
		assertEquals(1, components(key));
		assertEquals(1, count("sbom_component_support", "sbom_component_uuid = ?", uuid));
		assertEquals(2, count("sbom_component_support_audit", "sbom_component_uuid = ?", uuid));
	}

	@Test
	void aRefusedRequestLeavesNoRowAndAnUndescribedKeyIsRefused() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "librefused-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		assertEquals("describe the component before attesting its support", assertThrows(IllegalArgumentException.class,
				() -> overlayService.saveVendoredSupport(rd, name, "1", level(), random())).getMessage());
		assertEquals(0, components(key));

		overlayService.describeVendored(rd, name, "1", description("d"), null, random());
		assertThrows(IllegalArgumentException.class, () -> overlayService.saveVendoredSupport(rd, name, "1", empty(), random()));
		assertEquals(0, components(key));
	}

	@Test
	void twoConcurrentSavesOfOneKeyYieldOneRowAndBothSucceed() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libconcurrent-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		overlayService.describeVendored(rd, name, "1", description("d"), null, random());
		UUID writer = admin().getUuid();
		AtomicReference<UUID> first = new AtomicReference<>();
		AtomicReference<UUID> second = new AtomicReference<>();

		// both carry a justification: either can land second, and a change of level needs one
		together(() -> first.set(overlayService.saveVendoredSupport(rd, name, "1", new SupportAttestationRequest(
						LevelOfSupport.ACTIVELY_MAINTAINED, null, null, "resumed", null, null, null, null, null, Set.of(), null),
						writer)),
				() -> second.set(overlayService.saveVendoredSupport(rd, name, "1", new SupportAttestationRequest(
						LevelOfSupport.NO_LONGER_MAINTAINED, null, null, "stopped", null, null, null, null, null, Set.of(), null),
						writer)));

		assertEquals(first.get(), second.get());
		assertEquals(1, components(key));
		assertEquals(1, count("sbom_component_support", "sbom_component_uuid = ?", first.get()));
		assertEquals(2, count("sbom_component_support_audit", "sbom_component_uuid = ?", first.get()));
		// Row 6 says revision 2; the support row's @Version starts at 0, so two writes read 1. Named in the note.
		assertEquals(1, jdbc.queryForObject("SELECT revision FROM rearm.sbom_component_support WHERE sbom_component_uuid = ?",
				Integer.class, first.get()));
	}

	/** Row 6's removal keeps the born row; a supplier on it then leaves a COMPONENT overlay the next describe takes over. */
	@Test
	void aDescribeTakesOverAComponentOverlayLeftOnABornRow() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libreborn-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		overlayService.describeVendored(rd, name, "1", description("d"), null, random());
		UUID uuid = overlayService.saveVendoredSupport(rd, name, "1", level(), admin().getUuid());
		overlayService.removeVendoredDescription(rd, name, "1", "wrong", random());
		overlayService.setComponentOverlay(uuid, supplier("Acme"), null, random());
		assertEquals(OverlayKind.COMPONENT, overlayOf(key).getKind());

		overlayService.describeVendored(rd, name, "1", description("again"), null, random());

		SbomComponentOverlay row = overlayOf(key);
		assertEquals(OverlayKind.VENDORED, row.getKind());
		assertEquals(name, row.getRecordData().name());
		assertEquals("1", row.getRecordData().version());
		assertEquals("Acme", row.getRecordData().supplier());
		assertEquals("again", row.getRecordData().description());
		assertEquals(uuid, overlayService.saveVendoredSupport(rd, name, "1", level(), admin().getUuid()));
		overlayService.removeVendoredDescription(rd, name, "1", "wrong again", random());
		assertFalse(hasOverlay(key));
	}

	@Test
	void theBornRowSurvivesTheGcAndARemovalAndResolvesFromTheExportedPurl() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libgc-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		overlayService.describeVendored(rd, name, "1", description("d"), null, random());
		UUID uuid = overlayService.saveVendoredSupport(rd, name, "1", level(), admin().getUuid());

		sbomComponentService.gcOrphanedComponents(500);
		assertEquals(uuid, sbomComponentRepository.findByOrgAndCanonicalPurl(org.getUuid(), key).orElseThrow().getUuid());

		ObjectNode bom = Utils.OM.createObjectNode();
		bom.putArray("components").addObject().put("type", "library").put("name", name).put("version", "1")
				.put("purl", VendoredIdentifier.exportPurl(key));
		Map<String, ComponentSupportFacts> facts = supportInjectionService.resolveSupportFactsForBom(bom, org.getUuid());
		assertEquals(uuid, facts.get(key).component().getUuid());
		assertTrue(null != facts.get(key).support());

		overlayService.removeVendoredDescription(rd, name, "1", "wrong", random());
		assertFalse(hasOverlay(key));
		assertEquals(1, components(key));
		assertEquals(1, count("sbom_component_support", "sbom_component_uuid = ?", uuid));
	}
}

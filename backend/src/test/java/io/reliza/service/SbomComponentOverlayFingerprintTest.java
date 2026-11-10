/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentOverlayPatch;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** FDAW-15 row 11 (REQ-11): the fingerprint lines, the vendored keys and the count term. */
class SbomComponentOverlayFingerprintTest extends SbomComponentOverlayTestBase {

	private List<String> lines(List<String> keys, Map<String, ComponentSupportFacts> facts) {
		return overlayService.fingerprintLines(org.getUuid(), keys, facts);
	}

	@Test
	void theLineMovesWithEveryWriteAndNeverRepeatsAcrossARemoval() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libprint-" + random();
		String k1 = VendoredIdentifier.mint(org.getUuid(), name, "1");
		String k2 = VendoredIdentifier.mint(org.getUuid(), name, "2");
		List<String> keys = List.of(k1, k2);
		assertEquals(List.of("overlay:" + k1 + "=0", "overlay:" + k2 + "=0"), lines(keys, Map.of()));

		overlayService.describeVendored(rd, name, "1", new SbomComponentOverlayPatch("Acme", null, "d", null), null, random());
		assertEquals("overlay:" + k1 + "=1", lines(keys, Map.of()).get(0));
		overlayService.describeVendored(rd, name, "1", supplier(""), null, random());
		assertEquals("overlay:" + k1 + "=2", lines(keys, Map.of()).get(0));
		overlayService.removeVendoredDescription(rd, name, "1", "wrong", random());
		assertEquals("overlay:" + k1 + "=0", lines(keys, Map.of()).get(0));
		overlayService.describeVendored(rd, name, "1", description("other text"), null, random());
		assertEquals("overlay:" + k1 + "=4", lines(keys, Map.of()).get(0));
		assertEquals("overlay:" + k2 + "=0", lines(keys, Map.of()).get(1));
	}

	@Test
	void aFirstDescribeRemoveReDescribeReadsOneThenZeroThenThree() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libthree-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		overlayService.describeVendored(rd, name, "1", description("a"), null, random());
		assertEquals("overlay:" + key + "=1", lines(List.of(key), Map.of()).get(0));
		overlayService.removeVendoredDescription(rd, name, "1", "wrong", random());
		assertEquals("overlay:" + key + "=0", lines(List.of(key), Map.of()).get(0));
		overlayService.describeVendored(rd, name, "1", description("b"), null, random());
		assertEquals("overlay:" + key + "=3", lines(List.of(key), Map.of()).get(0));
	}

	@Test
	void theLineIsNamedByTheRecordedKeyAndReadsTheChosenRow() throws Exception {
		String salt = random().toString().substring(0, 8);
		for (String[] pair : List.of(new String[] {"pkg:deb/debian/libfoo" + salt + "@1.2+dfsg-1", "pkg:deb/debian/libfoo" + salt + "@1.2%2Bdfsg-1"},
				new String[] {"pkg:generic/acme" + salt + "/my%20lib@1", "pkg:generic/acme" + salt + "/my%2520lib@1"})) {
			String nodeKey = pair[0];
			SbomComponent row = components(org, null, List.of(pair[1]), false).get(0);
			Map<String, ComponentSupportFacts> facts = Map.of(nodeKey, new ComponentSupportFacts(row, null));
			assertEquals(List.of("overlay:" + nodeKey + "=0"), lines(List.of(nodeKey), facts));
			overlayService.setComponentOverlay(row.getUuid(), supplier("Acme"), null, random());
			assertEquals(List.of("overlay:" + nodeKey + "=1"), lines(List.of(nodeKey), facts));
		}
	}

	@Test
	void theCountTermCountsTheChosenRowsAuditAfterTheBuild() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libcount-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		String nodeKey = "pkg:deb/debian/libbar" + random().toString().substring(0, 8) + "@1+x";
		SbomComponent row = components(org, null, List.of(nodeKey.replace("+", "%2B")), false).get(0);
		Map<String, ComponentSupportFacts> facts = Map.of(nodeKey, new ComponentSupportFacts(row, null));
		List<String> keys = List.of(key, nodeKey);
		overlayService.describeVendored(rd, name, "1", description("before"), null, random());
		ZonedDateTime builtAt = ZonedDateTime.now();
		assertEquals(0, overlayService.countAuditSince(org.getUuid(), keys, facts, builtAt));

		overlayService.describeVendored(rd, name, "1", description("after"), null, random());
		assertEquals(1, overlayService.countAuditSince(org.getUuid(), keys, facts, builtAt));
		overlayService.setComponentOverlay(row.getUuid(), supplier("Acme"), null, random());
		assertEquals(2, overlayService.countAuditSince(org.getUuid(), keys, facts, builtAt));
		overlayService.removeVendoredDescription(rd, name, "1", "wrong", random());
		assertEquals(3, overlayService.countAuditSince(org.getUuid(), keys, facts, builtAt));
		overlayService.describeVendored(rd, "other-" + random(), "1", description("elsewhere"), null, random());
		assertEquals(3, overlayService.countAuditSince(org.getUuid(), keys, facts, builtAt));
	}

	@Test
	void theVendoredKeysAreTheStillKeylessDescribableNodes() throws Exception {
		ObjectNode bom = Utils.OM.createObjectNode();
		ArrayNode components = bom.putArray("components");
		components.addObject().put("name", " libdosecalc").put("version", "");
		String described = VendoredIdentifier.mint(org.getUuid(), "described", "1");
		components.addObject().put("name", "described").put("version", "1").put("purl", VendoredIdentifier.exportPurl(described));
		components.addObject().put("name", "libdosecalc").put("purl", "libdosecalc");
		components.addObject().put("name", "n".repeat(513)).put("version", "1");
		assertEquals(List.of(VendoredIdentifier.mint(org.getUuid(), "libdosecalc", null)),
				overlayService.vendoredKeysOf(org.getUuid(), bom));
	}
}

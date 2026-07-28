/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;

/**
 * Pins how VDR component enrichment binds a merged SBOM into the CycloneDX Java
 * model (see {@code ReleaseService.buildVdrBom}).
 *
 * <p>The regression these exist for: cyclonedx-core-java is a Jackson 2 library.
 * It models {@code licenses} as a single {@code LicenseChoice} while the spec
 * serialises it as an ARRAY, and bridges the two with deserializers registered
 * through Jackson 2 annotations. {@code Utils.OM} is Jackson 3
 * ({@code tools.jackson}) and cannot see those annotations, so binding a
 * component with it blew up on the first component carrying licenses — aborting
 * enrichment and silently degrading every component in the VDR to the minimal
 * purl-only fallback, while the document still exported and still validated.
 *
 * <p>Live from the Jackson 3 upgrade (2026-05-22) until 2026-07-28. Note that
 * cyclonedx-core-java 13.0.0 does NOT resolve this — it is still Jackson 2 with
 * {@code licenses} still a singular {@code LicenseChoice} — so the fix is to
 * bind through the library's own parser, and the second test below is what
 * keeps that decision honest if anyone reaches for {@code Utils.OM} again.
 */
public class VdrComponentBindingTest {

	/** A component carrying the ARRAY-shaped `licenses` the spec mandates. */
	private static final String BOM_WITH_LICENSED_COMPONENT = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "version": 1,
			  "components": [
			    {
			      "type": "library",
			      "name": "minimist",
			      "version": "1.2.0",
			      "purl": "pkg:npm/minimist@1.2.0",
			      "bom-ref": "pkg:npm/minimist@1.2.0",
			      "licenses": [{"license": {"id": "MIT"}}]
			    }
			  ]
			}
			""";

	/**
	 * The enrichment contract: the library's own parser binds the licenses array
	 * and keeps the rest of the component detail the VDR is supposed to carry.
	 */
	@Test
	public void cyclonedxParserBindsLicensesArray() throws Exception {
		Bom bom = new JsonParser().parse(BOM_WITH_LICENSED_COMPONENT.getBytes(StandardCharsets.UTF_8));

		assertNotNull(bom.getComponents(), "components must bind");
		assertEquals(1, bom.getComponents().size());
		Component c = bom.getComponents().get(0);

		assertEquals("pkg:npm/minimist@1.2.0", c.getPurl());
		// Version is the other field the minimal fallback cannot supply — a VDR
		// consumer may read a missing version as "all versions affected".
		assertEquals("1.2.0", c.getVersion());

		assertNotNull(c.getLicenses(), "licenses must bind (this is the whole bug)");
		assertNotNull(c.getLicenses().getLicenses(), "license list must bind");
		assertEquals(1, c.getLicenses().getLicenses().size());
		assertEquals("MIT", c.getLicenses().getLicenses().get(0).getId());
	}

	/**
	 * The guard rail: binding the same document with the Jackson 3 mapper still
	 * fails, and must keep failing. If a future Jackson/cyclonedx combination
	 * ever makes this pass, the workaround above can be revisited — but until
	 * then this test is what stops the round-trip being "simplified" back.
	 */
	@Test
	public void jackson3MapperCannotBindCyclonedxComponent() {
		assertThrows(Exception.class, () -> {
			var tree = Utils.OM.readTree(BOM_WITH_LICENSED_COMPONENT);
			for (var compNode : tree.get("components")) {
				Utils.OM.treeToValue(compNode, Component.class);
			}
		}, "Utils.OM (Jackson 3) must not be used to bind org.cyclonedx.model types");
	}

	/** A component with no licenses binds fine either way — pins the trigger. */
	@Test
	public void componentWithoutLicensesBindsWithBothMappers() throws Exception {
		String bomJson = BOM_WITH_LICENSED_COMPONENT
				.replace(",\n      \"licenses\": [{\"license\": {\"id\": \"MIT\"}}]", "");
		assertTrue(!bomJson.contains("licenses"), "fixture must have dropped the licenses array");

		Bom bom = new JsonParser().parse(bomJson.getBytes(StandardCharsets.UTF_8));
		assertEquals("pkg:npm/minimist@1.2.0", bom.getComponents().get(0).getPurl());

		var tree = Utils.OM.readTree(bomJson);
		Component viaJackson3 = Utils.OM.treeToValue(tree.get("components").get(0), Component.class);
		assertEquals("pkg:npm/minimist@1.2.0", viaJackson3.getPurl());
	}
}

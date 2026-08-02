/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Pins the rule that {@code org.cyclonedx.model.*} is bound with
 * {@link Utils#CDX_OM} (Jackson 2) and never {@link Utils#OM} (Jackson 3).
 *
 * <p>cyclonedx-core-java is a Jackson 2 library. Annotations are not the issue --
 * {@code com.fasterxml.jackson.annotation.*} was never renamed -- but
 * {@code @JsonSerialize/@JsonDeserialize(using = ...)} handlers extend Jackson 2
 * databind classes that Jackson 3 cannot apply. Both directions break, and the
 * write direction breaks SILENTLY, which is why it went unnoticed longer than the
 * read direction.
 */
public class CdxMapperContractTest {

	/** A VEX statement whose dates exercise the library's CustomDateSerializer. */
	private static Vulnerability vulnerabilityWithDates() {
		Vulnerability v = new Vulnerability();
		v.setBomRef("v-1");
		v.setId("CVE-2020-7598");
		v.setPublished(new Date(1750000000000L));
		Vulnerability.Analysis a = new Vulnerability.Analysis();
		a.setState(Vulnerability.Analysis.State.NOT_AFFECTED);
		a.setFirstIssued(new Date(1750000000000L));
		v.setAnalysis(a);
		return v;
	}

	/**
	 * WRITE: the spec types dates as ISO-8601 strings. This is what
	 * CdxVexParser persists as a VEX statement's provenance record and hashes as
	 * its dedup key, so the shape is durable, not cosmetic.
	 */
	@Test
	public void cdxMapperWritesSpecDatesAsIsoStrings() throws Exception {
		String json = Utils.CDX_OM.writeValueAsString(vulnerabilityWithDates());

		assertTrue(json.contains("\"published\":\"2025-06-15T15:06:40Z\""),
				"published must be an ISO-8601 string, got: " + json);
		assertTrue(json.contains("\"firstIssued\":\"2025-06-15T15:06:40Z\""),
				"analysis.firstIssued must be an ISO-8601 string, got: " + json);
		// bom-ref proves the annotation-driven rename still applies.
		assertTrue(json.contains("\"bom-ref\":\"v-1\""), "bom-ref key must survive: " + json);
	}

	/**
	 * The guard rail for the write direction. Utils.OM emits epoch millis here --
	 * a JSON number where the spec demands a string -- and does NOT throw. If this
	 * ever starts matching CDX_OM, the workaround can be revisited; until then
	 * this is what stops someone "simplifying" CdxVexParser back to Utils.OM.
	 */
	@Test
	public void jackson3MapperSilentlyWritesNonSpecDates() throws Exception {
		String viaJackson3 = Utils.OM.writeValueAsString(vulnerabilityWithDates());
		String viaCdx = Utils.CDX_OM.writeValueAsString(vulnerabilityWithDates());

		assertFalse(viaJackson3.equals(viaCdx),
				"if these now agree, Utils.OM may be safe for cyclonedx writes -- re-evaluate");
		assertTrue(viaJackson3.contains("\"published\":1750000000000"),
				"Utils.OM is expected to emit epoch millis (the bug being guarded), got: " + viaJackson3);
	}

	/** READ: the licenses ARRAY binds through CDX_OM. */
	@Test
	public void cdxMapperReadsLicensesArray() throws Exception {
		String bomJson = """
				{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
				 "components":[{"type":"library","name":"minimist","version":"1.2.0",
				   "purl":"pkg:npm/minimist@1.2.0","bom-ref":"pkg:npm/minimist@1.2.0",
				   "licenses":[{"license":{"id":"MIT"}}]}]}
				""";
		Bom bom = new JsonParser().parse(bomJson.getBytes(StandardCharsets.UTF_8));
		Component c = bom.getComponents().get(0);
		assertNotNull(c.getLicenses(), "licenses must bind");
		assertEquals("MIT", c.getLicenses().getLicenses().get(0).getId());

		// And the same document must still defeat the Jackson 3 mapper.
		assertThrows(Exception.class, () -> {
			var tree = Utils.OM.readTree(bomJson);
			Utils.OM.treeToValue(tree.get("components").get(0), Component.class);
		}, "Utils.OM must not be able to bind org.cyclonedx.model types");
	}
}

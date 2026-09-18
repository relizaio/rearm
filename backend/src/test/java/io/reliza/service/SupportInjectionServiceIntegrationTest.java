/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.Organization;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportSource;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.JsonNode;

/**
 * Integration test for the read-time support-injection MATCH against a real Postgres: the
 * byte-exact pass, the encoding-safe fallback for the {@code +}/{@code %2B} canonical-purl
 * drift, and that unmatched components get nothing. Exercises the actual repository queries
 * ({@code findByOrgAndCanonicalPurlIn} / {@code findCandidatesByOrgAndNames}) +
 * {@code Utils.purlsSemanticallyEqual}. Uses a fresh per-test org.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SupportInjectionServiceIntegrationTest {

	@Autowired private SupportInjectionService supportInjectionService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private TestInitializer testInitializer;

	private SbomComponent supported(UUID org, String canonicalPurl, String name, String version, LocalDate eos) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", name);
		rd.put("version", version);
		sc.setRecordData(rd);
		sc.setSupportSource(SupportSource.MANUAL);
		sc.setEndOfSupportDate(eos);
		sc.setSupportLastAssessed(ZonedDateTime.now());
		return sbomComponentRepository.save(sc);
	}

	private Map<String, String> propMap(JsonNode component) {
		Map<String, String> out = new HashMap<>();
		JsonNode props = component.get("properties");
		if (props == null || !props.isArray()) return out;
		for (JsonNode p : props) {
			out.put(p.get("name").asText(), p.get("value").asText());
		}
		return out;
	}

	@Test
	void injectsByteMatchedAndEncodingDriftedAndSkipsUnmatched() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		String salt = UUID.randomUUID().toString().substring(0, 8);

		// recordData 'name' must equal the purl's name -- the encoding-safe fallback resolves
		// candidates by that decoded name. The salted namespace keeps canonical_purl unique
		// across reruns on the shared DB without perturbing the name.
		// (A) byte-exact: stored canonical == the node's preserving-canonical.
		supported(orgUuid, "pkg:maven/org.reliza.test." + salt + "/lib@1.2.3", "lib", "1.2.3",
				LocalDate.now().minusDays(10)); // past EOS -> END_OF_SUPPORT
		// (B) encoding drift: stored with %2B, the BOM node carries a raw + -> byte pass misses,
		// the name + purlsSemanticallyEqual fallback must catch it.
		supported(orgUuid, "pkg:maven/org.reliza.test." + salt + "/thing@1.0.0%2Br1", "thing", "1.0.0+r1",
				LocalDate.now().plusYears(1)); // future EOS -> ACTIVELY_SUPPORTED

		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.5","components":[
			  {"type":"library","name":"lib","purl":"pkg:maven/org.reliza.test.%s/lib@1.2.3"},
			  {"type":"library","name":"thing","purl":"pkg:maven/org.reliza.test.%s/thing@1.0.0+r1"},
			  {"type":"library","name":"nope","purl":"pkg:npm/nope-%s@9.9.9"}
			]}""".formatted(salt, salt, salt));

		supportInjectionService.injectCurrentSupport(bom, orgUuid);

		JsonNode comps = bom.get("components");
		Map<String, String> a = propMap(comps.get(0));
		assertEquals("MANUAL", a.get(SupportBomInjector.PROP_SOURCE), "byte-exact component matched");
		assertEquals("END_OF_SUPPORT", a.get(SupportBomInjector.PROP_STATUS));

		Map<String, String> b = propMap(comps.get(1));
		assertEquals("MANUAL", b.get(SupportBomInjector.PROP_SOURCE),
				"encoding-drifted (+ vs %2B) component matched via the fallback");
		assertEquals("ACTIVELY_SUPPORTED", b.get(SupportBomInjector.PROP_STATUS));

		assertTrue(propMap(comps.get(2)).isEmpty(), "unmatched component gets no support properties");

		// Document-level disclosure marker is always stamped (metadata created since the
		// fixture had none).
		Map<String, String> meta = propMap(bom.get("metadata"));
		assertEquals(SupportBomInjector.DISCLOSURE_CURRENT_STATE, meta.get(SupportBomInjector.PROP_DISCLOSURE));
	}
}

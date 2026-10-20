/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.Utils;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.OrganizationData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentSupport;
import io.reliza.model.SupportData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-29 (design T-8 and T-13, ADR-4 and ADR-6) against a real Postgres: support facts stored on a release-node
 * row written before the purl fix (its canonical purl reads %2520 for a space) still reach the
 * export, whose root now carries %20; a row under the single-encoded key itself wins over the
 * legacy one. Fresh org per test, rows in one transaction (see
 * SupportInjectionServiceIntegrationTest for why).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SupportInjectionLegacyReleaseNodeKeyIntegrationTest {

	@Autowired private SupportInjectionService supportInjectionService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SbomComponentSupportRepository sbomComponentSupportRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private OrganizationService organizationService;

	private record Row(String canonicalPurl, LevelOfSupport level, ZonedDateTime assessedAt) {}

	private void supported(UUID org, Row... rows) {
		new TransactionTemplate(transactionManager).execute(status -> {
			for (Row r : rows) {
				SbomComponent sc = new SbomComponent();
				sc.setOrg(org);
				sc.setCanonicalPurl(r.canonicalPurl());
				Map<String, Object> rd = new HashMap<>();
				rd.put("name", "ReARM Pro");
				rd.put("version", "1.0.0");
				sc.setRecordData(rd);
				sc = sbomComponentRepository.save(sc);
				String assessed = r.assessedAt().toInstant().toString();
				SbomComponentSupport row = new SbomComponentSupport();
				row.setSbomComponentUuid(sc.getUuid());
				row.setOrg(org);
				row.setCanonicalPurl(r.canonicalPurl());
				row.setSupportData(new SupportData(r.level(), SupportState.ATTESTED, null, SupportSource.MANUAL,
						assessed, null, null,
						Map.of(SupportMilestoneType.END_OF_SUPPORT, new SupportMilestoneFact(
								LocalDate.now().plusYears(1).toString(), SupportSource.MANUAL, assessed, null, null))));
				sbomComponentSupportRepository.save(row);
			}
			return null;
		});
	}

	private UUID enabledOrg() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		OrganizationData.Settings enable = new OrganizationData.Settings();
		enable.setSupportInjection(SupportInjectionSetting.ENABLED);
		organizationService.updateSettings(org, enable, WhoUpdated.getTestWhoUpdated());
		return org;
	}

	/** An export whose root is the single-encoded release node, beside a component nobody attests. */
	private JsonNode export(String namespace) {
		String root = "pkg:generic/" + namespace + "/ReARM%20Pro@1.0.0?tldonly=true";
		return Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"ReARM Pro","group":"%s","version":"1.0.0",
			   "purl":"%s","bom-ref":"%s"}},
			 "components":[{"type":"library","name":"nobody","purl":"pkg:npm/nobody-%s@1.0.0"},
			   {"type":"library","name":"none","purl":"pkg:npm/none-%s@2.0.0"}]}"""
				.formatted(namespace, root, root, namespace, namespace));
	}

	private String rootLevel(JsonNode served) {
		JsonNode props = served.get("metadata").get("component").get("properties");
		if (props == null) return null;
		for (JsonNode p : props) {
			if (SupportBomInjector.PROP_LEVEL.equals(p.get("name").asText())) return p.get("value").asText();
		}
		return null;
	}

	private JsonNode serve(JsonNode bom, UUID org) {
		return supportInjectionService.injectIfEnabledElseStrip(bom, org, ExportMetadataOptions.callerSilent());
	}

	@Test
	void factsOnTheLegacyRowReachTheSingleEncodedRoot() throws Exception {
		UUID org = enabledOrg();
		String ns = "reliza-test-" + UUID.randomUUID().toString().substring(0, 8);
		supported(org, new Row("pkg:generic/" + ns + "/ReARM%2520Pro@1.0.0", LevelOfSupport.ACTIVELY_MAINTAINED,
				ZonedDateTime.now().minusDays(2)));

		JsonNode served = serve(export(ns), org);

		assertEquals("actively maintained", rootLevel(served), "the legacy row matched through its variant: " + served);
	}

	/**
	 * Design T-13 (ADR-6): an org name with a slash. The pre-fix rebom wrote it {@code %252F}, the
	 * fixed one keeps it raw. The encoding-safe fallback cannot reach the legacy row (one decode of
	 * {@code acme%252Flabs} is {@code acme%2Flabs}, not {@code acme/labs}), so the facts arrive
	 * through the variant probe of the byte pass or not at all.
	 */
	@Test
	void factsOnALegacyRowWhoseNamespaceHeldASlashReachTheRoot() throws Exception {
		UUID org = enabledOrg();
		supported(org, new Row("pkg:generic/acme%252Flabs/svc@1.0.0", LevelOfSupport.ACTIVELY_MAINTAINED,
				ZonedDateTime.now().minusDays(2)));
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"svc","group":"acme/labs","version":"1.0.0",
			   "purl":"pkg:generic/acme/labs/svc@1.0.0","bom-ref":"pkg:generic/acme/labs/svc@1.0.0"}},
			 "components":[{"type":"library","name":"nobody","purl":"pkg:npm/nobody@1.0.0"}]}""");

		JsonNode served = serve(bom, org);

		assertEquals("actively maintained", rootLevel(served), "the legacy row matched through its variant: " + served);
	}

	@Test
	void aNewerRowUnderTheSingleEncodedKeyWinsOverTheLegacyRow() throws Exception {
		UUID org = enabledOrg();
		String ns = "reliza-test-" + UUID.randomUUID().toString().substring(0, 8);
		supported(org,
				new Row("pkg:generic/" + ns + "/ReARM%2520Pro@1.0.0", LevelOfSupport.ACTIVELY_MAINTAINED,
						ZonedDateTime.now().minusDays(2)),
				new Row("pkg:generic/" + ns + "/ReARM%20Pro@1.0.0", LevelOfSupport.NO_LONGER_MAINTAINED,
						ZonedDateTime.now().minusHours(1)));

		JsonNode served = serve(export(ns), org);

		assertEquals("no longer maintained", rootLevel(served), "the row under the key itself: " + served);
	}

	@Test
	void theRowUnderTheKeyItselfWinsEvenWhenTheLegacyRowWasAssessedLater() throws Exception {
		UUID org = enabledOrg();
		String ns = "reliza-test-" + UUID.randomUUID().toString().substring(0, 8);
		supported(org,
				new Row("pkg:generic/" + ns + "/ReARM%2520Pro@1.0.0", LevelOfSupport.ACTIVELY_MAINTAINED,
						ZonedDateTime.now().minusHours(1)),
				new Row("pkg:generic/" + ns + "/ReARM%20Pro@1.0.0", LevelOfSupport.NO_LONGER_MAINTAINED,
						ZonedDateTime.now().minusDays(2)));

		JsonNode served = serve(export(ns), org);

		assertEquals("no longer maintained", rootLevel(served), "design ADR-4: the key wins over its variant: " + served);
	}
}

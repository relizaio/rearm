/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.Utils;
import io.reliza.model.ExportMetadataChoice;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.Organization;
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
	@Autowired private SbomComponentSupportRepository sbomComponentSupportRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private OrganizationService organizationService;

	private static final String SELF = "reliza:support:selfComponent";
	private static final String STAMP = "reliza:export:releaseNode";

	/**
	 * Both rows in ONE transaction, which matters more than it looks.
	 *
	 * <p>{@code gcOrphanedComponents} runs on the PT1M tick in every {@code @SpringBootTest}
	 * context, and deletes any sbom_component that is unbucketed, mapped to no artifact, and
	 * carries no support row. Between a committed component save and a separately committed
	 * support save, this fixture's component is exactly that, and the support row it would have
	 * been protected by does not exist yet. A tick landing in that window deletes the component,
	 * the byte-exact pass then matches nothing, and the test fails with no properties injected --
	 * only ever in a long run, never standalone, which is what a millisecond window looks like.
	 * Production does not have the window: the ingest path writes components and their
	 * artifact_sbom_components mappings inside one {@code @Transactional} method.
	 */
	private SbomComponent supported(UUID org, String canonicalPurl, String name, String version, LocalDate eos) {
		return supported(org, canonicalPurl, name, version, eos, null);
	}

	/** As above, with an attested level of support (null: none). */
	private SbomComponent supported(UUID org, String canonicalPurl, String name, String version, LocalDate eos,
			LevelOfSupport level) {
		return supported(org, canonicalPurl, name, version, eos, level, false);
	}

	/**
	 * As above, the row marked {@code isRoot} when asked, as the reconcile marks the
	 * {@code metadata.component} of an uploaded SBOM (an operator's statement on their own entry
	 * is kept beside the marker, SCORE-58 ADR-2).
	 */
	private SbomComponent supported(UUID org, String canonicalPurl, String name, String version, LocalDate eos,
			LevelOfSupport level, boolean isRoot) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			SbomComponent sc = new SbomComponent();
			sc.setOrg(org);
			sc.setCanonicalPurl(canonicalPurl);
			Map<String, Object> rd = new HashMap<>();
			rd.put("name", name);
			rd.put("version", version);
			if (isRoot) {
				rd.put("isRoot", Boolean.TRUE);
			}
			sc.setRecordData(rd);
			sc = sbomComponentRepository.save(sc);
			String assessedAt = ZonedDateTime.now().toInstant().toString();
			SbomComponentSupport row = new SbomComponentSupport();
			row.setSbomComponentUuid(sc.getUuid());
			row.setOrg(org);
			row.setCanonicalPurl(canonicalPurl);
			row.setSupportData(new SupportData(level, SupportState.ATTESTED, null, SupportSource.MANUAL,
					assessedAt, null, null,
					Map.of(SupportMilestoneType.END_OF_SUPPORT, new SupportMilestoneFact(
							eos.toString(), SupportSource.MANUAL, assessedAt, null, null))));
			sbomComponentSupportRepository.save(row);
			return sc;
		});
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

	/**
	 * T-11 (SCORE-21, SCORE-12 ADR-10): a component whose purl sits only in its bom-ref gets the
	 * facts of the organization's row for that purl, through both lookup passes, on the export
	 * path of an ENABLED organization; the document carries the marker and the counts.
	 */
	@Test
	void injectsThroughABomRefPurlOnBothLookupPasses() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		OrganizationData.Settings enable = new OrganizationData.Settings();
		enable.setSupportInjection(SupportInjectionSetting.ENABLED);
		organizationService.updateSettings(orgUuid, enable, WhoUpdated.getTestWhoUpdated());
		String salt = UUID.randomUUID().toString().substring(0, 8);

		// Byte-exact pass: the row is the bom-ref's canonical form.
		supported(orgUuid, "pkg:golang/reliza.test." + salt + "/stdlib@v1.26.6", "stdlib", "v1.26.6",
				LocalDate.now().plusYears(1), LevelOfSupport.ACTIVELY_MAINTAINED);
		// Encoding-safe pass: the row has %2B, the bom-ref a raw +.
		supported(orgUuid, "pkg:deb/reliza-test-" + salt + "/g%2B%2B@12", "g++", "12",
				LocalDate.now().plusYears(1), LevelOfSupport.NO_LONGER_MAINTAINED);

		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","components":[
			  {"type":"library","name":"stdlib","version":"v1.26.6",
			   "bom-ref":"pkg:golang/reliza.test.%s/stdlib@v1.26.6?package-id=abc"},
			  {"type":"library","name":"g++","version":"12","bom-ref":"pkg:deb/reliza-test-%s/g++@12"},
			  {"type":"library","name":"nobody","purl":"pkg:npm/nobody-%s@1.0.0"},
			  {"type":"cryptographic-asset","name":"key","bom-ref":"comp-12"}
			]}""".formatted(salt, salt, salt));

		JsonNode served = supportInjectionService.injectIfEnabledElseStrip(bom, orgUuid,
				ExportMetadataOptions.callerSilent());

		JsonNode comps = served.get("components");
		assertEquals("actively maintained", propMap(comps.get(0)).get("reliza:support:levelOfSupport"),
				"the bom-ref purl matched byte-exact: " + served);
		assertEquals("no longer maintained", propMap(comps.get(1)).get("reliza:support:levelOfSupport"),
				"the bom-ref purl with + matched the %2B row on the second pass: " + served);
		Map<String, String> meta = propMap(served.get("metadata"));
		assertEquals("derived-non-attested-current-state", meta.get("reliza:support:disclosure"));
		assertEquals("1", meta.get("reliza:support:unidentifiedComponents"), "only the key file: " + meta);
		assertEquals("1", meta.get("reliza:support:unassessedComponents"), "only nobody: " + meta);
	}

	/** Every occurrence of a property name anywhere in the tree. */
	private static int countEverywhere(JsonNode node, String name) {
		int n = 0;
		if (node.isObject() && node.has("value") && node.get("name") != null
				&& name.equals(node.get("name").asText())) {
			n++;
		}
		for (JsonNode child : node) {
			n += countEverywhere(child, name);
		}
		return n;
	}

	/** A component row with no attestation, marked {@code isRoot} when asked. */
	private SbomComponent bareRow(UUID org, String canonicalPurl, String name, boolean isRoot) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", name);
		if (isRoot) {
			rd.put("isRoot", Boolean.TRUE);
		}
		sc.setRecordData(rd);
		return new TransactionTemplate(transactionManager).execute(status -> sbomComponentRepository.save(sc));
	}

	/**
	 * T-15 (SCORE-60): an organization that has attested nothing still gets its rows read, so a
	 * root row is self (rule 1) and a non-root row overrides rebom's stamp (rule 2); the old
	 * no-attestation shortcut handed the injector no rows at all.
	 */
	@Test
	void marksOwnEntriesFromRowsInAnOrganizationThatAttestsNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		OrganizationData.Settings enable = new OrganizationData.Settings();
		enable.setSupportInjection(SupportInjectionSetting.ENABLED);
		organizationService.updateSettings(orgUuid, enable, WhoUpdated.getTestWhoUpdated());
		String salt = UUID.randomUUID().toString().substring(0, 8);
		bareRow(orgUuid, "pkg:generic/reliza-test-" + salt + "/own@1.0.0", "own", true);
		bareRow(orgUuid, "pkg:npm/reliza-test-" + salt + "-dep@1.0.0", "reliza-test-" + salt + "-dep", false);

		JsonNode served = supportInjectionService.injectIfEnabledElseStrip(Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"own",
			   "purl":"pkg:generic/reliza-test-%1$s/own@1.0.0"}},
			 "components":[
			  {"type":"library","name":"dep","purl":"pkg:npm/reliza-test-%1$s-dep@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}]}""".formatted(salt)),
				orgUuid, ExportMetadataOptions.callerSilent());

		assertEquals("true", propMap(served.get("metadata").get("component")).get(SELF), "root row: " + served);
		assertNull(propMap(served.get("components").get(0)).get(SELF),
				"a non-root row wins over the stamp: " + served);
		Map<String, String> meta = propMap(served.get("metadata"));
		assertEquals("1", meta.get("reliza:support:selfComponents"), meta.toString());
		assertEquals("1", meta.get("reliza:support:unassessedComponents"), "only dep: " + meta);
	}

	/**
	 * T-15 (SCORE-58 ADR-4, built as SCORE-60): on an ENABLED organization the marker follows the
	 * rows of a real Postgres: a root row is self, a non-root row is not whatever rebom's stamp
	 * says, a rowless stamped node is self; the internal strip keeps the marker and removes the
	 * stamp, and with the support disclosure declined nothing carries either.
	 */
	@Test
	void marksTheOrganizationsOwnEntriesFromRootRowsAndTheStamp() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		OrganizationData.Settings enable = new OrganizationData.Settings();
		enable.setSupportInjection(SupportInjectionSetting.ENABLED);
		organizationService.updateSettings(orgUuid, enable, WhoUpdated.getTestWhoUpdated());
		String salt = UUID.randomUUID().toString().substring(0, 8);
		LocalDate eos = LocalDate.now().plusYears(1);

		supported(orgUuid, "pkg:generic/reliza-test-" + salt + "/product@1.0.0", "product", "1.0.0", eos, null, true);
		supported(orgUuid, "pkg:generic/reliza-test-" + salt + "/part@2.0.0", "part", "2.0.0", eos, null, true);
		supported(orgUuid, "pkg:npm/reliza-test-" + salt + "-listed@3.0.0", "reliza-test-" + salt + "-listed", "3.0.0",
				eos, null, false);

		String bomJson = """
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"product",
			   "purl":"pkg:generic/reliza-test-%1$s/product@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}},
			 "components":[
			  {"type":"application","name":"part","purl":"pkg:generic/reliza-test-%1$s/part@2.0.0"},
			  {"type":"library","name":"listed","purl":"pkg:npm/reliza-test-%1$s-listed@3.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]},
			  {"type":"application","name":"rowless","purl":"pkg:generic/reliza-test-%1$s/rowless@4.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]},
			  {"type":"library","name":"unattested","purl":"pkg:npm/reliza-test-%1$s-unattested@5.0.0"}
			]}""".formatted(salt);

		JsonNode served = supportInjectionService.injectIfEnabledElseStrip(Utils.OM.readTree(bomJson), orgUuid,
				ExportMetadataOptions.callerSilent());
		JsonNode comps = served.get("components");
		assertEquals("true", propMap(served.get("metadata").get("component")).get(SELF), "root row: " + served);
		assertEquals("true", propMap(comps.get(0)).get(SELF), "root row: " + served);
		assertNull(propMap(comps.get(1)).get(SELF), "non-root row with the stamp is a dependency: " + served);
		assertEquals("true", propMap(comps.get(2)).get(SELF), "rowless with the stamp: " + served);
		assertNull(propMap(comps.get(3)).get(SELF));
		Map<String, String> meta = propMap(served.get("metadata"));
		assertEquals("3", meta.get("reliza:support:selfComponents"), meta.toString());
		assertEquals("1", meta.get("reliza:support:unassessedComponents"), "only the unattested library: " + meta);

		JsonNode internalDeclined = supportInjectionService.injectIfEnabledElseStrip(Utils.OM.readTree(bomJson),
				orgUuid, new ExportMetadataOptions(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));
		assertEquals(3, countEverywhere(internalDeclined, SELF), "the marker stays: " + internalDeclined);
		assertEquals(0, countEverywhere(internalDeclined, STAMP), "the stamp is internal: " + internalDeclined);

		JsonNode supportDeclined = supportInjectionService.injectIfEnabledElseStrip(Utils.OM.readTree(bomJson),
				orgUuid, new ExportMetadataOptions(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.EXCLUDE));
		assertEquals(0, countEverywhere(supportDeclined, SELF), supportDeclined.toString());
		assertEquals(0, countEverywhere(supportDeclined, "reliza:support:selfComponents"));
		assertEquals(0, countEverywhere(supportDeclined, STAMP));
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
		assertEquals("MANUAL", a.get(SupportBomInjector.PROP_SOURCE_PREFIX + "endOfSupport"), "byte-exact component matched");
		assertEquals("END_OF_SUPPORT", a.get(SupportBomInjector.PROP_STATUS));

		Map<String, String> b = propMap(comps.get(1));
		assertEquals("MANUAL", b.get(SupportBomInjector.PROP_SOURCE_PREFIX + "endOfSupport"),
				"encoding-drifted (+ vs %2B) component matched via the fallback");
		// Future EOS -> WITHIN_SUPPORT_WINDOW. The point of this assertion is that the component
		// MATCHED (proved by the MANUAL provenance above); the derived status is future-dated.
		assertEquals("WITHIN_SUPPORT_WINDOW", b.get(SupportBomInjector.PROP_STATUS));

		assertTrue(propMap(comps.get(2)).isEmpty(), "unmatched component gets no support properties");

		// Document-level disclosure marker is always stamped (metadata created since the
		// fixture had none).
		Map<String, String> meta = propMap(bom.get("metadata"));
		assertEquals(SupportBomInjector.DISCLOSURE_CURRENT_STATE, meta.get(SupportBomInjector.PROP_DISCLOSURE));
	}
}

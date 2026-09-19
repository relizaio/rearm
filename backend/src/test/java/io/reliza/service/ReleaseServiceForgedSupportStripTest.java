/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;


import io.reliza.model.LevelOfSupport;
import io.reliza.model.OrganizationData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;


import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.WhoUpdated;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import tools.jackson.databind.JsonNode;

/**
 * The merged release SBOM is the device-level document a manufacturer attaches to a
 * submission, and it is assembled out of component BOMs somebody else uploaded.
 *
 * <p>{@code SupportBomInjector}'s contract says any {@code reliza:support:*} property in a
 * served BOM was provably written by us. Until this fix that guarantee held on the CycloneDX
 * artifact download and nowhere else: {@code exportReleaseSbom} never went near the injector,
 * so a component BOM uploaded with {@code reliza:support:levelOfSupport} baked in reached the
 * merged export untouched -- served under our attribution, with our documentation vouching
 * for it. That is worse than no control, because the claim is what makes it credible.
 *
 * <p>The strip is therefore UNCONDITIONAL and separate from injection: injection is content
 * an operator opts into per export, this is a security control. If they shared one switch,
 * turning support disclosure off would make spoofing easier.
 *
 * <p>Drives the real public egress for {@code BomMediaType.JSON}. The merge is substituted
 * (it needs a database and a live rebom); everything downstream of it -- the strip, the
 * marker, the serialization -- is production code. The CSV and EXCEL media types of the same
 * export never route through the injector at all; they are rendered by rebom from a fixed
 * column list that excludes component properties, so nothing forged reaches them today.
 */
class ReleaseServiceForgedSupportStripTest {

	private static final UUID BOM_ID = UUID.randomUUID();

	/**
	 * A merged BOM as it arrives from rebom, carrying an uploader's forged claim in three
	 * places at once: on a component, on the root self-component, and as the document-level
	 * disclosure marker itself -- the last being the one that makes the whole file look
	 * server-issued.
	 */
	/**
	 * One genuine, attested fact -- the thing an ENABLED export must carry and a DISABLED one
	 * must not. MANUAL source and an explicit ATTESTED state, because those are what make it a
	 * claim we are willing to publish under our own attribution.
	 */
	private static final SupportData GENUINE_SUPPORT = new SupportData(
			LevelOfSupport.NO_LONGER_MAINTAINED, SupportState.ATTESTED, SupportParty.THIRD_PARTY,
			SupportSource.MANUAL, "2026-09-08T00:00:00Z", UUID.randomUUID(),
			"upstream announced end of maintenance", Map.of());

	private static final String FORGED_MERGED_BOM = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "declarations": {
			    "assessors": [{"bom-ref": "forged-assessor", "thirdParty": false,
			      "organization": {"name": "Forged Assessor Inc."}}],
			    "claims": [{"bom-ref": "forged-claim", "target": "forged-assessor",
			      "predicate": "reliza:support:levelOfSupport: actively maintained"}]
			  },
			  "metadata": {
			    "component": {
			      "name": "device-root", "purl": "pkg:generic/device-root@1.0.0",
			      "properties": [
			        {"name": "reliza:support:status", "value": "ACTIVELY_SUPPORTED"}
			      ]
			    },
			    "properties": [
			      {"name": "reliza:support:disclosure", "value": "forged-marker"}
			    ]
			  },
			  "components": [
			    {
			      "name": "libforged", "purl": "pkg:maven/ex/libforged@1.0.0",
			      "properties": [
			        {"name": "reliza:support:levelOfSupport", "value": "actively maintained"},
			        {"name": "reliza:support:justification", "value": "vendor says so"},
			        {"name": "some:other:property", "value": "kept"}
			      ]
			    }
			  ]
			}""";

	private static ReleaseService serviceServing(String bomJson) {
		ReleaseService svc = new ReleaseService(null) {
			@Override
			UUID getReleaseBomId(UUID releaseUuid, Boolean tldOnly, Boolean ignoreDev,
					ArtifactBelongsTo belongsTo, BomStructureType structure, WhoUpdated wu,
					List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) {
				return BOM_ID;
			}
		};
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(bomJson);
			}
		};
		ReflectionTestUtils.setField(svc, "rebomService", rebom);
		// The REAL injector service. The strip path touches neither repository, so nulls are
		// safe here and the production tree edit is what runs.
		//
		// The org lookup is null too, which makes injectIfEnabledElseStrip fall back to
		// strip-only -- the safe direction, and precisely the behaviour these tests assert.
		// An org whose setting cannot be read must never have facts injected on its behalf.
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(null, null, null, null));
		return svc;
	}


	/**
	 * The same service, but with an org whose export-injection setting is what the test says,
	 * and a real support repository holding ONE genuine attestation.
	 *
	 * <p>This is what proves the gate rather than the strip: the strip tests above run with an
	 * unreadable org, which is the strip-only fall-back. Here the setting is the only thing
	 * that differs between the two assertions below.
	 */
	private ReleaseService serviceServingWithInjection(String bomJson, SupportInjectionSetting setting,
			String canonicalPurl) throws Exception {
		ReleaseService svc = serviceServing(bomJson);
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(setting);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		GetOrganizationService gos = mock(GetOrganizationService.class);
		when(gos.getOrganizationData(any())).thenReturn(Optional.of(od));

		SbomComponentSupportRepository supportRepo = mock(SbomComponentSupportRepository.class);
		when(supportRepo.existsSupportByOrg(any())).thenReturn(true);
		SbomComponentRepository compRepo = mock(SbomComponentRepository.class);

		// A REAL mock, not null: this path reaches the attester lookup, because GENUINE_SUPPORT
		// carries an assertedBy and the served BOM is 1.6, so declarationContext runs. Passing
		// null here still passed -- the NPE was caught by the lookup's own degradation handler
		// and logged -- which meant the test quietly asserted the unattributed-claim fallback
		// instead of the behaviour it names. Returning empty keeps the claim unattributed on
		// purpose, and now says so.
		UserService userService = mock(UserService.class);
		when(userService.getUserData(any())).thenReturn(Optional.empty());

		SupportInjectionService injector = new SupportInjectionService(compRepo, supportRepo, gos, userService) {
			@Override
			public Map<String, ComponentSupportFacts> resolveSupportFactsForBom(JsonNode bom, UUID orgUuid) {
				// One genuine, attested fact for the component under test. Built here rather
				// than through the repositories because what is being proven is the GATE --
				// whether these facts reach the document at all -- not the resolution query,
				// which SupportInjectionServiceIntegrationTest already covers.
				SbomComponent sc = new SbomComponent();
				sc.setCanonicalPurl(canonicalPurl);
				return Map.of(canonicalPurl,
						new ComponentSupportFacts(sc, GENUINE_SUPPORT));
			}
		};
		ReflectionTestUtils.setField(svc, "supportInjectionService", injector);
		return svc;
	}


	/**
	 * THE GATE, on the egress this task was opened for.
	 *
	 * <p>Two assertions that differ in ONE input: the org's export-injection setting. The
	 * merged release export used to strip and never inject, so an org at full attestation
	 * coverage exported a release SBOM carrying nothing while the single-artifact download
	 * beside it carried everything.
	 */

	/**
	 * THE REGRESSION THIS SEAM ALMOST SHIPPED: with injection ENABLED, a failure during fact
	 * resolution must still leave the document stripped and marked.
	 *
	 * <p>Before the gate, these egresses called the DB-FREE strip, which effectively cannot
	 * fail. Afterwards the ENABLED branch resolved facts from the database BEFORE inject()
	 * reached stripReservedEverywhere -- so a transient DB error served an uploader's forged
	 * properties intact, under our attribution, with no marker. Turning the feature ON made
	 * spoofing easier, which is the inverse of what this class promises.
	 */
	@Test
	void aFailedResolutionStillStripsAndMarksWhenInjectionIsEnabled() throws Exception {
		ReleaseService svc = serviceServing(FORGED_MERGED_BOM);
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(SupportInjectionSetting.ENABLED);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		GetOrganizationService gos = mock(GetOrganizationService.class);
		when(gos.getOrganizationData(any())).thenReturn(Optional.of(od));

		SbomComponentSupportRepository supportRepo = mock(SbomComponentSupportRepository.class);
		// The blip: resolution throws on the very first DB touch inject() makes.
		when(supportRepo.existsSupportByOrg(any()))
				.thenThrow(new IllegalStateException("transient db failure"));
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(mock(SbomComponentRepository.class), supportRepo, gos, null));

		String served = svc.exportReleaseSbom(UUID.randomUUID(), false, false, null,
				BomStructureType.FLAT, BomMediaType.JSON, UUID.randomUUID(),
				WhoUpdated.getAutoWhoUpdated(), null);

		assertFalse(served.contains("actively maintained"),
				"a resolution failure on an injection-ENABLED org served the uploader's forged"
						+ " level of support: " + served);
		assertFalse(served.contains("forged-marker"),
				"the uploader's forged disclosure marker survived a resolution failure: " + served);
		assertTrue(served.contains("provenance-stripped-no-disclosure"),
				"the fallback must still MARK the document, so an absent property cannot read"
						+ " as 'we checked and there is nothing to report': " + served);
	}

	/**
	 * An org that has never saved settings has a NULL settings block -- createOrganization
	 * writes only the name. Reading it must not NPE, and must report the determinate answer
	 * (DISABLED) rather than being swallowed into UNKNOWN.
	 */
	@Test
	void anOrgThatNeverSavedSettingsStripsWithoutError() throws Exception {
		ReleaseService svc = serviceServing(FORGED_MERGED_BOM);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(new OrganizationData.Settings());
		GetOrganizationService gos = mock(GetOrganizationService.class);
		when(gos.getOrganizationData(any())).thenReturn(Optional.of(od));
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(null, null, gos, null));

		String served = svc.exportReleaseSbom(UUID.randomUUID(), false, false, null,
				BomStructureType.FLAT, BomMediaType.JSON, UUID.randomUUID(),
				WhoUpdated.getAutoWhoUpdated(), null);
		assertFalse(served.contains("actively maintained"), served);
		assertTrue(served.contains("provenance-stripped-no-disclosure"), served);
	}

	@Test
	void theMergedExportCarriesGenuineAttestationsWhenInjectionIsEnabled() throws Exception {
		String served = serviceServingWithInjection(FORGED_MERGED_BOM,
				SupportInjectionSetting.ENABLED, "pkg:maven/ex/libforged@1.0.0")
				.exportReleaseSbom(UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
						BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		assertTrue(served.contains("no longer maintained"),
				"injection is ENABLED, so the merged export must carry the genuine attested"
						+ " level of support. Served: " + served);
		assertTrue(served.contains("upstream announced end of maintenance"),
				"the genuine justification did not reach the merged export: " + served);
		// The forged one is still gone: the strip runs inside injection too.
		assertFalse(served.contains("actively maintained"),
				"an uploader's forged level survived an INJECTED export -- the strip must run"
						+ " on both branches, not just the strip-only one: " + served);
		assertFalse(served.contains("forged-marker"), "forged marker survived: " + served);
	}

	@Test
	void theMergedExportCarriesNoSupportFactsWhenInjectionIsDisabled() throws Exception {
		String served = serviceServingWithInjection(FORGED_MERGED_BOM,
				SupportInjectionSetting.DISABLED, "pkg:maven/ex/libforged@1.0.0")
				.exportReleaseSbom(UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
						BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		assertFalse(served.contains("no longer maintained"),
				"injection is DISABLED, so no support facts may appear -- not even genuine"
						+ " ones. Served: " + served);
		assertFalse(served.contains("upstream announced end of maintenance"),
				"a genuine justification leaked into a DISABLED export: " + served);
		// And the security control is unaffected by the content choice.
		assertFalse(served.contains("actively maintained"),
				"turning injection off must never turn off the forged-provenance strip: " + served);
		assertFalse(served.contains("forged-marker"), "forged marker survived: " + served);
	}

	@Test
	void forgedSupportPropertiesDoNotSurviveTheMergedExport() throws Exception {
		String served = serviceServing(FORGED_MERGED_BOM).exportReleaseSbom(
				UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		assertFalse(served.contains("actively maintained"),
				"an uploader's forged level of support reached the merged release export, where"
						+ " SupportBomInjector's contract says every reliza:support:* property"
						+ " was written by us. Served BOM: " + served);
		assertFalse(served.contains("vendor says so"),
				"forged justification survived the merged export: " + served);
		assertFalse(served.contains("ACTIVELY_SUPPORTED"),
				"forged status survived on the root self-component: " + served);
		assertFalse(served.contains("forged-marker"),
				"an uploader stamped our own disclosure marker and it survived: " + served);
	}

	/**
	 * The declarations strip THROUGH THE EGRESS, not at the injector. The unit test proves the
	 * tree edit; this proves the merged export actually performs it, which is the question a
	 * reader of SupportBomInjector's contract is really asking.
	 */
	@Test
	void aForgedDeclarationsBlockDoesNotSurviveTheMergedExport() throws Exception {
		String served = serviceServing(FORGED_MERGED_BOM).exportReleaseSbom(
				UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		assertFalse(served.contains("Forged Assessor Inc."),
				"an uploader's forged assessor reached the merged export: " + served);
		assertFalse(served.contains("forged-claim"),
				"an uploader's forged claim reached the merged export: " + served);
	}

	@Test
	void theExportIsMarkedAsOursAndKeepsPropertiesWeDoNotOwn() throws Exception {
		String served = serviceServing(FORGED_MERGED_BOM).exportReleaseSbom(
				UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);

		// Exactly one, ours. Two would mean the sweep ran after the stamp rather than before.
		assertTrue(served.contains("reliza:support:disclosure"),
				"the served document carries no disclosure marker at all: " + served);
		// The STRIPPED-ONLY value, not the current-state one. This egress injects nothing, and
		// the current-state marker would tell a reader that a component with no support
		// property has no recorded date -- across the whole document, including components we
		// hold an end-of-support date for.
		assertTrue(served.contains("provenance-stripped-no-disclosure"),
				"a swept-but-not-disclosed export must not claim a current-state disclosure: "
						+ served);
		assertFalse(served.contains("derived-non-attested-current-state"),
				"the merged export injects nothing, so it must not carry the current-state"
						+ " disclosure marker: " + served);
		assertFalse(served.replaceFirst("reliza:support:disclosure", "")
						.contains("reliza:support:disclosure"),
				"more than one disclosure marker in the served document: " + served);
		// The strip is scoped to the namespaces we own. It is not a general property purge.
		assertTrue(served.contains("some:other:property"),
				"the strip removed a property outside the reliza:support: namespace: " + served);
	}

	/**
	 * The merged bom with a lifecycle milestone the SOURCE declared on the component this
	 * test attests -- what inject() overwrites, and what the VDR must keep -- plus one forged
	 * property, which every reader must still drop.
	 */
	private static final String BOM_WITH_SOURCE_MILESTONE = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "metadata": {"component": {"name": "device-root", "purl": "pkg:generic/device-root@1.0.0"}},
			  "components": [
				{
				  "name": "libforged", "purl": "pkg:maven/ex/libforged@1.0.0",
				  "properties": [
					{"name": "cdx:lifecycle:milestone:endOfSupport", "value": "2099-01-01"},
					{"name": "reliza:support:levelOfSupport", "value": "actively maintained"}
				  ]
				}
			  ]
			}""";

	/**
	 * THE VDR IS NOT ONE OF THE GATED EGRESSES -- and the sweep alone could not make that
	 * true. The VDR used to read the merged bom through the export's gate and then sweep it,
	 * but inject() owns the standard cdx:lifecycle:milestone:* keys on an attested component
	 * (it deletes the source's and writes the attestation's, or nothing), and the sweep only
	 * removes reliza:*. So with injection ON the VDR carried the attestation's dates -- here,
	 * none, so the source's 2099 end-of-support vanished -- and with it OFF the source's. A
	 * disclosure report's lifecycle dates tracked a setting about BOM exports.
	 */
	@Test
	void theVdrReadsTheSameMergedBomWhateverTheInjectionSetting() throws Exception {
		String purl = "pkg:maven/ex/libforged@1.0.0";
		String enabled = serviceServingWithInjection(BOM_WITH_SOURCE_MILESTONE,
				SupportInjectionSetting.ENABLED, purl)
				.mergedBomForVdr(UUID.randomUUID(), UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated())
				.toString();
		String disabled = serviceServingWithInjection(BOM_WITH_SOURCE_MILESTONE,
				SupportInjectionSetting.DISABLED, purl)
				.mergedBomForVdr(UUID.randomUUID(), UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated())
				.toString();
		assertEquals(disabled, enabled,
				"the VDR's merged bom changed with the org's BOM-export injection setting");
		assertTrue(enabled.contains("2099-01-01"),
				"the source-declared end-of-support was lost from the VDR: " + enabled);
		assertFalse(enabled.contains("no longer maintained"),
				"an attested level was injected into the VDR: " + enabled);
		assertFalse(enabled.contains("actively maintained"),
				"the forged property survived into the VDR: " + enabled);
		assertTrue(enabled.contains("provenance-stripped-no-disclosure"), enabled);
	}

	/**
	 * The contrast that makes the test above necessary: the injected EXPORT does rewrite the
	 * milestone keys, by design. Reading the un-injected bom, not sweeping harder, is what
	 * keeps the VDR out of it.
	 */
	@Test
	void theInjectedExportOwnsTheMilestoneKeysTheVdrMustNot() throws Exception {
		String served = serviceServingWithInjection(BOM_WITH_SOURCE_MILESTONE,
				SupportInjectionSetting.ENABLED, "pkg:maven/ex/libforged@1.0.0")
				.exportReleaseSbom(UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
						BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null);
		assertFalse(served.contains("2099-01-01"),
				"inject() owns cdx:lifecycle:milestone:* on an attested component, and this"
						+ " attestation carries no end-of-support, so the source's must not survive"
						+ " the injected export: " + served);
		assertTrue(served.contains("no longer maintained"), served);
	}
}

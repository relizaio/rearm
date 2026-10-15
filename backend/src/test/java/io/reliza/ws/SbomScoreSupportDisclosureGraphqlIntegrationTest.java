/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.Utils;
import io.reliza.model.OrganizationData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.service.OrganizationService;
import tools.jackson.databind.JsonNode;

/**
 * The score of a release follows the organization's support disclosure setting when the caller
 * omits the metadata flags, as the UI now always does (SCORE-21, SCORE-12 ADR-3 and ADR-4), and the
 * injected document never reaches rebom's stored merge. Tests T-12 and T-13.
 *
 * <p>T-12 cases already pinned elsewhere, not repeated here:
 * {@code ExportMetadataFlagsTest.mergedExportIsUnchangedWhenTheCallerSaysNothing} (DEFAULT and
 * DEFAULT on an ENABLED org injects), {@code theOrgDisabledPathStillMarksButAnExplicitDeclineDoesNot}
 * (DEFAULT on a DISABLED org strips and marks), {@code mergedExportStripsTheDisclosureWhenSupportMetadataIsExcluded}
 * (EXCLUDE on an ENABLED org strips silently),
 * {@code SbomScoreReleaseGraphqlIntegrationTest.supportMetadataTheOrgDoesNotExportIsRefusedAsTheExportRefusesIt}
 * (true on a DISABLED org is refused) and {@code theScoredDocumentIsTheExportedOne}.
 */
class SbomScoreSupportDisclosureGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	@Autowired private OrganizationService organizationService;

	private void setSupportInjection(Fixture f, SupportInjectionSetting setting) throws Exception {
		OrganizationData.Settings s = new OrganizationData.Settings();
		s.setSupportInjection(setting);
		organizationService.updateSettings(f.org().getUuid(), s, WU);
	}

	/** The document the score sent to the scoring service on its last call. */
	private String scoredDocument() {
		ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
		verify(rebomService, times(1)).scoreBom(scored.capture(), any());
		return scored.getValue();
	}

	private static String metaProperty(String doc, String name) {
		JsonNode props = Utils.OM.readTree(doc).get("metadata").get("properties");
		if (null == props) return null;
		for (JsonNode p : props) {
			if (name.equals(p.get("name").asText())) return p.get("value").asText();
		}
		return null;
	}

	// T-13
	@Test
	void exportScoreExportInAnEnabledOrgMergesOnceAndNeverStoresTheDisclosure() throws Exception {
		Fixture f = fixture();
		setSupportInjection(f, SupportInjectionSetting.ENABLED);
		Map<String, Object> vars = releaseVars(f.release());

		String first = data(as(f.downloader(), EXPORT_RELEASE, vars), "releaseSbomExport");
		String report = data(as(f.downloader(), SCORE_RELEASE, withProfiles(vars, PROFILES)), "releaseSbomScore");
		String scored = scoredDocument();
		String second = data(as(f.downloader(), EXPORT_RELEASE, vars), "releaseSbomExport");

		assertEquals(REPORT, report);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), any(), any());
		// Nothing the export or the score sent to rebom -- the merge request, the reads, the score
		// request aside -- carries a support property: the stored merge stays undisclosed.
		mockingDetails(rebomService).getInvocations().stream()
				.filter(inv -> !"scoreBom".equals(inv.getMethod().getName()))
				.forEach(inv -> assertFalse(Arrays.deepToString(inv.getArguments()).contains("reliza:support:"),
						"rebom received the disclosure in " + inv));
		for (String doc : List.of(first, scored, second)) {
			assertEquals("derived-non-attested-current-state", metaProperty(doc, "reliza:support:disclosure"), doc);
			assertEquals("2", metaProperty(doc, "reliza:support:unidentifiedComponents"),
					"the root and the library have neither purl nor cpe: " + doc);
			assertEquals("0", metaProperty(doc, "reliza:support:unassessedComponents"), doc);
		}
		assertEquals(first, scored, "the score scored the export");
		assertEquals(first, second, "the second export is the first: nothing was cached injected");
	}

	// T-12
	@Test
	void omittedFlagsScoreWhatTheOrgSettingGivesAndTrueOnADisabledOrgIsRefused() throws Exception {
		Fixture f = fixture();
		setSupportInjection(f, SupportInjectionSetting.DISABLED);
		clearInvocations(rebomService);
		data(as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release()), PROFILES)), "releaseSbomScore");
		String disabled = scoredDocument();
		assertEquals("provenance-stripped-no-disclosure", metaProperty(disabled, "reliza:support:disclosure"),
				"omitted on a DISABLED org scores the stripped and marked document: " + disabled);
		assertNull(metaProperty(disabled, "reliza:support:unidentifiedComponents"), disabled);
		assertNull(metaProperty(disabled, "reliza:support:unassessedComponents"), disabled);

		clearInvocations(rebomService);
		String refused = errorMessage(as(f.downloader(), SCORE_RELEASE,
				withProfiles(releaseVars(f.release(), "sm", true), PROFILES)));
		assertTrue(refused.contains("support metadata"), refused);
		verify(rebomService, never()).scoreBom(any(), any());

		setSupportInjection(f, SupportInjectionSetting.ENABLED);
		clearInvocations(rebomService);
		data(as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release(), "sm", false), PROFILES)),
				"releaseSbomScore");
		String declined = scoredDocument();
		assertFalse(declined.contains("reliza:support:"),
				"false on an ENABLED org scores a document with no disclosure at all: " + declined);
	}
}

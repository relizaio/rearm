/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.OrganizationData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.service.GetOrganizationService;

/**
 * {@code releaseSbomExportProgrammatic} (task SCORE-24, design 3.1, tests T-1, T-2 and T-4): the user
 * export's document for a key, the two ways of naming the release, and the metadata flags.
 * Who may export is {@link ReleaseSbomProgrammaticAccessIntegrationTest}.
 */
class ReleaseSbomExportProgrammaticIntegrationTest extends ProgrammaticReleaseSbomTestBase {

	@Autowired private GetOrganizationService getOrganizationService;

	private static final String FIELD = "releaseSbomExportProgrammatic";

	/** The variables rearm exportreleasebom sends with no option flags (design 3.3), without the release. */
	private static Object[] cliDefaults() {
		return new Object[] {"t", true, "i", false, "s", "FLAT", "b", null, "m", "JSON",
				"x", List.of("DEV", "TEST", "BUILD_TIME"), "sm", false, "im", false, "fc", false};
	}

	/** The same variables for the user field, which names the release by uuid only. */
	private static Map<String, Object> asUserVars(Map<String, Object> vars) {
		Map<String, Object> out = new LinkedHashMap<>(vars);
		out.remove("c");
		out.remove("v");
		return out;
	}

	// T-1
	@Test
	void aKeyAsBasicAndAsAnExchangedTokenGetsTheDocumentTheUserExportReturns() throws Exception {
		String exchanged = bearer(exchanged(w.orgKey()));
		for (Object[] options : List.of(cliDefaults(), new Object[0],
				new Object[] {"t", false, "s", "HIERARCHICAL", "b", "RELEASE", "x", null, "fc", true})) {
			Map<String, Object> vars = byUuid(r.covered(), options);
			String expected = userExport(asUserVars(vars));
			assertTrue(expected.contains("score17-lib"), expected);
			int logsBefore = downloadLogs(r.covered().uuid()).size();
			assertEquals(expected, value(gql(basic(w.orgKey()), EXPORT, vars), FIELD), "Basic " + Arrays.toString(options));
			assertEquals(expected, value(gql(exchanged, EXPORT, vars), FIELD), "bearer " + Arrays.toString(options));
			assertEquals(logsBefore + 2, downloadLogs(r.covered().uuid()).size(), "one download-log row per export");
		}
		for (Map<String, Object> log : downloadLogs(r.covered().uuid())) {
			assertEquals("SBOM_EXPORT", log.get("downloadType"), log.toString());
			assertEquals("RELEASE", log.get("subjectType"), log.toString());
		}
	}

	// T-1
	@Test
	void csvIsTextAndExcelIsBase64OfTheWorkbook() throws Exception {
		assertEquals(CSV, value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), "m", "CSV")), FIELD));
		String excel = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), "m", "EXCEL")), FIELD);
		byte[] workbook = Base64.getDecoder().decode(excel);
		assertArrayEquals(XLSX, workbook);
		assertEquals('P', workbook[0]);
		assertEquals('K', workbook[1]);
	}

	// T-2
	@Test
	void theReleaseNamedByComponentUuidOrNameAndVersionIsTheReleaseNamedByUuid() throws Exception {
		String expected = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), cliDefaults())), FIELD);
		assertEquals(expected, value(gql(basic(w.orgKey()), EXPORT, byComponent(r.covered(), cliDefaults())), FIELD));
		Map<String, Object> byName = byComponent(r.covered(), cliDefaults());
		byName.put("c", r.covered().name());
		assertEquals(expected, value(gql(basic(w.orgKey()), EXPORT, byName), FIELD));
		int logs = downloadLogs(r.covered().uuid()).size();
		gql(basic(w.orgKey()), EXPORT, byComponent(r.covered()));
		assertEquals(logs + 1, downloadLogs(r.covered().uuid()).size(), "the log names the release the version resolved to");
	}

	// T-2
	@Test
	void exactlyOneWayOfNamingTheReleaseIsAccepted() throws Exception {
		Map<String, Object> both = byUuid(r.covered());
		both.putAll(byComponent(r.covered()));
		Map<String, Object> componentOnly = byComponent(r.covered());
		componentOnly.remove("v");
		Map<String, Object> versionOnly = byComponent(r.covered());
		versionOnly.remove("c");
		Map<String, Object> uuidAndVersion = byUuid(r.covered(), "v", r.covered().version());
		Map<String, Object> blankComponent = byComponent(r.covered(), "c", " ");
		clearInvocations(rebomService);
		for (Map<String, Object> vars : List.of(both, new LinkedHashMap<String, Object>(), componentOnly, versionOnly,
				uuidAndVersion, blankComponent)) {
			assertEquals(ReleaseDatafetcher.RELEASE_SELECTION_MESSAGE, error(gql(basic(w.orgKey()), EXPORT, vars), FIELD),
					vars.toString());
		}
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	// T-2: blank counts as absent
	@Test
	void aBlankComponentOrVersionBesideTheReleaseCountsAsAbsent() throws Exception {
		String expected = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered())), FIELD);
		for (Object[] blank : List.of(new Object[] {"v", ""}, new Object[] {"v", " "}, new Object[] {"c", " ", "v", ""})) {
			assertEquals(expected, value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), blank)), FIELD),
					Arrays.toString(blank));
		}
	}

	// T-2
	@Test
	void anUnknownVersionIsReleaseNotFoundForAKeyThatMayDownloadOnTheComponent() throws Exception {
		Map<String, Object> vars = byComponent(r.covered(), "v", "0.0.0-not-there");
		assertEquals("Release not found", error(gql(basic(w.orgKey()), EXPORT, vars), FIELD));
		assertEquals("Release not found", error(gql(basic(r.componentKey()), EXPORT, vars), FIELD));
		Map<String, Object> byName = new LinkedHashMap<>(vars);
		byName.put("c", r.covered().name());
		assertEquals("Release not found", error(gql(basic(w.orgKey()), EXPORT, byName), FIELD));
	}

	// T-2
	@Test
	void aKeyThatMayNotDownloadOnTheComponentCannotTellAMissingVersionFromAnExistingOne() throws Exception {
		List<Object[]> cases = List.of(
				new Object[] {basic(r.readerKey()), r.covered()},
				new Object[] {basic(r.componentKey()), r.uncovered()},
				new Object[] {basic(w.foreignAdminKey()), r.covered()},
				new Object[] {basic(w.orgKey()), r.foreign()});
		for (Object[] c : cases) {
			Rel rel = (Rel) c[1];
			String existing = error(gql((String) c[0], EXPORT, byComponent(rel)), FIELD);
			String missing = error(gql((String) c[0], EXPORT, byComponent(rel, "v", "0.0.0-not-there")), FIELD);
			assertEquals(existing, missing, "refusal for " + rel.name());
			assertFalse(missing.contains("not found"), missing);
		}
		// by uuid: another organization's release and a release that does not exist read the same
		String foreign = error(gql(basic(w.orgKey()), EXPORT, byUuid(r.foreign())), FIELD);
		Map<String, Object> unknown = byUuid(r.covered());
		unknown.put("r", UUID.randomUUID().toString());
		assertEquals(foreign, error(gql(basic(w.orgKey()), EXPORT, unknown), FIELD));
		// another organization's component by name is not found in the key's own organization
		Map<String, Object> foreignName = byComponent(r.foreign());
		foreignName.put("c", r.foreign().name());
		assertTrue(error(gql(basic(w.orgKey()), EXPORT, foreignName), FIELD).contains("No active component or product named"));
	}

	// T-4
	@Test
	void theMetadataFlagsAreTheUserExportsFlags() throws Exception {
		OrganizationData.Settings settings = getOrganizationService.getOrganizationData(w.org().getUuid()).orElseThrow()
				.getSettings();
		SupportInjectionSetting before = null == settings ? SupportInjectionSetting.DISABLED
				: settings.getSupportInjectionOrDefault();
		try {
			setSupportInjection(w.org(), SupportInjectionSetting.DISABLED);
			clearInvocations(rebomService);
			int logs = downloadLogs(r.covered().uuid()).size();
			String refused = error(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), "sm", true)), FIELD);
			assertTrue(refused.contains("support metadata"), refused);
			verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
			assertEquals(logs, downloadLogs(r.covered().uuid()).size(), "a refused export downloaded nothing");
			for (Object flag : Arrays.asList(false, null)) {
				Map<String, Object> vars = byUuid(r.covered(), "sm", flag);
				assertEquals(userExport(vars), value(gql(basic(w.orgKey()), EXPORT, vars), FIELD), "sm " + flag);
			}

			setSupportInjection(w.org(), SupportInjectionSetting.ENABLED);
			String orgDefault = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), "sm", null)), FIELD);
			assertTrue(orgDefault.contains("reliza:support:disclosure"), orgDefault);
			String declined = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), "sm", false)), FIELD);
			assertFalse(declined.contains("reliza:support:"), declined);
			assertEquals(userExport(byUuid(r.covered(), "sm", null)), orgDefault);
			List<Object> recorded = new ArrayList<>();
			for (Map<String, Object> row : downloadLogs(r.covered().uuid())) {
				@SuppressWarnings("unchecked")
				Map<String, Object> config = (Map<String, Object>) row.get("downloadConfig");
				if (null != config) recorded.add(config.get("includeSupportMetadata"));
			}
			assertTrue(recorded.contains(false), "the parsed choice is logged: " + recorded);
		} finally {
			setSupportInjection(w.org(), before);
		}
	}
}

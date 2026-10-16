/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomScoreException.SbomScoreErrorReason;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebom;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.service.OrganizationService;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseRebomService;
import io.reliza.service.SharedReleaseService;

/**
 * {@code releaseSbomScore} (task SCORE-5, design 3.1, 3.3, 3.4, 3.6 R-24): the export's checks, the
 * export's document, the export's writes minus the download log, and the error shape.
 */
class SbomScoreReleaseGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final String DOWNLOAD_LOGS = "\"rearm\".\"download_logs\"";

	@Autowired private OrganizationService organizationService;
	@Autowired private ReleaseRebomService releaseRebomService;
	@Autowired private SharedReleaseService sharedReleaseService;

	private void verifyNoExportAndNoScore() throws Exception {
		verify(releaseService, never()).exportReleaseSbom(any(), any(), any(), anyBoolean(), any(), any(), any(), any(),
				any(), any(), any());
		verify(rebomService, never()).scoreBom(any(), any());
	}

	// I-1 (R-1, R-2)
	@Test
	void bothFieldsAreQueriesAndTheReleaseOneTakesTheExportArguments() throws Exception {
		Fixture f = fixture();
		String typeRef = "type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }";
		Map<String, Map<String, Object>> query = fields(introspect(f, "Query", typeRef));
		Map<String, Map<String, Object>> mutation = fields(introspect(f, "Mutation", typeRef));

		assertTrue(query.containsKey("releaseSbomScore") && query.containsKey("artifactSbomScore"));
		assertFalse(mutation.containsKey("releaseSbomScore") || mutation.containsKey("artifactSbomScore"));

		Map<String, Map<String, Object>> scoreArgs = args(query.get("releaseSbomScore"));
		Map<String, Map<String, Object>> exportArgs = args(mutation.get("releaseSbomExport"));
		exportArgs.remove("mediaType");
		for (var e : exportArgs.entrySet()) {
			assertEquals(e.getValue(), scoreArgs.get(e.getKey()), "argument " + e.getKey() + " as the export has it");
		}
		List<String> extra = new ArrayList<>(scoreArgs.keySet());
		extra.removeAll(exportArgs.keySet());
		assertEquals(List.of("profiles"), extra);

		ExecutionResult asMutation = as(f.downloader(),
				"mutation { releaseSbomScore(release: \"" + f.release() + "\", profiles: [\"fda\"]) }", Map.of());
		assertFalse(asMutation.getErrors().isEmpty(), "not a mutation");
		assertTrue(isValidationError(asMutation.getErrors().get(0)), asMutation.getErrors().toString());

		// Each export enum value by its name, through the query.
		for (String s : List.of("FLAT", "HIERARCHICAL")) {
			for (String b : List.of("DELIVERABLE", "RELEASE", "SCE")) {
				ExecutionResult r = as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release(),
						"s", s, "b", b, "x", List.of("DEV", "TEST", "BUILD_TIME")), PROFILES));
				assertTrue(r.getErrors().stream().noneMatch(SbomScoreReleaseGraphqlIntegrationTest::isValidationError),
						r.getErrors().toString());
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static boolean isValidationError(GraphQLError e) {
		Map<String, Object> extensions = (Map<String, Object>) e.toSpecification().get("extensions");
		return null != extensions && "ValidationError".equals(extensions.get("classification"));
	}

	/** One type per request: the server refuses more than one {@code __type} as bad-faith introspection. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> introspect(Fixture f, String type, String typeRef) {
		ExecutionResult r = as(f.downloader(), "{ __type(name: \"" + type + "\") { fields { name args { name defaultValue "
				+ typeRef + " } } } }", Map.of());
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("__type");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Object>> fields(Map<String, Object> type) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		((List<Map<String, Object>>) type.get("fields")).forEach(fd -> out.put((String) fd.get("name"), fd));
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Object>> args(Map<String, Object> field) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		((List<Map<String, Object>>) field.get("args")).forEach(a -> out.put((String) a.get("name"), a));
		return out;
	}

	// I-2 (R-5, R-8, R-25)
	@Test
	void aRefusedCallerGetsWhatTheExportGivesAndNothingRuns() throws Exception {
		Fixture f = fixture();
		for (UUID release : List.of(f.release(), UUID.randomUUID())) {
			ExecutionResult score = as(f.reader(), SCORE_RELEASE, withProfiles(releaseVars(release), PROFILES));
			ExecutionResult export = as(f.reader(), EXPORT_RELEASE, releaseVars(release));
			assertEquals("Not authorized", errorMessage(score));
			assertEquals(errorMessage(export), errorMessage(score), "the export's refusal for the same caller");
			assertNull(fieldOf(score, "releaseSbomScore"), "no data for a refused caller");
		}
		// The downloader does see an unknown release as refused too.
		assertEquals("Not authorized", errorMessage(as(f.downloader(), SCORE_RELEASE,
				withProfiles(releaseVars(UUID.randomUUID()), PROFILES))));
		verifyNoExportAndNoScore();
	}

	// I-2 (R-5): the release score authorizes at release scope, so a component-scope downloader
	// scores the release, and scores what the export gives that same user.
	@Test
	void aComponentScopeDownloaderScoresTheReleaseTheExportGivesThem() throws Exception {
		Fixture f = fixture();
		Map<String, Object> vars = releaseVars(f.release());
		String report = data(as(f.componentDownloader(), SCORE_RELEASE, withProfiles(vars, PROFILES)),
				"releaseSbomScore");
		assertEquals(REPORT, report);
		ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
		verify(rebomService).scoreBom(scored.capture(), eq(PROFILES));
		assertEquals(data(as(f.componentDownloader(), EXPORT_RELEASE, vars), "releaseSbomExport"),
				scored.getValue(), "the export the same user gets");

		// A release of another component in the org stays out of that user's reach.
		var other = componentService.createComponent("score5-other-" + UUID.randomUUID(), f.org().getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		UUID otherRelease = release(f.org(), other,
				List.of(artifact(f.org().getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true)));
		assertEquals("Not authorized", errorMessage(as(f.componentDownloader(), SCORE_RELEASE,
				withProfiles(releaseVars(otherRelease), PROFILES))));
	}

	// I-4 (R-6, R-25)
	@Test
	void supportMetadataTheOrgDoesNotExportIsRefusedAsTheExportRefusesIt() throws Exception {
		Fixture f = fixture();
		Map<String, Object> vars = releaseVars(f.release(), "sm", true);
		ExecutionResult score = as(f.downloader(), SCORE_RELEASE, withProfiles(vars, PROFILES));
		ExecutionResult export = as(f.downloader(), EXPORT_RELEASE, vars);
		assertTrue(errorMessage(score).contains("support metadata"), errorMessage(score));
		assertEquals(errorMessage(export), errorMessage(score));
		assertEquals("Not authorized", errorMessage(as(f.reader(), SCORE_RELEASE, withProfiles(vars, PROFILES))),
				"authorization comes before the metadata check");
		verifyNoExportAndNoScore();
	}

	// I-5 (R-9, R-3)
	@Test
	void theScoredDocumentIsTheExportedOne() throws Exception {
		Fixture f = fixture();
		OrganizationData.Settings enable = new OrganizationData.Settings();
		enable.setSupportInjection(SupportInjectionSetting.ENABLED);
		organizationService.updateSettings(f.org().getUuid(), enable, WU);

		List<Map<String, Object>> cases = List.of(
				releaseVars(f.release()),
				releaseVars(f.release(), "t", true, "s", "HIERARCHICAL", "b", "RELEASE"),
				releaseVars(f.release(), "sm", true, "im", false),
				releaseVars(f.release(), "i", true, "x", List.of("DEV", "TEST")),
				releaseVars(f.release(), "fc", true),
				releaseVars(f.release(), "fc", false));
		for (Map<String, Object> vars : cases) {
			clearInvocations(releaseService, rebomService);
			String report = data(as(f.downloader(), SCORE_RELEASE, withProfiles(vars, PROFILES)), "releaseSbomScore");
			assertEquals(REPORT, report, "the report as rebom answered it");

			ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
			verify(rebomService).scoreBom(scored.capture(), eq(PROFILES));
			verify(releaseService).exportReleaseSbom(eq(f.release()), eq((Boolean) vars.get("t")),
					eq((Boolean) vars.get("i")), eq(Boolean.TRUE.equals(vars.get("fc"))),
					eq(null == vars.get("b") ? null : ArtifactBelongsTo.valueOf((String) vars.get("b"))),
					eq(null == vars.get("s") ? null : BomStructureType.valueOf((String) vars.get("s"))),
					eq(BomMediaType.JSON), eq(f.org().getUuid()), any(), eq(coverageTypes(vars)),
					eq(ExportMetadataOptions.fromCallerInput((Boolean) vars.get("sm"), (Boolean) vars.get("im"))));

			String exported = data(as(f.downloader(), EXPORT_RELEASE, vars), "releaseSbomExport");
			assertEquals(exported, scored.getValue(), "byte for byte the export of " + vars);
			assertTrue(exported.contains("caf\u00e9"), "the fixture document went through");
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ArtifactCoverageType> coverageTypes(Map<String, Object> vars) {
		List<String> x = (List<String>) vars.get("x");
		return null == x ? null : x.stream().map(ArtifactCoverageType::valueOf).toList();
	}

	// I-6 (R-12)
	@Test
	void anExportFailureIsTheCallersErrorAndNothingIsScored() throws Exception {
		Fixture f = fixture();
		UUID empty = release(f.org(), f.component(), List.of());
		ExecutionResult score = as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(empty), PROFILES));
		ExecutionResult export = as(f.downloader(), EXPORT_RELEASE, releaseVars(empty));
		assertEquals("No SBOMs found!", errorMessage(export));
		assertEquals(errorMessage(export), errorMessage(score));
		verify(rebomService, never()).scoreBom(any(), any());
	}

	// I-10 (R-10, R-11)
	@Test
	void aWarmScoreWritesNothing() throws Exception {
		Fixture f = fixture();
		Map<String, Object> vars = releaseVars(f.release(), "s", "FLAT");
		data(as(f.downloader(), EXPORT_RELEASE, vars), "releaseSbomExport");
		ReleaseData rd = sharedReleaseService.getReleaseData(f.release()).orElseThrow();
		ReleaseRebom warmRow = releaseRebomService.getReleaseRebom(f.org().getUuid(), f.release()).orElseThrow();
		var warmBoms = releaseRebomService.getReleaseBoms(rd);
		clearInvocations(downloadLogService, rebomService);

		Map<String, Long> change = changeMadeBy(() -> data(as(f.downloader(), SCORE_RELEASE,
				withProfiles(vars, PROFILES)), "releaseSbomScore"));

		assertEquals(Map.of(), change, "a score with the cache warm writes nothing");
		ReleaseRebom after = releaseRebomService.getReleaseRebom(f.org().getUuid(), f.release()).orElseThrow();
		assertEquals(warmRow.getRevision(), after.getRevision());
		assertEquals(warmBoms, releaseRebomService.getReleaseBoms(rd));
		verify(downloadLogService, never()).createDownloadLog(any(), any(), any(), any(), any(), any());
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
		verify(rebomService, times(1)).scoreBom(any(), any());
	}

	// I-11 (R-11)
	@Test
	void aColdScoreWritesWhatTheExportWritesButTheDownloadLog() throws Exception {
		Fixture f = fixture();
		Map<String, Long> score = null;
		Map<String, Long> exportMinusLog = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			UUID a = release(f.org(), f.component(), List.of(artifact(f.org().getUuid(),
					ArtifactType.BOM, BomFormat.CYCLONEDX, true)));
			UUID b = release(f.org(), f.component(), List.of(artifact(f.org().getUuid(),
					ArtifactType.BOM, BomFormat.CYCLONEDX, true)));
			Map<String, Long> before = settledRowCounts();
			data(as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(a), PROFILES)), "releaseSbomScore");
			Map<String, Long> mid = settledRowCounts();
			data(as(f.downloader(), EXPORT_RELEASE, releaseVars(b)), "releaseSbomExport");
			Map<String, Long> end = settledRowCounts();
			score = delta(before, mid);
			Map<String, Long> export = delta(mid, end);
			assertEquals(Long.valueOf(1), export.get(DOWNLOAD_LOGS), "the export logs its download: " + export);
			exportMinusLog = new TreeMap<>(export);
			exportMinusLog.remove(DOWNLOAD_LOGS);
			if (score.equals(exportMinusLog)) break;
		}
		assertFalse(score.isEmpty(), "a cold score stores the merged BOM as the export does");
		assertEquals(exportMinusLog, score, "the score writes exactly what the export writes, less the download log");
	}

	// I-12 (R-24, R-4)
	@Test
	void eachScoreErrorReachesTheCallerWithItsCodeAndReason() throws Exception {
		Fixture f = fixture();
		for (SbomScoreErrorReason reason : SbomScoreErrorReason.values()) {
			doThrow(new SbomScoreException(reason, "message for " + reason)).when(rebomService).scoreBom(any(), any());
			ExecutionResult r = as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release()), PROFILES));
			GraphQLError e = onlyError(r);
			assertEquals("message for " + reason, e.getMessage());
			assertNotNull(e.getExtensions());
			assertEquals(SbomScoreException.ERROR_CODE, e.getExtensions().get("code"));
			assertEquals(reason.name(), e.getExtensions().get("reason"));
			assertNull(fieldOf(r, "releaseSbomScore"), "no data");
		}

		doThrow(new SbomScoreException(SbomScoreErrorReason.UNKNOWN_PROFILE, "x")).when(rebomService).scoreBom(any(), any());
		for (List<String> profiles : List.of(List.<String>of(), List.of("nope"))) {
			clearInvocations(rebomService);
			as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release()), profiles));
			verify(rebomService).scoreBom(any(), eq(profiles));
		}
	}
}

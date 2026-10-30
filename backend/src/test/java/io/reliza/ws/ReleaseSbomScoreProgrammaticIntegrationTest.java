/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomScoreException.SbomScoreErrorReason;
import tools.jackson.databind.JsonNode;

/**
 * {@code releaseSbomScoreProgrammatic} (task SCORE-24, design 3.1, test T-5): the report of the
 * document the export returns for the same arguments, the score's error shape, no download log, and
 * the served programmatic schema carrying both fields and the four enums they take. Who may score is
 * {@link ReleaseSbomProgrammaticAccessIntegrationTest}.
 */
class ReleaseSbomScoreProgrammaticIntegrationTest extends ProgrammaticReleaseSbomTestBase {

	private static final String FIELD = "releaseSbomScoreProgrammatic";

	// T-5
	@Test
	void theReportIsTheScoreOfTheExportedDocumentAndLogsNoDownload() throws Exception {
		List<String> profiles = List.of("cisa-2026", "fda");
		for (Map<String, Object> vars : List.of(byUuid(r.covered(), "t", false, "s", "HIERARCHICAL"),
				byComponent(r.covered(), "t", false, "s", "HIERARCHICAL"))) {
			int logs = downloadLogs(r.covered().uuid()).size();
			clearInvocations(rebomService);
			vars.put("p", profiles);
			assertEquals(REPORT, value(gql(basic(w.orgKey()), SCORE, vars), FIELD));
			ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
			verify(rebomService, times(1)).scoreBom(scored.capture(), eq(profiles));
			assertEquals(logs, downloadLogs(r.covered().uuid()).size(), "a score downloads nothing");
			vars.remove("p");
			assertEquals(value(gql(basic(w.orgKey()), EXPORT, vars), "releaseSbomExportProgrammatic"), scored.getValue(),
					"the score scored the export");
		}
	}

	// T-5
	@Test
	void aScoreErrorReachesTheKeyWithItsCodeAndReason() throws Exception {
		doThrow(new SbomScoreException(SbomScoreErrorReason.UNKNOWN_PROFILE, "unknown profile nope"))
				.when(rebomService).scoreBom(any(), any());
		Map<String, Object> vars = byUuid(r.covered());
		vars.put("p", List.of("nope"));
		JsonNode body = gql(basic(w.orgKey()), SCORE, vars);
		assertEquals("unknown profile nope", error(body, FIELD));
		JsonNode extensions = body.get("errors").get(0).get("extensions");
		assertEquals(SbomScoreException.ERROR_CODE, extensions.get("code").asText(), body.toString());
		assertEquals("UNKNOWN_PROFILE", extensions.get("reason").asText(), body.toString());
		verify(rebomService).scoreBom(any(), eq(List.of("nope")));
	}

	// T-5
	@Test
	void theServedProgrammaticSchemaCarriesBothFieldsAndTheirEnums() throws Exception {
		HttpResponse<String> resp = get(ProgrammaticSchemaController.PATH, null);
		assertStatus(200, resp);
		String sdl = resp.body();
		for (String declared : List.of("releaseSbomExportProgrammatic(", "releaseSbomScoreProgrammatic(",
				"enum BomStructureType", "enum ArtifactBelongsToEnum", "enum BomMediaType", "enum ArtifactCoverageType")) {
			assertTrue(sdl.contains(declared), declared + " missing from the served schema");
		}
		// the bundle is a schema on its own: a client generator needs nothing else
		TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
		UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
	}
}

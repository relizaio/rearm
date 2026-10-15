/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ApiKeyService;

/**
 * Who may export and score a release's merged SBOM on the programmatic endpoint (task SCORE-24,
 * design 3.1 step 3, tests T-3 and the authorization half of T-5): ARTIFACT_DOWNLOAD on the release,
 * from a Free Form or personal key as Basic or exchanged, or a CLI login. A refusal merges nothing,
 * scores nothing and logs no download.
 */
class ReleaseSbomProgrammaticAccessIntegrationTest extends ProgrammaticReleaseSbomTestBase {

	private static final String EXPORT_FIELD = "releaseSbomExportProgrammatic";
	private static final String SCORE_FIELD = "releaseSbomScoreProgrammatic";

	private static Map<String, Object> scoreVars(Map<String, Object> vars) {
		vars.put("p", List.of("cisa-2026"));
		return vars;
	}

	/** Both fields refused for {@code authorization} on {@code rel}, with nothing merged, scored or logged. */
	private void refusedBoth(String authorization, Rel rel, String what) throws Exception {
		clearInvocations(rebomService);
		int logs = downloadLogs(rel.uuid()).size();
		String export = error(gql(authorization, EXPORT, byUuid(rel)), EXPORT_FIELD);
		String score = error(gql(authorization, SCORE, scoreVars(byUuid(rel))), SCORE_FIELD);
		assertTrue(export.contains("not authorized") || export.contains("Not authorized"), what + ": " + export);
		assertEquals(export, score, what);
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
		verify(rebomService, never()).findBomByIdJson(any(), any());
		verify(rebomService, never()).scoreBom(any(), any());
		assertEquals(logs, downloadLogs(rel.uuid()).size(), what + ": a refusal downloaded nothing");
	}

	/** Both fields answered for {@code authorization} on {@code rel}: the export is the user export's document. */
	private void allowedBoth(String authorization, Rel rel, String what) throws Exception {
		String expected = userExport(byUuid(rel));
		assertEquals(expected, value(gql(authorization, EXPORT, byUuid(rel)), EXPORT_FIELD), what);
		assertEquals(REPORT, value(gql(authorization, SCORE, scoreVars(byUuid(rel))), SCORE_FIELD), what);
	}

	private long loggedFor(UUID person) {
		return downloadLogs(r.covered().uuid()).stream()
				.filter(row -> person.toString().equals(String.valueOf(row.get("downloadedBy")))).count();
	}

	// T-3
	@Test
	void keysWithoutArtifactDownloadAreRefusedAsBasicAndAsExchangedTokens() throws Exception {
		for (Key k : List.of(r.readerKey(), w.agentKey())) {
			refusedBoth(basic(k), r.covered(), "Basic " + k.row().getUuid());
			refusedBoth(bearer(exchanged(k)), r.covered(), "bearer " + k.row().getUuid());
		}
	}

	// T-3
	@Test
	void aComponentGrantCoversItsComponentsReleasesOnly() throws Exception {
		allowedBoth(basic(r.componentKey()), r.covered(), "covered");
		refusedBoth(basic(r.componentKey()), r.uncovered(), "uncovered");
	}

	// T-3
	@Test
	void anOrganizationAdministratorKeyExportsWithoutTheFunction() throws Exception {
		allowedBoth(basic(w.adminKey()), r.uncovered(), "admin Basic");
		allowedBoth(bearer(exchanged(w.adminKey())), r.uncovered(), "admin bearer");
	}

	// T-3
	@Test
	void anotherOrganizationsKeyIsRefusedAndSoIsThisOrganizationsKeyThere() throws Exception {
		refusedBoth(basic(w.foreignAdminKey()), r.covered(), "foreign admin on ours");
		refusedBoth(bearer(exchanged(w.foreignAdminKey())), r.covered(), "foreign admin bearer on ours");
		refusedBoth(basic(w.adminKey()), r.foreign(), "our admin on theirs");
	}

	// T-3
	@Test
	void aCliLoginExportsAsThePersonWithinThePersonsOwnPermissions() throws Exception {
		Key personal = personal(w.downloader(), w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		String token = bearer(login(w.downloader(), personal.row()).token());
		allowedBoth(token, r.covered(), "downloader session");
		int rows = downloadLogs(r.covered().uuid()).size();
		long byDownloader = loggedFor(w.downloader().getUuid());
		value(gql(token, EXPORT, byUuid(r.covered())), EXPORT_FIELD);
		value(gql(token, SCORE, scoreVars(byUuid(r.covered()))), SCORE_FIELD);
		assertEquals(rows + 1, downloadLogs(r.covered().uuid()).size(), "the export logs, the score does not");
		assertEquals(byDownloader + 1, loggedFor(w.downloader().getUuid()), "logged as the person");
		allowedBoth(basic(personal), r.covered(), "downloader personal key");

		// the key may download, its person may not: the lower side wins
		Key readersKey = personal(w.reader(), w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		refusedBoth(bearer(login(w.reader(), readersKey.row()).token()), r.covered(), "reader session");
		refusedBoth(basic(readersKey), r.covered(), "reader personal key");
	}

	// T-3
	@Test
	void aLegacyOrganizationKeyIsRefused() throws Exception {
		ApiKey row = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.ORGANIZATION, w.org().getUuid(),
				UUID.randomUUID().toString(), "score24 legacy", WU);
		String secret = apiKeyService.addApiKeySecret(row.getUuid(), null, WU);
		String id = ApiKeyService.keyIdOf(apiKeyService.getApiKey(row.getUuid()).orElseThrow());
		clearInvocations(rebomService);
		String byUuid = error(gql(basic(id, secret), EXPORT, byUuid(r.covered())), EXPORT_FIELD);
		assertFalse(byUuid.isBlank());
		Map<String, Object> byName = byComponent(r.covered());
		byName.put("c", r.covered().name());
		assertEquals(byUuid, error(gql(basic(id, secret), EXPORT, byName), EXPORT_FIELD));
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	// T-3
	@Test
	void aWrongSecretLearnsNothingAndAnInvalidTokenIs401() throws Exception {
		String wrong = basic(w.orgKey().id(), "not-the-secret");
		Map<String, Object> existing = byComponent(r.covered());
		existing.put("c", r.covered().name());
		Map<String, Object> missing = byComponent(r.covered());
		missing.put("c", "score24-no-such-component");
		clearInvocations(rebomService);
		// TPF-27 (kept on merging main): a wrong Basic secret is refused by the programmatic filter with the one
		// invalid-credential 401 before any resolver runs, so every request reads the same, whatever it names
		for (Map<String, Object> vars : List.of(existing, missing, byUuid(r.covered()))) {
			HttpResponse<String> resp = gqlResponse(wrong, EXPORT, vars);
			assertStatus(401, resp);
			assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, header(resp, HttpHeaders.WWW_AUTHENTICATE));
			assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, resp.body());
			assertFalse(resp.body().contains(r.covered().component().getUuid().toString()), resp.body());
		}
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());

		for (String token : List.of(bearer("not-a-token"), bearer(exchanged(w.orgKey()) + "x"))) {
			HttpResponse<String> resp = gqlResponse(token, EXPORT, byUuid(r.covered()));
			assertStatus(401, resp);
			assertTrue(header(resp, HttpHeaders.WWW_AUTHENTICATE).startsWith(INVALID_TOKEN), resp.headers().toString());
		}
		assertStatus(401, gqlResponse(null, EXPORT, byUuid(r.covered())));
	}
}

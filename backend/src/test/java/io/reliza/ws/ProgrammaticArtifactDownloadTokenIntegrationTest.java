/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedGrant.GrantType;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ApiTokenService;

/**
 * SCORE-17: the programmatic artifact downloads accept a ReARM access token as Bearer, in each of
 * its three flavours -- a CLI browser login, a key exchanged at the token endpoint, a federated
 * exchange -- and refuse an invalid, expired or revoked one with 401 before the handler, and a valid
 * one whose key may not download with 403. Every case names path x credential x status. Before
 * SCORE-17 each of these requests was a 401 from the browser chain's IdP token decoder.
 */
class ProgrammaticArtifactDownloadTokenIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	private UUID freshReleaseArtifact() throws Exception {
		UUID a = bom(w.org().getUuid());
		release(w.org(), w.covered(), a);
		return a;
	}

	private static Object logField(Map<String, Object> log, String field) {
		return null == log.get(field) ? null : String.valueOf(log.get(field));
	}

	/** T-1: a CLI session of a person who may download, on their personal key: both paths 200, logged as that person. */
	@Test
	void aCliSessionOnAPersonalKeyDownloadsAndIsLoggedAsThePerson() throws Exception {
		Key personal = personal(w.downloader(), w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		String token = login(w.downloader(), personal.row()).token();
		UUID artifact = freshReleaseArtifact();

		HttpResponse<String> served = get(download(artifact), bearer(token));
		HttpResponse<String> raw = get(rawDownload(artifact), bearer(token));

		assertStatus(200, served);
		assertTrue(served.body().contains("score17-lib"), served.body());
		assertStatus(200, raw);
		assertEquals(Utils.OM.readTree(RAW_DOC), Utils.OM.readTree(raw.body()), "the raw download is the stored document");
		List<Map<String, Object>> logs = downloadLogs(artifact);
		assertEquals(2, logs.size(), logs.toString());
		for (Map<String, Object> log : logs) {
			assertEquals(w.downloader().getUuid().toString(), logField(log, "downloadedBy"), "a personal key acts as its owner");
			assertEquals(w.downloader().getUuid().toString(), logField(log, CommonVariables.LAST_UPDATED_ACTOR_FIELD), "the person who logged in");
		}
		assertEquals(Set.of("ARTIFACT_DOWNLOAD", "RAW_ARTIFACT_DOWNLOAD"),
				Set.of(logField(logs.get(0), "downloadType"), logField(logs.get(1), "downloadType")));
	}

	/** T-1: a CLI session on a Free Form key the person holds (the agents' browser login): 200, the key logged, the person as actor. */
	@Test
	void aCliSessionOnAHeldFreeFormKeyDownloadsWithThePersonAsActor() throws Exception {
		Key held = freeform(w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		apiKeyService.setApiKeyHolder(held.row().getUuid(), w.downloader().getUuid(), WU);
		String token = login(w.downloader(), held.row()).token();
		UUID artifact = freshReleaseArtifact();

		assertStatus(200, get(rawDownload(artifact), bearer(token)));

		List<Map<String, Object>> logs = downloadLogs(artifact);
		assertEquals(1, logs.size(), logs.toString());
		assertEquals(held.row().getUuid().toString(), logField(logs.get(0), "downloadedBy"));
		assertEquals(w.downloader().getUuid().toString(), logField(logs.get(0), CommonVariables.LAST_UPDATED_ACTOR_FIELD));
	}

	/** T-2: a key exchanged at the token endpoint, ARTIFACT_DOWNLOAD at organization scope: an artifact on no release and one on a release. */
	@Test
	void anExchangedOrganizationKeyDownloadsEverywhereInItsOrganization() throws Exception {
		String token = exchanged(w.orgKey());
		for (UUID artifact : List.of(w.orphanArtifact(), w.releaseArtifact(), w.otherArtifact())) {
			assertStatus(200, get(download(artifact), bearer(token)));
			assertStatus(200, get(rawDownload(artifact), bearer(token)));
		}
	}

	/** T-2: at component scope the key downloads what the component's releases carry, and nothing else. */
	@Test
	void anExchangedComponentKeyDownloadsItsComponentOnly() throws Exception {
		String token = exchanged(w.componentKey());
		assertStatus(200, get(download(w.releaseArtifact()), bearer(token)));
		assertStatus(200, get(rawDownload(w.releaseArtifact()), bearer(token)));
		HttpResponse<String> uncovered = get(download(w.otherArtifact()), bearer(token));
		assertStatus(403, uncovered);
		assertEquals("", uncovered.body());
		assertStatus(403, get(rawDownload(w.otherArtifact()), bearer(token)));
		assertStatus(403, get(rawDownload(w.orphanArtifact()), bearer(token)), "an artifact on no release needs organization scope");
	}

	/** T-3: a federated exchange through a usable rule downloads; the same token after the rule is disabled is 401. */
	@Test
	void aFederatedTokenDownloadsUntilItsRuleIsDisabled() throws Exception {
		Key bound = freeform(w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		FederatedMatcher m = new FederatedMatcher();
		m.setOwner("score17");
		FederatedGrant g = new FederatedGrant();
		g.setType(GrantType.KEY);
		g.setKeyUuid(bound.row().getUuid());
		FederatedTrustRule rule = federatedTrustRuleService.create(w.org().getUuid(), "score17-rule", null, null, m, g, null, WU);
		String token = apiTokenService.issueForFederation(bound.row(), List.of(rule),
				Map.of("repository", "score17/app", "ref", "refs/heads/main"), ApiTokenService.AUD_PROGRAMMATIC, null);

		assertStatus(200, get(download(w.releaseArtifact()), bearer(token)));
		assertStatus(200, get(rawDownload(w.orphanArtifact()), bearer(token)));

		federatedTrustRuleService.setStatus(rule.getUuid(), FederatedTrustRule.Status.DISABLED, WU);
		HttpResponse<String> after = get(download(w.releaseArtifact()), bearer(token));
		assertStatus(401, after);
		assertTrue(header(after, "WWW-Authenticate").startsWith(INVALID_TOKEN), header(after, "WWW-Authenticate"));
	}

	/** T-6: a valid token whose key holds no ARTIFACT_DOWNLOAD and is no org admin: 403, empty body, nothing logged. */
	@Test
	void aValidTokenWithoutTheDownloadFunctionIs403AndLogsNothing() throws Exception {
		UUID artifact = freshReleaseArtifact();
		String keyToken = exchanged(w.agentKey());
		Key personal = personal(w.downloader(), w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.RESOURCE));
		String sessionToken = login(w.downloader(), personal.row()).token();
		for (String token : List.of(keyToken, sessionToken)) {
			for (String path : List.of(download(artifact), rawDownload(artifact))) {
				HttpResponse<String> r = get(path, bearer(token));
				assertStatus(403, r);
				assertEquals("", r.body(), "the refusal says nothing");
			}
		}
		assertEquals(List.of(), downloadLogs(artifact));
	}

	/** T-6: a personal key may download, its owner may not: the lower of the two wins, as on every key path. */
	@Test
	void aSessionOfAPersonWhoMayNotDownloadIs403EvenIfTheKeyMay() throws Exception {
		Key personal = personal(w.reader(), w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		String token = login(w.reader(), personal.row()).token();
		assertStatus(403, get(download(w.releaseArtifact()), bearer(token)));
	}

	/** Cross-organization: a valid token of another organization's admin key reaches nothing here. */
	@Test
	void aTokenOfAnotherOrganizationIs403() throws Exception {
		String token = exchanged(w.foreignAdminKey());
		for (UUID artifact : List.of(w.releaseArtifact(), w.orphanArtifact())) {
			assertStatus(403, get(download(artifact), bearer(token)));
			assertStatus(403, get(rawDownload(artifact), bearer(token)));
		}
		assertStatus(200, get(rawDownload(w.foreignArtifact()), bearer(token)), "its own organization's artifact is served");
	}

	/** T-8: a garbage token, an expired one, one for another audience and one of a revoked CLI session: 401 before the handler. */
	@Test
	void invalidExpiredWrongAudienceAndRevokedTokensAre401() throws Exception {
		ApiKeySecret stored = ApiKeyData.dataFromRecord(w.orgKey().row()).effectiveSecrets(w.orgKey().row().getApiKey()).get(0);
		ApiKeySecret expired = new ApiKeySecret(stored.getSlot(), stored.getHash(), true, stored.getCreatedDate(), null,
				ZonedDateTime.now().minusHours(2));
		Key personal = personal(w.downloader(), w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		CliLogin login = login(w.downloader(), personal.row());
		assertStatus(200, get(rawDownload(w.releaseArtifact()), bearer(login.token())));
		cliSessionService.revoke(cliSessionService.get(login.session()).orElseThrow(), WU);

		Map<String, String> tokens = Map.of(
				"garbage", "not-a-token",
				"expired", apiTokenService.issue(w.orgKey().row(), expired, ApiTokenService.AUD_PROGRAMMATIC),
				"tea audience", apiTokenService.issue(w.orgKey().row(), stored, ApiTokenService.AUD_TEA),
				"revoked session", login.token());
		for (Map.Entry<String, String> t : tokens.entrySet()) {
			for (String path : List.of(download(w.releaseArtifact()), rawDownload(w.releaseArtifact()))) {
				HttpResponse<String> r = get(path, bearer(t.getValue()));
				assertEquals(401, r.statusCode(), t.getKey() + " on " + path + ": " + r.body());
				assertTrue(header(r, "WWW-Authenticate").startsWith(INVALID_TOKEN), t.getKey());
				assertTrue(r.body().contains("invalid_token"), r.body());
			}
		}
	}

	/**
	 * Cross-organization: a key whose component-scope grant names another organization's component
	 * is still judged in its own organization, so the foreign component's artifact is refused.
	 */
	@Test
	void aComponentGrantNamingAnotherOrganizationsComponentCoversNothing() throws Exception {
		Key k = freeform(w.org(), new PermissionDto(w.org().getUuid(), PermissionScope.COMPONENT, w.foreignComponent().getUuid(),
				PermissionType.READ_ONLY, Set.of(PermissionFunction.ARTIFACT_DOWNLOAD), null));
		String token = exchanged(k);
		assertStatus(403, get(rawDownload(w.foreignArtifact()), bearer(token)));
		assertStatus(403, get(download(w.foreignArtifact()), bearer(token)));
		assertStatus(403, get(rawDownload(w.releaseArtifact()), bearer(token)));
	}
}

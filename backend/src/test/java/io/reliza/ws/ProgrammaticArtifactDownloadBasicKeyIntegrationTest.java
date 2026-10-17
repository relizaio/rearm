/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ApiKeyService;

/**
 * SCORE-17: the programmatic artifact downloads with the API key itself as HTTP Basic (deprecated,
 * still accepted). The secret is verified by the programmatic filter before the handler: a wrong
 * secret, an unknown key id, an inactive key or a malformed header is the one invalid-credential 401
 * of {@link BasicKeyVerifier#refuse} (TPF-27; on merging main the TPF answer replaced SCORE-17's Basic
 * challenge), a valid key that may not download is 403. Before SCORE-17 all of these, and a key of
 * another organization, were 401.
 */
class ProgrammaticArtifactDownloadBasicKeyIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	/** T-4: a Free Form key with ARTIFACT_DOWNLOAD as Basic: 200 on both paths, with the three deprecation headers. */
	@Test
	void aBasicKeyWithTheDownloadFunctionDownloadsWithDeprecationHeaders() throws Exception {
		for (String path : List.of(download(w.releaseArtifact()), rawDownload(w.releaseArtifact()), rawDownload(w.orphanArtifact()))) {
			HttpResponse<String> r = get(path, basic(w.orgKey()));
			assertStatus(200, r);
			assertEquals("true", header(r, "Deprecation"), path);
			assertTrue(header(r, "Link").contains(ProgrammaticTokenController.PATH), path);
			assertTrue(header(r, "X-ReARM-Deprecation").contains("deprecated"), path);
		}
	}

	/** T-4: the download is logged under the key, which has no person behind it. */
	@Test
	void aBasicKeyDownloadIsLoggedUnderTheKey() throws Exception {
		UUID artifact = bom(w.org().getUuid());
		assertStatus(200, get(rawDownload(artifact), basic(w.orgKey())));
		List<Map<String, Object>> logs = downloadLogs(artifact);
		assertEquals(1, logs.size(), logs.toString());
		assertEquals(w.orgKey().row().getUuid().toString(), String.valueOf(logs.get(0).get("downloadedBy")));
		assertFalse(logs.get(0).containsKey(CommonVariables.LAST_UPDATED_ACTOR_FIELD), "no actor without a login");
	}

	/** T-4: an organization ADMIN key needs no function: ADMIN clears the call, as everywhere. */
	@Test
	void anOrganizationAdminKeyDownloadsWithoutTheFunction() throws Exception {
		assertStatus(200, get(download(w.otherArtifact()), basic(w.adminKey())));
		assertStatus(200, get(rawDownload(w.orphanArtifact()), basic(w.adminKey())));
	}

	/**
	 * T-5: a wrong secret, an unknown key id, an inactive key and a malformed header are the uniform
	 * invalid-credential 401 (TPF-27), with no key detail and no deprecation header.
	 */
	@Test
	void anInvalidBasicKeyIsTheInvalidCredential401() throws Exception {
		UUID artifact = bom(w.org().getUuid());
		Key inactive = freeform(w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		assertStatus(200, get(rawDownload(artifact), basic(inactive)));
		apiKeyService.setApiKeyStatus(inactive.row().getUuid(), ApiKeyStatus.INACTIVE, true, WU);
		String unknownId = "FREEFORM__" + w.org().getUuid() + "__ord__" + UUID.randomUUID();

		Map<String, String> credentials = Map.of(
				"wrong secret", basic(w.orgKey().id(), w.orgKey().secret() + "x"),
				"unknown key id", basic(unknownId, w.orgKey().secret()),
				"inactive key", basic(inactive),
				"malformed", "Basic $(ReARM-ApiKeyId)");
		for (Map.Entry<String, String> c : credentials.entrySet()) {
			for (String path : List.of(download(artifact), rawDownload(artifact))) {
				HttpResponse<String> r = get(path, c.getValue());
				assertEquals(401, r.statusCode(), c.getKey() + " on " + path + ": " + r.body());
				assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, header(r, "WWW-Authenticate"), c.getKey());
				assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.body(), c.getKey());
				assertNull(header(r, "Deprecation"), c.getKey() + ": no deprecation header on a refusal");
			}
		}
		assertEquals(1, downloadLogs(artifact).size(), "only the download made while the key was active is logged");
	}

	/** T-6: a valid key without ARTIFACT_DOWNLOAD (the agents' AGENT-only key) is 403, empty body, nothing logged. */
	@Test
	void aValidBasicKeyWithoutTheDownloadFunctionIs403() throws Exception {
		UUID artifact = bom(w.org().getUuid());
		for (String path : List.of(download(artifact), rawDownload(artifact), download(w.releaseArtifact()))) {
			HttpResponse<String> r = get(path, basic(w.agentKey()));
			assertStatus(403, r);
			assertEquals("", r.body());
			assertEquals("true", header(r, "Deprecation"), "the key was valid, the deprecation still applies");
		}
		assertEquals(List.of(), downloadLogs(artifact));
	}

	/** T-6: a valid key of a type that carries no permission set (a component's own key) is 403 too, not 401. */
	@Test
	void aValidKeyOfANonRbacTypeIs403() throws Exception {
		String secret = apiKeyService.setObjectApiKey(w.covered().getUuid(), ApiTypeEnum.COMPONENT, w.org().getUuid(), null, "score17", false, WU);
		ApiKey row = apiKeyService.getApiKeyByObjUuidTypeOrder(w.covered().getUuid(), ApiTypeEnum.COMPONENT, null, w.org().getUuid()).orElseThrow();
		assertStatus(403, get(rawDownload(w.releaseArtifact()), basic(ApiKeyService.keyIdOf(row), secret)));
	}

	/** Cross-organization: another organization's admin key, and this organization's key on the other's artifacts, are 403. */
	@Test
	void keysReachNoArtifactOfAnotherOrganization() throws Exception {
		for (UUID artifact : List.of(w.releaseArtifact(), w.orphanArtifact())) {
			assertStatus(403, get(download(artifact), basic(w.foreignAdminKey())));
			assertStatus(403, get(rawDownload(artifact), basic(w.foreignAdminKey())));
		}
		for (Key k : List.of(w.orgKey(), w.adminKey())) {
			for (UUID artifact : List.of(w.foreignArtifact(), w.foreignOrphan())) {
				assertStatus(403, get(download(artifact), basic(k)));
				assertStatus(403, get(rawDownload(artifact), basic(k)));
			}
		}
		assertStatus(200, get(rawDownload(w.foreignOrphan()), basic(w.foreignAdminKey())), "its own artifact is served");
	}

	/**
	 * T-9: with a valid key an unknown version is 404 and an unknown artifact is 403, the answer of an
	 * artifact the key may not download (TPF-12: a uuid's existence is not told; main's SCORE-17 answered
	 * 404 here); without a credential either is 401, never 404.
	 */
	@Test
	void anUnknownArtifactIs403AndAnUnknownVersion404ForAnAuthenticatedCallerOnly() throws Exception {
		UUID unknown = UUID.randomUUID();
		assertStatus(403, get(download(unknown), basic(w.orgKey())));
		assertStatus(403, get(rawDownload(unknown), basic(w.orgKey())));
		assertStatus(404, get(rawDownload(w.releaseArtifact()) + "?version=99", basic(w.orgKey())));
		assertStatus(404, get(download(w.releaseArtifact()) + "?version=99", bearer(exchanged(w.orgKey()))));
		for (String path : List.of(download(unknown), rawDownload(unknown), rawDownload(w.releaseArtifact()) + "?version=99")) {
			HttpResponse<String> anonymous = get(path, null);
			assertStatus(401, anonymous);
			assertEquals("Bearer realm=\"rearm\"", header(anonymous, "WWW-Authenticate"));
		}
		assertStatus(401, get(download(unknown), basic(w.orgKey().id(), "wrong")), "a wrong secret learns nothing either");
	}
}

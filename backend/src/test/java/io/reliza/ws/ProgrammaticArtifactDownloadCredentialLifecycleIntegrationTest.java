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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;

/**
 * SCORE-17: a credential that worked stops working on the programmatic artifact downloads when
 * what stands behind it is switched off after it was issued: the key (deactivated), the secret a
 * token was exchanged with (retired or expired while the token itself is unexpired), or the person
 * (soft-deleted) who owns a personal key or logged in through the CLI. Each case first downloads
 * with the credential (200), then switches one thing off and expects the refusal on both paths:
 * 401 {@code invalid_token} from the programmatic filter for a bearer it no longer accepts, and from the
 * handler's key verification for a personal key whose owner is no longer active (TPF-12: a key-level
 * refusal is answered before any lookup as an invalid credential; main's SCORE-17 answered it 403).
 */
class ProgrammaticArtifactDownloadCredentialLifecycleIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	private Key downloadKey() throws Exception {
		return freeform(w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
	}

	/** A person of A who may download, of their own, so switching them off touches no other test. */
	private UserData freshDownloader() throws Exception {
		return user(w.org(), List.of(PermissionFunction.RESOURCE, PermissionFunction.ARTIFACT_DOWNLOAD));
	}

	private int slotOf(Key k) {
		var row = apiKeyService.getApiKey(k.row().getUuid()).orElseThrow();
		return ApiKeyData.dataFromRecord(row).effectiveSecrets(row.getApiKey()).get(0).getSlot();
	}

	private void assertServed(Map<String, String> credentials, UUID artifact) throws Exception {
		for (Map.Entry<String, String> c : credentials.entrySet()) {
			for (String path : List.of(download(artifact), rawDownload(artifact))) {
				assertStatus(200, get(path, c.getValue()), c.getKey() + " before:");
			}
		}
	}

	/** Every path refuses every credential with {@code status}; a 401 (bearer tokens only here) carries the invalid_token challenge, a 403 says nothing. */
	private void assertRefused(int status, Map<String, String> credentials, UUID artifact) throws Exception {
		for (Map.Entry<String, String> c : credentials.entrySet()) {
			for (String path : List.of(download(artifact), rawDownload(artifact))) {
				HttpResponse<String> r = get(path, c.getValue());
				assertStatus(status, r, c.getKey() + " after:");
				if (status == 401) {
					assertTrue(header(r, "WWW-Authenticate").startsWith(INVALID_TOKEN), c.getKey() + ": " + header(r, "WWW-Authenticate"));
				}
				if (status == 403) {
					assertEquals("", r.body(), c.getKey() + ": the refusal says nothing");
				}
			}
		}
	}

	/** T-2: a token exchanged while the key was active is 401 once the key is deactivated, and works again once it is re-activated. */
	@Test
	void aTokenOfADeactivatedKeyIs401() throws Exception {
		Key k = downloadKey();
		Map<String, String> token = Map.of("exchanged token", bearer(exchanged(k)));
		assertServed(token, w.releaseArtifact());

		apiKeyService.setApiKeyStatus(k.row().getUuid(), ApiKeyStatus.INACTIVE, true, WU);
		assertRefused(401, token, w.releaseArtifact());

		apiKeyService.setApiKeyStatus(k.row().getUuid(), ApiKeyStatus.ACTIVE, true, WU);
		assertServed(token, w.releaseArtifact());
	}

	/** T-2: a token exchanged with a secret that is then retired (set inactive, not regenerated) is 401, and works again once the secret is re-enabled. */
	@Test
	void aTokenOfARetiredSecretIs401() throws Exception {
		Key k = downloadKey();
		Map<String, String> token = Map.of("exchanged token", bearer(exchanged(k)));
		assertServed(token, w.orphanArtifact());

		apiKeyService.setApiKeySecretActive(k.row().getUuid(), slotOf(k), false, WU);
		assertRefused(401, token, w.orphanArtifact());

		apiKeyService.setApiKeySecretActive(k.row().getUuid(), slotOf(k), true, WU);
		assertServed(token, w.orphanArtifact());
	}

	/** T-2: a token exchanged with a secret whose expiry is then moved into the past is 401 while the token's own exp is still ahead. */
	@Test
	void aTokenOfAnExpiredSecretIs401BeforeItsOwnExpiry() throws Exception {
		Key k = downloadKey();
		Map<String, String> token = Map.of("exchanged token", bearer(exchanged(k)));
		assertServed(token, w.releaseArtifact());

		apiKeyService.setApiKeySecretExpiry(k.row().getUuid(), slotOf(k), ZonedDateTime.now().minusMinutes(1), WU);
		assertRefused(401, token, w.releaseArtifact());
	}

	/**
	 * T-3: a personal key whose owner is soft-deleted (still a member of the organization, still
	 * holding ARTIFACT_DOWNLOAD) is refused, as Basic and as a token exchanged before the owner went:
	 * 401 invalid_token from the handler's key verification ({@code verifyProgrammaticKey}), which
	 * requires an active owner before the artifact is looked up (TPF-12); nothing is logged after.
	 */
	@Test
	void aPersonalKeyOfAnInactiveOwnerIsRefusedAsBasicAndAsAToken() throws Exception {
		UserData owner = freshDownloader();
		Key personal = personal(owner, w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		Map<String, String> credentials = Map.of("Basic", basic(personal), "exchanged token", bearer(exchanged(personal)));
		UUID artifact = bom(w.org().getUuid());
		assertServed(credentials, artifact);
		int servedLogs = downloadLogs(artifact).size();

		userService.softDeleteUser(owner.getUuid(), WU);
		assertRefused(401, credentials, artifact);
		assertEquals(servedLogs, downloadLogs(artifact).size(), "nothing logged for the refusals");
	}

	/**
	 * T-3: a CLI session of a person who is then soft-deleted is 401, on their personal key and on a
	 * Free Form key they hold. The held key has no owner the handler could check, so only the
	 * filter's check of the session's user refuses it there.
	 */
	@Test
	void aCliSessionOfAnInactiveUserIs401OnAPersonalAndOnAHeldKey() throws Exception {
		UserData person = freshDownloader();
		Key personal = personal(person, w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		Key held = downloadKey();
		apiKeyService.setApiKeyHolder(held.row().getUuid(), person.getUuid(), WU);
		Map<String, String> sessions = Map.of(
				"session on the personal key", bearer(login(person, personal.row()).token()),
				"session on the held Free Form key", bearer(login(person, held.row()).token()));
		assertServed(sessions, w.releaseArtifact());

		userService.softDeleteUser(person.getUuid(), WU);
		assertRefused(401, sessions, w.releaseArtifact());
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.model.CliSession.Status;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.repositories.CliSessionRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A CLI session acts as its key, so deleting the key revokes the key's active sessions in the same
 * transaction; sessions of other keys stay (task t20261013-090918-2857).
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyDeleteRevokesCliSessionsTest {

	@Autowired private UserService userService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private CliSessionService cliSessionService;
	@Autowired private CliSessionRepository cliSessionRepository;
	@Autowired private ApiKeyRepository apiKeyRepository;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private UserData member(Organization org, PermissionType level) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("D " + tag, "d-" + tag + "@key-delete.io", true, List.of(org.getUuid()),
				"d-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level, null, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A device login approved on the given key; the session reads ACTIVE. */
	private UUID approvedSession(UserData member, UUID keyUuid) throws Exception {
		CliSessionService.DeviceAuthorization d = cliSessionService.start(
				new CliSessionService.DeviceInfo("delete-host", null, null, null, null));
		CliSession s = cliSessionService.approveWithKey(d.userCode(), member, keyUuid, null);
		assertEquals(Status.ACTIVE, s.getStatus());
		return s.getUuid();
	}

	/** A deleted key, read past getApiKey, which hides revoked keys. */
	private ApiKeyData revokedKey(UUID key) {
		return ApiKeyData.dataFromRecord(apiKeyRepository.findById(key).orElseThrow());
	}

	private CliSession read(UUID session) {
		return cliSessionRepository.findById(session).orElseThrow();
	}

	@Test
	public void deletingAKeyRevokesItsActiveSessionsOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData member = member(org, PermissionType.READ_WRITE);
		ApiKey first = apiKeyService.createObjectApiKey(member.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "first", WU);
		ApiKey second = apiKeyService.createObjectApiKey(member.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "second", WU);
		UUID a = approvedSession(member, first.getUuid());
		UUID b = approvedSession(member, first.getUuid());
		UUID other = approvedSession(member, second.getUuid());

		apiKeyService.deleteApiKey(first.getUuid(), WU);

		for (UUID s : List.of(a, b)) {
			assertEquals(Status.REVOKED, read(s).getStatus());
			assertNotNull(read(s).getRevokedDate());
		}
		assertTrue(cliSessionService.listByKey(first.getUuid()).isEmpty());
		assertEquals(Status.ACTIVE, read(other).getStatus(), "another key's session stays");
		ApiKeyData akd = revokedKey(first.getUuid());
		assertEquals(ApiKeyStatus.REVOKED, akd.getStatus());
		assertTrue(akd.getSecrets().isEmpty());

		// a second delete is harmless: the key is no longer found, the sessions and the key read the same
		apiKeyService.deleteApiKey(first.getUuid(), WU);
		for (UUID s : List.of(a, b)) assertEquals(Status.REVOKED, read(s).getStatus());
		assertEquals(Status.ACTIVE, read(other).getStatus());
		assertEquals(ApiKeyStatus.REVOKED, revokedKey(first.getUuid()).getStatus());
	}

	@Test
	public void revokingTheLastSessionOfACliKeyStillWorks() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData member = member(org, PermissionType.READ_WRITE);
		CliSessionService.DeviceAuthorization d = cliSessionService.start(
				new CliSessionService.DeviceInfo("delete-host", null, null, null, null));
		CliSession s = cliSessionService.approveWithNewKey(d.userCode(), member, org.getUuid(), null, PermissionType.NONE,
				List.of(), null, WU);
		UUID key = s.getApiKey();

		// revoke saves the session REVOKED, then deletes its CLI_SESSION-origin key, whose update finds no active row
		cliSessionService.revoke(read(s.getUuid()), WU);

		assertEquals(Status.REVOKED, read(s.getUuid()).getStatus());
		assertEquals(ApiKeyStatus.REVOKED, revokedKey(key).getStatus());
	}

	@Test
	public void removingAUserFromTheOrgRevokesTheirPersonalKeySessions() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData member = member(org, PermissionType.READ_WRITE);
		ApiKey personal = apiKeyService.createObjectApiKey(member.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "personal", WU);
		UUID session = approvedSession(member, personal.getUuid());

		apiKeyService.revokeUserKeysInOrg(member.getUuid(), org.getUuid(), WU);

		assertEquals(Status.REVOKED, read(session).getStatus());
	}

	/** A key whose record carries no status yet (written before statuses existed) is still audited when deleted. */
	@Test
	public void deletingAKeyWithoutAStatusInItsRecordBumpsItsRevision() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "legacy", WU);
		ApiKey legacy = apiKeyRepository.findById(ak.getUuid()).orElseThrow();
		Map<String, Object> record = new HashMap<>(legacy.getRecordData());
		record.remove("status");
		legacy.setRecordData(record);
		legacy.setApiKey("legacy-hash");
		legacy = apiKeyRepository.save(legacy);
		int before = legacy.getRevision();

		apiKeyService.deleteApiKey(ak.getUuid(), WU);

		ApiKey after = apiKeyRepository.findById(ak.getUuid()).orElseThrow();
		assertEquals(before + 1, after.getRevision(), "saveApiKey saw the live key and recorded the change");
		assertNull(after.getApiKey());
		assertEquals(ApiKeyStatus.REVOKED, ApiKeyData.dataFromRecord(after).getStatus());
	}
}

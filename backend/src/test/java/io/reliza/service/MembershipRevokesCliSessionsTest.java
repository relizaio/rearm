/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
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
import io.reliza.service.saas.SaasOrganizationService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * CLI sessions follow membership and holdership: removing a member from an org revokes their sessions on
 * that org's keys, and changing a FREEFORM key's holder revokes the key's sessions (task t20261013-190257-31171).
 */
@SpringBootTest(classes = {App.class})
public class MembershipRevokesCliSessionsTest {

	@Autowired private UserService userService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private SaasOrganizationService saasOrganizationService;
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

	/** A READ_WRITE member of each of the given orgs. */
	private UserData member(Organization... orgs) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		List<UUID> orgUuids = Arrays.stream(orgs).map(Organization::getUuid).toList();
		User u = userService.createUser("M " + tag, "m-" + tag + "@membership.io", true, orgUuids,
				"m-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		for (UUID org : orgUuids) {
			userService.setUserPermission(u.getUuid(), org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, null, null, WU);
		}
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A FREEFORM key of the org held by the given member. */
	private UUID heldKey(Organization org, UserData holder) throws Exception {
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "held", WU);
		apiKeyService.setApiKeyHolder(ak.getUuid(), holder.getUuid(), WU);
		return ak.getUuid();
	}

	/** A device login approved on the given key; the session reads ACTIVE. */
	private UUID approvedSession(UserData member, UUID keyUuid) throws Exception {
		CliSessionService.DeviceInfo info = new CliSessionService.DeviceInfo("membership-host", null, null, null, null);
		CliSession s = cliSessionService.approveWithKey(cliSessionService.start(info).userCode(), member, keyUuid, null);
		assertEquals(Status.ACTIVE, s.getStatus());
		return s.getUuid();
	}

	/** Rewrites a key's holder in place, as a holder change before this release did: its sessions stay active. */
	private void holderWrittenDirectly(UUID key, UUID holder) {
		ApiKey ak = apiKeyRepository.findById(key).orElseThrow();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setHolder(holder);
		ak.setRecordData(Utils.dataToRecord(akd));
		apiKeyRepository.save(ak);
	}

	private UUID holderOf(UUID key) {
		return apiKeyService.getApiKeyData(key).orElseThrow().getHolder();
	}

	private CliSession read(UUID session) {
		return cliSessionRepository.findById(session).orElseThrow();
	}

	private void assertRevoked(UUID session) {
		assertEquals(Status.REVOKED, read(session).getStatus());
		assertNotNull(read(session).getRevokedDate());
	}

	@Test
	public void removingAMemberRevokesTheirSessionsOnThatOrgsKeysOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization other = saasOrganizationService.createOrganization("x-" + UUID.randomUUID(), WU);
		UserData leaving = member(org, other);
		UserData staying = member(org);
		UUID keyHere = heldKey(org, leaving);
		UUID keyThere = heldKey(other, leaving);
		UUID asked = apiKeyService.requestFreeformApiKey(leaving.getUuid(), org.getUuid(), "asked", null, null, WU).getUuid();
		UUID denied = apiKeyService.requestFreeformApiKey(leaving.getUuid(), org.getUuid(), "denied", null, null, WU).getUuid();
		apiKeyService.resolveApiKeyRequest(denied, false, "no", WU);
		UUID leavingHere = approvedSession(leaving, keyHere);
		UUID leavingThere = approvedSession(leaving, keyThere);
		UUID stayingHere = approvedSession(staying, heldKey(org, staying));

		assertTrue(userService.removeUserFromOrg(org.getUuid(), leaving.getUuid(), WU));

		assertRevoked(leavingHere);
		assertEquals(Status.ACTIVE, read(leavingThere).getStatus(), "the member's session in another org stays");
		assertEquals(Status.ACTIVE, read(stayingHere).getStatus(), "another member's session stays");
		UserData after = userService.getUserData(leaving.getUuid()).orElseThrow();
		assertFalse(after.getOrganizations().contains(org.getUuid()));
		assertTrue(after.getOrganizations().contains(other.getUuid()));
		assertNull(holderOf(keyHere), "the key the member held here has no holder");
		assertEquals(leaving.getUuid(), holderOf(keyThere), "the key in the other org keeps its holder");
		assertThrows(RelizaException.class, () -> approvedSession(after, keyHere), "a former member cannot log in on it again");
		assertTrue(apiKeyService.getApiKey(asked).isEmpty(), "a request still waiting is withdrawn");
		assertTrue(apiKeyService.getApiKey(denied).isEmpty(), "a denied request is withdrawn too");
		assertTrue(apiKeyService.listUserKeyDtos(leaving.getUuid()).stream().noneMatch(k -> org.getUuid().equals(k.getOrg())),
				"none of the org's keys is listed as theirs any more");

		// removing again finds no membership and revokes nothing more
		assertFalse(userService.removeUserFromOrg(org.getUuid(), leaving.getUuid(), WU));
		assertEquals(Status.ACTIVE, read(leavingThere).getStatus());
	}

	/** A session on a key whose holder changed before this release (and so was not revoked) goes when its user leaves. */
	@Test
	public void removingAMemberRevokesTheirSessionOnAKeyTheyNoLongerHold() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData leaving = member(org);
		UserData next = member(org);
		UUID key = heldKey(org, leaving);
		UUID session = approvedSession(leaving, key);
		holderWrittenDirectly(key, next.getUuid());
		assertEquals(Status.ACTIVE, read(session).getStatus());

		userService.removeUserFromOrg(org.getUuid(), leaving.getUuid(), WU);

		assertRevoked(session);
		assertEquals(next.getUuid(), holderOf(key), "the key's current holder stays");
	}

	/** Refresh asks again whether the session's user still holds the key: one that stopped is refused, revoked or not. */
	@Test
	public void aRefreshIsRefusedOnceTheUserNoLongerHoldsTheKey() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData first = member(org);
		UserData next = member(org);
		UUID key = heldKey(org, first);
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("membership-host", null, null, null, null));
		cliSessionService.approveWithKey(d.userCode(), first, key, null);
		CliSessionService.Delivery delivery = cliSessionService.poll(d.deviceCode()).delivery();
		CliSessionService.Refreshed refreshed = cliSessionService.refresh(delivery.refreshToken(), "198.51.100.9").orElseThrow();

		String hash = read(delivery.session().getUuid()).getRefreshTokenHash();

		holderWrittenDirectly(key, next.getUuid());

		assertTrue(cliSessionService.refresh(refreshed.refreshToken(), "198.51.100.9").isEmpty());
		assertTrue(cliSessionService.refresh(delivery.refreshToken(), "198.51.100.9").isEmpty(), "the retired token, in its grace window, too");
		assertEquals(Status.ACTIVE, read(delivery.session().getUuid()).getStatus(), "refused, not revoked");
		assertEquals(hash, read(delivery.session().getUuid()).getRefreshTokenHash(), "nothing rotated");
	}

	/** A holder left over from before this release, when leaving the org kept holdership, approves nothing. */
	@Test
	public void aFormerMemberStillNamedHolderCannotApprove() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData leaving = member(org);
		UUID key = heldKey(org, leaving);
		userService.removeUserFromOrg(org.getUuid(), leaving.getUuid(), WU);
		apiKeyService.setApiKeyHolder(key, leaving.getUuid(), WU);
		UserData after = userService.getUserData(leaving.getUuid()).orElseThrow();

		RelizaException e = assertThrows(RelizaException.class, () -> approvedSession(after, key));
		assertEquals("Not a member of this organization", e.getMessage());
	}

	@Test
	public void changingTheHolderRevokesTheKeysSessions() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData first = member(org);
		UserData second = member(org);
		UUID key = heldKey(org, first);
		UUID firstSession = approvedSession(first, key);

		apiKeyService.setApiKeyHolder(key, second.getUuid(), WU);
		assertRevoked(firstSession);

		UUID secondSession = approvedSession(second, key);
		apiKeyService.setApiKeyHolder(key, second.getUuid(), WU);
		assertEquals(Status.ACTIVE, read(secondSession).getStatus(), "the same holder again is no change");

		apiKeyService.setApiKeyHolder(key, null, WU);
		assertRevoked(secondSession);
	}

	@Test
	public void aPersonalKeyHasNoHolder() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData owner = member(org);
		ApiKey personal = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "personal", WU);
		UUID session = approvedSession(owner, personal.getUuid());

		RelizaException e = assertThrows(RelizaException.class,
				() -> apiKeyService.setApiKeyHolder(personal.getUuid(), owner.getUuid(), WU));
		assertEquals("Only FREEFORM keys have a holder", e.getMessage());
		assertEquals(Status.ACTIVE, read(session).getStatus());
	}
}

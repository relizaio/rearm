/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.repositories.ApiKeyGroupRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * ApiKeyGroupService (TPF-91 architecture-5 row 11): names unique over active and inactive groups of an
 * organization, null fields keep the stored value, the by-key read returns only the ACTIVE groups of
 * the key's organization holding it, and the deletion sweep reaches inactive groups and no other
 * organization's.
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyGroupServiceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private ApiKeyGroupService apiKeyGroupService;
	@Autowired private ApiKeyGroupRepository apiKeyGroupRepository;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private TestInitializer testInitializer;

	private ApiKeyGroupData create(Organization org, String name) throws RelizaException {
		return apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder().name(name).org(org.getUuid()).build(), WU);
	}

	private ApiKeyGroupData update(UpdateApiKeyGroupDto.UpdateApiKeyGroupDtoBuilder b) throws RelizaException {
		return apiKeyGroupService.updateApiKeyGroup(b.build(), WU);
	}

	private ApiKey freeform(Organization org) {
		return apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "akg service", WU);
	}

	private static UserGroupPermissionDto boardRead(UUID board) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.BOARD).objectId(board)
				.type(PermissionType.READ_ONLY).functions(List.of(PermissionFunction.BOARD_READ)).build();
	}

	private ApiKeyGroupData stored(ApiKeyGroupData g) {
		return apiKeyGroupService.getApiKeyGroupData(g.getUuid()).orElseThrow();
	}

	/** The key's uuid written into the row directly, past the service's member validation. */
	private void plant(ApiKeyGroupData g, UUID keyUuid) {
		ApiKeyGroup row = apiKeyGroupRepository.findById(g.getUuid()).orElseThrow();
		ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(row);
		d.addApiKey(keyUuid);
		row.setRecordData(Utils.dataToRecord(d));
		apiKeyGroupRepository.save(row);
	}

	@Test
	public void aNameIsUniqueOverActiveAndInactiveGroupsOfTheOrganization() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String active = "akg-active-" + UUID.randomUUID();
		create(org, active);
		RelizaException taken = assertThrows(RelizaException.class, () -> create(org, active));
		assertTrue(taken.getMessage().contains("already exists"), taken.getMessage());

		String inactive = "akg-inactive-" + UUID.randomUUID();
		ApiKeyGroupData g = create(org, inactive);
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		RelizaException restoreIt = assertThrows(RelizaException.class, () -> create(org, inactive));
		assertTrue(restoreIt.getMessage().contains("inactive group"), restoreIt.getMessage());

		ApiKeyGroupData other = create(org, "akg-other-" + UUID.randomUUID());
		RelizaException rename = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(other.getUuid()).name(active)));
		assertTrue(rename.getMessage().contains("already exists"), rename.getMessage());

		// a restore that would land on a name another group holds is refused; the plain restore is not
		RelizaException restore = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(g.getUuid()).name(active).status(ApiKeyGroupStatus.ACTIVE)));
		assertTrue(restore.getMessage().contains("already exists"), restore.getMessage());
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus());
		assertEquals(ApiKeyGroupStatus.ACTIVE, update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.status(ApiKeyGroupStatus.ACTIVE)).getStatus());
	}

	@Test
	public void theSameNameInAnotherOrganizationIsAllowed() throws RelizaException {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org1.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		String name = "akg-cross-" + UUID.randomUUID();
		create(org1, name);
		assertEquals(name, create(org2, name).getName());
	}

	@Test
	public void nullFieldsKeepTheStoredValueAndAnUnknownGroupIsNotFound() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org);
		UUID board = UUID.randomUUID();
		ApiKeyGroupData g = create(org, "akg-keep-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).apiKeys(Set.of(k.getUuid())).permissions(List.of(boardRead(board))));
		ApiKeyGroupData before = stored(g);
		ApiKeyGroupData after = update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).description("only this"));
		assertEquals("only this", after.getDescription());
		assertEquals(before.getName(), after.getName());
		assertEquals(Set.of(k.getUuid()), after.getApiKeys());
		assertEquals(before.getOrgPermissions(org.getUuid()), after.getOrgPermissions(org.getUuid()));
		assertEquals(ApiKeyGroupStatus.ACTIVE, after.getStatus());

		RelizaException e = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(UUID.randomUUID()).description("x")));
		assertTrue(e.getMessage().contains("API key group not found"), e.getMessage());
	}

	@Test
	public void permissionSetsForKeyReadsOnlyTheActiveGroupsOfTheKeysOrganizationHoldingIt() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey k = freeform(org);
		UUID b1 = UUID.randomUUID();
		ApiKeyGroupData holding = create(org, "akg-holding-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(holding.getUuid()).apiKeys(Set.of(k.getUuid())).permissions(List.of(boardRead(b1))));
		ApiKeyGroupData inactive = create(org, "akg-inactive-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(inactive.getUuid()).apiKeys(Set.of(k.getUuid()))
				.permissions(List.of(boardRead(UUID.randomUUID()))));
		apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);
		ApiKeyGroupData notHolding = create(org, "akg-not-holding-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(notHolding.getUuid()).permissions(List.of(boardRead(UUID.randomUUID()))));
		ApiKeyGroupData foreign = create(org2, "akg-foreign-" + UUID.randomUUID());
		plant(foreign, k.getUuid());

		List<Set<UserPermission>> sets = apiKeyGroupService.permissionSetsForKey(k.getUuid(), org.getUuid());
		assertEquals(List.of(stored(holding).getOrgPermissions(org.getUuid())), sets,
				"the inactive group, the group not holding the key and the other organization's group are absent");
	}

	@Test
	public void theDeletionSweepReachesInactiveGroupsAndNoOtherOrganization() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey k = freeform(org);
		ApiKeyGroupData active = create(org, "akg-sweep-a-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(active.getUuid()).apiKeys(Set.of(k.getUuid())));
		ApiKeyGroupData inactive = create(org, "akg-sweep-i-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(inactive.getUuid()).apiKeys(Set.of(k.getUuid())));
		apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);
		ApiKeyGroupData foreign = create(org2, "akg-sweep-f-" + UUID.randomUUID());
		plant(foreign, k.getUuid());

		apiKeyGroupService.removeKeyFromAllGroups(k.getUuid(), org.getUuid(), WU);
		assertFalse(stored(active).getApiKeys().contains(k.getUuid()));
		assertFalse(stored(inactive).getApiKeys().contains(k.getUuid()));
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(inactive).getStatus());
		assertEquals(Set.of(k.getUuid()), stored(foreign).getApiKeys(), "another organization's group is untouched");
	}
}

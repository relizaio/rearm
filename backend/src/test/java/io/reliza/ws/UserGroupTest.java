/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.service.UserGroupService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Unit tests for UserGroup functionality including:
 * - CRUD operations
 * - Name uniqueness (active and inactive, cross-org isolation)
 * - Name conflict on rename and restore
 * - SSO group uniqueness enforcement
 * - User management within groups
 * - Full lifecycle tests
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class UserGroupTest {

	@Autowired
	private UserGroupService userGroupService;

	@Autowired
	private UserService userService;

	@Autowired
	private TestInitializer testInitializer;

	// ==================== CREATE ====================

	@Test
	public void testCreateUserGroup() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("test-group")
				.description("A test group")
				.org(org.getUuid())
				.build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertNotNull(created.getUuid());
		Assertions.assertEquals("test-group", created.getName());
		Assertions.assertEquals("A test group", created.getDescription());
		Assertions.assertEquals(org.getUuid(), created.getOrg());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, created.getStatus());
	}

	@Test
	public void testCreateUserGroupBlockedByInactiveSameName() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String groupName = "duplicate-name-" + UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		RelizaException ex = Assertions.assertThrows(RelizaException.class, () -> {
			userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("inactive group"));
	}

	@Test
	public void testCreateUserGroupBlockedByActiveSameName() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String groupName = "active-dup-" + UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		RelizaException ex = Assertions.assertThrows(RelizaException.class, () -> {
			userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("already exists"));
	}

	@Test
	public void testCreateUserGroupNameIsolatedByOrg() throws Exception {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		String groupName = "cross-org-" + UUID.randomUUID();

		CreateUserGroupDto dto1 = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org1.getUuid()).build();
		userGroupService.createUserGroup(dto1, WhoUpdated.getTestWhoUpdated());

		// Same name in different org should succeed
		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org2.getUuid()).build();
		UserGroupData created2 = userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(groupName, created2.getName());
	}

	// ==================== READ ====================

	@Test
	public void testGetUserGroupData() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("read-test-group").description("desc").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		Optional<UserGroupData> fetched = userGroupService.getUserGroupData(created.getUuid());
		Assertions.assertTrue(fetched.isPresent());
		Assertions.assertEquals("read-test-group", fetched.get().getName());
	}

	@Test
	public void testGetUserGroupDataNotFound() throws Exception {
		Optional<UserGroupData> fetched = userGroupService.getUserGroupData(UUID.randomUUID());
		Assertions.assertFalse(fetched.isPresent());
	}

	@Test
	public void testGetUserGroupsByOrganizationReturnsActiveOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto dto1 = CreateUserGroupDto.builder()
				.name("active-" + suffix).description("").org(org.getUuid()).build();
		userGroupService.createUserGroup(dto1, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name("to-deactivate-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData created2 = userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created2.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		List<UserGroupData> activeGroups = userGroupService.getUserGroupsByOrganization(org.getUuid());
		Assertions.assertTrue(activeGroups.stream().allMatch(g -> g.getStatus() == UserGroupStatus.ACTIVE));
		Assertions.assertTrue(activeGroups.stream().noneMatch(g -> g.getUuid().equals(created2.getUuid())));
	}

	@Test
	public void testGetAllUserGroupsByOrganizationIncludesInactive() throws Exception {
		Organization org = testInitializer.obtainOrganization();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("inactive-list-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		List<UserGroupData> allGroups = userGroupService.getAllUserGroupsByOrganization(org.getUuid());
		boolean foundInactive = allGroups.stream()
				.anyMatch(g -> g.getUuid().equals(created.getUuid()) && g.getStatus() == UserGroupStatus.INACTIVE);
		Assertions.assertTrue(foundInactive, "Inactive group should appear in getAllUserGroupsByOrganization");
	}

	// ==================== UPDATE ====================

	@Test
	public void testUpdateUserGroupName() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("original-name").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).name("updated-name").build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals("updated-name", updated.getName());
	}

	@Test
	public void testUpdateUserGroupDescription() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("desc-update-test").description("old desc").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).description("new desc").build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals("new desc", updated.getDescription());
		Assertions.assertEquals("desc-update-test", updated.getName());
	}

	@Test
	public void testUpdateUserGroupUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("users-update-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertTrue(created.getUsers().isEmpty());

		UUID testUserUuid = UUID.randomUUID();
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).users(Set.of(testUserUuid)).build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(1, updated.getUsers().size());
		Assertions.assertTrue(updated.getUsers().contains(testUserUuid));

		// Verify persistence by re-reading
		UserGroupData refetched = userGroupService.getUserGroupData(created.getUuid()).get();
		Assertions.assertEquals(1, refetched.getUsers().size());
		Assertions.assertTrue(refetched.getUsers().contains(testUserUuid));
	}

	@Test
	public void testUpdateUserGroupConnectedSsoGroups() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("sso-update-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.connectedSsoGroups(Set.of("sso-group-1", "sso-group-2"))
				.build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(2, updated.getConnectedSsoGroups().size());
		Assertions.assertTrue(updated.getConnectedSsoGroups().contains("sso-group-1"));
		Assertions.assertTrue(updated.getConnectedSsoGroups().contains("sso-group-2"));
	}

	@Test
	public void testUpdateNonExistentGroupThrows() throws Exception {
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(UUID.randomUUID()).name("no-such-group").build();
		Assertions.assertThrows(RelizaException.class, () -> {
			userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		});
	}

	@Test
	public void testUpdatePreservesUnchangedFields() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto createDto = CreateUserGroupDto.builder()
				.name("preserve-fields").description("original desc").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(createDto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).name("new-name").build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		Assertions.assertEquals("new-name", updated.getName());
		Assertions.assertEquals("original desc", updated.getDescription());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, updated.getStatus());
		Assertions.assertTrue(updated.getUsers().isEmpty());
		Assertions.assertTrue(updated.getConnectedSsoGroups().isEmpty());
	}

	@Test
	public void testUpdateMultipleFieldsAtOnce() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto createDto = CreateUserGroupDto.builder()
				.name("multi-update").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(createDto, WhoUpdated.getTestWhoUpdated());

		UUID testUser = UUID.randomUUID();
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.name("multi-updated").description("new desc")
				.users(Set.of(testUser)).connectedSsoGroups(Set.of("sso1"))
				.build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		Assertions.assertEquals("multi-updated", updated.getName());
		Assertions.assertEquals("new desc", updated.getDescription());
		Assertions.assertEquals(1, updated.getUsers().size());
		Assertions.assertTrue(updated.getUsers().contains(testUser));
		Assertions.assertTrue(updated.getConnectedSsoGroups().contains("sso1"));
	}

	// ==================== DEACTIVATE / RESTORE ====================

	@Test
	public void testDeactivateUserGroup() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("deactivate-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		UserGroupData deactivated = userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(UserGroupStatus.INACTIVE, deactivated.getStatus());
	}

	@Test
	public void testRestoreUserGroup() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("restore-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto restoreDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.ACTIVE).build();
		UserGroupData restored = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, restored.getStatus());
	}

	// ==================== T5: RENAME CONFLICT ====================

	@Test
	public void testRenameActiveGroupNameConflictThrows() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto dto1 = CreateUserGroupDto.builder()
				.name("abc-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group1 = userGroupService.createUserGroup(dto1, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name("fenda-" + suffix).description("").org(org.getUuid()).build();
		userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());

		// Rename "abc" to "fenda" should throw
		UpdateUserGroupDto renameDto = UpdateUserGroupDto.builder()
				.groupId(group1.getUuid()).name("fenda-" + suffix).build();
		RelizaException ex = Assertions.assertThrows(RelizaException.class, () -> {
			userGroupService.updateUserGroupComprehensive(renameDto, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("already exists"));
	}

	@Test
	public void testRenameActiveGroupSucceedsWhenNameIsFree() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("rename-src-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		String newName = "rename-dst-" + suffix;
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).name(newName).build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(newName, updated.getName());
	}

	// ==================== T6: RESTORE AND RENAME-TO-INACTIVE ====================

	@Test
	public void testRestoreSucceedsWhenNoConflict() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("restore-ok-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(group.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto restoreDto = UpdateUserGroupDto.builder()
				.groupId(group.getUuid()).status(UserGroupStatus.ACTIVE).build();
		UserGroupData restored = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, restored.getStatus());
	}

	@Test
	public void testRenameToInactiveGroupNameBlocked() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		// Create and deactivate a group
		CreateUserGroupDto dto1 = CreateUserGroupDto.builder()
				.name("taken-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group1 = userGroupService.createUserGroup(dto1, WhoUpdated.getTestWhoUpdated());
		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(group1.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		// Create another group and try to rename to the inactive group's name
		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name("other-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group2 = userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto renameDto = UpdateUserGroupDto.builder()
				.groupId(group2.getUuid()).name("taken-" + suffix).build();
		RelizaException ex = Assertions.assertThrows(RelizaException.class, () -> {
			userGroupService.updateUserGroupComprehensive(renameDto, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("already exists"));
	}

	// ==================== SSO GROUP MAPPING (MANY-TO-MANY) ====================

	@Test
	public void testSameSsoGroupAllowedOnMultipleRearmGroups() throws Exception {
		// SSO groups are many-to-many: multiple REARM groups can share the same SSO group
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto dto1 = CreateUserGroupDto.builder()
				.name("sso-shared-1-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group1 = userGroupService.createUserGroup(dto1, WhoUpdated.getTestWhoUpdated());
		UpdateUserGroupDto ssoDto1 = UpdateUserGroupDto.builder()
				.groupId(group1.getUuid()).connectedSsoGroups(Set.of("shared-sso")).build();
		userGroupService.updateUserGroupComprehensive(ssoDto1, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name("sso-shared-2-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group2 = userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		UpdateUserGroupDto ssoDto2 = UpdateUserGroupDto.builder()
				.groupId(group2.getUuid()).connectedSsoGroups(Set.of("shared-sso")).build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(ssoDto2, WhoUpdated.getTestWhoUpdated());

		// Both groups should have the same SSO group — no conflict
		Assertions.assertTrue(updated.getConnectedSsoGroups().contains("shared-sso"));
		UserGroupData refetched1 = userGroupService.getUserGroupData(group1.getUuid()).get();
		Assertions.assertTrue(refetched1.getConnectedSsoGroups().contains("shared-sso"));
	}

	@Test
	public void testRestoreGroupWithSharedSsoGroupSucceeds() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		// Create two groups with the same SSO mapping
		CreateUserGroupDto dto1 = CreateUserGroupDto.builder()
				.name("sso-restore-ok-1-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group1 = userGroupService.createUserGroup(dto1, WhoUpdated.getTestWhoUpdated());
		UpdateUserGroupDto ssoDto1 = UpdateUserGroupDto.builder()
				.groupId(group1.getUuid()).connectedSsoGroups(Set.of("restore-sso")).build();
		userGroupService.updateUserGroupComprehensive(ssoDto1, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name("sso-restore-ok-2-" + suffix).description("").org(org.getUuid()).build();
		UserGroupData group2 = userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		UpdateUserGroupDto ssoDto2 = UpdateUserGroupDto.builder()
				.groupId(group2.getUuid()).connectedSsoGroups(Set.of("restore-sso")).build();
		userGroupService.updateUserGroupComprehensive(ssoDto2, WhoUpdated.getTestWhoUpdated());

		// Deactivate group1, then restore — should succeed despite group2 having same SSO
		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(group1.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto restoreDto = UpdateUserGroupDto.builder()
				.groupId(group1.getUuid()).status(UserGroupStatus.ACTIVE).build();
		UserGroupData restored = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, restored.getStatus());
		Assertions.assertTrue(restored.getConnectedSsoGroups().contains("restore-sso"));
	}

	// ==================== T7: FULL LIFECYCLE ====================

	@Test
	public void testFullLifecyclePreservesAllData() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto createDto = CreateUserGroupDto.builder()
				.name("lifecycle-" + suffix).description("lifecycle desc").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(createDto, WhoUpdated.getTestWhoUpdated());

		UUID testUser1 = UUID.randomUUID();
		UUID testUser2 = UUID.randomUUID();
		UpdateUserGroupDto enrichDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(testUser1, testUser2))
				.connectedSsoGroups(Set.of("lifecycle-sso"))
				.build();
		userGroupService.updateUserGroupComprehensive(enrichDto, WhoUpdated.getTestWhoUpdated());

		// Deactivate
		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		// Restore
		UpdateUserGroupDto restoreDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.ACTIVE).build();
		UserGroupData restored = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());

		Assertions.assertEquals(UserGroupStatus.ACTIVE, restored.getStatus());
		Assertions.assertEquals("lifecycle-" + suffix, restored.getName());
		Assertions.assertEquals("lifecycle desc", restored.getDescription());
		Assertions.assertEquals(2, restored.getUsers().size());
		Assertions.assertTrue(restored.getUsers().contains(testUser1));
		Assertions.assertTrue(restored.getUsers().contains(testUser2));
		Assertions.assertTrue(restored.getConnectedSsoGroups().contains("lifecycle-sso"));
	}

	// ==================== T8: CREATE-AFTER-RESTORE CYCLE ====================

	@Test
	public void testCreateAfterRestoreCycle() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String groupName = "cycle-" + UUID.randomUUID().toString().substring(0, 8);

		CreateUserGroupDto createDto = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(createDto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto restoreDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.ACTIVE).build();
		userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());

		// Deactivate again
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		// Create same name should fail
		CreateUserGroupDto createDto2 = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		Assertions.assertThrows(RelizaException.class, () -> {
			userGroupService.createUserGroup(createDto2, WhoUpdated.getTestWhoUpdated());
		});

		// Restore should succeed
		UserGroupData restoredAgain = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, restoredAgain.getStatus());
		Assertions.assertEquals(groupName, restoredAgain.getName());
	}

	// ==================== USER MANAGEMENT ====================

	@Test
	public void testGetUserGroupsByUserAndOrg() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID testUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("user-query-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).users(Set.of(testUser)).build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		List<UserGroupData> userGroups = userGroupService.getUserGroupsByUserAndOrg(testUser, org.getUuid());
		Assertions.assertFalse(userGroups.isEmpty());
		Assertions.assertTrue(userGroups.stream().anyMatch(g -> g.getUuid().equals(created.getUuid())));
	}

	@Test
	public void testGetUserGroupsByUserAndOrgEmpty() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		List<UserGroupData> userGroups = userGroupService.getUserGroupsByUserAndOrg(UUID.randomUUID(), org.getUuid());
		Assertions.assertTrue(userGroups.isEmpty());
	}

	@Test
	public void testClearUsersFromGroup() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID testUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("clear-users-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto addDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).users(Set.of(testUser)).build();
		UserGroupData withUser = userGroupService.updateUserGroupComprehensive(addDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(1, withUser.getUsers().size());

		UpdateUserGroupDto clearDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).users(Set.of()).build();
		UserGroupData cleared = userGroupService.updateUserGroupComprehensive(clearDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertTrue(cleared.getUsers().isEmpty());
	}

	// ==================== MANUAL USERS ====================

	@Test
	public void testAddManualUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("manual-users-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).manualUsers(Set.of(manualUser)).build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(1, updated.getManualUsers().size());
		Assertions.assertTrue(updated.getManualUsers().contains(manualUser));
		Assertions.assertTrue(updated.getUsers().isEmpty()); // SSO users untouched
	}

	@Test
	public void testGetAllUsersReturnsUnion() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID ssoUser = UUID.randomUUID();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("all-users-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(ssoUser))
				.manualUsers(Set.of(manualUser))
				.build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		Assertions.assertEquals(1, updated.getUsers().size());
		Assertions.assertEquals(1, updated.getManualUsers().size());
		Set<UUID> allUsers = updated.getAllUsers();
		Assertions.assertEquals(2, allUsers.size());
		Assertions.assertTrue(allUsers.contains(ssoUser));
		Assertions.assertTrue(allUsers.contains(manualUser));
	}

	@Test
	public void testHasUserChecksBothSets() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID ssoUser = UUID.randomUUID();
		UUID manualUser = UUID.randomUUID();
		UUID unknownUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("has-user-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(ssoUser))
				.manualUsers(Set.of(manualUser))
				.build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		Assertions.assertTrue(updated.hasUser(ssoUser));
		Assertions.assertTrue(updated.hasUser(manualUser));
		Assertions.assertFalse(updated.hasUser(unknownUser));
	}

	@Test
	public void testManualUsersIndependentOfSsoUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID ssoUser = UUID.randomUUID();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("independent-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		// Add both SSO and manual users
		UpdateUserGroupDto addBoth = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(ssoUser))
				.manualUsers(Set.of(manualUser))
				.build();
		userGroupService.updateUserGroupComprehensive(addBoth, WhoUpdated.getTestWhoUpdated());

		// Clear SSO users, manual users should remain
		UpdateUserGroupDto clearSso = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of())
				.build();
		UserGroupData afterClearSso = userGroupService.updateUserGroupComprehensive(clearSso, WhoUpdated.getTestWhoUpdated());
		Assertions.assertTrue(afterClearSso.getUsers().isEmpty());
		Assertions.assertEquals(1, afterClearSso.getManualUsers().size());
		Assertions.assertTrue(afterClearSso.hasUser(manualUser));
	}

	@Test
	public void testFindByUserAndOrgFindsManualUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("find-manual-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).manualUsers(Set.of(manualUser)).build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// SQL query should find the group via manualUsers
		List<UserGroupData> groups = userGroupService.getUserGroupsByUserAndOrg(manualUser, org.getUuid());
		Assertions.assertFalse(groups.isEmpty());
		Assertions.assertTrue(groups.stream().anyMatch(g -> g.getUuid().equals(created.getUuid())));
	}

	@Test
	public void testFindByUserAndOrgFindsBothSsoAndManualUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID ssoUser = UUID.randomUUID();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("find-both-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(ssoUser))
				.manualUsers(Set.of(manualUser))
				.build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// Both SSO and manual users should be found
		List<UserGroupData> ssoGroups = userGroupService.getUserGroupsByUserAndOrg(ssoUser, org.getUuid());
		Assertions.assertTrue(ssoGroups.stream().anyMatch(g -> g.getUuid().equals(created.getUuid())));

		List<UserGroupData> manualGroups = userGroupService.getUserGroupsByUserAndOrg(manualUser, org.getUuid());
		Assertions.assertTrue(manualGroups.stream().anyMatch(g -> g.getUuid().equals(created.getUuid())));
	}

	@Test
	public void testManualUsersPersistence() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID manualUser1 = UUID.randomUUID();
		UUID manualUser2 = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("persist-manual-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).manualUsers(Set.of(manualUser1, manualUser2)).build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// Re-read from DB
		UserGroupData refetched = userGroupService.getUserGroupData(created.getUuid()).get();
		Assertions.assertEquals(2, refetched.getManualUsers().size());
		Assertions.assertTrue(refetched.getManualUsers().contains(manualUser1));
		Assertions.assertTrue(refetched.getManualUsers().contains(manualUser2));
	}

	@Test
	public void testClearManualUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("clear-manual-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto addDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).manualUsers(Set.of(manualUser)).build();
		UserGroupData withManual = userGroupService.updateUserGroupComprehensive(addDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(1, withManual.getManualUsers().size());

		UpdateUserGroupDto clearDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).manualUsers(Set.of()).build();
		UserGroupData cleared = userGroupService.updateUserGroupComprehensive(clearDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertTrue(cleared.getManualUsers().isEmpty());
	}

	@Test
	public void testDeactivateRestorePreservesManualUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID manualUser = UUID.randomUUID();
		UUID ssoUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("lifecycle-manual-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto addUsers = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(ssoUser))
				.manualUsers(Set.of(manualUser))
				.build();
		userGroupService.updateUserGroupComprehensive(addUsers, WhoUpdated.getTestWhoUpdated());

		// Deactivate
		UpdateUserGroupDto deactivateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.INACTIVE).build();
		userGroupService.updateUserGroupComprehensive(deactivateDto, WhoUpdated.getTestWhoUpdated());

		// Restore
		UpdateUserGroupDto restoreDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid()).status(UserGroupStatus.ACTIVE).build();
		UserGroupData restored = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());

		Assertions.assertEquals(UserGroupStatus.ACTIVE, restored.getStatus());
		Assertions.assertEquals(1, restored.getUsers().size());
		Assertions.assertTrue(restored.getUsers().contains(ssoUser));
		Assertions.assertEquals(1, restored.getManualUsers().size());
		Assertions.assertTrue(restored.getManualUsers().contains(manualUser));
	}

	@Test
	public void testDuplicateUserInBothSetsDeduplicatedInGetAllUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID sharedUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("dedup-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		// Same user in both SSO and manual sets
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(sharedUser))
				.manualUsers(Set.of(sharedUser))
				.build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// getAllUsers should deduplicate
		Set<UUID> allUsers = updated.getAllUsers();
		Assertions.assertEquals(1, allUsers.size());
		Assertions.assertTrue(allUsers.contains(sharedUser));
	}

	@Test
	public void testWebDtoUsersContainsUnion() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID ssoUser = UUID.randomUUID();
		UUID manualUser = UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("webdto-union-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(created.getUuid())
				.users(Set.of(ssoUser))
				.manualUsers(Set.of(manualUser))
				.build();
		UserGroupData updated = userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		var webDto = UserGroupData.toWebDto(updated);
		// WebDto.users should be the union of SSO + manual
		Assertions.assertEquals(2, webDto.getUsers().size());
		Assertions.assertTrue(webDto.getUsers().contains(ssoUser));
		Assertions.assertTrue(webDto.getUsers().contains(manualUser));
		// WebDto.manualUsers should be only manual
		Assertions.assertEquals(1, webDto.getManualUsers().size());
		Assertions.assertTrue(webDto.getManualUsers().contains(manualUser));
	}

	@Test
	public void testNewGroupHasEmptyManualUsers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("empty-manual-test").description("").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertNotNull(created.getManualUsers());
		Assertions.assertTrue(created.getManualUsers().isEmpty());
	}

	// ==================== SSO SYNC INTEGRATION ====================

	/**
	 * Helper: create a real user in the given org for SSO sync tests.
	 */
	private UserData createTestUserInOrg(Organization org) throws RelizaException {
		Long ts = System.currentTimeMillis();
		User u = userService.createUser("SyncTestUser_" + ts, ts + "sync@test.io", false,
				List.of(org.getUuid()), "oauth_" + ts, OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		return UserData.dataFromRecord(u);
	}

	/**
	 * Helper: create a group and set its connectedSsoGroups (CreateUserGroupDto doesn't have that field).
	 */
	private UserGroupData createGroupWithSso(UUID orgUuid, String name, Set<String> ssoGroups) throws RelizaException {
		CreateUserGroupDto createDto = CreateUserGroupDto.builder()
				.name(name).description("").org(orgUuid).build();
		UserGroupData created = userGroupService.createUserGroup(createDto, WhoUpdated.getTestWhoUpdated());
		if (ssoGroups != null && !ssoGroups.isEmpty()) {
			UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
					.groupId(created.getUuid())
					.connectedSsoGroups(ssoGroups)
					.build();
			return userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		}
		return created;
	}

	@Test
	public void testSsoSyncPreservesManualUsersWhenSsoGroupsEmpty() throws RelizaException {
		// Setup: group with SSO mapping, user added as manualUser, user has NO SSO groups
		Organization org = testInitializer.obtainOrganization();
		UserData user = createTestUserInOrg(org);

		UserGroupData group = createGroupWithSso(org.getUuid(), "sso-sync-preserve-test", Set.of("sso-group-1"));

		// Add user as manual user
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(group.getUuid())
				.manualUsers(Set.of(user.getUuid()))
				.connectedSsoGroups(group.getConnectedSsoGroups())
				.build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// SSO sync with empty SSO groups (user has no SSO claims)
		userService.synchronizeUserWithGroupsPerOrg(user, Collections.emptySet(), org.getUuid());

		// Verify: manualUsers should be preserved
		UserGroupData afterSync = userGroupService.getUserGroupData(group.getUuid()).get();
		Assertions.assertEquals(1, afterSync.getManualUsers().size(),
				"manualUsers should be preserved after SSO sync with empty SSO groups");
		Assertions.assertTrue(afterSync.getManualUsers().contains(user.getUuid()));
	}

	@Test
	public void testSsoSyncPreservesManualUsersWhenSsoGroupsDontMatch() throws RelizaException {
		// Setup: group connected to "sso-A", user's JWT has "sso-B", user is manual member
		Organization org = testInitializer.obtainOrganization();
		UserData user = createTestUserInOrg(org);

		UserGroupData group = createGroupWithSso(org.getUuid(), "sso-sync-nomatch-test", Set.of("sso-A"));

		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(group.getUuid())
				.manualUsers(Set.of(user.getUuid()))
				.connectedSsoGroups(group.getConnectedSsoGroups())
				.build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// SSO sync with different SSO group
		userService.synchronizeUserWithGroupsPerOrg(user, Set.of("sso-B"), org.getUuid());

		// Verify: manualUsers preserved, SSO users still empty
		UserGroupData afterSync = userGroupService.getUserGroupData(group.getUuid()).get();
		Assertions.assertEquals(1, afterSync.getManualUsers().size(),
				"manualUsers should be preserved when SSO groups don't match");
		Assertions.assertTrue(afterSync.getUsers().isEmpty(),
				"SSO users should remain empty when SSO groups don't match");
	}

	@Test
	public void testSsoSyncAddsToUsersWhenSsoGroupsMatch() throws RelizaException {
		// Setup: group connected to "sso-X", user's JWT has "sso-X"
		Organization org = testInitializer.obtainOrganization();
		UserData user = createTestUserInOrg(org);

		UserGroupData group = createGroupWithSso(org.getUuid(), "sso-sync-match-test", Set.of("sso-X"));

		// SSO sync with matching SSO group
		userService.synchronizeUserWithGroupsPerOrg(user, Set.of("sso-X"), org.getUuid());

		// Verify: user added to SSO users field
		UserGroupData afterSync = userGroupService.getUserGroupData(group.getUuid()).get();
		Assertions.assertTrue(afterSync.getUsers().contains(user.getUuid()),
				"User should be added to SSO users when SSO groups match");
	}

	@Test
	public void testSsoSyncRemovesFromUsersButPreservesManualUsers() throws RelizaException {
		// Setup: user is in both users (SSO) and manualUsers, then SSO group is removed
		Organization org = testInitializer.obtainOrganization();
		UserData user = createTestUserInOrg(org);

		UserGroupData group = createGroupWithSso(org.getUuid(), "sso-sync-remove-test", Set.of("sso-Y"));

		// Add user to both SSO users and manualUsers
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(group.getUuid())
				.users(Set.of(user.getUuid()))
				.manualUsers(Set.of(user.getUuid()))
				.connectedSsoGroups(group.getConnectedSsoGroups())
				.build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// SSO sync with empty groups (SSO revoked)
		userService.synchronizeUserWithGroupsPerOrg(user, Collections.emptySet(), org.getUuid());

		// Verify: removed from SSO users, but manualUsers preserved
		UserGroupData afterSync = userGroupService.getUserGroupData(group.getUuid()).get();
		Assertions.assertFalse(afterSync.getUsers().contains(user.getUuid()),
				"User should be removed from SSO users when SSO group revoked");
		Assertions.assertTrue(afterSync.getManualUsers().contains(user.getUuid()),
				"manualUsers should be preserved even when SSO access revoked");
	}

	@Test
	public void testSsoSyncIgnoresNonSsoConnectedGroups() throws RelizaException {
		// Setup: group with NO SSO mapping, user is manual member
		Organization org = testInitializer.obtainOrganization();
		UserData user = createTestUserInOrg(org);

		CreateUserGroupDto createDto = CreateUserGroupDto.builder()
				.name("sso-sync-nosso-test").description("").org(org.getUuid()).build();
		UserGroupData group = userGroupService.createUserGroup(createDto, WhoUpdated.getTestWhoUpdated());

		// Add user as manual user to non-SSO group
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(group.getUuid())
				.manualUsers(Set.of(user.getUuid()))
				.build();
		userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());

		// SSO sync with random SSO groups
		userService.synchronizeUserWithGroupsPerOrg(user, Set.of("random-sso"), org.getUuid());

		// Verify: non-SSO group completely untouched
		UserGroupData afterSync = userGroupService.getUserGroupData(group.getUuid()).get();
		Assertions.assertEquals(1, afterSync.getManualUsers().size(),
				"Non-SSO-connected group should be completely untouched by SSO sync");
		Assertions.assertTrue(afterSync.getManualUsers().contains(user.getUuid()));
	}

	@Test
	public void testSsoSyncMultipleGroupsMixedScenario() throws RelizaException {
		// Setup: 3 groups - one SSO-matched, one SSO-unmatched, one non-SSO
		// User is manual member of all three
		Organization org = testInitializer.obtainOrganization();
		UserData user = createTestUserInOrg(org);

		UserGroupData group1 = createGroupWithSso(org.getUuid(), "sso-multi-1", Set.of("alpha"));
		UserGroupData group2 = createGroupWithSso(org.getUuid(), "sso-multi-2", Set.of("beta"));

		// Group 3: No SSO connection
		CreateUserGroupDto dto3 = CreateUserGroupDto.builder()
				.name("sso-multi-3").description("").org(org.getUuid()).build();
		UserGroupData group3 = userGroupService.createUserGroup(dto3, WhoUpdated.getTestWhoUpdated());

		// Add user as manual member to all three (preserve SSO groups on update)
		for (UserGroupData g : List.of(group1, group2, group3)) {
			UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
					.groupId(g.getUuid())
					.manualUsers(Set.of(user.getUuid()))
					.connectedSsoGroups(g.getConnectedSsoGroups())
					.build();
			userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		}

		// SSO sync: user has "alpha" SSO group
		userService.synchronizeUserWithGroupsPerOrg(user, Set.of("alpha"), org.getUuid());

		// Group 1 (SSO match): manual preserved; user already in group via manualUsers
		// so sync sees membership as satisfied and doesn't redundantly add to SSO users
		UserGroupData g1After = userGroupService.getUserGroupData(group1.getUuid()).get();
		Assertions.assertTrue(g1After.getManualUsers().contains(user.getUuid()),
				"Group1: manualUsers should be preserved");

		// Group 2 (SSO no match): SSO users empty, manual preserved
		UserGroupData g2After = userGroupService.getUserGroupData(group2.getUuid()).get();
		Assertions.assertFalse(g2After.getUsers().contains(user.getUuid()),
				"Group2: user should NOT be in SSO users (SSO didn't match)");
		Assertions.assertTrue(g2After.getManualUsers().contains(user.getUuid()),
				"Group2: manualUsers should be preserved");

		// Group 3 (no SSO): completely untouched
		UserGroupData g3After = userGroupService.getUserGroupData(group3.getUuid()).get();
		Assertions.assertTrue(g3After.getManualUsers().contains(user.getUuid()),
				"Group3: non-SSO group should be completely untouched");
	}
}

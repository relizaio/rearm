/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

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

import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.model.Organization;
import io.reliza.model.UserGroupData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.service.UserGroupService;
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
	private TestInitializer testInitializer;

	// ==================== CREATE ====================

	@Test
	public void testCreateUserGroup() {
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
	public void testCreateUserGroupBlockedByInactiveSameName() {
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
		IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> {
			userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("inactive group"));
	}

	@Test
	public void testCreateUserGroupBlockedByActiveSameName() {
		Organization org = testInitializer.obtainOrganization();
		String groupName = "active-dup-" + UUID.randomUUID();

		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		CreateUserGroupDto dto2 = CreateUserGroupDto.builder()
				.name(groupName).description("").org(org.getUuid()).build();
		IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> {
			userGroupService.createUserGroup(dto2, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("already exists"));
	}

	@Test
	public void testCreateUserGroupNameIsolatedByOrg() {
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
	public void testGetUserGroupData() {
		Organization org = testInitializer.obtainOrganization();
		CreateUserGroupDto dto = CreateUserGroupDto.builder()
				.name("read-test-group").description("desc").org(org.getUuid()).build();
		UserGroupData created = userGroupService.createUserGroup(dto, WhoUpdated.getTestWhoUpdated());

		Optional<UserGroupData> fetched = userGroupService.getUserGroupData(created.getUuid());
		Assertions.assertTrue(fetched.isPresent());
		Assertions.assertEquals("read-test-group", fetched.get().getName());
	}

	@Test
	public void testGetUserGroupDataNotFound() {
		Optional<UserGroupData> fetched = userGroupService.getUserGroupData(UUID.randomUUID());
		Assertions.assertFalse(fetched.isPresent());
	}

	@Test
	public void testGetUserGroupsByOrganizationReturnsActiveOnly() {
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
	public void testGetAllUserGroupsByOrganizationIncludesInactive() {
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
	public void testUpdateUserGroupName() {
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
	public void testUpdateUserGroupDescription() {
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
	public void testUpdateUserGroupUsers() {
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
	public void testUpdateUserGroupConnectedSsoGroups() {
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
	public void testUpdateNonExistentGroupThrows() {
		UpdateUserGroupDto updateDto = UpdateUserGroupDto.builder()
				.groupId(UUID.randomUUID()).name("no-such-group").build();
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			userGroupService.updateUserGroupComprehensive(updateDto, WhoUpdated.getTestWhoUpdated());
		});
	}

	@Test
	public void testUpdatePreservesUnchangedFields() {
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
	public void testUpdateMultipleFieldsAtOnce() {
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
	public void testDeactivateUserGroup() {
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
	public void testRestoreUserGroup() {
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
	public void testRenameActiveGroupNameConflictThrows() {
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
		IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> {
			userGroupService.updateUserGroupComprehensive(renameDto, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("already exists"));
	}

	@Test
	public void testRenameActiveGroupSucceedsWhenNameIsFree() {
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
	public void testRestoreSucceedsWhenNoConflict() {
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
	public void testRenameToInactiveGroupNameBlocked() {
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
		IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> {
			userGroupService.updateUserGroupComprehensive(renameDto, WhoUpdated.getTestWhoUpdated());
		});
		Assertions.assertTrue(ex.getMessage().contains("already exists"));
	}

	// ==================== SSO GROUP MAPPING (MANY-TO-MANY) ====================

	@Test
	public void testSameSsoGroupAllowedOnMultipleRearmGroups() {
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
	public void testRestoreGroupWithSharedSsoGroupSucceeds() {
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
	public void testFullLifecyclePreservesAllData() {
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
	public void testCreateAfterRestoreCycle() {
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
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			userGroupService.createUserGroup(createDto2, WhoUpdated.getTestWhoUpdated());
		});

		// Restore should succeed
		UserGroupData restoredAgain = userGroupService.updateUserGroupComprehensive(restoreDto, WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals(UserGroupStatus.ACTIVE, restoredAgain.getStatus());
		Assertions.assertEquals(groupName, restoredAgain.getName());
	}

	// ==================== USER MANAGEMENT ====================

	@Test
	public void testGetUserGroupsByUserAndOrg() {
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
	public void testGetUserGroupsByUserAndOrgEmpty() {
		Organization org = testInitializer.obtainOrganization();
		List<UserGroupData> userGroups = userGroupService.getUserGroupsByUserAndOrg(UUID.randomUUID(), org.getUuid());
		Assertions.assertTrue(userGroups.isEmpty());
	}

	@Test
	public void testClearUsersFromGroup() {
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
}

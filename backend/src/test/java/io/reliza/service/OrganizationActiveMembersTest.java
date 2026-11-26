/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.UserStatus;
import io.reliza.common.Utils;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.service.OrganizationService.ActiveMember;
import io.reliza.service.UserRoleRules.RoleLevel;

/**
 * The walk of an organization's members that the per-person role counts share
 * ({@link OrganizationService#activeMembers}) and the Statistics user figures built on it
 * ({@link OrganizationService#orgUserLevels}): only ACTIVE members, a missing status counted as active,
 * group permissions merged with a member's own, one level per person.
 */
class OrganizationActiveMembersTest {

	private final UUID org = UUID.randomUUID();

	@Test
	void anInactiveMemberIsLeftOutAndTheOthersKeepTheirOrder() {
		UserData first = member("first@walk.io", PermissionType.READ_ONLY, UserStatus.ACTIVE);
		UserData inactive = member("inactive@walk.io", PermissionType.ADMIN, UserStatus.INACTIVE);
		UserData last = member("last@walk.io", PermissionType.NONE, UserStatus.ACTIVE);

		List<ActiveMember<UserData>> members = OrganizationService.activeMembers(org, List.of(first, inactive, last),
				Function.identity(), List.of());

		assertEquals(2, members.size());
		assertSame(first, members.get(0).source());
		assertSame(last, members.get(1).source());
	}

	@Test
	void aMemberWithNoStatusCountsAsActive() {
		UserData noStatus = member("no-status@walk.io", PermissionType.READ_ONLY, null);

		List<ActiveMember<UserData>> members = OrganizationService.activeMembers(org, List.of(noStatus),
				Function.identity(), List.of());

		assertEquals(1, members.size());
		assertEquals(PermissionType.READ_ONLY, orgType(members.get(0)));
	}

	@Test
	void groupPermissionsMergeWithAMembersOwn() {
		UserData raised = member("raised@walk.io", PermissionType.READ_ONLY, UserStatus.ACTIVE);
		UserData groupOnly = member("group-only@walk.io", null, UserStatus.ACTIVE);
		UserData outside = member("outside@walk.io", PermissionType.READ_ONLY, UserStatus.ACTIVE);
		UserData higherOwn = member("higher-own@walk.io", PermissionType.ADMIN, UserStatus.ACTIVE);
		UserGroupData writers = group(PermissionType.READ_WRITE, Set.of(raised.getUuid(), groupOnly.getUuid(),
				higherOwn.getUuid()));

		List<ActiveMember<UserData>> members = OrganizationService.activeMembers(org,
				List.of(raised, groupOnly, outside, higherOwn), Function.identity(), List.of(writers));

		assertEquals(PermissionType.READ_WRITE, orgType(members.get(0)), "the group's higher type wins");
		assertEquals(PermissionType.READ_WRITE, orgType(members.get(1)), "a member with no own role takes the group's");
		assertEquals(PermissionType.READ_ONLY, orgType(members.get(2)), "a non-member keeps their own");
		assertEquals(PermissionType.ADMIN, orgType(members.get(3)), "a higher own type is kept");
	}

	@Test
	void theUserFiguresKeyEachPersonOnceAtTheirHighestLevel() {
		User viaGroup = userRecord("via-group@walk.io", PermissionType.NONE, UserStatus.ACTIVE);
		User blank = userRecord("", PermissionType.READ_ONLY, UserStatus.ACTIVE);
		List<User> users = List.of(
				userRecord("Twin@Walk.io", PermissionType.READ_ONLY, UserStatus.ACTIVE),
				userRecord("twin@walk.io", PermissionType.READ_WRITE, UserStatus.ACTIVE),
				userRecord("gone@walk.io", PermissionType.ADMIN, UserStatus.INACTIVE),
				viaGroup, blank);
		UserGroupData writers = group(PermissionType.READ_WRITE, Set.of(viaGroup.getUuid()));

		Map<String, RoleLevel> levels = OrganizationService.orgUserLevels(org, users, List.of(writers));

		assertEquals(Map.of(
				"twin@walk.io", RoleLevel.WRITE,
				"via-group@walk.io", RoleLevel.WRITE,
				"user:" + blank.getUuid(), RoleLevel.READ), levels);
	}

	private PermissionType orgType(ActiveMember<?> m) {
		return m.effective().stream()
				.filter(p -> p.getScope() == PermissionScope.ORGANIZATION && org.equals(p.getObject()))
				.map(UserPermission::getType).findFirst().orElse(PermissionType.NONE);
	}

	private UserData member(String email, PermissionType type, UserStatus status) {
		UserData ud = UserData.userDataFactory("walk", email, List.of(org), "sub", OauthType.RELIZA_KEYCLOAK_OWN, true);
		ReflectionTestUtils.setField(ud, "uuid", UUID.randomUUID());
		ud.setStatus(status);
		if (null != type) ud.setPermission(org, PermissionScope.ORGANIZATION, org, type, List.of());
		return ud;
	}

	private User userRecord(String email, PermissionType type, UserStatus status) {
		User u = new User();
		u.setUuid(UUID.randomUUID());
		u.setRecordData(Utils.dataToRecord(member(email, type, status)));
		return u;
	}

	private UserGroupData group(PermissionType type, Set<UUID> members) {
		UserGroupData g = UserGroupData.userGroupDataFactory(CreateUserGroupDto.builder().name("writers").org(org).build());
		g.setPermission(PermissionScope.ORGANIZATION, org, type, List.of());
		ReflectionTestUtils.setField(g, "users", new LinkedHashSet<>(members));
		return g;
	}
}

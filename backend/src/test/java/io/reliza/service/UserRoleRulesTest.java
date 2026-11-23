/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.UserRoleRules.RoleLevel;

/** The role levels and the person key the Statistics user figures count by. */
class UserRoleRulesTest {

	private final UUID org = UUID.randomUUID();

	@Test
	void theLevelOfAnEffectivePermissionSet() {
		assertEquals(RoleLevel.NONE, UserRoleRules.levelOf(Set.of(perm(PermissionType.NONE,
				Set.of(PermissionFunction.FINDING_ANALYSIS_READ), null))), "a function-only grant with type NONE is no role");
		assertEquals(RoleLevel.READ, UserRoleRules.levelOf(Set.of(perm(PermissionType.ESSENTIAL_READ, Set.of(), null))));
		assertEquals(RoleLevel.WRITE, UserRoleRules.levelOf(Set.of(perm(PermissionType.NONE,
				Set.of(PermissionFunction.FINDING_ANALYSIS_WRITE), null))));
		assertEquals(RoleLevel.WRITE, UserRoleRules.levelOf(Set.of(perm(PermissionType.READ_ONLY, Set.of(), Set.of("qa")))),
				"any approval is a write user");
		assertEquals(RoleLevel.WRITE, UserRoleRules.levelOf(Set.of(perm(PermissionType.READ_WRITE, Set.of(), null))));
		assertEquals(RoleLevel.NONE, UserRoleRules.levelOf(Set.of()));
		assertEquals(RoleLevel.NONE, UserRoleRules.levelOf((Set<UserPermission>) null));
	}

	@Test
	void theLevelOfAPermissionType() {
		assertEquals(RoleLevel.WRITE, UserRoleRules.levelOf(PermissionType.ADMIN));
		assertEquals(RoleLevel.WRITE, UserRoleRules.levelOf(PermissionType.READ_WRITE));
		assertEquals(RoleLevel.READ, UserRoleRules.levelOf(PermissionType.READ_ONLY));
		assertEquals(RoleLevel.READ, UserRoleRules.levelOf(PermissionType.ESSENTIAL_READ));
		assertEquals(RoleLevel.NONE, UserRoleRules.levelOf(PermissionType.NONE));
		assertEquals(RoleLevel.NONE, UserRoleRules.levelOf((PermissionType) null));
	}

	@Test
	void maxIsTheHigherLevel() {
		assertEquals(RoleLevel.WRITE, RoleLevel.max(RoleLevel.READ, RoleLevel.WRITE));
		assertEquals(RoleLevel.READ, RoleLevel.max(RoleLevel.READ, RoleLevel.NONE));
		assertEquals(RoleLevel.NONE, RoleLevel.max(null, RoleLevel.NONE));
	}

	@Test
	void anAccountWithABlankEmailIsKeyedByItsUuidAndAnEmailByItsLowerCase() {
		UserData ud = UserData.userDataFactory("m", "Primary@Case.IO", List.of(org), null, OauthType.RELIZA_KEYCLOAK_OWN, true);
		ReflectionTestUtils.setField(ud, "uuid", UUID.randomUUID());
		assertEquals("user:" + ud.getUuid(), UserRoleRules.personKey(ud, ""));
		assertEquals("user:" + ud.getUuid(), UserRoleRules.personKey(ud, "   "));
		assertEquals("user:" + ud.getUuid(), UserRoleRules.personKey(ud, null));
		assertEquals("mixed@case.io", UserRoleRules.personKey(ud, "Mixed@Case.IO"));
		assertEquals("primary@case.io", UserRoleRules.personKey(ud));
	}

	private UserPermission perm(PermissionType t, Set<PermissionFunction> f, Set<String> approvals) {
		UserPermission.Permissions ps = new UserPermission.Permissions();
		ps.setPermission(org, PermissionScope.ORGANIZATION, org, t, f, approvals);
		return ps.getOrgPermissionsAsSet(org).iterator().next();
	}
}

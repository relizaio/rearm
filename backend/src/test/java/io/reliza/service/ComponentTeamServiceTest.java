/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ComponentData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * Mockito unit tests for {@link ComponentTeamService} — the live derivation of
 * a component's team / approvers from the permission model, plus the
 * parent-walk override semantics and the cycle guard. The two collaborators
 * ({@link UserService}, {@link GetComponentService}) are mocked, so no Spring
 * context / DB is needed.
 */
@ExtendWith(MockitoExtension.class)
class ComponentTeamServiceTest {

	@Mock
	private UserService userService;

	@Mock
	private GetComponentService getComponentService;

	@InjectMocks
	private ComponentTeamService componentTeamService;

	private final UUID org = UUID.randomUUID();

	private ComponentData component (UUID uuid, UUID parent) {
		ComponentData cd = new ComponentData();
		cd.setUuid(uuid);
		cd.setOrg(org);
		cd.setParent(parent);
		return cd;
	}

	private UserData userWith (String name, UUID componentUuid, PermissionType type, Set<String> approvals) {
		UserData ud = UserData.userDataFactory(name, name + "@example.com", List.of(org),
				null, OauthType.RELIZA_KEYCLOAK_OWN, true);
		ud.setPermission(org, PermissionScope.COMPONENT, componentUuid, type, approvals);
		return ud;
	}

	@Test
	void deriveTeamKeepsWriteOrStrongerDropsReadOnly () {
		UUID c = UUID.randomUUID();
		UserData writer = userWith("writer", c, PermissionType.READ_WRITE, null);
		UserData admin = userWith("admin", c, PermissionType.ADMIN, null);
		UserData reader = userWith("reader", c, PermissionType.READ_ONLY, null);
		when(userService.getUsersByPermissionObject(c)).thenReturn(List.of(writer, admin, reader));

		List<UserData> team = componentTeamService.deriveTeam(component(c, null));

		assertEquals(2, team.size());
		assertTrue(team.contains(writer));
		assertTrue(team.contains(admin));
	}

	@Test
	void deriveApproversKeepsOnlyNonEmptyApprovalGrants () {
		UUID c = UUID.randomUUID();
		UserData approver = userWith("approver", c, PermissionType.READ_WRITE, Set.of("role-qa"));
		UserData plainWriter = userWith("plain", c, PermissionType.READ_WRITE, null);
		when(userService.getUsersByPermissionObject(c)).thenReturn(List.of(approver, plainWriter));

		List<UserData> approvers = componentTeamService.deriveApprovers(component(c, null));

		assertEquals(List.of(approver), approvers);
	}

	@Test
	void emptyComponentFallsBackToParentTeam () {
		UUID child = UUID.randomUUID();
		UUID parent = UUID.randomUUID();
		UserData productOwner = userWith("owner", parent, PermissionType.READ_WRITE, null);
		when(userService.getUsersByPermissionObject(child)).thenReturn(List.of());
		when(userService.getUsersByPermissionObject(parent)).thenReturn(List.of(productOwner));
		when(getComponentService.getComponentData(parent)).thenReturn(Optional.of(component(parent, null)));

		List<UserData> team = componentTeamService.deriveTeam(component(child, parent));

		assertEquals(List.of(productOwner), team);
	}

	@Test
	void directMembersWinOverParentNoFallback () {
		UUID child = UUID.randomUUID();
		UUID parent = UUID.randomUUID();
		UserData direct = userWith("direct", child, PermissionType.READ_WRITE, null);
		when(userService.getUsersByPermissionObject(child)).thenReturn(List.of(direct));

		List<UserData> team = componentTeamService.deriveTeam(component(child, parent));

		assertEquals(List.of(direct), team);
	}

	@Test
	void deriveTeamDropsEveryTierBelowReadWrite () {
		UUID c = UUID.randomUUID();
		UserData essential = userWith("essential", c, PermissionType.ESSENTIAL_READ, null);
		UserData none = userWith("none", c, PermissionType.NONE, null);
		UserData writer = userWith("writer", c, PermissionType.READ_WRITE, null);
		when(userService.getUsersByPermissionObject(c)).thenReturn(List.of(essential, none, writer));

		List<UserData> team = componentTeamService.deriveTeam(component(c, null));

		assertEquals(List.of(writer), team);
	}

	@Test
	void parentInDifferentOrgIsNotWalked () {
		UUID child = UUID.randomUUID();
		UUID parent = UUID.randomUUID();
		UUID otherOrg = UUID.randomUUID();
		// Child (this.org) has no direct members; the parent lives in a *different*
		// org and has a member there. The walk must stop at the org boundary rather
		// than surface the foreign org's user as this component's team.
		UserData foreignOwner = UserData.userDataFactory("foreign", "foreign@example.com", List.of(otherOrg),
				null, OauthType.RELIZA_KEYCLOAK_OWN, true);
		foreignOwner.setPermission(otherOrg, PermissionScope.COMPONENT, parent, PermissionType.READ_WRITE, null);
		when(userService.getUsersByPermissionObject(child)).thenReturn(List.of());
		ComponentData parentCd = new ComponentData();
		parentCd.setUuid(parent);
		parentCd.setOrg(otherOrg);
		when(getComponentService.getComponentData(parent)).thenReturn(Optional.of(parentCd));

		List<UserData> team = componentTeamService.deriveTeam(component(child, parent));

		assertTrue(team.isEmpty());
	}

	@Test
	void parentCycleIsGuardedAndReturnsEmpty () {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		// a -> b -> a, neither has any members
		lenient().when(userService.getUsersByPermissionObject(a)).thenReturn(List.of());
		lenient().when(userService.getUsersByPermissionObject(b)).thenReturn(List.of());
		lenient().when(getComponentService.getComponentData(a)).thenReturn(Optional.of(component(a, b)));
		lenient().when(getComponentService.getComponentData(b)).thenReturn(Optional.of(component(b, a)));

		List<UserData> team = componentTeamService.deriveTeam(component(a, b));

		assertTrue(team.isEmpty());
	}
}

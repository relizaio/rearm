/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.ComponentData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AuthorizationService.ClampedPermissions;

/** The clamp over an explicit owner set. The component service knows no components, so no cascade: only exact matches count. */
public class AuthorizationServiceClampTest {

	private static AuthorizationService service() {
		AuthorizationService svc = new AuthorizationService();
		ReflectionTestUtils.setField(svc, "getComponentService", new GetComponentService(null) {
			@Override public Optional<ComponentData> getComponentData(UUID uuid) { return Optional.empty(); }
			@Override public List<ComponentData> listComponentsByPerspective(UUID perspectiveUuid) { return List.of(); }
			@Override public List<ComponentData> listComponentsByProduct(UUID productUuid) { return List.of(); }
		});
		return svc;
	}

	private final UUID org = UUID.randomUUID();
	private final UUID owner = UUID.randomUUID();
	private final UUID comp = UUID.randomUUID();
	private final UUID other = UUID.randomUUID();

	private static UserPermission perm(UUID org, PermissionScope scope, UUID object, PermissionType type, PermissionFunction... fns) {
		return UserPermission.permissionFactory(org, scope, object, type, List.of(fns), null);
	}

	@Test
	void anAdministratorOfOneComponentClearsAdminCallsOnThatComponentOnly() {
		AuthorizationService svc = service();
		// The tier a scoped grant clears is its own type, at any scope -- which is what lets a
		// marketing release be moved backwards by an administrator of its component rather than
		// only by one of the whole organization.
		UserPermission componentAdmin = perm(org, PermissionScope.COMPONENT, comp, PermissionType.ADMIN,
				PermissionFunction.LIFECYCLE_UPDATE);
		assertTrue(svc.permissionClearsCall(componentAdmin, org, PermissionFunction.LIFECYCLE_UPDATE, CallType.ADMIN));
		assertTrue(svc.permissionClearsCall(componentAdmin, org, PermissionFunction.LIFECYCLE_UPDATE, CallType.WRITE),
				"administrator clears the lower tiers too");

		// ...and not beyond its function list: only an ORGANIZATION-scoped administrator skips
		// the function check, so a component administrator cannot reach a function nobody granted.
		assertFalse(svc.permissionClearsCall(componentAdmin, org, PermissionFunction.ARTIFACT_DOWNLOAD, CallType.READ),
				"a scoped administrator is still held to the functions it was given");

		UserPermission componentWriter = perm(org, PermissionScope.COMPONENT, comp, PermissionType.READ_WRITE,
				PermissionFunction.LIFECYCLE_UPDATE);
		assertTrue(svc.permissionClearsCall(componentWriter, org, PermissionFunction.LIFECYCLE_UPDATE, CallType.WRITE),
				"a writer moves a lifecycle forward");
		assertFalse(svc.permissionClearsCall(componentWriter, org, PermissionFunction.LIFECYCLE_UPDATE, CallType.ADMIN),
				"but not backwards -- that is the administrator tier");

		UserPermission orgAdmin = perm(org, PermissionScope.ORGANIZATION, org, PermissionType.ADMIN);
		assertTrue(svc.permissionClearsCall(orgAdmin, org, PermissionFunction.LIFECYCLE_UPDATE, CallType.ADMIN),
				"an organization administrator still clears it, with no function list at all");
	}

	@Test
	void instanceListingNeedsTheSameFunctionThePerInstanceCheckNeeds() {
		AuthorizationService svc = service();
		UUID inst = UUID.randomUUID();
		// an instance grant without DEVOPS_READ authorizes no instance read, so it must list none either
		Set<UserPermission> bare = Set.of(perm(org, PermissionScope.INSTANCE, inst, PermissionType.READ_ONLY));
		assertEquals(Set.of(), svc.readableInstanceUuids(bare, org));
		assertFalse(svc.permissionClearsCall(bare.iterator().next(), org, PermissionFunction.DEVOPS_READ, CallType.READ),
				"the filter and the check agree: no DEVOPS_READ, no read");

		Set<UserPermission> granted = Set.of(perm(org, PermissionScope.INSTANCE, inst, PermissionType.READ_ONLY, PermissionFunction.DEVOPS_READ));
		assertEquals(Set.of(inst), svc.readableInstanceUuids(granted, org));

		// organization-wide DEVOPS_READ, or admin, sees everything
		assertNull(svc.readableInstanceUuids(Set.of(perm(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY, PermissionFunction.DEVOPS_READ)), org));
		assertNull(svc.readableInstanceUuids(Set.of(perm(org, PermissionScope.ORGANIZATION, org, PermissionType.ADMIN)), org));
		// organization-wide read without the function is not a pass for instances
		assertEquals(Set.of(), svc.readableInstanceUuids(Set.of(perm(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY)), org));
		// another org's grant never counts
		assertEquals(Set.of(), svc.readableInstanceUuids(Set.of(perm(UUID.randomUUID(), PermissionScope.INSTANCE, inst, PermissionType.READ_ONLY, PermissionFunction.DEVOPS_READ)), org));
	}

	@Test
	void componentListingFollowsTheGrantsTheCheckWouldHonour() {
		AuthorizationService svc = service();
		// the stub component service knows no components, so only what a grant names directly survives
		assertEquals(Set.of(), svc.readableComponentUuids(Set.of(perm(org, PermissionScope.COMPONENT, comp, PermissionType.ESSENTIAL_READ)), org),
				"below read level, the same as the check would decide");
		assertNull(svc.readableComponentUuids(Set.of(perm(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY)), org),
				"organization-wide read sees every component");
		assertEquals(Set.of(), svc.readableComponentUuids(Set.of(perm(UUID.randomUUID(), PermissionScope.COMPONENT, comp, PermissionType.READ_WRITE)), org),
				"another org's grant never counts");
	}

	@Test
	void organizationLevelAndFunctionsFallToTheOwners() {
		AuthorizationService svc = service();
		Set<UserPermission> ownerSet = Set.of(perm(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY, PermissionFunction.AGENT));
		ClampedPermissions c = svc.clamp(ownerSet, false, owner, org, PermissionType.READ_WRITE, List.of(
				new PermissionDto(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT, PermissionFunction.DEVOPS_WRITE), null)));
		assertEquals(PermissionType.READ_ONLY, c.orgType(), "READ_WRITE asked, owner holds READ_ONLY");
		assertEquals(1, c.permissions().size());
		assertEquals(PermissionType.READ_ONLY, c.permissions().get(0).type());
		assertEquals(Set.of(PermissionFunction.AGENT), c.permissions().get(0).functions(), "DEVOPS_WRITE is not the owner's to give");
		assertEquals(2, c.reductions().size());
	}

	@Test
	void objectPermissionsFallToTheOwnersLevelOrAreDropped() {
		AuthorizationService svc = service();
		Set<UserPermission> ownerSet = Set.of(perm(org, PermissionScope.COMPONENT, comp, PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		ClampedPermissions c = svc.clamp(ownerSet, false, owner, org, PermissionType.NONE, List.of(
				new PermissionDto(org, PermissionScope.COMPONENT, comp, PermissionType.READ_WRITE, Set.of(PermissionFunction.ARTIFACT_DOWNLOAD, PermissionFunction.LIFECYCLE_UPDATE), null),
				new PermissionDto(org, PermissionScope.COMPONENT, other, PermissionType.READ_ONLY, Set.of(), null)));
		assertEquals(PermissionType.NONE, c.orgType());
		assertEquals(1, c.permissions().size(), "the component the owner cannot read is dropped");
		PermissionDto kept = c.permissions().get(0);
		assertEquals(comp, kept.object());
		assertEquals(PermissionType.READ_ONLY, kept.type());
		assertEquals(Set.of(PermissionFunction.ARTIFACT_DOWNLOAD), kept.functions());
		assertTrue(c.reductions().stream().anyMatch(r -> r.contains("dropped")));
	}

	@Test
	void anAdminOwnerIsNotReduced() {
		AuthorizationService svc = service();
		Set<UserPermission> ownerSet = Set.of(perm(org, PermissionScope.ORGANIZATION, org, PermissionType.ADMIN));
		ClampedPermissions c = svc.clamp(ownerSet, false, owner, org, PermissionType.READ_WRITE, List.of(
				new PermissionDto(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT, PermissionFunction.DEVOPS_WRITE), null),
				new PermissionDto(org, PermissionScope.COMPONENT, other, PermissionType.READ_WRITE, Set.of(PermissionFunction.LIFECYCLE_UPDATE), null)));
		assertEquals(PermissionType.READ_WRITE, c.orgType());
		assertEquals(2, c.permissions().size());
		assertTrue(c.reductions().isEmpty());
	}

	@Test
	void nothingAboveAnOwnerWithoutOrganizationRights() {
		AuthorizationService svc = service();
		ClampedPermissions c = svc.clamp(Set.of(), false, owner, org, PermissionType.ESSENTIAL_READ, List.of(
				new PermissionDto(org, PermissionScope.ORGANIZATION, org, PermissionType.ESSENTIAL_READ, Set.of(PermissionFunction.AGENT), null)));
		assertEquals(PermissionType.NONE, c.orgType());
		assertTrue(c.permissions().isEmpty());
	}
}

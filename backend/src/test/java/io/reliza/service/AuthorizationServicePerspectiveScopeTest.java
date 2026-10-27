/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.saas.PerspectiveData;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * Task TEA-8, design 3.4 item 3: a PERSPECTIVE-scope grant expands to the perspective's members only
 * while the perspective is ACTIVE and of the grant's organization. A grant left behind by
 * deletePerspective (the perspective is ARCHIVED) reaches no component, not even one that still holds
 * the stale membership. Round 2 (design 3.6): such a grant clears no call at all, the perspective
 * itself included, so the by-perspective readers refuse its holder too.
 */
public class AuthorizationServicePerspectiveScopeTest {

	private final UUID org = UUID.randomUUID();
	private final UUID otherOrg = UUID.randomUUID();
	private final UUID perspective = UUID.randomUUID();
	private final UUID component = UUID.randomUUID();

	private AuthorizationService service(UUID perspectiveOrg, StatusEnum status) {
		ComponentData cd = new ComponentData();
		cd.setUuid(component);
		cd.setOrg(org);
		cd.setName("tea8-member");

		PerspectiveData pd = new PerspectiveData();
		pd.setUuid(perspective);
		pd.setOrg(perspectiveOrg);
		pd.setName("tea8-perspective");
		pd.setType(PerspectiveType.PERSPECTIVE);
		pd.setStatus(status);

		AuthorizationService svc = new AuthorizationService();
		OssPerspectiveService perspectives = mock(OssPerspectiveService.class);
		when(perspectives.getPerspectiveData(perspective)).thenReturn(Optional.of(pd));
		ReflectionTestUtils.setField(svc, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(svc, "getComponentService", new GetComponentService(null) {
			@Override public Optional<ComponentData> getComponentData(UUID uuid) {
				return component.equals(uuid) ? Optional.of(cd) : Optional.empty();
			}
			@Override public List<ComponentData> listComponentsByPerspective(UUID perspectiveUuid) {
				// The stored membership, as listComponentsByPerspective reads it: stale or not.
				return perspective.equals(perspectiveUuid) ? List.of(cd) : List.of();
			}
		});
		return svc;
	}

	private UserPermission grant() {
		return UserPermission.permissionFactory(org, PermissionScope.PERSPECTIVE, perspective,
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null);
	}

	private boolean reaches(AuthorizationService svc) {
		return svc.doesPermissionAuthorize(grant(), org, PermissionFunction.RESOURCE, PermissionScope.COMPONENT,
				component, CallType.READ);
	}

	/** The check the by-perspective fetchers make: the grant against the perspective's own uuid. */
	private boolean clearsThePerspectiveItself(AuthorizationService svc) {
		return svc.doesPermissionAuthorize(grant(), org, PermissionFunction.RESOURCE, PermissionScope.PERSPECTIVE,
				perspective, CallType.READ);
	}

	@Test
	void aGrantOnAnActivePerspectiveReachesItsComponents() {
		assertTrue(reaches(service(org, StatusEnum.ACTIVE)));
	}

	@Test
	void aGrantOnADeletedPerspectiveReachesNoComponent() {
		assertFalse(reaches(service(org, StatusEnum.ARCHIVED)));
	}

	@Test
	void aGrantOnAForeignPerspectiveReachesNoComponent() {
		assertFalse(reaches(service(otherOrg, StatusEnum.ACTIVE)));
	}

	// ---- Round 2 (T-1): the live-grant rule in permissionClearsCall, design 3.6 ----

	@Test
	void aGrantOnAnActivePerspectiveClearsThePerspectiveItself() {
		AuthorizationService svc = service(org, StatusEnum.ACTIVE);
		assertTrue(clearsThePerspectiveItself(svc));
		assertEquals(Set.of(component), svc.readableComponentUuids(Set.of(grant()), org));
	}

	@Test
	void aGrantOnADeletedPerspectiveClearsNothing() {
		AuthorizationService svc = service(org, StatusEnum.ARCHIVED);
		assertFalse(clearsThePerspectiveItself(svc));
		assertTrue(svc.readableComponentUuids(Set.of(grant()), org).isEmpty());
	}

	@Test
	void aGrantOnAForeignPerspectiveClearsNothing() {
		assertFalse(clearsThePerspectiveItself(service(otherOrg, StatusEnum.ACTIVE)));
	}

	// ---- Round 3 (T-4): the inbox's perspective arm, design 4.1 ----

	@Test
	void livePerspectivesOfGrantsKeepsTheLiveGrantsOfTheOrg() {
		AuthorizationService svc = service(org, StatusEnum.ACTIVE);
		Set<UserPermission> perms = new LinkedHashSet<>();
		perms.add(grant());
		// a PERSPECTIVE grant of the org on a uuid without a row (the mock answers empty)
		perms.add(UserPermission.permissionFactory(org, PermissionScope.PERSPECTIVE, UUID.randomUUID(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null));
		// a PERSPECTIVE grant of another organization on the same perspective
		perms.add(UserPermission.permissionFactory(otherOrg, PermissionScope.PERSPECTIVE, perspective,
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null));
		perms.add(UserPermission.permissionFactory(org, PermissionScope.COMPONENT, component,
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null));
		perms.add(UserPermission.permissionFactory(org, PermissionScope.ORGANIZATION, org,
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null));
		assertEquals(List.of(perspective), svc.livePerspectivesOfGrants(perms, org));
		assertEquals(List.of(), svc.livePerspectivesOfGrants(null, org));
		assertEquals(List.of(), svc.livePerspectivesOfGrants(Set.of(), org));
		// a grant of another scope answers nothing, even when its object is a live perspective uuid
		assertEquals(List.of(), svc.livePerspectivesOfGrants(Set.of(UserPermission.permissionFactory(org,
				PermissionScope.COMPONENT, perspective, PermissionType.READ_ONLY,
				List.of(PermissionFunction.RESOURCE), null)), org));
		// a grant of another organization alone answers nothing, even on a perspective live in org
		assertEquals(List.of(), svc.livePerspectivesOfGrants(Set.of(UserPermission.permissionFactory(otherOrg,
				PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY,
				List.of(PermissionFunction.RESOURCE), null)), org));
	}

	@Test
	void livePerspectivesOfGrantsDropsADeletedPerspective() {
		assertEquals(List.of(), service(org, StatusEnum.ARCHIVED).livePerspectivesOfGrants(Set.of(grant()), org));
	}
}

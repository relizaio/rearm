/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentOwner;
import io.reliza.model.ComponentOwnerType;
import io.reliza.model.ComponentOwnershipStatus;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ComponentOwnership;
import io.reliza.model.dto.ComponentOwnershipReportRow;

/**
 * Unit coverage for {@link ComponentOwnershipService#resolveOwnership} across
 * every {@link ComponentOwnershipStatus}: stored TEAM/USER validation +
 * durability, and the no-owner candidate-team suggestion / orphan fallbacks.
 */
class ComponentOwnershipServiceTest {

	private UserGroupService userGroupService;
	private UserService userService;
	private ComponentTeamService componentTeamService;
	private ComponentOwnershipService service;

	private UUID org;
	private UUID comp;

	@BeforeEach
	void setUp() {
		userGroupService = mock(UserGroupService.class);
		userService = mock(UserService.class);
		componentTeamService = mock(ComponentTeamService.class);
		service = new ComponentOwnershipService();
		ReflectionTestUtils.setField(service, "userGroupService", userGroupService);
		ReflectionTestUtils.setField(service, "userService", userService);
		ReflectionTestUtils.setField(service, "componentTeamService", componentTeamService);
		org = UUID.randomUUID();
		comp = UUID.randomUUID();
	}

	private ComponentData component(ComponentOwner owner) {
		ComponentData cd = new ComponentData();
		cd.setUuid(comp);
		cd.setOrg(org);
		cd.setOwner(owner);
		return cd;
	}

	/** A mock team; pass {@code compPerm=null} for "no COMPONENT grant on this component". */
	private UserGroupData team(UUID uuid, String name, UUID teamOrg, UserGroupStatus status,
			int members, boolean sso, PermissionType compPerm) {
		UserGroupData g = mock(UserGroupData.class);
		when(g.getUuid()).thenReturn(uuid);
		when(g.getName()).thenReturn(name);
		when(g.getOrg()).thenReturn(teamOrg);
		when(g.getStatus()).thenReturn(status);
		Set<UUID> users = new LinkedHashSet<>();
		for (int i = 0; i < members; i++) users.add(UUID.randomUUID());
		when(g.getAllUsers()).thenReturn(users);
		when(g.getConnectedSsoGroups()).thenReturn(sso ? Set.of("sso-group") : Set.of());
		UserPermission up = null;
		if (compPerm != null) {
			up = mock(UserPermission.class);
			when(up.getType()).thenReturn(compPerm);
		}
		when(g.getPermission(PermissionScope.COMPONENT, comp))
				.thenReturn(Optional.ofNullable(up));
		return g;
	}

	private ComponentOwner teamOwner(UUID teamUuid) {
		return new ComponentOwner(ComponentOwnerType.TEAM, teamUuid);
	}

	// ---------- stored TEAM owner ----------

	@Test
	void storedTeamActiveWithTwoMembersIsOwnedAndDurable() {
		UUID t = UUID.randomUUID();
		UserGroupData tm = team(t, "Platform", org, UserGroupStatus.ACTIVE, 2, false, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.OWNED, o.status());
		assertTrue(o.durable());
		assertFalse(o.derived());
		assertEquals(t, o.ownerRef());
	}

	@Test
	void storedTeamActiveSingleMemberNoSsoIsNonDurable() {
		UUID t = UUID.randomUUID();
		UserGroupData tm = team(t, "Solo", org, UserGroupStatus.ACTIVE, 1, false, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status());
		assertFalse(o.durable());
	}

	/**
	 * End-to-end counterpart to {@code UserGroupTeamMembersTest}: uses a REAL
	 * (non-mocked) UserGroupData so {@code getAllUsers()} is the production
	 * implementation, proving external members never reach the durability count.
	 * DECIDED 2026-07-28 -- externals are addressable but confer no durability,
	 * so a 1-real-member team stays NON_DURABLE no matter how many externals it
	 * carries. Mocking {@code getAllUsers} (as the helper above does) could not
	 * catch a regression that folded externals into the roster.
	 */
	@Test
	void storedTeamWithExternalMembersStaysNonDurableOnOneRealMember() {
		UUID t = UUID.randomUUID();
		UserGroupData realTeam = Utils.OM.readValue("""
				{"name":"Docs Team","org":"%s","status":"ACTIVE","manualUsers":["%s"],
				 "externalMembers":[
				   {"name":"Ext One","contact":"e1@vendor.example","role":"SECURITY_SPECIALIST"},
				   {"name":"Ext Two","contact":"e2@vendor.example","role":"DEVELOPER"}]}
				""".formatted(org, UUID.randomUUID()), UserGroupData.class);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(realTeam));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status(),
				"two external members must not lift a one-person team over the durability bar");
		assertFalse(o.durable());
	}

	@Test
	void storedTeamActiveSsoBackedIsDurableEvenBelowMemberBar() {
		UUID t = UUID.randomUUID();
		// 0 direct members but SSO-backed -> durable (IdP membership materializes at login).
		UserGroupData tm = team(t, "IdP Team", org, UserGroupStatus.ACTIVE, 0, true, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.OWNED, o.status());
		assertTrue(o.durable());
	}

	@Test
	void storedTeamInactiveIsDegraded() {
		UUID t = UUID.randomUUID();
		UserGroupData tm = team(t, "Archived", org, UserGroupStatus.INACTIVE, 5, false, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.DEGRADED, o.status());
		assertFalse(o.durable());
	}

	@Test
	void storedTeamMissingIsOrphaned() {
		UUID t = UUID.randomUUID();
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.empty());
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
	}

	@Test
	void storedTeamCrossOrgIsOrphaned() {
		UUID t = UUID.randomUUID();
		UserGroupData tm = team(t, "Other org", UUID.randomUUID(), UserGroupStatus.ACTIVE, 5, false, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), List.of());
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
	}

	// ---------- stored USER owner ----------

	@Test
	void storedUserInOrgIsNonDurable() {
		UUID u = UUID.randomUUID();
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.of(mock(UserData.class)));
		ComponentOwner owner = new ComponentOwner(ComponentOwnerType.USER, u);
		ComponentOwnership o = service.resolveOwnership(component(owner), List.of());
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status());
		assertFalse(o.durable());
		assertEquals(ComponentOwnerType.USER, o.ownerType());
	}

	@Test
	void storedUserNotInOrgIsOrphaned() {
		UUID u = UUID.randomUUID();
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.empty());
		ComponentOwner owner = new ComponentOwner(ComponentOwnerType.USER, u);
		ComponentOwnership o = service.resolveOwnership(component(owner), List.of());
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
	}

	// ---------- no stored owner: suggestion / orphan ----------

	@Test
	void noOwnerWithOneCandidateTeamSuggestsItUnset() {
		UUID t = UUID.randomUUID();
		UserGroupData cand = team(t, "Writers", org, UserGroupStatus.ACTIVE, 3, false, PermissionType.READ_WRITE);
		ComponentOwnership o = service.resolveOwnership(component(null), List.of(cand));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertTrue(o.derived());
		assertEquals(t, o.ownerRef());
		assertTrue(o.durable());
	}

	@Test
	void noOwnerPrefersDurableThenLargestCandidate() {
		UUID small = UUID.randomUUID();
		UUID big = UUID.randomUUID();
		UserGroupData smallDurable = team(small, "Small", org, UserGroupStatus.ACTIVE, 2, false, PermissionType.READ_WRITE);
		UserGroupData bigDurable = team(big, "Big", org, UserGroupStatus.ACTIVE, 9, false, PermissionType.ADMIN);
		ComponentOwnership o = service.resolveOwnership(component(null), List.of(smallDurable, bigDurable));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertEquals(big, o.ownerRef(), "should suggest the largest durable candidate");
	}

	@Test
	void noOwnerReadOnlyGroupIsNotACandidate() {
		// A group with only READ_ONLY on the component is NOT an owner candidate
		// (owner-team floor is >= READ_WRITE); with no writers, orphaned.
		UUID t = UUID.randomUUID();
		UserGroupData readOnly = team(t, "Viewers", org, UserGroupStatus.ACTIVE, 4, false, PermissionType.READ_ONLY);
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());
		ComponentOwnership o = service.resolveOwnership(component(null), List.of(readOnly));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
		assertNull(o.ownerRef());
	}

	@Test
	void noOwnerNoTeamButIndividualWritersSuggestsCreateTeam() {
		when(componentTeamService.deriveTeam(any(ComponentData.class)))
				.thenReturn(List.of(mock(UserData.class), mock(UserData.class)));
		ComponentOwnership o = service.resolveOwnership(component(null), List.of());
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertTrue(o.derived());
		assertNull(o.ownerRef(), "no team to point at yet -- a create-team hint");
	}

	@Test
	void noOwnerNothingDerivableIsOrphaned() {
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());
		ComponentOwnership o = service.resolveOwnership(component(null), List.of());
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
		assertFalse(o.derived());
	}

	@Test
	void convenienceOverloadFetchesOrgGroupsOnce() {
		UUID t = UUID.randomUUID();
		UserGroupData cand = team(t, "Writers", org, UserGroupStatus.ACTIVE, 3, false, PermissionType.READ_WRITE);
		when(userGroupService.getUserGroupsByOrganization(org)).thenReturn(List.of(cand));
		ComponentOwnership o = service.resolveOwnership(component(null));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertEquals(t, o.ownerRef());
	}

	@Test
	void malformedStoredOwnerRoutesToSuggestionNotNpe() {
		// A stored owner missing its ref (or type) is not usable -> the resolver
		// falls through to the suggestion path rather than NPE-ing.
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());
		ComponentOwner malformed = new ComponentOwner(ComponentOwnerType.TEAM, null);
		ComponentOwnership o = service.resolveOwnership(component(malformed), List.of());
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
		assertFalse(o.derived());
	}

	@Test
	void noOwnerPrefersDurableOverNonDurableCandidate() {
		// Both are candidates (ACTIVE, >= READ_WRITE); durability wins over a
		// non-durable (1-member) candidate.
		UUID durableTeamUuid = UUID.randomUUID();
		UUID tinyUuid = UUID.randomUUID();
		UserGroupData durableTeam =
				team(durableTeamUuid, "Durable", org, UserGroupStatus.ACTIVE, 2, false, PermissionType.READ_WRITE);
		UserGroupData tiny =
				team(tinyUuid, "Tiny", org, UserGroupStatus.ACTIVE, 1, false, PermissionType.READ_WRITE);
		ComponentOwnership o = service.resolveOwnership(component(null), List.of(tiny, durableTeam));
		assertEquals(durableTeamUuid, o.ownerRef());
		assertTrue(o.durable());
	}

	// ---------- validateOwner (Phase 4b write path) ----------

	// ---------- ownershipReport (Phase 4c) ----------

	@Test
	void ownershipReportFiltersToNonOwnedAndHoistsGroupsOnce() {
		UUID t1 = UUID.randomUUID();
		ComponentData owned = new ComponentData();
		owned.setUuid(UUID.randomUUID());
		owned.setName("owned");
		owned.setOrg(org);
		owned.setOwner(teamOwner(t1));
		ComponentData orphan = new ComponentData();
		orphan.setUuid(UUID.randomUUID());
		orphan.setName("orphan");
		orphan.setOrg(org);

		when(userGroupService.getUserGroupsByOrganization(org)).thenReturn(List.of());
		UserGroupData durable = team(t1, "T1", org, UserGroupStatus.ACTIVE, 2, false, null);
		when(userGroupService.getUserGroupData(t1)).thenReturn(Optional.of(durable));
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());

		List<ComponentOwnershipReportRow> rows = service.ownershipReport(org, List.of(owned, orphan));

		assertEquals(1, rows.size(), "only the non-OWNED component is reported");
		assertEquals(orphan.getUuid(), rows.get(0).componentUuid());
		assertEquals(ComponentOwnershipStatus.ORPHANED, rows.get(0).ownership().status());
		// The org group list is fetched ONCE for the whole report, not per component.
		verify(userGroupService, times(1)).getUserGroupsByOrganization(org);
	}

	@Test
	void validateOwnerNullIsNoOp() {
		assertDoesNotThrow(() -> service.validateOwner(null, org));
	}

	@Test
	void validateOwnerRejectsMissingTypeOrRef() {
		assertThrows(RelizaException.class,
				() -> service.validateOwner(new ComponentOwner(ComponentOwnerType.TEAM, null), org));
		assertThrows(RelizaException.class,
				() -> service.validateOwner(new ComponentOwner(null, UUID.randomUUID()), org));
	}

	@Test
	void validateOwnerAcceptsTeamInOrg() {
		UUID t = UUID.randomUUID();
		// An INACTIVE team is still a valid ref to store (resolveOwnership reports
		// DEGRADED later) -- validateOwner only checks existence + org.
		UserGroupData tm = team(t, "Any", org, UserGroupStatus.INACTIVE, 3, false, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		assertDoesNotThrow(() -> service.validateOwner(teamOwner(t), org));
	}

	@Test
	void validateOwnerRejectsMissingTeam() {
		UUID t = UUID.randomUUID();
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.empty());
		assertThrows(RelizaException.class, () -> service.validateOwner(teamOwner(t), org));
	}

	@Test
	void validateOwnerRejectsCrossOrgTeam() {
		UUID t = UUID.randomUUID();
		UserGroupData tm = team(t, "Other", UUID.randomUUID(), UserGroupStatus.ACTIVE, 3, false, null);
		when(userGroupService.getUserGroupData(t)).thenReturn(Optional.of(tm));
		assertThrows(RelizaException.class, () -> service.validateOwner(teamOwner(t), org));
	}

	@Test
	void validateOwnerAcceptsUserInOrg() {
		UUID u = UUID.randomUUID();
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.of(mock(UserData.class)));
		assertDoesNotThrow(() -> service.validateOwner(new ComponentOwner(ComponentOwnerType.USER, u), org));
	}

	@Test
	void validateOwnerRejectsUserNotInOrg() {
		UUID u = UUID.randomUUID();
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.empty());
		assertThrows(RelizaException.class,
				() -> service.validateOwner(new ComponentOwner(ComponentOwnerType.USER, u), org));
	}
}

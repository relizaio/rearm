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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentOwner;
import io.reliza.model.ComponentOwnerType;
import io.reliza.model.ComponentOwnershipStatus;
import io.reliza.model.OrganizationData;
import io.reliza.model.TeamData;
import io.reliza.model.TeamStatus;
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
	private TeamService teamService;
	private UserService userService;
	private ComponentTeamService componentTeamService;
	private GetOrganizationService getOrganizationService;
	private OrgTeamAssignmentRuleService teamAssignmentRuleService;
	private ComponentOwnershipService service;

	private UUID org;
	private UUID comp;

	@BeforeEach
	void setUp() {
		userGroupService = mock(UserGroupService.class);
		teamService = mock(TeamService.class);
		groupsById.clear();
		userService = mock(UserService.class);
		componentTeamService = mock(ComponentTeamService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		// Real rule service over a mocked group service: the matching logic is
		// what these tests exercise, so stubbing it would test nothing.
		teamAssignmentRuleService = new OrgTeamAssignmentRuleService();
		ReflectionTestUtils.setField(teamAssignmentRuleService, "teamService", teamService);
		service = new ComponentOwnershipService();
		ReflectionTestUtils.setField(service, "userGroupService", userGroupService);
		ReflectionTestUtils.setField(service, "teamService", teamService);
		ReflectionTestUtils.setField(service, "userService", userService);
		ReflectionTestUtils.setField(service, "componentTeamService", componentTeamService);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "teamAssignmentRuleService", teamAssignmentRuleService);
		// Default: org resolves but carries no rules, so every pre-T2 test keeps
		// its original meaning (stored owner / candidate suggestion only).
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.empty());
		// ownershipContext() builds its group map from THIS call, so it has to see
		// whatever team() registered. Without it the map is empty, every
		// contained-group lookup misses, and durability/candidacy quietly answer
		// as though the teams had no members at all.
		when(userGroupService.getUserGroupsByOrganization(any()))
				.thenAnswer(inv -> List.copyOf(groupsById.values()));
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

	/**
	 * The org's groups by uuid, as the resolver receives them. {@link #team}
	 * registers each team's contained group here so a call site stays a one-liner
	 * while the transitive resolution stays REAL.
	 */
	private final Map<UUID, UserGroupData> groupsById = new LinkedHashMap<>();

	/** The hoisted context a resolution runs against. */
	private ComponentOwnershipService.OwnershipContext ctx(List<TeamData> teams, OrganizationData od) {
		return new ComponentOwnershipService.OwnershipContext(teams, groupsById, od);
	}

	/**
	 * A team with the given roster size, SSO backing and COMPONENT permission --
	 * all of which now live on a group the team CONTAINS, because a team holds
	 * none of them itself. The TeamData is REAL, not a mock, so {@code rosterWith},
	 * {@code hasSsoBackedGroup} and the candidacy walk all execute for real; only
	 * the contained group's own accessors are stubbed. Mocking the team would let
	 * a regression in the transitive resolution pass unnoticed, which is the one
	 * thing this switch could plausibly break.
	 *
	 * <p>Pass {@code compPerm=null} for "no COMPONENT grant on this component".
	 */
	private TeamData team(UUID uuid, String name, UUID teamOrg, TeamStatus status,
			int members, boolean sso, PermissionType compPerm) {
		UUID groupUuid = UUID.randomUUID();
		UserGroupData g = mock(UserGroupData.class);
		when(g.getUuid()).thenReturn(groupUuid);
		when(g.getOrg()).thenReturn(teamOrg);
		Set<UUID> users = new LinkedHashSet<>();
		for (int i = 0; i < members; i++) users.add(UUID.randomUUID());
		when(g.getAllUsers()).thenReturn(users);
		when(g.getConnectedSsoGroups()).thenReturn(sso ? Set.of("sso-group") : Set.of());
		UserPermission up = null;
		if (compPerm != null) {
			up = mock(UserPermission.class);
			when(up.getType()).thenReturn(compPerm);
		}
		when(g.getPermission(PermissionScope.COMPONENT, comp)).thenReturn(Optional.ofNullable(up));
		groupsById.put(groupUuid, g);
		return realTeam(uuid, name, teamOrg, status, Set.of(groupUuid));
	}

	/** A real TeamData, built the way the production read path builds one. */
	private TeamData realTeam(UUID uuid, String name, UUID teamOrg, TeamStatus status,
			Set<UUID> userGroups) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uuid", uuid);
		m.put("name", name);
		m.put("org", teamOrg);
		m.put("status", status.name());
		m.put("members", Set.of());
		m.put("userGroups", userGroups);
		m.put("notificationChannels", Set.of());
		m.put("leads", Set.of());
		TeamData td = Utils.OM.convertValue(m, TeamData.class);
		// dataFromRecord takes uuid from the entity row, not from record_data, so
		// convertValue alone leaves it null.
		ReflectionTestUtils.setField(td, "uuid", uuid);
		return td;
	}

	private ComponentOwner teamOwner(UUID teamUuid) {
		return new ComponentOwner(ComponentOwnerType.TEAM, teamUuid);
	}

	// ---------- stored TEAM owner ----------

	@Test
	void storedTeamActiveWithTwoMembersIsOwnedAndDurable() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Platform", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.OWNED, o.status());
		assertTrue(o.durable());
		assertFalse(o.derived());
		assertEquals(t, o.ownerRef());
	}

	@Test
	void storedTeamActiveSingleMemberNoSsoIsNonDurable() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Solo", org, TeamStatus.ACTIVE, 1, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status());
		assertFalse(o.durable());
	}

	/**
	 * Durability counts the ROSTER, and the roster resolves THROUGH contained
	 * groups. Uses a real TeamData over a real roster rather than a mocked
	 * {@code rosterWith}, because the regression that matters here is counting
	 * only DIRECT members -- which would report every IdP-managed team as
	 * fragile the moment ownership moved off UserGroup.
	 *
	 * <p>This replaces a test that pinned "external members never lift the
	 * durability bar". Team carries no external members -- they were dropped as
	 * undeliverable -- so that rule is now unrepresentable, not merely untested.
	 */
	@Test
	void durabilityCountsMembersReachedThroughAContainedGroup() {
		UUID t = UUID.randomUUID();
		TeamData oneMember = team(t, "Docs", org, TeamStatus.ACTIVE, 1, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(oneMember));
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(oneMember));
		assertEquals(ComponentOwnershipStatus.NON_DURABLE,
				service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null)).status(),
				"one member reached through a group is still only one member");

		UUID t2 = UUID.randomUUID();
		TeamData twoMembers = team(t2, "Platform", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t2)).thenReturn(Optional.of(twoMembers));
		when(teamService.getReadableTeamData(t2)).thenReturn(Optional.of(twoMembers));
		assertEquals(ComponentOwnershipStatus.OWNED,
				service.resolveOwnership(component(teamOwner(t2)), ctx(List.of(), null)).status(),
				"two members reached through a group clear the bar -- the team itself has none");
	}

	@Test
	void storedTeamActiveSsoBackedIsDurableEvenBelowMemberBar() {
		UUID t = UUID.randomUUID();
		// 0 direct members but SSO-backed -> durable (IdP membership materializes at login).
		TeamData tm = team(t, "IdP Team", org, TeamStatus.ACTIVE, 0, true, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.OWNED, o.status());
		assertTrue(o.durable());
	}

	@Test
	void storedTeamInactiveIsDegraded() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Archived", org, TeamStatus.INACTIVE, 5, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.DEGRADED, o.status());
		assertFalse(o.durable());
	}

	@Test
	void storedTeamMissingIsOrphaned() {
		UUID t = UUID.randomUUID();
		when(teamService.getTeamData(t)).thenReturn(Optional.empty());
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.empty());
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
	}

	@Test
	void storedTeamCrossOrgIsOrphaned() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Other org", UUID.randomUUID(), TeamStatus.ACTIVE, 5, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(component(teamOwner(t)), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
	}

	// ---------- stored USER owner ----------

	@Test
	void storedUserInOrgIsNonDurable() {
		UUID u = UUID.randomUUID();
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.of(mock(UserData.class)));
		ComponentOwner owner = new ComponentOwner(ComponentOwnerType.USER, u);
		ComponentOwnership o = service.resolveOwnership(component(owner), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status());
		assertFalse(o.durable());
		assertEquals(ComponentOwnerType.USER, o.ownerType());
	}

	@Test
	void storedUserNotInOrgIsOrphaned() {
		UUID u = UUID.randomUUID();
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.empty());
		ComponentOwner owner = new ComponentOwner(ComponentOwnerType.USER, u);
		ComponentOwnership o = service.resolveOwnership(component(owner), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
	}

	// ---------- no stored owner: suggestion / orphan ----------

	@Test
	void noOwnerWithOneCandidateTeamSuggestsItUnset() {
		UUID t = UUID.randomUUID();
		TeamData cand = team(t, "Writers", org, TeamStatus.ACTIVE, 3, false, PermissionType.READ_WRITE);
		ComponentOwnership o = service.resolveOwnership(component(null), ctx(List.of(cand), null));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertTrue(o.derived());
		assertEquals(t, o.ownerRef());
		assertTrue(o.durable());
	}

	@Test
	void noOwnerPrefersDurableThenLargestCandidate() {
		UUID small = UUID.randomUUID();
		UUID big = UUID.randomUUID();
		TeamData smallDurable = team(small, "Small", org, TeamStatus.ACTIVE, 2, false, PermissionType.READ_WRITE);
		TeamData bigDurable = team(big, "Big", org, TeamStatus.ACTIVE, 9, false, PermissionType.ADMIN);
		ComponentOwnership o = service.resolveOwnership(component(null), ctx(List.of(smallDurable, bigDurable), null));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertEquals(big, o.ownerRef(), "should suggest the largest durable candidate");
	}

	@Test
	void noOwnerReadOnlyGroupIsNotACandidate() {
		// A group with only READ_ONLY on the component is NOT an owner candidate
		// (owner-team floor is >= READ_WRITE); with no writers, orphaned.
		UUID t = UUID.randomUUID();
		TeamData readOnly = team(t, "Viewers", org, TeamStatus.ACTIVE, 4, false, PermissionType.READ_ONLY);
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());
		ComponentOwnership o = service.resolveOwnership(component(null), ctx(List.of(readOnly), null));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
		assertNull(o.ownerRef());
	}

	@Test
	void noOwnerNoTeamButIndividualWritersSuggestsCreateTeam() {
		when(componentTeamService.deriveTeam(any(ComponentData.class)))
				.thenReturn(List.of(mock(UserData.class), mock(UserData.class)));
		ComponentOwnership o = service.resolveOwnership(component(null), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status());
		assertTrue(o.derived());
		assertNull(o.ownerRef(), "no team to point at yet -- a create-team hint");
	}

	@Test
	void noOwnerNothingDerivableIsOrphaned() {
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());
		ComponentOwnership o = service.resolveOwnership(component(null), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
		assertFalse(o.derived());
	}

	@Test
	void convenienceOverloadFetchesOrgGroupsOnce() {
		UUID t = UUID.randomUUID();
		TeamData cand = team(t, "Writers", org, TeamStatus.ACTIVE, 3, false, PermissionType.READ_WRITE);
		when(teamService.getAllTeamsByOrganization(org)).thenReturn(List.of(cand));
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
		ComponentOwnership o = service.resolveOwnership(component(malformed), ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status());
		assertFalse(o.derived());
	}

	@Test
	void noOwnerPrefersDurableOverNonDurableCandidate() {
		// Both are candidates (ACTIVE, >= READ_WRITE); durability wins over a
		// non-durable (1-member) candidate.
		UUID durableTeamUuid = UUID.randomUUID();
		UUID tinyUuid = UUID.randomUUID();
		TeamData durableTeam =
				team(durableTeamUuid, "Durable", org, TeamStatus.ACTIVE, 2, false, PermissionType.READ_WRITE);
		TeamData tiny =
				team(tinyUuid, "Tiny", org, TeamStatus.ACTIVE, 1, false, PermissionType.READ_WRITE);
		ComponentOwnership o = service.resolveOwnership(component(null), ctx(List.of(tiny, durableTeam), null));
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

		when(teamService.getAllTeamsByOrganization(org)).thenReturn(List.of());
		TeamData durable = team(t1, "T1", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t1)).thenReturn(Optional.of(durable));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t1)).thenReturn(Optional.of(durable));
		when(componentTeamService.deriveTeam(any(ComponentData.class))).thenReturn(List.of());

		List<ComponentOwnershipReportRow> rows = service.ownershipReport(org, List.of(owned, orphan));

		assertEquals(1, rows.size(), "only the non-OWNED component is reported");
		assertEquals(orphan.getUuid(), rows.get(0).componentUuid());
		assertEquals(ComponentOwnershipStatus.ORPHANED, rows.get(0).ownership().status());
		// The org team list is fetched ONCE for the whole report, not per component.
		verify(teamService, times(1)).getAllTeamsByOrganization(org);
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
		TeamData tm = team(t, "Any", org, TeamStatus.INACTIVE, 3, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		assertDoesNotThrow(() -> service.validateOwner(teamOwner(t), org));
	}

	@Test
	void validateOwnerRejectsMissingTeam() {
		UUID t = UUID.randomUUID();
		when(teamService.getTeamData(t)).thenReturn(Optional.empty());
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.empty());
		assertThrows(RelizaException.class, () -> service.validateOwner(teamOwner(t), org));
	}

	@Test
	void validateOwnerRejectsCrossOrgTeam() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Other", UUID.randomUUID(), TeamStatus.ACTIVE, 3, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
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

	// ---------- T2: team-assignment rules (Option A -- a rule SETS the owner) ----------

	private OrganizationData orgWithRule(String ruleName, String pattern, UUID team) {
		var r = new OrganizationData.GlobalTeamAssignmentRule();
		r.setName(ruleName);
		r.setNamePattern(pattern);
		r.setOwnerTeam(team);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getUuid()).thenReturn(org);
		when(od.getGlobalTeamAssignmentRules()).thenReturn(List.of(r));
		return od;
	}

	private ComponentData named(String name, ComponentOwner owner) {
		ComponentData cd = component(owner);
		cd.setName(name);
		cd.setType(ComponentData.ComponentType.COMPONENT);
		return cd;
	}

	@Test
	void ruleAssignsOwnerWhenComponentHasNoStoredOwner() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Rebom", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(
				named("rebom-backend", null), ctx(List.of(), orgWithRule("rebom", "rebom-.*", t)));
		assertEquals(ComponentOwnershipStatus.OWNED, o.status());
		assertEquals(t, o.ownerRef());
		assertTrue(o.derived(), "a rule-assigned owner is derived, not hand-picked");
		assertTrue(o.reason().contains("rebom"), "the reason names the rule for provenance: " + o.reason());
	}

	@Test
	void storedOwnerBeatsAMatchingRule() {
		// The whole point of Option A's precedence: a rule never overrides a
		// deliberate per-component choice.
		UUID stored = UUID.randomUUID();
		UUID ruleTeam = UUID.randomUUID();
		TeamData storedTeam = team(stored, "Chosen", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(stored)).thenReturn(Optional.of(storedTeam));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(stored)).thenReturn(Optional.of(storedTeam));
		ComponentOwnership o = service.resolveOwnership(
				named("rebom-backend", teamOwner(stored)), ctx(List.of(), orgWithRule("rebom", "rebom-.*", ruleTeam)));
		assertEquals(stored, o.ownerRef());
		assertFalse(o.derived(), "a stored owner is not derived");
	}

	@Test
	void ruleAssignedOneMemberTeamIsStillReportedNonDurable() {
		// A rule must not launder a one-person team into OWNED.
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Solo", org, TeamStatus.ACTIVE, 1, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(
				named("rebom-backend", null), ctx(List.of(), orgWithRule("rebom", "rebom-.*", t)));
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status());
		assertFalse(o.durable());
	}

	@Test
	void aMatchingRuleBeatsACandidateSuggestion() {
		// Precedence tier 2 over tier 3: a durable candidate team exists AND a rule
		// matches -- the rule must win, and the result must be OWNED (not an UNSET
		// suggestion). Previously unverified.
		UUID ruleTeam = UUID.randomUUID();
		TeamData rt = team(ruleTeam, "Rule", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(ruleTeam)).thenReturn(Optional.of(rt));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(ruleTeam)).thenReturn(Optional.of(rt));
		UUID candidate = UUID.randomUUID();
		TeamData cand = team(candidate, "Candidate", org, TeamStatus.ACTIVE, 3, false,
				PermissionType.READ_WRITE);
		ComponentOwnership o = service.resolveOwnership(
				named("rebom-backend", null), ctx(List.of(cand), orgWithRule("rebom", "rebom-.*", ruleTeam)));
		assertEquals(ComponentOwnershipStatus.OWNED, o.status());
		assertEquals(ruleTeam, o.ownerRef(), "the rule must win over a candidate suggestion");
	}

	@Test
	void ruleTeamArchivedAfterTheRuleWasSavedReportsDegraded() {
		// Writes reject archived teams, but a team can be archived later. The
		// resolver must surface that as DEGRADED rather than silently skipping to
		// the next rule -- an archived team is recoverable and worth telling the
		// operator about.
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Archived", org, TeamStatus.INACTIVE, 5, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(
				named("rebom-backend", null), ctx(List.of(), orgWithRule("rebom", "rebom-.*", t)));
		assertEquals(ComponentOwnershipStatus.DEGRADED, o.status());
		assertTrue(o.derived());
		assertTrue(o.reason().contains("rebom"), "reason names the rule: " + o.reason());
	}

	@Test
	void nonMatchingRuleFallsThroughToTheCandidateSuggestion() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Rebom", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentOwnership o = service.resolveOwnership(
				named("unrelated-thing", null), ctx(List.of(), orgWithRule("rebom", "rebom-.*", t)));
		assertEquals(ComponentOwnershipStatus.ORPHANED, o.status(),
				"no rule match and no candidate team -> orphaned, as before T2");
	}

	@Test
	void clearingAStoredOwnerHandsTheComponentBackToTheRule() {
		// The workflow clearOwner exists for: a component manually pinned to one
		// team, then released back to org rules. Modeled here as owner -> null.
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Rebom", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		var od = orgWithRule("rebom", "rebom-.*", t);
		ComponentData pinned = named("rebom-backend", teamOwner(UUID.randomUUID()));
		assertFalse(service.resolveOwnership(pinned, ctx(List.of(), od)).derived(),
				"while pinned, the stored owner wins");
		ComponentData cleared = named("rebom-backend", null);
		ComponentOwnership after = service.resolveOwnership(cleared, ctx(List.of(), od));
		assertTrue(after.derived(), "once cleared, the rule takes over again");
		assertEquals(t, after.ownerRef());
	}

	// ---- T4a: owner-team channel resolution for notification routing --------

	/** Component in the given org with the given stored owner (null = none). */
	private ComponentData compIn(UUID inOrg, ComponentOwner owner) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(inOrg);
		cd.setOwner(owner);
		return cd;
	}

	@Test
	void ownerTeamChannelsResolveThroughTheStoredOwner() {
		UUID t = UUID.randomUUID();
		UUID ch = UUID.randomUUID();
		TeamData tm = team(t, "Payments", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		when(teamService.resolveTeamChannelUuids(any(), any())).thenReturn(List.of(ch));
		List<UUID> out = service.resolveOwnerTeamChannels(List.of(compIn(org, teamOwner(t))), org);
		assertEquals(List.of(ch), out);
	}

	@Test
	void ownerTeamChannelsIncludeNonDurableOwners() {
		// A one-person team is a weak owner, not a wrong one. Withholding a KEV
		// notification from the only person who owns the component is worse.
		UUID t = UUID.randomUUID();
		UUID ch = UUID.randomUUID();
		TeamData tm = team(t, "Solo", org, TeamStatus.ACTIVE, 1, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		when(teamService.resolveTeamChannelUuids(any(), any())).thenReturn(List.of(ch));
		ComponentData cd = compIn(org, teamOwner(t));
		assertEquals(ComponentOwnershipStatus.NON_DURABLE,
				service.resolveOwnership(cd, ctx(List.of(), null)).status(),
				"precondition: this owner is NON_DURABLE");
		assertEquals(List.of(ch), service.resolveOwnerTeamChannels(List.of(cd), org));
	}

	@Test
	void ownerTeamChannelsSkipAnArchivedOwnerTeam() {
		// DEGRADED: the team is archived, so its channels are stale by definition.
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Retired", org, TeamStatus.INACTIVE, 3, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentData cd = compIn(org, teamOwner(t));
		assertEquals(ComponentOwnershipStatus.DEGRADED,
				service.resolveOwnership(cd, ctx(List.of(), null)).status(),
				"precondition: archived owner team is DEGRADED");
		assertTrue(service.resolveOwnerTeamChannels(List.of(cd), org).isEmpty());
		verify(teamService, times(0)).resolveTeamChannelUuids(any(), any());
	}

	@Test
	void ownerTeamChannelsIgnoreASuggestionThatWasNeverAccepted() {
		// UNSET with candidates is a SUGGESTION. Routing to it would notify a
		// team that never took the component on -- the thing this allowlist exists
		// to prevent.
		//
		// Two things this test needs that an earlier version lacked, either of
		// which alone makes it vacuous:
		//   1. component(null), not compIn(org, null) -- team() scopes its
		//      COMPONENT grant to the `comp` fixture uuid, so a component with a
		//      random uuid never matches and no candidate forms.
		//   2. a getUserGroupsByOrganization stub -- resolveOwnerTeamChannels
		//      hoists its OWN group list rather than reusing the one handed to
		//      the precondition call below.
		// Missing either, the result is ORPHANED with a null ownerRef, the
		// assertion passes on the null-ownerRef guard instead of on
		// ROUTABLE_OWNERSHIP, and the test stays green even with UNSET added to
		// the allowlist -- i.e. it guards nothing.
		UUID t = UUID.randomUUID();
		TeamData candidate = team(t, "Maybe", org, TeamStatus.ACTIVE, 2, false,
				PermissionType.READ_WRITE);
		when(teamService.getAllTeamsByOrganization(org)).thenReturn(List.of(candidate));
		ComponentData cd = component(null);
		ComponentOwnership o = service.resolveOwnership(cd, ctx(List.of(candidate), null));
		assertEquals(ComponentOwnershipStatus.UNSET, o.status(), "precondition: a suggestion");
		assertEquals(t, o.ownerRef(), "precondition: the suggestion names the candidate team");
		assertTrue(service.resolveOwnerTeamChannels(List.of(cd), org).isEmpty());
		verify(teamService, times(0)).resolveTeamChannelUuids(any(), any());
	}

	@Test
	void ownerTeamChannelsIgnoreUserOwners() {
		// A user is not a team and has no channels. Reached via the inbox arms.
		//
		// getUserDataWithOrg, not getUserData: that is what the USER branch of
		// resolveStored actually calls. Stubbing the wrong one leaves the owner
		// ORPHANED, and the test then passes because of the status allowlist
		// rather than the ownerType guard it claims to cover -- it stayed green
		// with that guard deleted. An in-org USER owner is NON_DURABLE, which IS
		// routable, so the ownerType guard is the only thing excluding it.
		UUID u = UUID.randomUUID();
		UserData ud = mock(UserData.class);
		when(ud.getUuid()).thenReturn(u);
		when(userService.getUserDataWithOrg(u, org)).thenReturn(Optional.of(ud));
		ComponentData cd = compIn(org, new ComponentOwner(ComponentOwnerType.USER, u));
		ComponentOwnership o = service.resolveOwnership(cd, ctx(List.of(), null));
		assertEquals(ComponentOwnershipStatus.NON_DURABLE, o.status(),
				"precondition: an in-org USER owner is routable BY STATUS");
		assertEquals(ComponentOwnerType.USER, o.ownerType());
		assertTrue(service.resolveOwnerTeamChannels(List.of(cd), org).isEmpty());
		verify(teamService, times(0)).resolveTeamChannelUuids(any(), any());
	}

	@Test
	void ownerTeamChannelsFailClosedOnACrossOrgComponent() {
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Other", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		ComponentData foreign = compIn(UUID.randomUUID(), teamOwner(t));
		assertTrue(service.resolveOwnerTeamChannels(List.of(foreign), org).isEmpty());
		verify(teamService, times(0)).resolveTeamChannelUuids(any(), any());
	}

	@Test
	void ownerTeamChannelsFailClosedOnANullOrg() {
		assertTrue(service.resolveOwnerTeamChannels(List.of(compIn(org, null)), null).isEmpty());
	}

	@Test
	void ownerTeamChannelsDedupeTheOwnerTeamAcrossComponents() {
		// One event affecting three components of the same team must resolve that
		// team once -- otherwise the merge downstream does redundant work and the
		// team lookup is repeated per component.
		UUID t = UUID.randomUUID();
		UUID ch = UUID.randomUUID();
		TeamData tm = team(t, "Payments", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		when(teamService.resolveTeamChannelUuids(any(), any())).thenReturn(List.of(ch));
		List<ComponentData> three = List.of(
				compIn(org, teamOwner(t)), compIn(org, teamOwner(t)), compIn(org, teamOwner(t)));
		assertEquals(List.of(ch), service.resolveOwnerTeamChannels(three, org));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<UUID>> teams = ArgumentCaptor.forClass(Collection.class);
		verify(teamService, times(1)).resolveTeamChannelUuids(teams.capture(), any());
		assertEquals(1, teams.getValue().size(), "the same owner team must be passed once");
	}

	@Test
	void ownerTeamChannelsHoistTheOrgLookupsOutOfTheComponentLoop() {
		// N+1 guard: rule-derived ownership consults the org groups AND the org
		// record. Re-fetching either per component is the exact regression the
		// report path already documents.
		UUID t = UUID.randomUUID();
		TeamData tm = team(t, "Rebom", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		when(teamService.getAllTeamsByOrganization(org)).thenReturn(List.of(tm));
		when(teamService.resolveTeamChannelUuids(any(), any())).thenReturn(List.of());
		service.resolveOwnerTeamChannels(List.of(
				compIn(org, null), compIn(org, null), compIn(org, null), compIn(org, null)), org);
		verify(teamService, times(1)).getAllTeamsByOrganization(org);
		verify(getOrganizationService, times(1)).getOrganizationData(org);
	}

	@Test
	void ownerTeamChannelsResolveThroughAnAssignmentRule() {
		// The whole point of T4a: the owner came from a T2 rule, not a stored
		// value, and routing still finds it -- so reassigning by rule needs no
		// subscription edit.
		UUID t = UUID.randomUUID();
		UUID ch = UUID.randomUUID();
		TeamData tm = team(t, "Rebom", org, TeamStatus.ACTIVE, 2, false, null);
		when(teamService.getTeamData(t)).thenReturn(Optional.of(tm));
		// resolveStored reads through the tolerant variant; a mock does not
		// delegate, so it needs its own stub.
		when(teamService.getReadableTeamData(t)).thenReturn(Optional.of(tm));
		when(teamService.resolveTeamChannelUuids(any(), any())).thenReturn(List.of(ch));
		// Build the org BEFORE stubbing: orgWithRule stubs internally, and
		// nesting it inside when(...) trips Mockito's UnfinishedStubbing.
		OrganizationData od = orgWithRule("rebom", "rebom-.*", t);
		when(getOrganizationService.getOrganizationData(org)).thenReturn(Optional.of(od));
		ComponentData cd = named("rebom-backend", null);
		assertEquals(List.of(ch), service.resolveOwnerTeamChannels(List.of(cd), org));
	}

	@Test
	void ownerTeamChannelsTolerateNullsAndEmptyInput() {
		assertTrue(service.resolveOwnerTeamChannels(null, org).isEmpty());
		assertTrue(service.resolveOwnerTeamChannels(List.of(), org).isEmpty());
		assertDoesNotThrow(() -> service.resolveOwnerTeamChannels(
				Collections.singletonList(null), org));
	}
}

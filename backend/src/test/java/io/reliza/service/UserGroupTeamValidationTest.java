/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.TeamRole;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserGroupData.ExternalTeamMember;
import io.reliza.model.UserGroupData.TeamMemberRole;
import io.reliza.model.dto.UpdateUserGroupDto;

/**
 * Covers the Team-member write-path invariants enforced in
 * {@link UserGroupService} (T1). These validators are the ONLY thing keeping
 * {@code memberRoles} an annotation rather than a second roster, so they are
 * pinned directly rather than through the GraphQL layer.
 *
 * <p>They live on the service (not the data fetcher) so that every caller of
 * {@code updateUserGroupComprehensive} is held to them -- these tests exercise
 * them at that boundary.
 */
class UserGroupTeamValidationTest {

	private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID STRANGER = UUID.fromString("99999999-9999-9999-9999-999999999999");

	private static UserGroupData group(String json) {
		return Utils.OM.readValue(json, UserGroupData.class);
	}

	// ---------- effectiveRoster mirrors the merge (null = keep, non-null = replace) ----------

	@Test
	void effectiveRosterFallsBackToStoredWhenUpdateOmitsMembers() {
		UserGroupData existing = group("""
				{"name":"T","manualUsers":["%s"],"users":["%s"]}""".formatted(USER_A, USER_B));
		Set<UUID> roster = UserGroupService.effectiveRoster(existing, UpdateUserGroupDto.builder().build());
		assertEquals(Set.of(USER_A, USER_B), roster);
	}

	@Test
	void effectiveRosterUsesIncomingListWhenSupplied() {
		UserGroupData existing = group("""
				{"name":"T","manualUsers":["%s"]}""".formatted(USER_A));
		Set<UUID> roster = UserGroupService.effectiveRoster(existing,
				UpdateUserGroupDto.builder().manualUsers(Set.of(USER_B)).build());
		assertTrue(roster.contains(USER_B));
		assertTrue(!roster.contains(USER_A), "a replaced roster must not retain the old members");
	}

	// ---------- a role may only annotate an existing member ----------

	@Test
	void roleForNonRosterUserIsRejected() {
		RelizaException e = assertThrows(RelizaException.class, () ->
				UserGroupService.validateMemberRoles(
						List.of(new TeamMemberRole(STRANGER, TeamRole.DEVELOPER, null)),
						Set.of(USER_A)));
		assertTrue(e.getMessage().contains(STRANGER.toString()),
				"the message should name the offending user so the operator can fix it");
	}

	@Test
	void roleForUserAddedInTheSameCallIsAccepted() {
		// The roster is computed post-merge, so "add a member and give them a
		// role in one mutation" must work -- otherwise the UI would need two round trips.
		assertDoesNotThrow(() -> UserGroupService.validateMemberRoles(
				List.of(new TeamMemberRole(USER_B, TeamRole.TEAM_LEAD, null)),
				Set.of(USER_A, USER_B)));
	}

	@Test
	void nullMemberRolesIsANoOp() {
		assertDoesNotThrow(() -> UserGroupService.validateMemberRoles(null, Set.of()));
	}

	@Test
	void duplicateRoleForSameUserIsRejected() {
		// getRoleForUser returns a single Optional, so a second entry would be
		// silently dropped -- reject rather than persist a role that never applies.
		assertThrows(RelizaException.class, () -> UserGroupService.validateMemberRoles(
				List.of(new TeamMemberRole(USER_A, TeamRole.QA, null),
						new TeamMemberRole(USER_A, TeamRole.TEAM_LEAD, null)),
				Set.of(USER_A)));
	}

	// ---------- CUSTOM requires a label, checked post-sanitization ----------

	@Test
	void customRoleWithoutLabelIsRejected() {
		assertThrows(RelizaException.class, () -> UserGroupService.validateCustomRole(TeamRole.CUSTOM, null));
		assertThrows(RelizaException.class, () -> UserGroupService.validateCustomRole(TeamRole.CUSTOM, ""));
	}

	@Test
	void customRoleThatSanitizesToEmptyIsRejected() {
		// The order matters: "<script>x</script>" is non-blank on the way in but
		// sanitizes to "", which is exactly the state the CUSTOM rule forbids.
		// Sanitizing first is what makes this reachable.
		List<TeamMemberRole> sanitized = UserGroupData.sanitizeMemberRoles(
				List.of(new TeamMemberRole(USER_A, TeamRole.CUSTOM, "<script>x</script>")));
		assertThrows(RelizaException.class,
				() -> UserGroupService.validateMemberRoles(sanitized, Set.of(USER_A)));
	}

	@Test
	void nonCustomRoleNeedsNoLabel() {
		assertDoesNotThrow(() -> UserGroupService.validateCustomRole(TeamRole.SECURITY_SPECIALIST, null));
	}

	// ---------- external members must stay reachable ----------

	@Test
	void externalMemberWithBlankContactIsRejected() {
		assertThrows(RelizaException.class, () -> UserGroupService.validateExternalMembers(
				List.of(new ExternalTeamMember("Vendor", "  ", TeamRole.DEVELOPER, null))));
	}

	@Test
	void externalMemberThatSanitizesToBlankNameIsRejected() {
		// GraphQL String! stops null but not a pure-markup value that sanitizes away.
		List<ExternalTeamMember> sanitized = UserGroupData.sanitizeExternalMembers(
				List.of(new ExternalTeamMember("<script>x</script>", "ops@vendor.example", TeamRole.QA, null)));
		assertThrows(RelizaException.class, () -> UserGroupService.validateExternalMembers(sanitized));
	}

	@Test
	void wellFormedExternalMemberIsAccepted() {
		assertDoesNotThrow(() -> UserGroupService.validateExternalMembers(
				List.of(new ExternalTeamMember("Vendor Ops", "ops@vendor.example", TeamRole.CUSTOM, "On-call"))));
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.UserGroupData.ExternalTeamMember;
import io.reliza.model.UserGroupData.TeamMemberRole;
import io.reliza.model.dto.UpdateUserGroupDto;

/**
 * Pins the Team-primitive additions (T1: member roles + external members) at the
 * model layer, where the JSONB round-trip actually happens.
 *
 * <p>The headline invariant is the durability one: <strong>external (non-ReARM)
 * members must not count toward the {@code DURABLE_MIN_MEMBERS} bar</strong>
 * (DECIDED 2026-07-28). That holds by construction because externals live
 * outside {@code users}/{@code manualUsers} and {@code getAllUsers()} -- which
 * {@code ComponentOwnershipService.isTeamDurable} counts -- never sees them.
 * It is pinned here so a future refactor that folds externals into the roster
 * fails loudly instead of silently making one-person teams look durable.
 */
class UserGroupTeamMembersTest {

	private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static UserGroupData fromJson(String json) {
		return Utils.OM.readValue(json, UserGroupData.class);
	}

	// ---------- durability: externals are addressable but never durable ----------

	@Test
	void externalMembersDoNotCountTowardTheDurabilityRoster() {
		// One real member + two externals. A naive "count everyone" rule would
		// read 3 and call this durable; the roster must still report exactly 1.
		UserGroupData ugd = fromJson("""
				{"name":"Docs Team","manualUsers":["%s"],
				 "externalMembers":[
				   {"name":"Ext One","contact":"ext1@vendor.example","role":"SECURITY_SPECIALIST"},
				   {"name":"Ext Two","contact":"ext2@vendor.example","role":"DEVELOPER"}]}
				""".formatted(USER_A));
		assertEquals(2, ugd.getExternalMembers().size(), "externals must still be stored and addressable");
		assertEquals(1, ugd.getAllUsers().size(),
				"getAllUsers -- what the durability bar counts -- must exclude external members");
		assertTrue(ugd.getAllUsers().contains(USER_A));
	}

	@Test
	void rosterUsersStillCountNormally() {
		UserGroupData ugd = fromJson("""
				{"name":"Platform","users":["%s"],"manualUsers":["%s"]}
				""".formatted(USER_A, USER_B));
		assertEquals(2, ugd.getAllUsers().size(), "SSO + manual users both count toward durability");
	}

	// ---------- roles are annotations on the roster ----------

	@Test
	void roleLookupResolvesForARosterUserAndIsEmptyOtherwise() {
		UserGroupData ugd = fromJson("""
				{"name":"Platform","manualUsers":["%s"],
				 "memberRoles":[{"userRef":"%s","role":"TEAM_LEAD"}]}
				""".formatted(USER_A, USER_A));
		var role = ugd.getRoleForUser(USER_A);
		assertTrue(role.isPresent());
		assertEquals(TeamRole.TEAM_LEAD, role.get().role());
		assertTrue(ugd.getRoleForUser(USER_B).isEmpty(), "no role recorded -> empty, not a default role");
	}

	@Test
	void roleIsNotReadableForSomeoneNoLongerOnTheRoster() {
		// A role can outlive its member: write-time validation only fires when the
		// caller sends memberRoles, and SSO-driven removals never touch the field.
		// Read-side filtering is what stops escalation targeting an ex-member.
		UserGroupData ugd = fromJson("""
				{"name":"Platform","manualUsers":["%s"],
				 "memberRoles":[{"userRef":"%s","role":"SECURITY_SPECIALIST"}]}
				""".formatted(USER_A, USER_B));
		assertTrue(ugd.getRoleForUser(USER_B).isEmpty(),
				"a stale role for a departed member must not resolve");
		assertEquals(1, ugd.getMemberRoles().size(),
				"the stored annotation is left intact -- only the read is filtered");
	}

	@Test
	void memberRoleCustomLabelIsSanitized() {
		// The external half is not the only freeform sink: memberRoles[].customRole
		// is operator text too and renders into the same team UI.
		List<TeamMemberRole> sanitized = UserGroupData.sanitizeMemberRoles(
				List.of(new TeamMemberRole(USER_A, TeamRole.CUSTOM, "<script>alert(1)</script>Release Captain")));
		assertFalse(sanitized.get(0).customRole().contains("<script"));
		assertTrue(sanitized.get(0).customRole().contains("Release Captain"));
	}

	@Test
	void customRoleCarriesItsOperatorLabel() {
		UserGroupData ugd = fromJson("""
				{"name":"Platform","manualUsers":["%s"],
				 "memberRoles":[{"userRef":"%s","role":"CUSTOM","customRole":"Release Captain"}]}
				""".formatted(USER_A, USER_A));
		var role = ugd.getRoleForUser(USER_A).orElseThrow();
		assertEquals(TeamRole.CUSTOM, role.role());
		assertEquals("Release Captain", role.customRole());
	}

	// ---------- sanitization of operator-supplied freeform text ----------

	@Test
	void sanitizeExternalMembersStripsScriptMarkupButKeepsBenignText() {
		List<ExternalTeamMember> sanitized = UserGroupData.sanitizeExternalMembers(List.of(
				new ExternalTeamMember("<script>alert(1)</script>Vendor",
						"<img src=x onerror=alert(1)>ops@vendor.example",
						TeamRole.CUSTOM, "<script>x</script>On-call")));
		ExternalTeamMember m = sanitized.get(0);
		assertFalse(m.name().contains("<script"), "script markup must not survive into the team UI");
		assertTrue(m.name().contains("Vendor"), "benign text survives");
		assertFalse(m.contact().contains("onerror"));
		assertTrue(m.contact().contains("ops@vendor.example"));
		assertFalse(m.customRole().contains("<script"));
		assertTrue(m.customRole().contains("On-call"));
	}

	@Test
	void sanitizeExternalMembersIsNullSafe() {
		assertNull(UserGroupData.sanitizeExternalMembers(null));
		ExternalTeamMember m = UserGroupData.sanitizeExternalMembers(
				List.of(new ExternalTeamMember(null, null, TeamRole.QA, null))).get(0);
		assertNull(m.name(), "null fields are preserved, not coerced to empty");
		assertNull(m.contact());
		assertEquals(TeamRole.QA, m.role());
	}

	// ---------- update merge: null means keep, non-null means replace ----------

	@Test
	void updateKeepsExistingMembersWhenFieldsOmitted() {
		UserGroupData existing = fromJson("""
				{"name":"Platform","org":"33333333-3333-3333-3333-333333333333","manualUsers":["%s"],
				 "memberRoles":[{"userRef":"%s","role":"QA"}],
				 "externalMembers":[{"name":"Ext","contact":"e@x.example","role":"DEVELOPER"}]}
				""".formatted(USER_A, USER_A));
		UserGroupData merged = UserGroupData.updateUserGroupData(existing,
				UpdateUserGroupDto.builder().name("Platform Renamed").build());
		assertEquals("Platform Renamed", merged.getName());
		assertEquals(1, merged.getMemberRoles().size(), "omitted memberRoles must be preserved");
		assertEquals(1, merged.getExternalMembers().size(), "omitted externalMembers must be preserved");
	}

	@Test
	void updateSanitizesExternalMembersOnWrite() {
		UserGroupData existing = fromJson("""
				{"name":"Platform","org":"33333333-3333-3333-3333-333333333333"}
				""");
		UserGroupData merged = UserGroupData.updateUserGroupData(existing,
				UpdateUserGroupDto.builder()
						.externalMembers(List.of(new ExternalTeamMember(
								"<script>bad</script>Vendor", "ops@vendor.example", TeamRole.DEVELOPER, null)))
						.build());
		assertFalse(merged.getExternalMembers().get(0).name().contains("<script"),
				"sanitization happens on the write path, not on read");
	}

	@Test
	void updateReplacesMemberRolesWhenSupplied() {
		UserGroupData existing = fromJson("""
				{"name":"Platform","org":"33333333-3333-3333-3333-333333333333","manualUsers":["%s","%s"],
				 "memberRoles":[{"userRef":"%s","role":"QA"}]}
				""".formatted(USER_A, USER_B, USER_A));
		UserGroupData merged = UserGroupData.updateUserGroupData(existing,
				UpdateUserGroupDto.builder()
						.memberRoles(List.of(new TeamMemberRole(USER_B, TeamRole.TEAM_LEAD, null)))
						.build());
		assertEquals(1, merged.getMemberRoles().size());
		assertEquals(USER_B, merged.getMemberRoles().get(0).userRef(), "supplied list replaces wholesale");
		assertTrue(merged.getRoleForUser(USER_A).isEmpty());
	}
}

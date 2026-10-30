/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * OrganizationService.combinePermissions, the one merge loop users and FREEFORM keys share (TPF-91
 * architecture-5 row 12): plain JUnit, no context.
 */
class OrganizationServiceCombinePermissionsTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID A = UUID.randomUUID();
	private static final UUID B = UUID.randomUUID();

	private static UserPermission board(UUID board, PermissionType type, List<PermissionFunction> functions, List<String> approvals) {
		return UserPermission.permissionFactory(ORG, PermissionScope.BOARD, board, type, functions, approvals);
	}

	private static UserPermission only(Set<UserPermission> set, UUID object) {
		List<UserPermission> found = set.stream().filter(p -> object.equals(p.getObject())).toList();
		assertEquals(1, found.size(), set.toString());
		return found.get(0);
	}

	private static Set<UserPermission> combine(Set<UserPermission> own, List<Set<UserPermission>> groups) {
		return OrganizationService.combinePermissions(own, ORG, groups).getOrgPermissionsAsSet(ORG);
	}

	@Test
	void theHigherTypeWinsOnTheSameTriple() {
		Set<UserPermission> c = combine(Set.of(board(A, PermissionType.READ_ONLY, null, null)),
				List.of(Set.of(board(A, PermissionType.READ_WRITE, null, null))));
		assertEquals(PermissionType.READ_WRITE, only(c, A).getType());
		Set<UserPermission> admin = combine(Set.of(board(A, PermissionType.ADMIN, null, null)),
				List.of(Set.of(board(A, PermissionType.READ_ONLY, null, null))));
		assertEquals(PermissionType.ADMIN, only(admin, A).getType(), "own ADMIN plus a group READ_ONLY stays ADMIN");
	}

	@Test
	void functionsAndApprovalsAreUnioned() {
		Set<UserPermission> c = combine(Set.of(board(A, PermissionType.READ_ONLY, List.of(PermissionFunction.BOARD_READ), List.of("QA"))),
				List.of(Set.of(board(A, PermissionType.READ_ONLY, List.of(PermissionFunction.BOARD_WRITE), List.of("SEC")))));
		assertEquals(Set.of(PermissionFunction.BOARD_READ, PermissionFunction.BOARD_WRITE), only(c, A).getFunctions());
		assertEquals(Set.of("QA", "SEC"), only(c, A).getApprovals());
	}

	@Test
	void aGroupOnlyTripleIsAdded() {
		Set<UserPermission> c = combine(Set.of(board(A, PermissionType.READ_ONLY, null, null)),
				List.of(Set.of(board(B, PermissionType.READ_WRITE, List.of(PermissionFunction.BOARD_READ), null))));
		assertEquals(2, c.size());
		assertEquals(PermissionType.READ_ONLY, only(c, A).getType());
		assertEquals(PermissionType.READ_WRITE, only(c, B).getType());
	}

	@Test
	void noGroupsReturnsOwnUnchanged() {
		Set<UserPermission> own = Set.of(board(A, PermissionType.READ_ONLY, List.of(PermissionFunction.BOARD_READ), List.of("QA")),
				board(B, PermissionType.ADMIN, null, null));
		assertEquals(own, combine(own, List.of()));
		assertEquals(own, combine(own, null));
	}

	@Test
	void nullFunctionsOrApprovalsOnEitherSideDoNotThrow() {
		UserPermission ownNulls = board(A, PermissionType.READ_ONLY, null, null);
		UserPermission groupNulls = board(A, PermissionType.READ_WRITE, null, null);
		Set<UserPermission> c = combine(Set.of(ownNulls), List.of(Set.of(groupNulls)));
		assertEquals(PermissionType.READ_WRITE, only(c, A).getType());
		assertEquals(Set.of(), only(c, A).getFunctions());
		assertEquals(Set.of(), only(c, A).getApprovals());
		assertEquals(Set.of(groupNulls), combine(null, List.of(Set.of(groupNulls))));
	}

	@Test
	void theInputsAreNotModified() {
		UserPermission own = board(A, PermissionType.READ_ONLY, List.of(PermissionFunction.BOARD_READ), null);
		combine(Set.of(own), List.of(Set.of(board(A, PermissionType.ADMIN, List.of(PermissionFunction.BOARD_WRITE), List.of("QA")))));
		assertEquals(PermissionType.READ_ONLY, own.getType());
		assertEquals(Set.of(PermissionFunction.BOARD_READ), own.getFunctions());
		assertSame(PermissionScope.BOARD, own.getScope());
	}
}

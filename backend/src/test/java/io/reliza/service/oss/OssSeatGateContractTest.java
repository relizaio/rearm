/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.service.SeatGate.Change;
import io.reliza.service.SeatGate.SsoAdds;

/**
 * This edition does not limit user seats: {@link OssSeatGate} admits every change and every account,
 * counts nothing and refuses a sign-in only for the cause it is given. Plain unit test, no Spring context.
 */
class OssSeatGateContractTest {

	private final OssSeatGate gate = new OssSeatGate();
	private final UUID org = UUID.randomUUID();

	private UserData user() {
		return UserData.userDataFactory("gate", "gate@example.com", List.of(org), "sub", OauthType.RELIZA_KEYCLOAK_OWN, true);
	}

	/** A group that grants an organization-wide ADMIN role, so a gate that limits seats would have to judge it. */
	private UserGroupData adminGroup(String name) {
		UserGroupData g = UserGroupData.userGroupDataFactory(CreateUserGroupDto.builder().name(name).org(org).build());
		g.setPermission(PermissionScope.ORGANIZATION, org, PermissionType.ADMIN, List.of());
		return g;
	}

	@Test
	void aSeatChangeHasAHandleAndEveryCheckPasses() {
		Change change = gate.beginSeatChange();
		assertNotNull(change);
		UserGroupData group = adminGroup("g");
		assertDoesNotThrow(() -> {
			gate.assertWouldFit(change, "gate@example.com", PermissionType.ADMIN);
			gate.assertNewMemberFits(change, "gate@example.com", org, List.of(group));
			gate.assertFitsAfterWrite(change);
			gate.assertAdmitted(user());
			gate.noRoleRefused(org, "gate@example.com");
			gate.forgetSkippedLift(UUID.randomUUID(), UUID.randomUUID());
		});
	}

	@Test
	void everySsoAddIsAdmitted() {
		SsoAdds adds = gate.ssoAdds(gate.beginSeatChange(), user(), org);
		for (int i = 0; i < 3; i++) {
			assertTrue(adds.admit(adminGroup("g" + i)));
		}
	}

	@Test
	void noAccountIsOutsideTheSeatsAndNoLiftIsSkipped() {
		assertFalse(gate.outsideLicense(user()));
		assertTrue(gate.skippedLiftsOf(UUID.randomUUID()).isEmpty());
	}

	@Test
	void aRefusedNewAccountIsRefusedForItsCause() {
		ActionRefusedException cause = new ActionRefusedException("no role");
		assertSame(cause, gate.newAccountRefusal(org, "gate@example.com", cause));
	}
}

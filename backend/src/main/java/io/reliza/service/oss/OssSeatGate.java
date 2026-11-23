/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.exceptions.ActionRefusedException;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.SeatGate;

/**
 * The seat gate of this edition, which does not limit user seats: every change fits, every account is
 * admitted and nothing is locked or counted.
 */
@Service
public class OssSeatGate implements SeatGate {

	private static final Change NO_CHANGE = new Change() {};

	@Override
	public Change beginSeatChange() {
		return NO_CHANGE;
	}

	@Override
	public void assertFitsAfterWrite(Change change) {
	}

	@Override
	public void assertWouldFit(Change change, String key, PermissionType type) {
	}

	@Override
	public void assertNewMemberFits(Change change, String key, UUID org, Collection<UserGroupData> groups) {
	}

	@Override
	public void assertAdmitted(UserData user) {
	}

	@Override
	public boolean outsideLicense(UserData user) {
		return false;
	}

	@Override
	public Set<UUID> skippedLiftsOf(UUID user) {
		return Set.of();
	}

	@Override
	public void forgetSkippedLift(UUID user, UUID group) {
	}

	@Override
	public SsoAdds ssoAdds(Change change, UserData user, UUID org) {
		return group -> true;
	}

	/** No seat limit refuses a sign-in here, so the refusal is the cause itself. */
	@Override
	public RuntimeException newAccountRefusal(UUID eventOrg, String email, ActionRefusedException cause) {
		return cause;
	}

	@Override
	public void noRoleRefused(UUID org, String email) {
	}
}

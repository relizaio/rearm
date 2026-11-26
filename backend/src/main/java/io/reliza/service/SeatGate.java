/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import io.reliza.exceptions.ActionRefusedException;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;

/**
 * The user-seat checks the shared services call wherever a write can grant a user a seat, and on every
 * authenticated request. Each edition provides exactly one bean: an edition that limits seats judges
 * the change, an edition that does not lets everything through.
 *
 * <h3>A required bean</h3>
 * Unlike the optional {@code *Hook} extension points ({@code @Autowired(required = false)}, skipped
 * when absent), the gate is required: a licensing control must fail closed. The injection points are
 * {@code @Lazy}, so an edition without a gate still boots, then fails every signed-in request and
 * every user API key request at the first gate call, instead of silently admitting everyone.
 *
 * <h3>Where an implementation lives</h3>
 * The open-source edition's gate must live under an {@code oss/} package. The sync that mirrors the
 * shared sources deletes every destination file it does not carry, except under edition-specific
 * packages such as {@code oss/} and in the test files it excludes, so a gate anywhere else is deleted.
 *
 * <h3>Lock order</h3>
 * {@link #beginSeatChange()} may take a global lock. Call it before the transaction's first row lock or
 * write; a nested call in the same transaction returns the handle it bound. Never call a method that
 * opens a REQUIRES_NEW transaction which begins a seat change while holding the handle: it would wait
 * for its own caller.
 */
public interface SeatGate {

	/** The handle of one transaction's seat change; opaque to the shared code. */
	interface Change {}

	/**
	 * The judge of the SSO group adds of one user in one organization, for one locked sync. Ask it
	 * right before each add, in order: it remembers what it admitted.
	 */
	interface SsoAdds {
		/** Whether the add into {@code group} may go ahead; false when the seat is refused or cannot be judged. */
		boolean admit(UserGroupData group);
	}

	/**
	 * Begins a seat change, before the transaction's first row lock or write (see Lock order). Never
	 * null: an edition that does not limit seats returns a no-op handle.
	 */
	Change beginSeatChange();

	/** Refuses the transaction's write when the users it now grants seats to exceed what is allowed. */
	void assertFitsAfterWrite(Change change);

	/** Refuses, before anything is written, the person {@code key} getting the seat {@code type} grants. */
	void assertWouldFit(Change change, String key, PermissionType type);

	/**
	 * Refuses, before anything is written, a new account {@code key} joining {@code org} through
	 * {@code groups}: judged on the highest seat those groups grant, none when they grant no role.
	 */
	void assertNewMemberFits(Change change, String key, UUID org, Collection<UserGroupData> groups);

	/** Refuses an account outside the allowed number of users, on every request and at sign-in. */
	void assertAdmitted(UserData user);

	/** Whether the account is outside the allowed number of users (a key or session it owns stops working). */
	boolean outsideLicense(UserData user);

	/**
	 * The groups whose SSO add was refused for this user on this pod, so the sync does not retry them.
	 * Never null: an empty set when there are none. The SSO sync reads it on every authenticated
	 * request, and a null would stop that organization's sync, removals included.
	 */
	Set<UUID> skippedLiftsOf(UUID user);

	/** Forgets a refused SSO add once the user is a member of that group after all. */
	void forgetSkippedLift(UUID user, UUID group);

	/** The judge of one locked sync's SSO adds for {@code user} in {@code org}; never null. */
	SsoAdds ssoAdds(Change change, UserData user, UUID org);

	/**
	 * What a first sign-in refused by {@code cause} becomes: the exception thrown to the person, never
	 * null ({@code cause} itself where nothing else applies). {@code eventOrg} is the organization whose
	 * administrators hear of it.
	 */
	RuntimeException newAccountRefusal(UUID eventOrg, String email, ActionRefusedException cause);

	/**
	 * A first sign-in with no role was refused because {@code org} refuses accounts without a role; the
	 * caller throws {@code AccountRefusedException.noRole()} after this. For the
	 * organization's administrators to hear of it.
	 */
	void noRoleRefused(UUID org, String email);
}

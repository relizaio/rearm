/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import io.reliza.model.AttestationData;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.ReleaseData;

/**
 * Optional Pro-side half of locks: raising one from a rule, and letting an attestation resolve it.
 *
 * <p>Locks themselves are CE -- the model, the enforcement and raising one by hand. What is Pro is
 * the policy: a rule that raises a lock when a build fails it, and the automatic release when the
 * causes are all claimed. Same line the agent stack draws, and the same optional-bean shape
 * {@code AgentPolicyHook} and {@code ActionGuardHook} use, so CE keeps working with no bean.
 */
public interface PolicyLockHook {

	/**
	 * Raise or extend the lock a fired LOCK output event asks for. The causes are the release
	 * plus every commit in it that nobody is accountable for.
	 */
	void raiseFromTrigger(ReleaseData rd, ReleaseOutputEvent trigger);

	/**
	 * Re-evaluate every active lock this attestation could resolve, releasing the ones whose
	 * requirement is now met and which are allowed to release themselves.
	 *
	 * @return how many locks were released
	 */
	int onAttestation(AttestationData attestation);
}

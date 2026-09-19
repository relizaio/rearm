/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.UUID;

/**
 * Optional Pro-side release trigger engine.
 *
 * <p>Triggers decide when something happens on its own -- a lifecycle promotion, a PR comment, a
 * VDR snapshot -- from input events, approval policies and CEL rules. CE has none of that: no
 * approval entries, no approval policies, no output-event model. So the engine is a Pro bean
 * reached through this interface, and CE ships the interface with no implementation.
 *
 * <p>That is the point of the indirection. The call site in {@code OssReleaseService} is a null
 * check that reads the same in both repositories, instead of ~600 lines of engine that the CE sync
 * had to empty by hand on every pass -- which is exactly the kind of manual step that goes wrong
 * quietly.
 *
 * <p>The implementation is expected to join the caller's transaction, not start its own: the
 * scheduled metrics recompute calls {@code processRelease} with no ambient transaction, and
 * {@code processRelease} is the transaction boundary above the MANDATORY change hooks the engine
 * reaches.
 */
public interface ReleaseTriggerHook {

	/**
	 * What a pass of the trigger engine is allowed to do.
	 *
	 * <p>{@code NORMAL} is the engine reacting to a change, and it lets the repeatable output
	 * kinds repeat: a VDR snapshot is meant to be taken again each time its rule matches, which is
	 * why it is exempt from the fired-once check.
	 *
	 * <p>{@code REEVALUATION} is somebody asking for another look at a release that has not
	 * changed, and it must not manufacture work. The repeatable kinds are skipped entirely, so the
	 * only outputs that can fire are those never recorded as fired -- which is exactly the set a
	 * guard withheld. Without this, every press of Re-evaluate on a release whose rule still
	 * matches would mint another snapshot artifact.
	 */
	public enum TriggerPass { NORMAL, REEVALUATION }

	/**
	 * Evaluate this release's triggers and fire whatever they entail.
	 *
	 * @param releaseUuid the release to evaluate
	 * @param pass which pass is asking; see {@link TriggerPass}
	 */
	void processRelease(UUID releaseUuid, TriggerPass pass);
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Lifecycle status of a {@link Team}.
 *
 * <p>Deliberately its own enum rather than a reuse of
 * {@code CommonVariables.UserGroupStatus}. The whole point of the Team entity is
 * that a team is not a permission group (see
 * {@code ai-plans/team-entity-design.md}); sharing a status enum would put a
 * compile-time edge back between them and invite exactly the coupling this work
 * removes. The two happen to have the same members today; that is a coincidence,
 * not a contract.
 *
 * <p>INACTIVE is a soft archive: the team keeps its roster and channels but is
 * not a delivery target and is hidden from pickers. Phase 2's resolver drops it
 * for the same reason the UserGroup one does -- an operator who archives a team
 * reasonably expects notifications to stop.
 */
public enum TeamStatus {
	ACTIVE,
	INACTIVE;
}

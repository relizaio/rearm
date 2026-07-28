/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Computed ownership state of a component (RFC Phase 4, sec. 10.2/sec. 10.3). This is a
 * <em>pure recompute</em> -- never stored -- so setting an owner recomputes to
 * {@code OWNED} with no auto-clear step. Split so a deliberate USER/tiny-team
 * owner does not read as an alarming error, distinct from a degraded or truly
 * orphaned one.
 *
 * <ul>
 *   <li>{@code OWNED} -- stored owner, valid and durable.</li>
 *   <li>{@code NON_DURABLE} -- stored + valid but by-design non-durable: a USER
 *       owner, or an ACTIVE team below the durability bar (fewer than
 *       {@code DURABLE_MIN_MEMBERS} direct members and not SSO-backed).</li>
 *   <li>{@code DEGRADED} -- stored ref resolves but is weakened: the owner team
 *       is archived / INACTIVE.</li>
 *   <li>{@code UNSET} -- no stored owner, but a candidate owner-team is derivable
 *       (a backfill suggestion is available).</li>
 *   <li>{@code ORPHANED} -- stored ref no longer resolves / crosses org, OR no
 *       stored owner and nothing derivable. Needs a human.</li>
 * </ul>
 *
 * None of these ever blocks a release (sec. 9 decision) -- they drive a non-blocking
 * banner and the at-risk report only.
 */
public enum ComponentOwnershipStatus {
	OWNED,
	NON_DURABLE,
	DEGRADED,
	UNSET,
	ORPHANED;
}

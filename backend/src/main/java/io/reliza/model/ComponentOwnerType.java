/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The kind of durable owner a component points at (RFC Phase 4, sec. 10.2).
 *
 * <ul>
 *   <li>{@code TEAM} -- a {@link UserGroup} (the Phase-3 Team primitive). The
 *       preferred, durable form: a roster survives any individual leaving.</li>
 *   <li>{@code USER} -- a single registered user. An escape hatch for
 *       personal/experimental components; always resolves as <em>non-durable</em>
 *       ("will orphan on departure") and is surfaced as such in the at-risk
 *       report.</li>
 * </ul>
 */
public enum ComponentOwnerType {
	TEAM,
	USER;
}

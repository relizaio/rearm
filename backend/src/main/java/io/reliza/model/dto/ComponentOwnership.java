/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.model.ComponentOwnerType;
import io.reliza.model.ComponentOwnershipStatus;

/**
 * Resolved ownership view of a component (RFC Phase 4, sec. 10.3) -- the return of
 * {@code ComponentOwnershipService.resolveOwnership}. Purely computed, never
 * persisted; projected 1:1 onto the GraphQL {@code type ComponentOwnership}
 * (record accessor names match the schema field names, so DGS binds them). The
 * name matches the GraphQL type on purpose.
 *
 * @param ownerType  the stored owner's type, or the suggested team's type when
 *                   {@code derived}; null when unowned and only a "create a team"
 *                   hint is available.
 * @param ownerRef   the stored owner ref, or the suggested team's uuid when
 *                   {@code derived}; null when there is nothing concrete to point at.
 * @param durable    whether the (stored or suggested) owner is durable.
 * @param status     the {@link ComponentOwnershipStatus}.
 * @param derived    true when this is a suggestion (no stored owner), false when
 *                   it reflects a stored owner.
 * @param reason     human-readable explanation for the UI badge / at-risk report.
 */
public record ComponentOwnership(
		ComponentOwnerType ownerType,
		UUID ownerRef,
		boolean durable,
		ComponentOwnershipStatus status,
		boolean derived,
		String reason) {}

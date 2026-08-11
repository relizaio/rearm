/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.model.ComponentData.ComponentType;

/**
 * One row of the org durable-ownership report (RFC Phase 4c, sec. 10.5): a
 * non-OWNED component plus its computed {@link ComponentOwnership}. Projected
 * onto the GraphQL {@code type ComponentOwnershipReportRow} (accessor names match
 * schema fields). Read-only governance view -- never blocks releases.
 */
public record ComponentOwnershipReportRow(
		UUID componentUuid,
		String componentName,
		ComponentType componentType,
		ComponentOwnership ownership) {}

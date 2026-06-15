/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

/**
 * Role of a {@link Party} on a hardware deliverable / HBOM component. Aligned
 * with the CycloneDX 2.0 generic parties model (spec PR #930). Extensible —
 * use OTHER for roles we don't model yet.
 */
public enum PartyRole {
	MANUFACTURER,
	ASSEMBLER,
	SUPPLIER,
	QUALITY_CONTROL,
	DISTRIBUTOR,
	OTHER;
}

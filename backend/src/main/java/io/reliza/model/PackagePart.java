/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/** A document kind a regulatory package part generator produces from a release (tasks TEA-29, TEA-33), in document order. */
public enum PackagePart {
	AGGREGATE_SBOM, VDR, VEX, SUPPORT_ADDENDUM, DEVICE_SUPPORT_STATEMENT, NARRATIVE, CLE, RAW_ARTIFACTS
}

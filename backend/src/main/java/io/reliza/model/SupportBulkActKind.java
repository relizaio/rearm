/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * What a bulk support act wrote, and so which skip rule its sweep ran under (FDAW-14 3.2a). Stored by
 * name in {@code support_bulk_acts.kind}.
 */
public enum SupportBulkActKind {
	/** An assessment sweep: a level or a milestone date; an attested row is skipped whole. */
	LEVEL_OF_SUPPORT,
	/** An end-of-support sweep: the justified unknown alone; only rows holding an end of support are skipped. */
	END_OF_SUPPORT_UNKNOWN,
	/** Hash justifications on one release SBOM head; justified keys are skipped by a selector sweep. */
	HASH_JUSTIFICATION
}

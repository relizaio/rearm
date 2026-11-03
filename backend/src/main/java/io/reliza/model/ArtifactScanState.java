/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * The synthetic scan state of one artifact as the browser query {@code artifactScanStatus} explains it
 * (TPF-121, SyntheticSbomService.explainArtifactScanStatus). Exact simple name and values of the schema
 * enum in user.graphqls (GraphQlSchemaEnumSyncTest).
 */
public enum ArtifactScanState {
	/** The artifact is not a BOM: no synthetic scan runs on its type. */
	NOT_A_BOM,
	/** A BOM without a canonical map row: the post-upload reconcile has not mapped it yet. */
	UNMAPPED,
	/** Unscanned, and at least one gate-matchable component is not covered by an INGESTED bucket. */
	BLOCKED_ON_COVERAGE,
	/** Unscanned with the coverage gate clear: a fan-out pass has not applied the findings yet. */
	WAITING_FOR_FAN_OUT,
	/** metrics.firstScanned is set: a fan-out pass applied findings (possibly none) to the artifact. */
	SCANNED,
	/**
	 * A mapped BOM whose canonical has no gate-matchable component (see NoMatchableComponentsReason); the
	 * meaning TPF-125/TPF-126 store as the not-scannable marker.
	 */
	NO_MATCHABLE_COMPONENTS
}

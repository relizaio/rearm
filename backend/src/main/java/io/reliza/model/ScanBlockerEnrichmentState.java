/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * The enrichment state of a component on an artifactScanStatus blocker row (TPF-121). Exact simple name and
 * values of the schema enum in user.graphqls (GraphQlSchemaEnumSyncTest).
 */
public enum ScanBlockerEnrichmentState {
	/** sbom_components.enriched_at is null. */
	UNENRICHED,
	/** sbom_components.enriched_at is set. */
	ENRICHED,
	/**
	 * Enrichment-terminal (flow_control.enrichmentTerminalAt set). Never on a blocker today, since the gate
	 * excludes terminal components; kept so excluded rows can be listed later without a schema change.
	 */
	TERMINAL
}

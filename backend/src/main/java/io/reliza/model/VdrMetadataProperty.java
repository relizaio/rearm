/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Enum representing VDR metadata property names.
 * Uses uppercase snake case for GraphQL compatibility.
 */
public enum VdrMetadataProperty {
	/**
	 * Indicates whether this VDR is a historical snapshot
	 */
	VDR_SNAPSHOT,
	
	/**
	 * The cutoff date for the snapshot, an RFC-3339 UTC instant ({@code 2024-01-02T02:04:05Z})
	 */
	VDR_CUTOFF_DATE,
	
	/**
	 * The type of snapshot (DATE, LIFECYCLE, or APPROVAL)
	 */
	VDR_SNAPSHOT_TYPE,
	
	/**
	 * The value associated with the snapshot type (lifecycle name or approval name)
	 */
	VDR_SNAPSHOT_VALUE,

	/**
	 * Marks the document as a CycloneDX VEX (Vulnerability Exploitability eXchange) rather than a VDR.
	 * Consumers can key off this metadata property to distinguish VEX from VDR output when both are emitted
	 * from the same producer. Value is always "true" when present.
	 */
	VEX_DOCUMENT,

	/**
	 * Whether the vulnerability analysis this document reports on has happened (FDAW-2), always
	 * present: {@code COMPLETED} (every release unit analysed), {@code PARTIAL} (some release units
	 * analysed, some carry no scannable SBOM; the counts say how many) or {@code NOT_ANALYSED}
	 * (see {@link #VDR_ANALYSIS_REASON}). An empty vulnerabilities list means "no vulnerabilities"
	 * only when this says COMPLETED. See {@link VdrAnalysisState}.
	 */
	VDR_ANALYSIS_STATE,

	/**
	 * Why the document is NOT_ANALYSED, only then: {@code SCAN_PENDING} (a first scan has not
	 * completed), {@code NO_SBOM} (nothing to analyse) or {@code SBOM_NOT_SCANNABLE} (SBOMs without
	 * matchable components).
	 */
	VDR_ANALYSIS_REASON,

	/**
	 * When the analysis completed: the latest real first scan among the analysed release units, an
	 * RFC-3339 UTC instant; only when COMPLETED or PARTIAL, never a release's creation date.
	 */
	VDR_ANALYSIS_COMPLETED_AT,

	/** Release units analysed, only when PARTIAL. */
	VDR_ANALYSIS_RELEASES_ANALYSED,

	/** Release units with no scannable SBOM, only when PARTIAL. */
	VDR_ANALYSIS_RELEASES_NOT_ANALYSED,

	/**
	 * The known-exploited-vulnerability catalog the findings' KEV flags come from, always present:
	 * {@code CISA}, {@code VULNCHECK} or {@code NONE} (no enabled KEV integration).
	 */
	VDR_KEV_CATALOG_SOURCE,

	/**
	 * The catalog's release date, an RFC-3339 UTC instant; only when the source publishes one (CISA's
	 * dateReleased; VulnCheck publishes none).
	 */
	VDR_KEV_CATALOG_DATE,

	/**
	 * When the organisation last synced that catalog successfully, an RFC-3339 UTC instant; present
	 * once a sync has stamped it.
	 */
	VDR_KEV_CATALOG_SYNCED_AT;

	/** The {@link #VDR_KEV_CATALOG_SOURCE} value when the organisation has no enabled KEV integration. */
	public static final String NO_KEV_SOURCE = "NONE";
	
	
	VdrMetadataProperty() {}
}

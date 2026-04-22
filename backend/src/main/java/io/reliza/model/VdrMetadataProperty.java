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
	 * The cutoff date for the snapshot (ISO 8601 format)
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
	VEX_DOCUMENT;
	
	
	VdrMetadataProperty() {}
}

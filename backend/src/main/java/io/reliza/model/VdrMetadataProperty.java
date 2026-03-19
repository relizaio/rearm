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
	VDR_SNAPSHOT("vdr:snapshot"),
	
	/**
	 * The cutoff date for the snapshot (ISO 8601 format)
	 */
	VDR_CUTOFF_DATE("vdr:cutoffDate"),
	
	/**
	 * The type of snapshot (DATE, LIFECYCLE, or APPROVAL)
	 */
	VDR_SNAPSHOT_TYPE("vdr:snapshotType"),
	
	/**
	 * The value associated with the snapshot type (lifecycle name or approval name)
	 */
	VDR_SNAPSHOT_VALUE("vdr:snapshotValue");
	
	private final String propertyName;
	
	VdrMetadataProperty(String propertyName) {
		this.propertyName = propertyName;
	}
	
	public String getPropertyName() {
		return propertyName;
	}
	
	@Override
	public String toString() {
		return propertyName;
	}
}

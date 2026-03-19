/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Enum representing the type of VDR snapshot being generated.
 * Used for metadata to indicate what kind of historical snapshot was requested.
 */
public enum VdrSnapshotType {
	/**
	 * Snapshot at a specific date/time
	 */
	DATE,
	
	/**
	 * Snapshot at a specific lifecycle stage
	 */
	LIFECYCLE,
	
	/**
	 * Snapshot at a specific approval event (SaaS only)
	 */
	APPROVAL
}

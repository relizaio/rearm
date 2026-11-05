/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * What can be unusual about a declared device support window (FDAW-3, B3). Same simple
 * name as the GraphQL enum, which GraphQlSchemaEnumSyncTest resolves by name.
 */
public enum DeviceWindowWarningCode {
	/**
	 * End of support precedes end of life. Under IMDRF N70 end of life is the last sale date
	 * and normally comes first, so units would be sold after the last security update.
	 */
	EOS_BEFORE_EOL
}

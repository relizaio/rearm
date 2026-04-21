/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Response values for an affecting vulnerability analysis, mirroring
 * CycloneDX 1.7 {@code vulnerabilities[].analysis.response} enum.
 * These declare the action (if any) the supplier has taken or plans to take
 * against the vulnerability (CISA VEX "action statement").
 */
public enum AnalysisResponse {
	CAN_NOT_FIX,
	WILL_NOT_FIX,
	UPDATE,
	ROLLBACK,
	WORKAROUND_AVAILABLE
}

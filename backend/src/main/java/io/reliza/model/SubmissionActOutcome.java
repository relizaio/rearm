/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * What a one-click submission act did (tasks TEA-29, TEA-35). CANCELLED is a withdrawal of a FINAL
 * head since FDAW-8; WITHDRAWN stays for the acts recorded before it and is written by nothing now.
 */
public enum SubmissionActOutcome {
	CREATED, RESUBMITTED, WITHDRAWN, UNCHANGED, CANCELLED
}

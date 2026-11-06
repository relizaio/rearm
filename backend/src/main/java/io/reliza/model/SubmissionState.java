/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The state of one submission version (tasks TEA-29, TEA-35; FDAW-1 D2). Per version, derived from
 * the submission acts and materialised on the head ({@link SubmissionData#getVersionStates()}); the
 * head's column holds the latest version's. FINAL and CANCELLED replace the ACTIVE and WITHDRAWN of
 * before FDAW-8 (V107); SENT, AMENDED, SUPERSEDED and WITHDRAWN (of a SENT version) follow the acts
 * that write them.
 */
public enum SubmissionState {
	FINAL, SENT, AMENDED, SUPERSEDED, WITHDRAWN, CANCELLED
}

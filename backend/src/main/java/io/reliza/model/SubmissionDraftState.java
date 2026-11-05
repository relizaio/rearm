/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The state of a submission draft, the working copy before a version is final (FDAW-1 D2). Not in the
 * GraphQL schema yet: no field carries it until the draft acts (FDAW-1 A3) add one, and an unreachable
 * schema type is refused (SchemaParsesTest).
 */
public enum SubmissionDraftState {
	EDITING, AWAITING_CONFIRMATION
}

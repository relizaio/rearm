/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/** The kind of one row of the append-only submission act record (FDAW-1 D2). */
public enum SubmissionActKind {
	START_DRAFT, DISCARD_DRAFT, RETURN_TO_DRAFT, CONFIRM, FINALIZE, RECORD_SENT, FDA_NUMBER_ADDED,
	TIMELINE_ENTRY, AMENDED, SUPERSEDED, WITHDRAW, CANCEL, MIGRATED
}

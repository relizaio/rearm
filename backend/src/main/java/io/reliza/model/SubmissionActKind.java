/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** The kind of one row of the append-only submission act record (FDAW-1 D2). */
public enum SubmissionActKind {
	START_DRAFT, DISCARD_DRAFT, RETURN_TO_DRAFT, CONFIRM, FINALIZE, RECORD_SENT, FDA_NUMBER_ADDED,
	TIMELINE_ENTRY, AMENDED, SUPERSEDED, WITHDRAW, CANCEL, MIGRATED;

	/**
	 * The acts of a draft in progress, which only a signed-in reader sees (FDAW-29 3.3b). FINALIZE names
	 * its draft too, but it is the head's history and stays out of this list.
	 */
	public static final Set<SubmissionActKind> DRAFT_LANE = Collections.unmodifiableSet(
			EnumSet.of(START_DRAFT, CONFIRM, RETURN_TO_DRAFT, DISCARD_DRAFT));
}

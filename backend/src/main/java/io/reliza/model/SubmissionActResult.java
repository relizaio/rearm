/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The answer of a submission act (task TEA-35): the head as the act left it and the version it
 * wrote, null for a withdrawal and for UNCHANGED.
 */
public record SubmissionActResult(SubmissionActOutcome outcome, SubmissionData head, SubmissionVersionData version) {

	/** The wire name of {@link #head()}: {@code SubmissionActResult.submission}. */
	public SubmissionData submission() {
		return head;
	}
}

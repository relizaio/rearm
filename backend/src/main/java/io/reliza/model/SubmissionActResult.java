/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The answer of a submission act (task TEA-35): the head as the act left it and the version it wrote,
 * null for a withdrawal and for UNCHANGED. A draft act (FDAW-1 A3) answers the act row it wrote and the
 * draft as committed; its head is null while no version exists and its {@code outcome} is null. The
 * outcome goes with the one-click acts, whose three-argument form it serves.
 */
public record SubmissionActResult(SubmissionActOutcome outcome, SubmissionData head, SubmissionVersionData version,
		SubmissionActData act, SubmissionDraftData draft) {

	public SubmissionActResult(SubmissionActOutcome outcome, SubmissionData head, SubmissionVersionData version) {
		this(outcome, head, version, null, null);
	}

	/** The wire name of {@link #head()}: {@code SubmissionActResult.submission}. */
	public SubmissionData submission() {
		return head;
	}
}

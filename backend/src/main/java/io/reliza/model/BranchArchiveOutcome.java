/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * What a bulk archive did to one branch (or feature set).
 *
 * <p>An enum on the wire rather than a count, so the caller can show WHICH rows did not go and
 * why: the base branch is skipped by rule, a forbidden or failed row needs attention.
 */
public enum BranchArchiveOutcome {

	/** The status was set to ARCHIVED and one audit row was written. */
	ARCHIVED,

	/** The component's base branch, which archiveBranch refuses; nothing was written. */
	SKIPPED_BASE,

	/** Already ARCHIVED; nothing was written, so a re-run pads no history. */
	SKIPPED_ARCHIVED,

	/**
	 * No branch with this uuid that the caller can see. A branch of an organization the caller
	 * is not a member of answers this too, so the outcome list is no existence oracle.
	 */
	NOT_FOUND,

	/** A branch in one of the caller's organizations that the caller may not write. */
	FORBIDDEN,

	/**
	 * The write failed; the message says why. Isolated in its own transaction, so the batch
	 * continued and nothing archived before or after it was undone.
	 */
	FAILED
}

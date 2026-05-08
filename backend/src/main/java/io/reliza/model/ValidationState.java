/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Outcome of a single validation event — used both for inbound release
 * validation results (one release's contribution to a PR) and for
 * outbound PR-level validations dispatched to the SCM (e.g. GitHub
 * check-run conclusion).
 *
 * The aggregator treats these as a partial order:
 *   FAILURE > PENDING > SUCCESS > NEUTRAL > SKIPPED
 *
 * - SUCCESS / FAILURE / NEUTRAL / SKIPPED / CANCELLED are valid GitHub
 *   check-run conclusions and dispatch to the SCM.
 * - PENDING is an internal "verdict not yet computable" state used
 *   when at least one release attributed to the PR has no validation
 *   event yet. Recorded on pr_validation_events for the audit trail
 *   but never dispatched to the SCM (GitHub doesn't have a matching
 *   conclusion — the prior terminal verdict's check-run stays in
 *   place until a new terminal verdict supersedes it).
 *
 * CANCELLED is treated as an implicit FAILURE for any release that
 * participates in PR aggregation (a cancelled release means the
 * change associated with this PR is not viable on this head).
 */
public enum ValidationState {
	SUCCESS,
	FAILURE,
	NEUTRAL,
	SKIPPED,
	CANCELLED,
	PENDING
}

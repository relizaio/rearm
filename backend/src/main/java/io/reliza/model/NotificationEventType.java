/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Producer-side event type emitted into {@link NotificationOutboxEvent}.
 * v1 scope — three producers only (see §4.1 of the design doc); future
 * event types are added by inserting enum constants and updating producer
 * code, no schema migration needed.
 */
public enum NotificationEventType {
	/** New vulnerability record affects one or more existing releases. */
	NEW_VULN_AFFECTS_RELEASES,
	/** Existing vulnerability record changed (severity bump, KEV listing added, EPSS spike). */
	VULNERABILITY_RECORD_UPDATED,
	/** VEX statement transitioned between affected / not_affected / under_investigation / fixed. */
	VEX_STATE_CHANGED,
	/** A new release was created (or scheduled, when it lands PENDING). */
	RELEASE_CREATED,
	/**
	 * A release lifecycle transition the legacy Slack/Teams path notified on
	 * (DRAFT / ASSEMBLED / CANCELLED / REJECTED). The producer only emits on
	 * those four to preserve pre-Phase-2b-2 behaviour; the payload carries the
	 * old → new lifecycle.
	 */
	RELEASE_LIFECYCLE_CHANGED,
	/** Once-per-release BOM diff (added / removed components) on the ASSEMBLED reconcile. */
	RELEASE_BOM_DIFF,
	/**
	 * Someone with write permission on a release explicitly requested approvals
	 * on it. Fan-out targets the snapshot of users who can approve the requested
	 * entries (per-user inbox rows), in addition to any matching subscriptions.
	 */
	APPROVAL_REQUESTED,
	/**
	 * An approval entry on a release reached a terminal state — satisfied
	 * (APPROVED) or disapproved (terminal DISAPPROVED). Separate from
	 * APPROVAL_REQUESTED per the locked Q2 model (b): resolution is its own
	 * auditable event that channels can route differently.
	 */
	APPROVAL_RESOLVED;

	/**
	 * Actionable events ask a specific person to do something now, so they
	 * bypass email digest batching (always sent immediately) and are excluded
	 * from the rolling-cap "last counted send" computation.
	 */
	public boolean isActionable () {
		return this == APPROVAL_REQUESTED || this == APPROVAL_RESOLVED;
	}
}

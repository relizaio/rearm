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
	 * Any release lifecycle transition; the payload carries the old -> new
	 * lifecycle. Emitted whenever the two differ, so a re-save that does not
	 * move the lifecycle is silent.
	 *
	 * <p>Phase 2b-2 originally restricted this to the four the legacy
	 * Slack/Teams path sent (DRAFT / ASSEMBLED / CANCELLED / REJECTED) so that
	 * migration changed no behaviour. That left an event named for lifecycle
	 * changes silent on READY_TO_SHIP, GENERAL_AVAILABILITY and every
	 * end-of-life stage, and it now covers all of them.
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
	APPROVAL_RESOLVED,
	/**
	 * An instance's actual-state deployment settled with one or more
	 * project/bundle status transitions (NEW / IN_PROGRESS / CONVERGED /
	 * UNDEPLOYED / RECOVERED). Emitted once per deploy by the producer-side
	 * coalesce flush (see ai-plans/instance-event-notifications.md sec 6), carrying
	 * the net diff from the pre-burst revision to the settled state. Digest-
	 * eligible; ERROR items are split into {@link #INSTANCE_DEPLOYMENT_FAILED}.
	 */
	INSTANCE_DEPLOYMENT_CHANGED,
	/**
	 * An instance's actual-state deployment settled with one or more items in
	 * ERROR. Its own type (not a status inside {@link #INSTANCE_DEPLOYMENT_CHANGED})
	 * so it can be {@link #isActionable() actionable} -- sent immediately, bypassing
	 * email-digest batching -- since {@code isActionable()} is decided per event
	 * type, not per item.
	 */
	INSTANCE_DEPLOYMENT_FAILED;

	/**
	 * Actionable events ask a specific person to do something now, so they
	 * bypass email digest batching (always sent immediately) and are excluded
	 * from the rolling-cap "last counted send" computation.
	 */
	public boolean isActionable () {
		return this == APPROVAL_REQUESTED || this == APPROVAL_RESOLVED
				|| this == INSTANCE_DEPLOYMENT_FAILED;
	}

	/**
	 * Does an event of this type carry the affected components an ownership rule
	 * can resolve?
	 *
	 * <p>Producers stamp {@code affectedReleases} -- which names a component --
	 * on every family except VEX: {@code VexStateChangedPayload} carries a
	 * component PURL and nothing else, and the vuln enrichment path does not
	 * apply to it. Anything that reasons about ownership therefore cannot match a
	 * VEX event, and a team's owned-component subscription must not offer it.
	 *
	 * <p>Lives on the enum so the day a VEX producer ships and stamps affected
	 * releases, ONE line changes -- rather than a hardcoded skip inside a team's
	 * event-list computation that nobody would think to look for.
	 *
	 * <p>Instance-deployment events are org/instance-scoped, not component-owner
	 * scoped: they carry deployed items but no {@code affectedReleases} array, so
	 * ownership rules cannot match them and a team's owned-component subscription
	 * must not offer them (see ai-plans/instance-event-notifications.md sec 5).
	 */
	public boolean carriesAffectedComponents() {
		return this != VEX_STATE_CHANGED
				&& this != INSTANCE_DEPLOYMENT_CHANGED
				&& this != INSTANCE_DEPLOYMENT_FAILED;
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.UUID;

/**
 * Single source of truth for the approval-event dedup-key formats.
 *
 * <p>The APPROVAL_REQUESTED key is load-bearing across two services: the
 * producer ({@code ReleaseChangeHookImpl}) stamps it on the outbox event
 * (and thus on the targeted delivery rows fan-out writes), and the
 * APPROVAL_RESOLVED consumer ({@code NotificationFanOutService}) rebuilds
 * the same key to find those rows for resolve-marks-read. A silent drift
 * between the two would not fail anything visibly — resolved requests
 * would just stop auto-marking their inbox rows read.
 */
public final class NotificationDedupKeys {

	private NotificationDedupKeys() {}

	/** Key for an APPROVAL_REQUESTED event: one per approval request. */
	public static String approvalRequested(UUID releaseUuid, UUID requestUuid) {
		return "approval:requested:" + releaseUuid + ":" + requestUuid;
	}

	/** Key for an APPROVAL_RESOLVED event: one per terminal approval entry. */
	public static String approvalResolved(UUID releaseUuid, UUID approvalEntryUuid) {
		return "approval:resolved:" + releaseUuid + ":" + approvalEntryUuid;
	}
}

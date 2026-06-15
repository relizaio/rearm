/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

public enum AdvisoryLockKey {
	REJECT_PENDING_RELEASE(1),
	RESOLVE_DEPENDENCY_TRACK_STATUS(7),
	COMPUTE_ANALYTICS_METRICS(8),
	CLEANUP_DEPENDENCY_TRACK_PROJECTS(17),
	WEBHOOK_DELIVERY_PRUNE(18),
	PURGE_OLD_API_KEY_ACCESS(19),
	AUTOCLOSE_IDLE_AGENT_SESSIONS(20),
	SYNC_DEPENDENCY_TRACK_DATA(9),
	DRAIN_NOTIFICATION_OUTBOX(21),
	DRAIN_NOTIFICATION_DELIVERIES(22),
	FLUSH_EMAIL_DIGESTS(23),
	PURGE_NOTIFICATION_ROWS(24),
	SYNC_KEV_CATALOG(25);
	
	private int queryVal;
	
	private AdvisoryLockKey(int queryVal) {
		this.queryVal = queryVal;
	}
	
	public int getQueryVal() {
		return this.queryVal;
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

public enum AdvisoryLockKey {
	REJECT_PENDING_RELEASE(1),
	RESOLVE_DEPENDENCY_TRACK_STATUS(7),
	COMPUTE_ANALYTICS_METRICS(8),
	SYNC_DEPENDENCY_TRACK_DATA(9);
	
	private int queryVal;
	
	private AdvisoryLockKey(int queryVal) {
		this.queryVal = queryVal;
	}
	
	public int getQueryVal() {
		return this.queryVal;
	}
}

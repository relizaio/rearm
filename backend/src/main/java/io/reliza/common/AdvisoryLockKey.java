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
	SYNC_KEV_CATALOG(25),
	REFRESH_TODAY_ANALYTICS(28),
	BACKFILL_FINDING_CHANGE_V3(30),
	SWEEP_INSTANCE_DEPLOYMENT_FAILURES(31),
	ENRICHMENT_PULL(32),
	FLUSH_INSTANCE_DEPLOYMENT_COALESCE(33),
	PURGE_CLI_SESSIONS(34),
	AGENT_TASK_DELIVERY(35),
	/** 39 and 45 since main merged in: main took 36-38, 43 and 44 meanwhile. */
	AGENT_TASK_QUEUE_AGE(39),
	AGENT_BOARD_EVENT_RETENTION(45),
	/**
	 * Namespace of the per-organization transaction lock over the task-key prefix registry
	 * (board-documents.md D9), taken with the two-key form whose second key is the org; not a
	 * scheduler lock.
	 */
	AGENT_TASK_PREFIXES(40),
	/** The model catalogue's daily re-resolve and fold by canonical id (task RD2-26). */
	MODEL_CATALOGUE_DEDUP(41),
	/** The staleness sweep over boards that set any staleness threshold (task RD3-4). */
	AGENT_BOARD_STALENESS(42),
	RECOMPUTE_VULNERABILITY_RECORDS(36),
	REDETECT_DTRACK_VERSIONS(43),
	/**
	 * Namespace of the per-record transaction lock on vulnerability_records
	 * (VulnerabilityRecordRepository.lockRecord), taken with the two-key form
	 * whose second key is the record; unlike the others it is not a
	 * scheduler lock.
	 */
	VULNERABILITY_RECORD_WRITE(37),
	LATEST_VERSION_REFRESH(38),
	/**
	 * Namespace of the per-component transaction lock that serializes version assignment on one
	 * component (VersionAssignmentRepository.lockVersionAssignmentOfComponent), two-key form like
	 * VULNERABILITY_RECORD_WRITE; not a scheduler lock.
	 */
	VERSION_ASSIGNMENT(44),
	/**
	 * Namespace of the per-org session lock around one org's synthetic
	 * Dependency-Track cycle (SyntheticOrgLock), taken with the two-key form
	 * whose second key is the org; not a scheduler lock.
	 */
	SYNTHETIC_ORG_CYCLE(46);
	
	private int queryVal;
	
	private AdvisoryLockKey(int queryVal) {
		this.queryVal = queryVal;
	}
	
	public int getQueryVal() {
		return this.queryVal;
	}
}

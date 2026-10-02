/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.AdvisoryLockKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Serializes one org's synthetic Dependency-Track cycle across threads and
 * replicas.
 *
 * <p>Four entry points run that cycle: the per-minute tick, the daily resync,
 * the manual resync and the force re-upload. Each of them rewrites the org's
 * buckets and fans findings out to its artifacts. The tick and the daily
 * resync hold different scheduler locks, and the manual actions hold none, so
 * without this lock they overlap on the same org. Two fan-outs then write the
 * same artifacts, and the loser's write rolls back on the metrics_audit
 * revision index. A fan-out that snapshotted the buckets before another
 * thread rewrote one can also be the writer that wins. In that case it
 * stamps artifacts past the new cutoff with the old findings, and nothing
 * revisits them.
 *
 * <p>This is a Postgres session-level advisory lock in the two-key form:
 * {@link AdvisoryLockKey#SYNTHETIC_ORG_CYCLE}, plus {@code hashtext} of the
 * org. It spans a whole cycle, which runs many short transactions, so it
 * cannot be a transaction lock. The holder keeps a dedicated connection for
 * the duration. Waiting holds no connection, because {@link #acquire} polls.
 */
@Service
@Slf4j
public class SyntheticOrgLock {

	static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

	@Autowired private DataSource dataSource;

	/**
	 * Takes the org's lock if it is free.
	 *
	 * @return the held lock, or null when another session holds it.
	 */
	public Lease tryAcquire(UUID orgUuid) {
		Connection conn = null;
		try {
			conn = dataSource.getConnection();
			conn.setAutoCommit(true);
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT pg_try_advisory_lock(?, hashtext(?))")) {
				stmt.setInt(1, AdvisoryLockKey.SYNTHETIC_ORG_CYCLE.getQueryVal());
				stmt.setString(2, orgUuid.toString());
				try (ResultSet rs = stmt.executeQuery()) {
					rs.next();
					if (rs.getBoolean(1)) {
						Lease lease = new Lease(conn, orgUuid);
						conn = null; // now owned by the lease
						return lease;
					}
					return null;
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to take the synthetic cycle lock for org " + orgUuid, e);
		} finally {
			closeQuietly(conn);
		}
	}

	/**
	 * Takes the org's lock, polling until it is free or {@code maxWait} runs out.
	 *
	 * @return the held lock, or null on timeout.
	 */
	public Lease acquire(UUID orgUuid, Duration maxWait) throws InterruptedException {
		Instant deadline = Instant.now().plus(maxWait);
		while (true) {
			Lease lease = null;
			try {
				lease = tryAcquire(orgUuid);
			} catch (IllegalStateException e) {
				// A pool or database hiccup on one poll; keep waiting until the deadline.
				log.warn("Synthetic cycle lock poll failed for org {}, retrying: {}", orgUuid, e.getMessage());
			}
			if (lease != null) return lease;
			if (!Instant.now().isBefore(deadline)) return null;
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
	}

	private static void closeQuietly(Connection conn) {
		if (conn == null) return;
		try {
			conn.close();
		} catch (SQLException e) {
			log.warn("Failed to close a synthetic cycle lock connection: {}", e.getMessage());
		}
	}

	/** A held org lock. Closing it unlocks and returns the connection. */
	public static final class Lease implements AutoCloseable {
		private final Connection conn;
		private final UUID orgUuid;

		/** A null connection makes a lease that holds nothing, for tests. */
		Lease(Connection conn, UUID orgUuid) {
			this.conn = conn;
			this.orgUuid = orgUuid;
		}

		@Override
		public void close() {
			if (conn == null) return;
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT pg_advisory_unlock(?, hashtext(?))")) {
				stmt.setInt(1, AdvisoryLockKey.SYNTHETIC_ORG_CYCLE.getQueryVal());
				stmt.setString(2, orgUuid.toString());
				try (ResultSet rs = stmt.executeQuery()) {
					// false: the server session behind this connection is not the one
					// that took the lock (a proxy in between reconnected it), so the lock
					// went with the old session and another cycle for the org may have
					// overlapped this one. A session that simply died makes the unlock
					// throw instead, which the catch below handles.
					if (rs.next() && !rs.getBoolean(1)) {
						log.error("The synthetic cycle lock for org {} was no longer held at release; "
								+ "another cycle may have run on the org at the same time", orgUuid);
					}
				}
			} catch (SQLException e) {
				// close() would hand the pooled session, lock included, to the next
				// borrower and block this org's cycle for good. Abort the physical
				// connection instead: the pool evicts it and Postgres drops the lock
				// with the session.
				log.error("Failed to release the synthetic cycle lock for org {}, aborting its connection",
						orgUuid, e);
				try {
					conn.abort(Runnable::run);
				} catch (SQLException abortFailure) {
					log.error("Failed to abort the synthetic cycle lock connection for org {}", orgUuid, abortFailure);
				}
			} finally {
				closeQuietly(conn);
			}
		}
	}
}

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
 * Serializes every TEA act on one release, publish or hide, across threads and instances (task
 * TEA-5, design round 3 section 3.10).
 *
 * <p>An act reads the publication head and the release, generates the aggregate through rebom,
 * uploads it, and commits. Two acts on one release on two instances must not interleave
 * anywhere in that span, or both upload, both bind and the second writes a version with no
 * content change. The in-process lock of {@link TeaPublicationService} orders acts inside one
 * instance; this lease orders them across instances.
 *
 * <p>This is a Postgres session-level advisory lock in the two-key form:
 * {@link AdvisoryLockKey#TEA_PUBLISH}, plus {@code hashtext} of the release. It spans a whole
 * act, which runs several short transactions and calls rebom and the registry between them, so
 * it cannot be a transaction lock. The holder keeps a dedicated pooled connection for the
 * duration. Waiting holds no connection, because {@link #acquire} polls. The shape is
 * {@link SyntheticOrgLock}'s.
 */
@Service
@Slf4j
public class TeaPublishLock {

	static final Duration POLL_INTERVAL = Duration.ofMillis(500);

	@Autowired private DataSource dataSource;

	/**
	 * Takes the release's lease if it is free.
	 *
	 * @return the held lease, or null when another session holds it.
	 */
	public Lease tryAcquire(UUID release) {
		Connection conn = null;
		try {
			conn = dataSource.getConnection();
			conn.setAutoCommit(true);
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT pg_try_advisory_lock(?, hashtext(?))")) {
				stmt.setInt(1, AdvisoryLockKey.TEA_PUBLISH.getQueryVal());
				stmt.setString(2, release.toString());
				try (ResultSet rs = stmt.executeQuery()) {
					rs.next();
					if (rs.getBoolean(1)) {
						Lease lease = new Lease(conn, release);
						conn = null; // now owned by the lease
						return lease;
					}
					return null;
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to take the TEA publish lock of release " + release, e);
		} finally {
			closeQuietly(conn);
		}
	}

	/**
	 * Takes the release's lease, polling until it is free or {@code maxWait} runs out.
	 *
	 * @return the held lease, or null on timeout.
	 */
	public Lease acquire(UUID release, Duration maxWait) throws InterruptedException {
		Instant start = Instant.now();
		Instant deadline = start.plus(maxWait);
		int failedPolls = 0;
		while (true) {
			Lease lease = null;
			try {
				lease = tryAcquire(release);
			} catch (IllegalStateException e) {
				// A pool or database hiccup on one poll; keep waiting until the deadline.
				log.error("TEA publish lock poll failed for release {}, retrying", release, e);
			}
			if (lease != null) {
				lease.failedPolls = failedPolls;
				lease.waitedMillis = Duration.between(start, Instant.now()).toMillis();
				return lease;
			}
			++failedPolls;
			if (!Instant.now().isBefore(deadline)) return null;
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
	}

	private static void closeQuietly(Connection conn) {
		if (conn == null) return;
		try {
			conn.close();
		} catch (SQLException e) {
			log.error("Failed to close a TEA publish lock connection", e);
		}
	}

	/** A held release lease. Closing it unlocks and returns the connection. */
	public static final class Lease implements AutoCloseable {
		private final Connection conn;
		private final UUID release;
		private int failedPolls;
		private long waitedMillis;

		/** A null connection makes a lease that holds nothing, for tests. */
		Lease(Connection conn, UUID release) {
			this.conn = conn;
			this.release = release;
		}

		/** How many polls found the lease held by another session before this one took it. */
		public int failedPolls() {
			return failedPolls;
		}

		/** How long the taking waited, in milliseconds. */
		public long waitedMillis() {
			return waitedMillis;
		}

		public UUID release() {
			return release;
		}

		@Override
		public void close() {
			if (conn == null) return;
			try (PreparedStatement stmt = conn.prepareStatement(
					"SELECT pg_advisory_unlock(?, hashtext(?))")) {
				stmt.setInt(1, AdvisoryLockKey.TEA_PUBLISH.getQueryVal());
				stmt.setString(2, release.toString());
				try (ResultSet rs = stmt.executeQuery()) {
					// false: the server session behind this connection is not the one that took
					// the lease (a proxy in between reconnected it), so the lease went with the old
					// session and another act on the release may have overlapped this one.
					if (rs.next() && !rs.getBoolean(1)) {
						log.error("The TEA publish lock of release {} was no longer held at release; another "
								+ "publish or hide of the release may have run at the same time", release);
					}
				}
			} catch (SQLException e) {
				// close() would hand the pooled session, lease included, to the next borrower and
				// block this release's acts for good. Abort the physical connection instead: the pool
				// evicts it and Postgres drops the lock with the session.
				log.error("Failed to release the TEA publish lock of release {}, aborting its connection", release, e);
				try {
					conn.abort(Runnable::run);
				} catch (SQLException abortFailure) {
					log.error("Failed to abort the TEA publish lock connection of release {}", release, abortFailure);
				}
			} finally {
				closeQuietly(conn);
			}
		}
	}
}

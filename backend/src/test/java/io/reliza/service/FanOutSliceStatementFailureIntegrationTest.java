/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.ws.App;

/**
 * TPF-136 architecture 6.4 case 12 (F-3), carried over by TPF-137 (6.2 case 8): on the test
 * database, a slice statement of the real {@link SyntheticSbomService#fanOutOrg} that the server
 * cancels (SQLSTATE 57014, as the client timeout produces) is caught for that slice, the cursor
 * stays before it, and the rest of the pass commits, because no caller opens a transaction around
 * the pass. A second pooled connection holds ACCESS EXCLUSIVE on artifact_sbom_components, finds
 * the slice statement waiting on it and cancels it once.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutSliceStatementFailureIntegrationTest extends FanOutCandidateTestBase {

	private static final long CAP_MILLIS = 30_000L;
	private static final long POLL_MILLIS = 50L;

	/**
	 * The slice statement of FanOutWalkRepository is the only statement of the pass carrying this
	 * ORDER BY. Scoped to this fork's database (F-6): the other fork's backends are in the same
	 * cluster.
	 */
	private static final String WAITING_BUCKET_STATEMENT = """
			SELECT pid FROM pg_stat_activity
			WHERE pid <> pg_backend_pid() AND datname = current_database()
			  AND state = 'active' AND wait_event_type = 'Lock'
			  AND query LIKE '%ORDER BY ascx.canonical_artifact_uuid%'
			""";

	private static final String STILL_ACTIVE = """
			SELECT count(*) FROM pg_stat_activity
			WHERE pid = ? AND datname = current_database()
			  AND state = 'active' AND query LIKE '%ORDER BY ascx.canonical_artifact_uuid%'
			""";

	@Test
	public void aCancelledSliceStatementLeavesTheRestOfThePassRunningBecauseNoCallerOpensATransaction()
			throws Exception {
		// A warm org (one stamped row, so the guard counts 1 and the walk runs).
		UUID org = newOrg();
		String dep = uniquePurl("dep");
		Mapped s = mapBom(org, STALE_EPOCH, null, component(org, dep, 0));
		ZonedDateTime t0 = nowMicros().minusHours(2);
		stampFanOut(org, s.canonical(), t0);
		// Never stamped: an (A) row; its purl is not covered by the bucket, so it is a gate skip.
		Mapped n = mapBom(org, null, null, component(org, uniquePurl("new"), 0));
		ZonedDateTime t1 = nowMicros().minusMinutes(1);
		bumpWithChange(bucketAt(org, 0, t1, dep), t1, dep);
		assertEquals(1, artifactCanonicalMapRepository.countFanOutStamped(org));
		assertNull(fanOutAtOf(n.artifact()));

		CountDownLatch locked = new CountDownLatch(1);
		AtomicReference<Throwable> holderFailure = new AtomicReference<>();
		List<Integer> cancelled = Collections.synchronizedList(new ArrayList<>());
		Thread holder = new Thread(() -> holdLockAndCancel(locked, cancelled, holderFailure),
				"tpf137-lock-holder");
		holder.start();
		assertTrue(locked.await(CAP_MILLIS, TimeUnit.MILLISECONDS), "the holder did not take the lock in 30 s");

		assertDoesNotThrow(() -> syntheticSbomService.fanOutOrg(org));
		holder.join(CAP_MILLIS);
		assertFalse(holder.isAlive(), "the holder did not finish in 30 s");
		if (holderFailure.get() != null) {
			throw new AssertionError("lock holder failed: " + holderFailure.get().getMessage(), holderFailure.get());
		}

		assertEquals(1, cancelled.size(), "the holder cancelled exactly one pid");
		assertEquals(iso(t1), fanOutAtOf(n.artifact()), "(A)'s stamp committed although the slice statement failed");
		assertEquals(iso(t0), fanOutAtOf(s.artifact()), "the walked slice failed: its candidate was not applied");
		assertEquals(Double.valueOf(STALE_EPOCH), lastScannedOf(s.artifact()));
		assertNull(systemInfoService.getFanOutWalk(org), "the failed slice is the first: no progress to persist");
		assertNull(systemInfoService.getFanOutDrainedAt(org), "nothing is recorded");

		// Pass 2, no holder: the retry walks the space and applies the candidate.
		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t1), fanOutAtOf(s.artifact()));
		Double scanned = lastScannedOf(s.artifact());
		assertNotNull(scanned);
		assertTrue(scanned > STALE_EPOCH, "the retry applied the candidate");
		assertNull(systemInfoService.getFanOutWalk(org), "the drain completed");
		assertEquals(iso(t1), iso(systemInfoService.getFanOutDrainedAt(org)));
	}

	private void holdLockAndCancel(CountDownLatch locked, List<Integer> cancelled,
			AtomicReference<Throwable> holderFailure) {
		Connection c = null;
		try {
			c = jdbcTemplate.getDataSource().getConnection();
			c.setAutoCommit(false);
			try (Statement st = c.createStatement()) {
				st.execute("LOCK TABLE rearm.artifact_sbom_components IN ACCESS EXCLUSIVE MODE");
			}
			locked.countDown();

			// (b) find the waiting slice statement and cancel it once.
			Integer pid = null;
			long capAt = System.currentTimeMillis() + CAP_MILLIS;
			while (pid == null) {
				if (System.currentTimeMillis() > capAt) {
					throw new IllegalStateException("(b) no waiting slice statement within 30 s");
				}
				clearStatSnapshot(c);
				try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(WAITING_BUCKET_STATEMENT)) {
					if (rs.next()) pid = rs.getInt(1);
				}
				if (pid == null) Thread.sleep(POLL_MILLIS);
			}
			try (PreparedStatement ps = c.prepareStatement("SELECT pg_cancel_backend(?)")) {
				ps.setInt(1, pid);
				try (ResultSet rs = ps.executeQuery()) {
					rs.next();
				}
			}
			cancelled.add(pid);

			// (c) wait until the cancelled statement has left pg_stat_activity.
			capAt = System.currentTimeMillis() + CAP_MILLIS;
			while (true) {
				if (System.currentTimeMillis() > capAt) {
					throw new IllegalStateException("(c) the cancelled slice statement of pid " + pid
							+ " was still active after 30 s");
				}
				clearStatSnapshot(c);
				long active;
				try (PreparedStatement ps = c.prepareStatement(STILL_ACTIVE)) {
					ps.setInt(1, pid);
					try (ResultSet rs = ps.executeQuery()) {
						rs.next();
						active = rs.getLong(1);
					}
				}
				if (active == 0) break;
				Thread.sleep(POLL_MILLIS);
			}
		} catch (Throwable t) {
			holderFailure.compareAndSet(null, t);
		} finally {
			// (d) the lock never outlives the test.
			locked.countDown();
			if (c != null) {
				try {
					c.commit();
					c.close();
				} catch (SQLException e) {
					holderFailure.compareAndSet(null, e);
				}
			}
		}
	}

	/**
	 * F-5: PostgreSQL serves every read of pg_stat_activity in one transaction from the snapshot
	 * its first read took; the holder's polls run inside the transaction that holds the lock, so
	 * each discards that snapshot first and reads the live backend status.
	 */
	private static void clearStatSnapshot(Connection c) throws SQLException {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_stat_clear_snapshot()")) {
			rs.next();
		}
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.ws.App;

/**
 * The per-org synthetic cycle lock against a real Postgres. Unlocking matters
 * here: {@code close()} hands a pooled connection back without ending its
 * session, so a session lock that was not explicitly unlocked would outlive
 * the lease.
 */
@SpringBootTest(classes = {App.class})
public class SyntheticOrgLockTest {

	@Autowired private SyntheticOrgLock syntheticOrgLock;
	@Autowired private DataSource dataSource;

	@Test
	void anOrgLockIsExclusiveUntilReleasedAndOtherOrgsAreIndependent() throws Exception {
		UUID org = UUID.randomUUID();
		UUID otherOrg = UUID.randomUUID();
		try (SyntheticOrgLock.Lease first = syntheticOrgLock.tryAcquire(org)) {
			assertNotNull(first);
			assertEquals(1, sessionsHolding(org));
			assertNull(syntheticOrgLock.tryAcquire(org), "a second session must not get the same org");
			try (SyntheticOrgLock.Lease other = syntheticOrgLock.tryAcquire(otherOrg)) {
				assertNotNull(other, "another org's cycle is not blocked");
			}
			assertNull(syntheticOrgLock.acquire(org, Duration.ofMillis(100)), "waiting times out while held");
		}
		// Checked in pg_locks rather than by re-acquiring: the pool may hand the same
		// session back, and a session lock is re-entrant, so re-acquiring would pass
		// even if the lease never unlocked.
		assertEquals(0, sessionsHolding(org), "closing the lease released the lock");
		try (SyntheticOrgLock.Lease again = syntheticOrgLock.tryAcquire(org)) {
			assertNotNull(again);
		}
	}

	@Test
	void aWaitingAcquireGetsTheLockOnceTheHolderReleasesIt() throws Exception {
		UUID org = UUID.randomUUID();
		SyntheticOrgLock.Lease holder = syntheticOrgLock.tryAcquire(org);
		assertNotNull(holder);
		CompletableFuture<SyntheticOrgLock.Lease> waiter = CompletableFuture.supplyAsync(() -> {
			try {
				return syntheticOrgLock.acquire(org, Duration.ofSeconds(30));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		});
		Thread.sleep(500);
		holder.close();
		try (SyntheticOrgLock.Lease acquired = waiter.get(30, TimeUnit.SECONDS)) {
			assertNotNull(acquired);
		}
	}

	@Test
	void aLeaseWhoseSessionWasKilledClosesWithoutThrowing() throws Exception {
		// An infrastructure idle-kill of the lease's session mid-cycle: Postgres drops
		// the lock with the session, and the release must cope with the dead socket.
		UUID org = UUID.randomUUID();
		SyntheticOrgLock.Lease lease = syntheticOrgLock.tryAcquire(org);
		assertNotNull(lease);
		try (Connection conn = dataSource.getConnection();
				PreparedStatement stmt = conn.prepareStatement(
						"SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND granted"
						+ " AND objsubid = 2 AND classid::bigint = ? AND objid::bigint = (hashtext(?)::bigint & 4294967295)")) {
			stmt.setLong(1, AdvisoryLockKey.SYNTHETIC_ORG_CYCLE.getQueryVal());
			stmt.setString(2, org.toString());
			stmt.execute();
		}
		for (int i = 0; i < 50 && sessionsHolding(org) > 0; i++) Thread.sleep(100);
		assertEquals(0, sessionsHolding(org), "the lock went with the session");

		lease.close();

		try (SyntheticOrgLock.Lease again = syntheticOrgLock.tryAcquire(org)) {
			assertNotNull(again, "the org's cycle is not blocked afterwards");
		}
	}

	/** Sessions holding the org's two-key advisory lock. */
	private int sessionsHolding(UUID org) throws Exception {
		try (Connection conn = dataSource.getConnection();
				PreparedStatement stmt = conn.prepareStatement(
						"SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND objsubid = 2"
						+ " AND classid::bigint = ? AND objid::bigint = (hashtext(?)::bigint & 4294967295)")) {
			stmt.setLong(1, AdvisoryLockKey.SYNTHETIC_ORG_CYCLE.getQueryVal());
			stmt.setString(2, org.toString());
			try (ResultSet rs = stmt.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}
}

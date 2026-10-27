/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.exceptions.ComponentRevisionConflictException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AttestationData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentOwnerType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;

/**
 * TPF-61 (architecture-2 4.1): {@code ComponentService.updateComponent} refuses a settings save whose
 * {@code expectedRevision} is not the stored revision, compared with the row {@code lockedComponentRow}
 * locked and refreshed, and writes nothing; a save without a revision is not checked.
 *
 * <p>On the TPF-38 rig: {@link #preRead} pins the comparison with the refreshed row (row 8), {@link #held}
 * pins it after the lock (rows 9 and 10). Every assertion is read on the test thread after the writes
 * committed.
 */
class ComponentRevisionCheckIntegrationTest extends ComponentConcurrentWriteTestBase {

	private static final String IP = "203.0.113.9";

	// ---------- helpers ----------

	/** A settings save of repoPath carrying the given expected revision, as the TEST updater. */
	private void save(Integer expected, String repoPath) throws RelizaException {
		componentService.updateComponent(ComponentDto.builder().uuid(c).repoPath(repoPath)
				.expectedRevision(expected).build(), WU);
	}

	/** The same save without a revision, as {@code wu}. */
	private void xAs(WhoUpdated wu, String repoPath) throws RelizaException {
		componentService.updateComponent(ComponentDto.builder().uuid(c).repoPath(repoPath).build(), wu);
	}

	private static ComponentRevisionConflictException refused(Write w) {
		return assertThrows(ComponentRevisionConflictException.class, w::run);
	}

	/** Runs {@code w}: the refusal it raised, or null when it completed; anything else is rethrown. */
	private static ComponentRevisionConflictException catchConflict(Write w) {
		try {
			w.run();
			return null;
		} catch (ComponentRevisionConflictException e) {
			return e;
		} catch (Exception e) {
			throw unchecked(e);
		}
	}

	private record Snapshot(String record, int revision, Instant lastUpdatedDate, int audits) {}

	private Snapshot snapshot() {
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT record_data::text AS record, revision, last_updated_date FROM rearm.components WHERE uuid = ?", c);
		return new Snapshot((String) row.get("record"), ((Number) row.get("revision")).intValue(),
				((Timestamp) row.get("last_updated_date")).toInstant(), audits(c));
	}

	// ---------- rows ----------

	/** Row 1: a save prepared from the stored revision passes and adds exactly one. */
	@Test
	@Timeout(60)
	void aSaveFromTheStoredRevisionPasses() throws Exception {
		int r = revision(c);
		int before = audits(c);
		save(r, "a");
		assertEquals("a", stored().getRepoPath());
		assertEquals(r + 1, revision(c));
		assertEquals(before + 1, audits(c));
	}

	/** Row 2: a person changed the component after the read; the refusal names them and writes nothing. */
	@Test
	@Timeout(60)
	void aStaleSaveIsRefusedNamingThePersonAndWritesNothing() throws Exception {
		int r = revision(c);
		UUID m = newMember();
		xAs(WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, m, IP), "other");
		Snapshot s = snapshot();
		ComponentRevisionConflictException e = refused(() -> save(r, "mine"));
		assertEquals(r, e.getExpectedRevision());
		assertEquals(r + 1, e.getCurrentRevision());
		assertEquals(m, e.getLastUpdatedBy());
		assertEquals(ProgrammaticType.MANUAL, e.getLastUpdatedType());
		assertNull(e.getLastUpdatedActor());
		assertEquals(s.lastUpdatedDate(), e.getLastUpdatedDate().toInstant());
		assertEquals("Component was changed since it was read: expected revision " + r
				+ ", current revision " + (r + 1), e.getMessage());
		assertEquals(s, snapshot(), "a refused save must write nothing: record, revision, date and audits unchanged");
		assertEquals("other", stored().getRepoPath());
	}

	/** Row 3: the last write was a key acting for a person. */
	@Test
	@Timeout(60)
	void aRefusalAfterAKeyWriteNamesTheKeyAndThePersonBehindIt() throws Exception {
		int r = revision(c);
		UUID m = newMember();
		UUID k = UUID.randomUUID();
		xAs(WhoUpdated.getApiWhoUpdated(k, IP).withActor(m), "other");
		ComponentRevisionConflictException e = refused(() -> save(r, "mine"));
		assertEquals(k, e.getLastUpdatedBy());
		assertEquals(ProgrammaticType.API, e.getLastUpdatedType());
		assertEquals(m, e.getLastUpdatedActor());
	}

	/** Row 4: the last write was the system. */
	@Test
	@Timeout(60)
	void aRefusalAfterASystemWriteNamesNoOne() throws Exception {
		int r = revision(c);
		xAs(WhoUpdated.getAutoWhoUpdated(), "other");
		ComponentRevisionConflictException e = refused(() -> save(r, "mine"));
		assertNull(e.getLastUpdatedBy());
		assertEquals(ProgrammaticType.AUTO, e.getLastUpdatedType());
		assertNull(e.getLastUpdatedActor());
	}

	/** Row 5: no revision, no check. */
	@Test
	@Timeout(60)
	void aSaveWithoutARevisionIsNotChecked() throws Exception {
		int r = revision(c);
		x("other");
		save(null, "mine");
		assertEquals("mine", stored().getRepoPath());
		assertEquals(r + 2, revision(c));
	}

	/** Row 6: the resend with the revision the refusal reported saves (the page's Force). */
	@Test
	@Timeout(60)
	void theResendWithTheReportedRevisionSaves() throws Exception {
		int r = revision(c);
		x("other");
		ComponentRevisionConflictException e = refused(() -> save(r, "mine"));
		save(e.getCurrentRevision(), "mine");
		assertEquals("mine", stored().getRepoPath());
		assertEquals(r + 2, revision(c));
	}

	/** Row 7: a resend after yet another change is refused again. */
	@Test
	@Timeout(60)
	void aResendAfterAnotherChangeIsRefusedAgain() throws Exception {
		int r = revision(c);
		x("other");
		ComponentRevisionConflictException e1 = refused(() -> save(r, "mine"));
		x("third");
		ComponentRevisionConflictException e2 = refused(() -> save(e1.getCurrentRevision(), "mine"));
		assertEquals(r + 2, e2.getCurrentRevision());
		assertEquals("third", stored().getRepoPath());
	}

	/**
	 * Row 8: the request's own pre-read copy carries revision r; a marker commits before the lock. The
	 * comparison is with the refreshed locked row, so the save is refused; a comparison with the request's
	 * managed copy would pass and write {@code w}.
	 */
	@Test
	@Timeout(60)
	void theComparisonIsWithTheRefreshedLockedRowNotTheRequestsCopy() throws Exception {
		int r = revision(c);
		AtomicReference<Integer> seenRevision = new AtomicReference<>();
		AtomicReference<ComponentRevisionConflictException> holder = new AtomicReference<>();
		preRead(() -> x("marker"), seen -> {
			seenRevision.set(seen.getRevision());
			holder.set(catchConflict(() -> save(seen.getRevision(), "w")));
		});
		assertEquals(r, seenRevision.get());
		assertNotNull(holder.get(), "the stale save must be refused");
		assertEquals(r + 1, holder.get().getCurrentRevision());
		assertEquals("marker", stored().getRepoPath());
		assertEquals(r + 1, revision(c));
	}

	/** Row 9: a checked save waiting on a held writer is refused once that writer commits. */
	@Test
	@Timeout(60)
	void aCheckedSaveWaitingOnAHeldWriterIsRefused() throws Exception {
		int r = revision(c);
		AtomicReference<ComponentRevisionConflictException> holder = new AtomicReference<>();
		held(() -> x("held"), () -> holder.set(catchConflict(() -> save(r, "w"))));
		assertNotNull(holder.get(), "the waiting save must be refused");
		assertEquals(r + 1, holder.get().getCurrentRevision());
		assertEquals("held", stored().getRepoPath());
		assertEquals(r + 1, revision(c));
	}

	/** Row 10: two checked saves from the same revision: exactly one succeeds. */
	@Test
	@Timeout(60)
	void twoCheckedSavesFromOneRevisionExactlyOneSucceeds() throws Exception {
		int r = revision(c);
		int before = audits(c);
		AtomicReference<ComponentRevisionConflictException> holder = new AtomicReference<>();
		held(() -> save(r, "first"), () -> holder.set(catchConflict(() -> save(r, "second"))));
		assertEquals("first", stored().getRepoPath());
		assertEquals(r + 1, revision(c));
		assertEquals(before + 1, audits(c));
		assertNotNull(holder.get(), "the second save must be refused");
		assertEquals(r + 1, holder.get().getCurrentRevision());
	}

	/** Row 11: six checked saves from one revision, released together: one wins, five are refused. */
	@Test
	@Timeout(60)
	void sixConcurrentCheckedSavesOneWins() throws Exception {
		int r = revision(c);
		int before = audits(c);
		int n = 6;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Integer> wins = Collections.synchronizedList(new ArrayList<>());
		List<Integer> lost = Collections.synchronizedList(new ArrayList<>());
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		boolean allFinished;
		try {
			for (int i = 0; i < n; i++) {
				final int task = i;
				pool.submit(() -> {
					try {
						startTogether.await();
						bounded(() -> {
							try {
								save(r, "n" + task);
								wins.add(task);
							} catch (ComponentRevisionConflictException e) {
								lost.add(e.getCurrentRevision());
							}
						});
					} catch (Throwable t) {
						errors.add(t);
					}
					return null;
				});
			}
			startTogether.countDown();
			pool.shutdown();
			allFinished = pool.awaitTermination(60, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertTrue(allFinished, "the savers did not finish in time");
		assertEquals(List.of(), errors, "no saver should have errored");
		assertEquals(1, wins.size(), "exactly one save wins: " + wins);
		assertEquals("n" + wins.get(0), stored().getRepoPath());
		assertEquals(Collections.nCopies(n - 1, r + 1), lost);
		assertEquals(r + 1, revision(c));
		assertEquals(before + 1, audits(c));
	}

	/** Row 12: a checked save that sends neither approval policy nor owner leaves both as stored. */
	@Test
	@Timeout(60)
	void aCheckedSaveLeavesTheApprovalPolicyAndOwnerAsStored() throws Exception {
		UUID policy = UUID.randomUUID();
		UUID m = newMember();
		ComponentData.ComponentOwner owner = new ComponentData.ComponentOwner(ComponentOwnerType.USER, m);
		componentService.updateComponent(ComponentDto.builder().uuid(c).approvalPolicy(policy).owner(owner).build(), WU);
		int r = revision(c);
		save(r, "a");
		ComponentData cd = stored();
		assertEquals(policy, cd.getApprovalPolicy());
		assertEquals(owner, cd.getOwner());
		assertEquals("a", cd.getRepoPath());
		assertEquals(r + 1, revision(c));
	}

	/**
	 * Row 13: every write of the row the settings page makes adds exactly one (lock, release, perspectives,
	 * guards), and a checked save that counted them passes. Child 2 counts this way.
	 */
	@Test
	@Timeout(60)
	void everyPageWriteAddsExactlyOneAndACountedSavePasses() throws Exception {
		int r = revision(c);
		ComponentLock l = lock(c, "own");
		assertEquals(r + 1, revision(c));
		componentLockService.releaseLock(org, l.uuid(), "own", false, AttestationData.ActorType.USER,
				UUID.randomUUID(), null, WU);
		assertEquals(r + 2, revision(c));
		componentService.setPerspectives(c, Set.of(p), WU);
		assertEquals(r + 3, revision(c));
		componentService.updateComponent(ComponentDto.builder().uuid(c).actionGuards(List.of()).build(), WU);
		assertEquals(r + 4, revision(c));
		save(r + 4, "z");
		assertEquals(r + 5, revision(c));
		assertEquals("z", stored().getRepoPath());
	}

	/** Row 14: the revision never lands in the JSONB record, and a read serves the row's revision. */
	@Test
	@Timeout(60)
	void theRevisionStaysOutOfTheRecordAndIsServedFromTheRow() throws Exception {
		int r = revision(c);
		save(r, "a");
		Boolean inRecord = jdbcTemplate.queryForObject(
				"SELECT jsonb_exists(record_data, 'revision') FROM rearm.components WHERE uuid = ?", Boolean.class, c);
		assertFalse(inRecord);
		assertEquals(Integer.valueOf(revision(c)), getComponentService.getComponentData(c).get().getRevision());
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ApiKeyGroupService;
import io.reliza.service.AuditService;

/**
 * setApiKeyGroupsOfKey under its locks (TPF-123 architecture-3 section 5, row 15 (a) to (d), and the rollback case
 * of row 12): the key row is taken first, so same-key calls serialize and a racing delete leaves no stale member;
 * the delete and the mutation lock group rows in one sorted uuid order; a refusal after a save rolls the whole
 * call back. The spies are stubbed per test and reset in finally; every await and get is bounded.
 */
public class ApiKeyGroupKeySideConcurrencyTest extends ApiKeyGroupKeySideTestBase {

	@MockitoSpyBean ApiKeyGroupService apiKeyGroupServiceSpy;
	@MockitoSpyBean AuditService auditServiceSpy;
	@Autowired PlatformTransactionManager transactionManager;

	private ApiKeyGroupService groupSpy() {
		return AopTestUtils.getUltimateTargetObject(apiKeyGroupServiceSpy);
	}

	private AuditService auditSpy() {
		return AopTestUtils.getUltimateTargetObject(auditServiceSpy);
	}

	private List<UUID> holding(Organization org, UUID key) {
		return apiKeyGroupService.getAllApiKeyGroupsByOrganization(org.getUuid()).stream()
				.filter(g -> g.hasApiKey(key)).map(ApiKeyGroupData::getUuid).toList();
	}

	// ---------- row 12, rollback: a group deactivated between the read and its lock ----------

	@Test
	public void aRefusalOnALockedGroupRollsBackEveryEarlierSave() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org, "k12");
		ApiKeyGroupData g1 = group(org, Set.of(k.getUuid()));
		ApiKeyGroupData g2 = group(org, Set.of());
		ApiKeyGroupData g6 = group(org, Set.of());
		int n2 = groupAudits(g2);
		AtomicBoolean fired = new AtomicBoolean(false);
		try {
			doAnswer(inv -> {
				Object read = inv.callRealMethod();
				if (fired.compareAndSet(false, true)) {
					// the whole-list check sees G6 ACTIVE; the locked re-read sees it INACTIVE (the plant direct-row idiom)
					ApiKeyGroup row = apiKeyGroupRepository.findById(g6.getUuid()).orElseThrow();
					ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(row);
					d.setStatus(ApiKeyGroupStatus.INACTIVE);
					row.setRecordData(Utils.dataToRecord(d));
					apiKeyGroupRepository.saveAndFlush(row);
				}
				return read;
			}).when(groupSpy()).getAllApiKeyGroupsByOrganization(org.getUuid());
			signInAs(member(org, PermissionType.ADMIN));

			assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid(), g2.getUuid(), g6.getUuid()),
					SET_GROUPS_FIELD, "API key group is inactive: " + g6.getUuid());
		} finally {
			Mockito.reset(groupSpy());
		}
		// whichever of G2 and G6 sorts first, the add to G2 is not there: saved and rolled back, or never run
		assertFalse(stored(g2).hasApiKey(k.getUuid()));
		assertEquals(n2, groupAudits(g2));
		assertEquals(List.of(g1.getUuid()), holding(org, k.getUuid()));
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(g6).getStatus(), "the stub's own write rolled back with the call");
	}

	// ---------- row 15 (a): the mutation waits for the key row ----------

	@Test
	public void theMutationWaitsWhileAnotherTransactionHoldsTheKeyRow() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org, "k15");
		ApiKeyGroupData g1 = group(org, Set.of());
		int n1 = groupAudits(g1);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService holder = Executors.newSingleThreadExecutor();
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			Future<?> held = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				apiKeyService.getApiKeyWriteLocked(k.getUuid());
				locked.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertTrue(locked.await(30, TimeUnit.SECONDS));
			Future<List<ApiKeyGroupData>> f = caller.submit(() -> apiKeyGroupService.setGroupsOfKey(k.getUuid(), org.getUuid(),
					List.of(g1.getUuid()), WU));
			assertThrows(TimeoutException.class, () -> f.get(2, TimeUnit.SECONDS), "the mutation waits on the key row");
			assertFalse(stored(g1).hasApiKey(k.getUuid()));
			release.countDown();
			held.get(30, TimeUnit.SECONDS);
			f.get(30, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			holder.shutdownNow();
			caller.shutdownNow();
		}
		assertTrue(stored(g1).hasApiKey(k.getUuid()));
		assertEquals(n1 + 1, groupAudits(g1));
	}

	// ---------- row 15 (b): two calls, one key, exactly one set wins ----------

	@Test
	public void twoConcurrentCallsOnOneKeyLeaveExactlyOneDesiredSet() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org, "k15");
		ApiKeyGroupData g1 = group(org, Set.of());
		ApiKeyGroupData g2 = group(org, Set.of());
		int n1 = groupAudits(g1), n2 = groupAudits(g2), nk = keyAudits(k);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CyclicBarrier gate = new CyclicBarrier(2);
		CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
		try {
			List<Future<?>> futures = List.of(
					pool.submit(() -> setAfterGate(gate, k, org, g1, failures)),
					pool.submit(() -> setAfterGate(gate, k, org, g2, failures)));
			for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertTrue(failures.isEmpty(), failures.toString());
		assertNotEquals(stored(g1).hasApiKey(k.getUuid()), stored(g2).hasApiKey(k.getUuid()), "exactly one holds the key");
		assertEquals(1, holding(org, k.getUuid()).size());
		assertEquals(3, (groupAudits(g1) - n1) + (groupAudits(g2) - n2), "one add, then one removal and one add");
		assertEquals(nk, keyAudits(k), "the key row is locked, never written");
	}

	private void setAfterGate(CyclicBarrier gate, ApiKey k, Organization org, ApiKeyGroupData g, List<Throwable> failures) {
		try {
			gate.await(10, TimeUnit.SECONDS);
			apiKeyGroupService.setGroupsOfKey(k.getUuid(), org.getUuid(), List.of(g.getUuid()), WU);
		} catch (Throwable t) {
			failures.add(t);
		}
	}

	// ---------- row 15 (c): a delete cannot add a tombstone back ----------

	@Test
	public void aDeleteRacingTheMutationLeavesTheKeyInNoGroup() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org, "k15");
		ApiKeyGroupData g1 = group(org, Set.of());
		int n1 = groupAudits(g1);
		ExecutorService deleter = Executors.newSingleThreadExecutor();
		AtomicBoolean submitted = new AtomicBoolean(false);
		CopyOnWriteArrayList<Future<?>> deletes = new CopyOnWriteArrayList<>();
		try {
			doAnswer(inv -> {
				Object read = inv.callRealMethod();
				// setGroupsOfKey reads the groups twice (the whole-list check and its return): one delete only
				if (submitted.compareAndSet(false, true)) {
					Future<?> del = deleter.submit(() -> apiKeyService.deleteApiKey(k.getUuid(), WU));
					deletes.add(del);
					// the delete waits on the key row this mutation holds; it is never awaited to completion here
					assertThrows(TimeoutException.class, () -> del.get(2, TimeUnit.SECONDS));
				}
				return read;
			}).when(groupSpy()).getAllApiKeyGroupsByOrganization(org.getUuid());

			List<ApiKeyGroupData> after = apiKeyGroupService.setGroupsOfKey(k.getUuid(), org.getUuid(), List.of(g1.getUuid()), WU);
			assertEquals(List.of(g1.getUuid()), after.stream().map(ApiKeyGroupData::getUuid).toList(), "the add committed");
			assertEquals(1, deletes.size(), "one delete submitted");
			deletes.get(0).get(30, TimeUnit.SECONDS);
		} finally {
			Mockito.reset(groupSpy());
			deleter.shutdownNow();
		}
		assertFalse(stored(g1).hasApiKey(k.getUuid()), "the delete removed the key after the mutation committed");
		assertEquals(n1 + 2, groupAudits(g1), "one add, one removal");
		assertTrue(apiKeyService.getApiKey(k.getUuid()).isEmpty());
		RelizaException refused = assertThrows(RelizaException.class,
				() -> apiKeyGroupService.setGroupsOfKey(k.getUuid(), org.getUuid(), List.of(g1.getUuid()), WU));
		assertEquals("API key not found: " + k.getUuid(), refused.getMessage());
		assertFalse(stored(g1).hasApiKey(k.getUuid()));
		assertEquals(n1 + 2, groupAudits(g1));
		signInAs(member(org, PermissionType.ADMIN));
		assertRefused(gqlCombined(org.getUuid(), k.getUuid()), COMBINED_FIELD, "Not authorized");
		assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid()), SET_GROUPS_FIELD, "Not authorized");
	}

	// ---------- row 15 (d): the delete and the mutation lock groups in one order ----------

	@Test
	public void aDeleteAndAMutationOnAnotherKeyLockSharedGroupsInOneOrder() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k1 = freeform(org, "k15d1");
		ApiKey k2 = freeform(org, "k15d2");
		ApiKeyGroupData ga = group(org, Set.of(k2.getUuid()));
		ApiKeyGroupData gb = group(org, Set.of(k2.getUuid()));
		ApiKeyGroupData lo = ga.getUuid().compareTo(gb.getUuid()) < 0 ? ga : gb;
		ApiKeyGroupData hi = lo == ga ? gb : ga;
		// one more write to lo: its tuple is the newer one, so the heap scan returns hi first (the crossing setup)
		setMembers(lo, k2.getUuid());
		int nlo = groupAudits(lo), nhi = groupAudits(hi);

		Set<Thread> seen = ConcurrentHashMap.newKeySet();
		CountDownLatch arrived = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
		try {
			doAnswer(inv -> {
				if (seen.add(Thread.currentThread())) {   // once per thread: held after its first group lock only
					arrived.countDown();
					release.await(10, TimeUnit.SECONDS);
				}
				return inv.callRealMethod();
			}).when(auditSpy()).createAndSaveAuditRecord(eq(TableName.API_KEY_GROUPS), any());

			Future<?> fA = pool.submit(() -> {
				try {
					apiKeyGroupService.setGroupsOfKey(k1.getUuid(), org.getUuid(), List.of(lo.getUuid(), hi.getUuid()), WU);
				} catch (Throwable t) {
					failures.add(t);
				}
			});
			Future<?> fB = pool.submit(() -> {
				try {
					apiKeyService.deleteApiKey(k2.getUuid(), WU);
				} catch (Throwable t) {
					failures.add(t);
				}
			});
			// with the sort only A arrives (B waits on lo); without it both arrive and the orders cross
			arrived.await(5, TimeUnit.SECONDS);
			release.countDown();
			fA.get(30, TimeUnit.SECONDS);
			fB.get(30, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			pool.shutdownNow();
			Mockito.reset(auditSpy());
		}
		assertTrue(failures.isEmpty(), "neither call failed on a lock: " + failures);
		assertTrue(apiKeyService.getApiKey(k2.getUuid()).isEmpty(), "K2 deleted");
		assertTrue(holding(org, k2.getUuid()).isEmpty(), "no group holds the deleted key");
		assertFalse(stored(lo).hasApiKey(k2.getUuid()));
		assertFalse(stored(hi).hasApiKey(k2.getUuid()));
		assertTrue(stored(lo).hasApiKey(k1.getUuid()));
		assertTrue(stored(hi).hasApiKey(k1.getUuid()));
		assertEquals(nlo + 2, groupAudits(lo), "the add and the removal on lo");
		assertEquals(nhi + 2, groupAudits(hi), "the add and the removal on hi");
	}
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * A key row is locked while it is read, changed and saved, and the usage touch after a matched secret never writes
 * a stale record back over a delete, deactivation or regeneration (task t20261013-195114-27617).
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyRowLockingTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyRepository apiKeyRepository;
	@MockitoSpyBean private ApiKeySecretUsageService apiKeySecretUsageService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private EntityManagerFactory entityManagerFactory;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private record Keyed(Organization org, UUID uuid, String keyOrder, String secret) {}

	/** A FREEFORM key with one secret in slot 1. */
	private Keyed freeformKey() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String keyOrder = UUID.randomUUID().toString();
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(), keyOrder, "locking", WU);
		String secret = apiKeyService.addApiKeySecret(ak.getUuid(), null, WU);
		return new Keyed(org, ak.getUuid(), keyOrder, secret);
	}

	/** The row as stored, past getApiKey, which hides deleted keys. */
	private ApiKey row(UUID uuid) {
		return apiKeyRepository.findById(uuid).orElseThrow();
	}

	private Optional<ApiKeySecret> slot(UUID uuid, int slot) {
		ApiKey ak = row(uuid);
		return ApiKeyData.dataFromRecord(ak).effectiveSecrets(ak.getApiKey()).stream().filter(s -> s.getSlot() == slot).findFirst();
	}

	private String hash(UUID uuid, int slot) {
		return slot(uuid, slot).orElseThrow().getHash();
	}

	@Test
	public void aTouchAfterADeleteLeavesTheTombstone() throws Exception {
		Keyed k = freeformKey();
		String hash = hash(k.uuid(), 1);
		apiKeyService.deleteApiKey(k.uuid(), WU);

		apiKeySecretUsageService.touch(k.uuid(), 1, hash);

		ApiKey ak = row(k.uuid());
		assertEquals(ApiKeyStatus.REVOKED, ApiKeyData.dataFromRecord(ak).getStatus());
		assertTrue(ApiKeyData.dataFromRecord(ak).getSecrets().isEmpty());
		assertNull(ak.getApiKey());
	}

	@Test
	public void aTouchAfterADeactivationWritesNothing() throws Exception {
		Keyed k = freeformKey();
		String hash = hash(k.uuid(), 1);
		apiKeyService.setApiKeyStatus(k.uuid(), ApiKeyStatus.INACTIVE, true, WU);

		apiKeySecretUsageService.touch(k.uuid(), 1, hash);

		assertEquals(ApiKeyStatus.INACTIVE, ApiKeyData.dataFromRecord(row(k.uuid())).getStatus());
		assertNull(slot(k.uuid(), 1).orElseThrow().getLastUsedDate());
	}

	@Test
	public void aTouchWithARegeneratedSecretsOldHashWritesNothing() throws Exception {
		Keyed k = freeformKey();
		String old = hash(k.uuid(), 1);
		apiKeyService.regenerateApiKeySecret(k.uuid(), 1, null, WU);

		apiKeySecretUsageService.touch(k.uuid(), 1, old);

		assertNull(slot(k.uuid(), 1).orElseThrow().getLastUsedDate());
	}

	@Test
	public void aTouchOnALiveKeyStampsThatSlotOnly() throws Exception {
		Keyed k = freeformKey();
		apiKeyService.addApiKeySecret(k.uuid(), null, WU);
		Map<String, Object> before = row(k.uuid()).getRecordData();
		ZonedDateTime updated = row(k.uuid()).getLastUpdatedDate();
		int revision = row(k.uuid()).getRevision();

		apiKeySecretUsageService.touch(k.uuid(), 3, hash(k.uuid(), 1));
		assertEquals(before, row(k.uuid()).getRecordData(), "a slot the key does not have changes nothing");
		assertEquals(updated.toInstant(), row(k.uuid()).getLastUpdatedDate().toInstant());

		apiKeySecretUsageService.touch(k.uuid(), 1, hash(k.uuid(), 1));
		assertNotNull(slot(k.uuid(), 1).orElseThrow().getLastUsedDate());
		assertNull(slot(k.uuid(), 2).orElseThrow().getLastUsedDate());
		assertEquals(revision, row(k.uuid()).getRevision(), "bookkeeping, not a revision");
	}

	@Test
	public void aWriterRefusesADeletedKey() throws Exception {
		Keyed k = freeformKey();
		apiKeyService.deleteApiKey(k.uuid(), WU);

		RelizaException e = assertThrows(RelizaException.class, () -> apiKeyService.setNotesOnApiKey(k.uuid(), "x", WU));
		assertEquals("API key not found", e.getMessage());

		assertEquals(ApiKeyStatus.REVOKED, ApiKeyData.dataFromRecord(row(k.uuid())).getStatus());
		assertNull(row(k.uuid()).getApiKey());
	}

	/**
	 * The safety net in saveApiKey: a record loaded in this transaction before another one deleted the key is not
	 * written back. The persistence context still holds the live record, so the check must read the table.
	 */
	@Test
	public void aRecordLoadedBeforeADeleteInTheSameTransactionIsNotSavedOverIt() throws Exception {
		Keyed k = freeformKey();
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			Throwable refused = new TransactionTemplate(transactionManager).execute(status -> {
				ApiKey stale = apiKeyService.getApiKey(k.uuid()).orElseThrow();
				try {
					other.submit(() -> apiKeyService.deleteApiKey(k.uuid(), WU)).get(30, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
				ApiKeyData akd = ApiKeyData.dataFromRecord(stale);
				akd.setNotes("stale");
				status.setRollbackOnly();
				try {
					ApiKeyService target = AopTestUtils.getTargetObject(apiKeyService);
					ReflectionTestUtils.invokeMethod(target, "saveApiKey", stale, Utils.dataToRecord(akd), WU);
					return null;
				} catch (RuntimeException e) {
					return e;
				}
			});
			assertNotNull(refused, "the stale save was refused");
			assertTrue(refused instanceof IllegalStateException, String.valueOf(refused));
		} finally {
			other.shutdownNow();
		}
		ApiKey ak = row(k.uuid());
		assertEquals(ApiKeyStatus.REVOKED, ApiKeyData.dataFromRecord(ak).getStatus());
		assertNull(ak.getApiKey());
	}

	/**
	 * A busy row: the touch skips, and the caller's own transaction goes on. A no-wait refusal is an error in
	 * PostgreSQL that aborts the transaction it runs in, so the touch must not share the caller's.
	 */
	@Test
	public void aBusyRowSkipsTheTouchWithoutBreakingTheCallersTransaction() throws Exception {
		Keyed k = freeformKey();
		String hash = hash(k.uuid(), 1);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService holder = Executors.newSingleThreadExecutor();
		try {
			Future<?> held = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				apiKeyRepository.findByIdWriteLocked(k.uuid());
				locked.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertTrue(locked.await(30, TimeUnit.SECONDS));
			String notes = new TransactionTemplate(transactionManager).execute(status -> {
				apiKeySecretUsageService.touch(k.uuid(), 1, hash);
				return ApiKeyData.dataFromRecord(apiKeyRepository.findById(k.uuid()).orElseThrow()).getNotes();
			});
			assertEquals("locking", notes, "the caller's transaction went on and committed");
			release.countDown();
			held.get(30, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			holder.shutdownNow();
		}
		assertNull(slot(k.uuid(), 1).orElseThrow().getLastUsedDate(), "the busy touch wrote nothing");
	}

	/** A Basic match on a cache miss touches once; a second match within the cache TTL does not touch again. */
	@Test
	public void aMatchTouchesOnACacheMissOnly() throws Exception {
		Keyed k = freeformKey();
		String hash = hash(k.uuid(), 1);
		AuthHeaderParse ahp = AuthHeaderParse.builder().objUuid(k.org().getUuid()).type(ApiTypeEnum.FREEFORM)
				.keyOrder(k.keyOrder()).apiKey(k.secret()).build();
		clearInvocations(apiKeySecretUsageService);

		assertEquals(k.uuid(), apiKeyService.isMatchingApiKey(ahp));
		verify(apiKeySecretUsageService, times(1)).touch(eq(k.uuid()), eq(1), eq(hash));
		assertNotNull(slot(k.uuid(), 1).orElseThrow().getLastUsedDate());

		assertEquals(k.uuid(), apiKeyService.isMatchingApiKey(ahp));
		verify(apiKeySecretUsageService, times(1)).touch(eq(k.uuid()), anyInt(), anyString());
	}

	/** A key deleted before statuses existed (no secret column, no status) is a tombstone to the writers too. */
	@Test
	public void aKeyDeletedBeforeStatusesExistedIsNotFound() throws Exception {
		Keyed k = freeformKey();
		ApiKey legacy = row(k.uuid());
		Map<String, Object> record = new HashMap<>(legacy.getRecordData());
		record.remove(CommonVariables.STATUS_FIELD);
		record.put("secrets", List.of());
		legacy.setRecordData(record);
		legacy.setApiKey(null);
		apiKeyRepository.save(legacy);

		assertTrue(apiKeyService.getApiKey(k.uuid()).isEmpty());
		RelizaException e = assertThrows(RelizaException.class, () -> apiKeyService.setNotesOnApiKey(k.uuid(), "x", WU));
		assertEquals("API key not found", e.getMessage());
	}

	/** A key created and changed in one transaction gets the audit row and revision of the change, as before. */
	@Test
	public void aKeyChangedInTheTransactionThatCreatedItIsAudited() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = apiKeyService.requestFreeformApiKey(UUID.randomUUID(), org.getUuid(), "asked", null, null, WU);
		assertEquals(1, row(ak.getUuid()).getRevision());
		assertEquals(ApiKeyStatus.REQUESTED, ApiKeyData.dataFromRecord(row(ak.getUuid())).getStatus());
	}

	/**
	 * Open-in-view: the request's persistence context is bound with no transaction, and the touch's own transaction
	 * reuses it. The record the request read before the match must not be written back over a delete.
	 */
	@Test
	public void aTouchUnderOpenInViewDoesNotWriteTheRequestsStaleRecordBack() throws Exception {
		Keyed k = freeformKey();
		String hash = hash(k.uuid(), 1);
		EntityManager em = entityManagerFactory.createEntityManager();
		TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			ApiKey stale = apiKeyService.getApiKey(k.uuid()).orElseThrow();
			assertTrue(em.contains(stale), "the request's persistence context holds the record");
			other.submit(() -> apiKeyService.deleteApiKey(k.uuid(), WU)).get(30, TimeUnit.SECONDS);

			apiKeySecretUsageService.touch(k.uuid(), 1, hash);
		} finally {
			other.shutdownNow();
			TransactionSynchronizationManager.unbindResource(entityManagerFactory);
			em.close();
		}
		ApiKey ak = row(k.uuid());
		assertEquals(ApiKeyStatus.REVOKED, ApiKeyData.dataFromRecord(ak).getStatus());
		assertTrue(ApiKeyData.dataFromRecord(ak).getSecrets().isEmpty());
		assertNull(ak.getApiKey());
	}

	/** Minting reuses a deleted key's row on purpose: that revival is not the stale write the safety net refuses. */
	@Test
	public void aDeletedKeyCanBeMintedAgain() throws Exception {
		Keyed k = freeformKey();
		apiKeyService.deleteApiKey(k.uuid(), WU);

		String secret = apiKeyService.setObjectApiKey(k.org().getUuid(), ApiTypeEnum.FREEFORM, k.org().getUuid(), k.keyOrder(), "again", false, WU);

		assertNotNull(secret);
		assertTrue(apiKeyService.getApiKey(k.uuid()).isPresent(), "the same row is live again");
		AuthHeaderParse ahp = AuthHeaderParse.builder().objUuid(k.org().getUuid()).type(ApiTypeEnum.FREEFORM)
				.keyOrder(k.keyOrder()).apiKey(secret).build();
		assertEquals(k.uuid(), apiKeyService.isMatchingApiKey(ahp));
	}

	/** An admin's kill switch set while a federated exchange holds a stale copy of the identity stands. */
	@Test
	public void aFederatedIdentityDisabledMeanwhileStaysDisabled() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("acme");
		fi.setRepository("acme/locking-" + UUID.randomUUID());
		fi.setRepositoryUri("github.com/acme/locking");
		UUID identity = apiKeyService.ensureFederatedIdentity(org.getUuid(), fi, WU).getUuid();
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			ApiKeyStatus after = new TransactionTemplate(transactionManager).execute(status -> {
				apiKeyService.getFederatedIdentity(org.getUuid(), fi).orElseThrow();
				try {
					other.submit(() -> apiKeyService.setApiKeyStatus(identity, ApiKeyStatus.INACTIVE, true, WU)).get(30, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
				return ApiKeyData.dataFromRecord(apiKeyService.ensureFederatedIdentity(org.getUuid(), fi, WU)).getStatus();
			});
			assertEquals(ApiKeyStatus.INACTIVE, after, "the exchange sees the kill switch");
		} finally {
			other.shutdownNow();
		}
		assertEquals(ApiKeyStatus.INACTIVE, ApiKeyData.dataFromRecord(row(identity)).getStatus());
	}

	/**
	 * A check that runs while a regeneration is still uncommitted reads the old row and caches the old secret; the
	 * cache is cleared again once the regeneration commits, so the old secret stops working then, not 3 minutes on.
	 */
	@Test
	public void theVerificationCacheIsClearedAgainWhenAChangeCommits() throws Exception {
		Keyed k = freeformKey();
		AuthHeaderParse old = AuthHeaderParse.builder().objUuid(k.org().getUuid()).type(ApiTypeEnum.FREEFORM)
				.keyOrder(k.keyOrder()).apiKey(k.secret()).build();
		assertEquals(k.uuid(), apiKeyService.isMatchingApiKey(old));
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			UUID duringTheChange = new TransactionTemplate(transactionManager).execute(status -> {
				try {
					apiKeyService.regenerateApiKeySecret(k.uuid(), 1, null, WU);
					return other.submit(() -> apiKeyService.isMatchingApiKey(old)).get(30, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			assertEquals(k.uuid(), duringTheChange, "before the commit the old row still matches");
		} finally {
			other.shutdownNow();
		}
		assertNull(apiKeyService.isMatchingApiKey(old), "after the commit the old secret is refused");
	}

	/**
	 * Why this test exists: a lock read outside a transaction would lock and release at once and protect nothing.
	 * The reads are MANDATORY, so such a caller fails instead.
	 */
	@Test
	public void theLockReadsRefuseACallerWithoutATransaction() throws Exception {
		Keyed k = freeformKey();
		assertThrows(IllegalTransactionStateException.class, () -> apiKeyRepository.findByIdWriteLocked(k.uuid()));
		assertThrows(IllegalTransactionStateException.class, () -> apiKeyRepository.findByIdWriteLockedSkipLocked(k.uuid()));
		assertThrows(IllegalTransactionStateException.class, () -> apiKeyRepository.lockAndReadLiveness(k.uuid()));
		assertThrows(IllegalTransactionStateException.class, () -> apiKeyService.getApiKeyWriteLocked(k.uuid()));
	}

	/** The holder of the row lock keeps it until its transaction commits: a second locker waits that long. */
	@Test
	public void aLockedReadHoldsTheRowUntilCommit() throws Exception {
		Keyed k = freeformKey();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService holder = Executors.newSingleThreadExecutor();
		try {
			Future<?> held = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				apiKeyService.getApiKeyWriteLocked(k.uuid());
				locked.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertTrue(locked.await(30, TimeUnit.SECONDS));
			Optional<ApiKey> skipped = new TransactionTemplate(transactionManager).execute(status ->
					apiKeyRepository.findByIdWriteLockedSkipLocked(k.uuid()));
			assertTrue(skipped.isEmpty(), "a busy row reads as empty to the no-wait read");
			release.countDown();
			held.get(30, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			holder.shutdownNow();
		}
		Optional<ApiKey> free = new TransactionTemplate(transactionManager).execute(status ->
				apiKeyRepository.findByIdWriteLockedSkipLocked(k.uuid()));
		assertTrue(free.isPresent(), "once committed, the row is free");
	}
}

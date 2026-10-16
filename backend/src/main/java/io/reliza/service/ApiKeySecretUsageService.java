/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.repositories.ApiKeyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Last-use bookkeeping for a key's secrets, after a presented secret matched (task t20261013-195114-27617). It
 * depends on nothing but the repository, so ApiKeyService can call it without a cycle.
 */
@Slf4j
@Service
public class ApiKeySecretUsageService {

	private final ApiKeyRepository repository;

	@PersistenceContext
	private EntityManager entityManager;

	public ApiKeySecretUsageService(ApiKeyRepository repository) {
		this.repository = repository;
	}

	/**
	 * Records that a secret was presented and matched. Re-reads the row under a lock it does not wait for, and
	 * writes nothing when the row is busy, when the key is no longer ACTIVE, or when the slot no longer holds the
	 * hash that matched: a delete, deactivation or regeneration that committed meanwhile must not be overwritten
	 * by a stale read. Never throws from its body: usage bookkeeping must not fail the request that authenticated.
	 *
	 * <p>A transaction of its own: the touch never writes through, or waits on, a transactional caller (a
	 * resolver authenticating with a Basic credential); a caller already holding the row lock makes the row read
	 * as busy, and the touch is skipped.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void touch(UUID keyUuid, int slot, String matchedHash) {
		try {
			Optional<ApiKey> oak = repository.findByIdWriteLockedSkipLocked(keyUuid);
			if (oak.isEmpty()) return; // gone, or being written right now
			ApiKey ak = oak.get();
			// the row as it is now: under open-in-view this transaction reuses the request's persistence context,
			// which may hold the record as the caller read it before the match
			entityManager.refresh(ak);
			if (!ApiKeyService.isLive(ak)) return;
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			if (akd.getStatus() != ApiKeyStatus.ACTIVE) return;
			List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
			Optional<ApiKeySecret> target = list.stream()
					.filter(x -> x.getSlot() == slot && x.isUsable() && matchedHash.equals(x.getHash())).findFirst();
			if (target.isEmpty()) return;
			target.get().setLastUsedDate(ZonedDateTime.now());
			akd.setSecrets(list);
			ak.setRecordData(Utils.dataToRecord(akd));
			repository.save(ak); // no audit row: usage bookkeeping, same as last_access_date
		} catch (RuntimeException e) {
			log.error("could not record last use of secret slot {} on key {}", slot, keyUuid, e);
		}
	}
}

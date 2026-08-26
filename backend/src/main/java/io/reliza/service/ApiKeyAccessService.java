/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ApiKeyAccess;
import io.reliza.repositories.ApiKeyAccessRepository;
import io.reliza.repositories.ApiKeyRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ApiKeyAccessService {

	private final ApiKeyAccessRepository repository;

	private final ApiKeyRepository apiKeyRepository;

	public ApiKeyAccessService(ApiKeyAccessRepository repository, ApiKeyRepository apiKeyRepository) {
	    this.repository = repository;
	    this.apiKeyRepository = apiKeyRepository;
	}
	
	public List<ApiKeyAccess> listKeyAccessByOrg(UUID orgUuid){
		return this.repository.listKeyAccessByOrg(orgUuid);
	}
	
	public Optional<ApiKeyAccess> getKeyAccessByOrgKeyUuid (UUID orgUuid, UUID keyUuid){
		return this.repository.getKeyAccessByOrgKeyId(orgUuid, keyUuid);
	}

	private ApiKeyAccess saveApiKeyAccess (ApiKeyAccess ak) {
		return repository.save(ak);
	}

	public List<ApiKeyAccess> getListOfApiKeyAccesses(List<UUID> uuids) {
		return (List<ApiKeyAccess>) repository.findAllById(uuids);
	}

	public void saveAll(List<ApiKeyAccess> apiKeyAccesses){
		repository.saveAll(apiKeyAccesses);
	}

	/**
	 * Pending {@code api_keys.last_access_date} bumps, flushed by
	 * {@link #flushPendingLastAccessTouches()} on a scheduler tick.
	 *
	 * WHY an accumulator: this used to be a per-request UPDATE of the key's
	 * row. Every concurrent request authenticating with the same key then
	 * queued on that single row lock, and under production load the queue
	 * outlived the global 120s query timeout — each casualty failing an
	 * otherwise healthy API request with
	 * "canceling statement due to user request ... in relation api_keys".
	 * The map absorbs any request rate with zero DB writes on the hot
	 * path; the flush writes at most one guarded UPDATE per key per tick.
	 * Crash cost: at most one tick of last-access freshness on a
	 * telemetry field.
	 */
	private final ConcurrentHashMap<UUID, ZonedDateTime> pendingTouches = new ConcurrentHashMap<>();

	private void queueLastAccessTouch(UUID apiKeyUuid) {
		ZonedDateTime now = ZonedDateTime.now();
		pendingTouches.merge(apiKeyUuid, now, (a, b) -> a.isAfter(b) ? a : b);
	}

	/**
	 * Drains the accumulator into guarded per-key UPDATEs. Called from
	 * {@code SchedulingService} on a fixed rate WITHOUT an advisory lock —
	 * the accumulator is per-replica memory, so every replica must flush
	 * its own; the {@code IF NEWER} guard in the SQL keeps concurrent
	 * replica flushes monotonic. A failed key is re-queued for the next
	 * tick (keeping the newer timestamp if more accesses arrived meanwhile).
	 */
	public int flushPendingLastAccessTouches() {
		int flushed = 0;
		for (UUID keyUuid : pendingTouches.keySet()) {
			ZonedDateTime ts = pendingTouches.remove(keyUuid);
			if (ts == null) continue;
			try {
				apiKeyRepository.touchLastAccessDateIfNewer(keyUuid, ts);
				flushed++;
			} catch (Exception e) {
				pendingTouches.merge(keyUuid, ts, (a, b) -> a.isAfter(b) ? a : b);
				log.error("Failed to flush last_access_date for api key {}; re-queued", keyUuid, e);
			}
		}
		return flushed;
	}

	// Deliberately NOT @Transactional and non-throwing: this is telemetry on
	// the authentication hot path. The repository calls are individually
	// transactional, and an audit failure must never fail the request that
	// authenticated successfully.
    public void recordApiKeyAccess(UUID apiKeyUuid, String ipAddress, UUID org, String apiKeyId ){
		// Bump api_keys.last_access_date (what the per-org dashboard reads)
		// via the accumulator — never a direct row UPDATE from here.
		queueLastAccessTouch(apiKeyUuid);

		try {
			// Dedupe the per-access audit row: CI/CD pipelines polling main
			// can fire thousands of requests per key per day with the same
			// (api_key_uuid, ip_address) tuple, which grew this table to
			// >10 GB in two months in production. Skip the insert if an
			// access row already exists for the same tuple within the last
			// hour. Uses the existing (api_key_uuid, access_date DESC) index;
			// the in-line ip_address filter runs on the tiny candidate set
			// the index hands back.
			String safeIp = ipAddress != null ? ipAddress : "";
			if (repository.existsRecentAccess(apiKeyUuid, safeIp)) {
				return;
			}

			ApiKeyAccess aka = new ApiKeyAccess();
			aka.setApiKeyUuid(apiKeyUuid);
			aka.setIpAddress(safeIp);
			aka.setOrg(org);
			aka.setApiKeyId(apiKeyId);
			aka.setAccessDate(ZonedDateTime.now());

			saveApiKeyAccess(aka);
		} catch (Exception e) {
			log.error("Failed to record api key access audit row for key {}", apiKeyUuid, e);
		}
    }

	/**
	 * Daily retention sweep — deletes audit rows older than 90 days.
	 * Driven by {@code SchedulingService.purgeOldApiKeyAccessRows} at
	 * 04:30 UTC. Returns the number of rows actually deleted for log
	 * visibility.
	 */
	@Transactional
	public int purgeOldAccessRows() {
		int deleted = repository.deleteAccessRowsOlderThan90Days();
		if (deleted > 0) {
			log.info("api_key_access retention: deleted {} row(s) older than 90 days", deleted);
		}
		return deleted;
	}

	@Transactional
	public ApiKeyAccess createSbomProbingSession(UUID apiKeyUuid, String ipAddress, UUID orgUuid, String notesJson) {
		ApiKeyAccess session = new ApiKeyAccess();
		session.setApiKeyUuid(apiKeyUuid);
		session.setIpAddress(ipAddress != null ? ipAddress : "");
		session.setOrg(orgUuid);
		session.setApiKeyId("SBOM_PROBING");
		session.setNotes(notesJson);
		session.setAccessDate(ZonedDateTime.now());
		ApiKeyAccess saved = saveApiKeyAccess(session);
		queueLastAccessTouch(apiKeyUuid);
		return saved;
	}

	@Transactional
	public void updateSbomProbingSessionNotes(UUID sessionUuid, String notesJson) {
		Optional<ApiKeyAccess> oas = repository.findById(sessionUuid);
		if (oas.isPresent()) {
			ApiKeyAccess session = oas.get();
			session.setNotes(notesJson);
			saveApiKeyAccess(session);
		}
	}

	public Optional<ApiKeyAccess> getSbomProbingSession(UUID sessionUuid) {
		return repository.findById(sessionUuid);
	}

}

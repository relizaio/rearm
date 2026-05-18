/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ApiKeyAccess;
import io.reliza.repositories.ApiKeyAccessRepository;
import io.reliza.repositories.ApiKeyRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ApiKeyAccessService {

	@Autowired
	private final ApiKeyAccessRepository repository;

	@Autowired
	private ApiKeyRepository apiKeyRepository;

	public ApiKeyAccessService(ApiKeyAccessRepository repository) {
	    this.repository = repository;
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

	@Transactional
    public void recordApiKeyAccess(UUID apiKeyUuid, String ipAddress, UUID org, String apiKeyId ){
		// Always bump api_keys.last_access_date — that's what the per-org
		// dashboard reads, and it should reflect the latest activity.
		apiKeyRepository.touchLastAccessDate(apiKeyUuid);

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
		apiKeyRepository.touchLastAccessDate(apiKeyUuid);
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

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

@Service
public class ApiKeyAccessService {
	
	@Autowired
	private final ApiKeyAccessRepository repository;

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
		ApiKeyAccess aka = new ApiKeyAccess();
		aka.setApiKeyUuid(apiKeyUuid);
		aka.setIpAddress(ipAddress);
		aka.setOrg(org);
		aka.setApiKeyId(apiKeyId);
		aka.setAccessDate(ZonedDateTime.now());
      
        saveApiKeyAccess(aka);
    }
	
}

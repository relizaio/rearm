/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyAccess;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ComponentData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.repositories.ApiKeyRepository;

@Service
public class ApiKeyService {
	
	@Autowired
    private AuditService auditService;
	
	@Autowired
	private GetComponentService getComponentService;;

	@Autowired 
	private ApiKeyAccessService apiKeyAccessService;
	
	private final ApiKeyRepository repository;
	
    @Autowired
	public ApiKeyService(ApiKeyRepository repository) {
	    this.repository = repository;
	}
	
	public void deleteApiKey(UUID uuid, WhoUpdated wu){
		Optional<ApiKey> oak = getApiKey(uuid);
		ApiKey ak = oak.get();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		ak.setApiKey(null);
		
		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu);
	}

	private Optional<ApiKey> getApiKey (UUID uuid) {
		return repository.findByUUID(uuid);
	}
	
	public Optional<ApiKeyData> getApiKeyData (UUID uuid) {
		Optional<ApiKeyData> oakd = Optional.empty();
		Optional<ApiKey> oak = getApiKey(uuid);
		if (oak.isPresent()) {
			oakd = Optional.of(ApiKeyData.dataFromRecord(oak.get()));
		}
		return oakd;
	}
	
	public Optional<ApiKeyDto> getApiKeyDto (UUID uuid) {
		Optional<ApiKeyDto> oakd = Optional.empty();
		Optional<ApiKey> oak = getApiKey(uuid);
		if (oak.isPresent()) {
			oakd = Optional.of(ApiKeyDto.fromApiKey(oak.get()));
		}
		return oakd;
	}
	
	private List<ApiKey> listApiKeyByObjUuidAndType(UUID uuid, ApiTypeEnum type, UUID org) {
		List<ApiKey> retList = new LinkedList<>();
		// if type is user, org is required
		if (ApiTypeEnum.USER == type) {
			retList = repository.findUserApiKeyByUserUuidAndOrgUuid(uuid, org);
		} else {
			retList = repository.findApiKeyByUuidAndType(uuid, type.toString()); 
		}
		return retList;
	}
	
	private Optional<ApiKey> getApiKeyByObjUuidTypeOrder(UUID uuid, ApiTypeEnum type, String keyOrder, UUID org) {
		Optional<ApiKey> oak = Optional.empty();
		var keyList = listApiKeyByObjUuidAndType(uuid, type, org);
		if (!keyList.isEmpty()) {
			if (StringUtils.isEmpty(keyOrder)) {
				oak = Optional.of(keyList.get(0));
			} else {
				oak = keyList.stream().filter(k -> keyOrder.equals(k.getKeyOrder())).findFirst();
			}
		}
		return oak;
	}
	
	public List<ApiKeyData> listApiKeyDataByObjUuidAndType (UUID uuid, ApiTypeEnum type, UUID org) {
		List<ApiKeyData> oakd = new LinkedList<>();
		List<ApiKey> oak = listApiKeyByObjUuidAndType(uuid, type, org);
		if (!oak.isEmpty()) {
			oakd = oak.stream().map(ApiKeyData::dataFromRecord).collect(Collectors.toList());
		}
		return oakd;
	}
	
	public Optional<ApiKeyDto> getApiKeyDataByObjUuidTypeOrder (UUID uuid, ApiTypeEnum type, String keyOrder, UUID org) {
		Optional<ApiKeyDto> oakd = Optional.empty();
		Optional<ApiKey> oak = getApiKeyByObjUuidTypeOrder(uuid, type, keyOrder, org);
		if (oak.isPresent()) {
			oakd = Optional.of(ApiKeyDto.fromApiKey(oak.get()));
		}
		return oakd;
	}
	
	public List<ApiKey> listApiKeyByOrg(UUID orgUuid) {
		return repository.listKeysByOrg(orgUuid);
	}

	public List<ApiKey> getListOfApiKeys(List<UUID> apiKeyUuids) {
		return (List<ApiKey>) repository.findAllById(apiKeyUuids);
	}
	
	public List<ApiKeyData> listApiKeyDataByOrg(UUID orgUuid) {
		List<ApiKey> akList = listApiKeyByOrg(orgUuid);
		return akList.stream()
				.map(ApiKeyData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	public List<ApiKeyDto>  listApiKeyDtoByOrgWithLastAccessDate(UUID orgUuid) {
		List<ApiKey> akList = listApiKeyByOrg(orgUuid);
		List<ApiKeyAccess> akaList = apiKeyAccessService.listKeyAccessByOrg(orgUuid);
		Map<UUID, ZonedDateTime> apiKeyAccess = akaList.stream().collect(Collectors.toMap(ApiKeyAccess::getApiKeyUuid, ApiKeyAccess::getAccessDate));
		
		return akList.stream().map(ak -> {
			ApiKeyDto akDto = ApiKeyDto.fromApiKey(ak);
			akDto.setAccessDate(apiKeyAccess.get(ak.getUuid()));
			return akDto;
		}).toList();
		
	}
	
	public String setObjectApiKey (UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, String notes, WhoUpdated wu) {
		// invalidate old key first if any
		Optional<ApiKey> presentKey = getApiKeyByObjUuidTypeOrder(uuid, type, keyOrder, suppliedOrgUuid);
		ApiKey ak = null;
		ApiKeyData akd = null;
		if (presentKey.isPresent()) {
			ak = presentKey.get();
			akd = ApiKeyData.dataFromRecord(ak);
			// increment version
			akd.setVersion(akd.getVersion() + 1);
		} else {
			ak = new ApiKey();
			ak.setObjectType(type);
			ak.setObjectUuid(uuid);
			ak.setKeyOrder(keyOrder);
			// figure out organization
			UUID orgUuid = null;
			switch (type) {
			case INSTANCE:
			case CLUSTER:
				orgUuid = suppliedOrgUuid;
				break;
			case COMPONENT:
				Optional<ComponentData> ocd = getComponentService.getComponentData(uuid);
				if (ocd.isPresent()) {
					orgUuid = ocd.get().getOrg();
				}
				break;
			case USER:
			case APPROVAL:
			case ORGANIZATION:
			case ORGANIZATION_RW:
				orgUuid = suppliedOrgUuid;
				break;
			// no default case - will fail for any unknown types since org is required
			}
			ak.setOrg(orgUuid);
			// init ApiKeyData
			akd = ApiKeyData.apiKeyDataFactory(orgUuid);
		}
		// generate new key itself
		StringBuilder keyBuilder = new StringBuilder();
		for (int i=0; i<4; i++) {
			keyBuilder.append(KeyGenerators.string().generateKey());
		}
		String apiKeyString = keyBuilder.toString();
		Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		String enKey = encoder.encode(apiKeyString);
		ak.setApiKey(enKey);
		akd.setNotes(notes);
		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu);
		return apiKeyString;
	}

	/**
	 * 
	 * @param ahp
	 * @return UUID of matching API Key if matches, otherwise null
	 */
	public UUID isMatchingApiKey(AuthHeaderParse ahp) {
		UUID matchingKeyId = null;
		Optional<ApiKey> oak = getApiKeyByObjUuidTypeOrder(ahp.getObjUuid(), ahp.getType(), ahp.getKeyOrder(), ahp.getOrgUuid());
		
		if (oak.isPresent()) {
			Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
			ApiKey ak = oak.get();
			boolean matches = encoder.matches(ahp.getApiKey(), ak.getApiKey());
			if (!matches) {
				BCryptPasswordEncoder bcryptEncoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B);
				matches = bcryptEncoder.matches(ahp.getApiKey(), ak.getApiKey());
			}
			if (matches) matchingKeyId = oak.get().getUuid();
		}
		return matchingKeyId;
	}
	
	public UUID getOrgUuidFromKey (AuthHeaderParse ahp) {
		UUID orgUuid = null;
		var key = getApiKeyDataByObjUuidTypeOrder(ahp.getObjUuid(), ahp.getType(), ahp.getKeyOrder(), ahp.getOrgUuid());
		if (key.isPresent()) {
			orgUuid = key.get().getOrg();
		}
		return orgUuid;
	}
	
	@Transactional
	public ApiKeyDto setApprovalTypes(UUID keyUuid, Collection<String> approvals, WhoUpdated wu) {
		ApiKeyDto retAkd = null;
		Optional<ApiKey> oak = getApiKey(keyUuid);
		if (oak.isPresent()) {
			ApiKey ak = oak.get();
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			akd.setPermission(ak.getOrg(), PermissionScope.ORGANIZATION, ak.getOrg(), PermissionType.NONE, approvals);
			Map<String,Object> recordData = Utils.dataToRecord(akd);
			ak = saveApiKey(ak, recordData, wu);
			retAkd = ApiKeyDto.fromApiKey(ak);
		}
		return retAkd;
	}
	
	@Transactional
	public ApiKeyDto setNotes(UUID keyUuid, String notes, WhoUpdated wu){
		ApiKeyDto retAkd = null;
		Optional<ApiKey> oak = getApiKey(keyUuid);
		if (oak.isPresent()) {
			ApiKey ak = oak.get();
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			akd.setNotes(notes);
			Map<String,Object> recordData = Utils.dataToRecord(akd);
			ak = saveApiKey(ak, recordData, wu);
			retAkd = ApiKeyDto.fromApiKey(ak);
		}
		return retAkd;
	}
	
	@Transactional
	private ApiKey saveApiKey (ApiKey ak, Map<String,Object> recordData, WhoUpdated wu) {
		// TODO: add validation
		Optional<ApiKey> oak = getApiKey(ak.getUuid());
		if (oak.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.API_KEYS, ak);
			ak.setRevision(ak.getRevision() + 1);
			ak.setLastUpdatedDate(ZonedDateTime.now());
		}
		ak.setRecordData(recordData);
		ak = (ApiKey) WhoUpdated.injectWhoUpdatedData(ak, wu);
		return repository.save(ak);
	}
	
	public void saveAll(List<ApiKey> apiKeys){
		repository.saveAll(apiKeys);
	}
	
}

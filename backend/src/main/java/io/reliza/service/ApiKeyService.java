/**
* Copyright Reliza Incorporated. 2019 - 2022. All rights reserved.
*/
package io.reliza.service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.RequestType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyAccess;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
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
	
	@Autowired 
	private OrganizationService organizationService;
	
	private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
	
	private final ApiKeyRepository repository;

	private UserService userService;
	
    @Autowired
	public ApiKeyService(ApiKeyRepository repository, @Lazy UserService userService) {
	    this.repository = repository;
	    this.userService = userService;
	}
	
	public void deleteApiKey(UUID uuid, WhoUpdated wu){
		Optional<ApiKey> oak = getApiKey(uuid);
		ApiKey ak = oak.get();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		ak.setApiKey(null);
		
		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu);
	}

	/**
	 * Deletes all API keys of a user in org including registry keys
	 * @param userId
	 * @param org
	 * @param wu
	 */
	public void deleteAllApiKeysByUserAndOrg(UUID userId, UUID org, WhoUpdated wu){
		List<ApiKey> userAks = repository.findUserApiKeyByUserUuidAndOrgUuid(userId, org);
		List<ApiKey> regAks = repository.findRegistryApiKey(userId, org, ApiTypeEnum.REGISTRY_USER.toString());
		Stream.concat(userAks.stream(), regAks.stream()).forEach(ak -> deleteApiKey(ak.getUuid(), wu));
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
		} else if (ApiTypeEnum.REGISTRY_ORG == type || ApiTypeEnum.REGISTRY_USER == type) {
			retList = repository.findRegistryApiKey(uuid, org, type.toString());
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
//			case INSTANCE:
//			case CLUSTER:
//				Optional<InstanceData> oid = instanceService.getInstanceData(uuid);
//				if (oid.isPresent()) {
//					orgUuid = oid.get().getOrg();
//				}
//				break;
			case COMPONENT:
			case VERSION_GEN:
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
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B);
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
			BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B);
			ApiKey ak = oak.get();
			boolean matches = encoder.matches(ahp.getApiKey(), ak.getApiKey());
			if (matches) matchingKeyId = oak.get().getUuid();
		}
		return matchingKeyId;
	}
	
	public AuthHeaderParse isProgrammaticAccessAuthorized(HttpHeaders headers,
			HttpServletResponse response, String remoteIp, CallType ct) {
		AuthHeaderParse ahp = null;
		try {
			ahp = AuthHeaderParse.parseAuthHeader(headers, remoteIp);
			log.debug("PSDEBUG: ahp org = " + ahp.getOrgUuid() + ", type = " + ahp.getType() + 
					", obj = " + ahp.getObjUuid());
			isProgrammaticAccessAuthorized(ahp, response, ct);
		} catch (Exception e) {
			try {
				log.warn("Exception when authorizing programmatic access", e);
				if (!response.isCommitted()) {
					response.sendError(HttpStatus.FORBIDDEN.value(), "You do not have permissions to this resource");
				}
			} catch (IOException ioe) {
				throw new IllegalStateException("No permissions");
			}
		}
		return ahp;
	}
	
	public UUID getOrgUuidFromKey (AuthHeaderParse ahp) {
		UUID orgUuid = null;
		var key = getApiKeyDataByObjUuidTypeOrder(ahp.getObjUuid(), ahp.getType(), ahp.getKeyOrder(), ahp.getOrgUuid());
		if (key.isPresent()) {
			orgUuid = key.get().getOrg();
		}
		return orgUuid;
	}

	public UUID isProgrammaticAccessAuthorized(AuthHeaderParse ahp, CallType ct) {
		return isProgrammaticAccessAuthorized(ahp, null, RequestType.GRAPHQL, ct);
	}
	
	public UUID isProgrammaticAccessAuthorized(AuthHeaderParse ahp, HttpServletResponse response, CallType ct) {
		return isProgrammaticAccessAuthorized(ahp, response, RequestType.REST, ct);
	}
	
	/**
	 * 
	 * @param ahp
	 * @param response
	 * @param rt
	 * @return if authorized, returns matching key UUID, otherwise returns null
	 */
	public UUID isProgrammaticAccessAuthorized(AuthHeaderParse ahp, HttpServletResponse response, RequestType rt, CallType ct) {
		UUID matchingKeyId = null;;
		String apiKey = ahp.getApiKey();
		if (StringUtils.isNotEmpty(apiKey)) matchingKeyId = isMatchingApiKey(ahp);
		if (null == matchingKeyId) {
			try {
				if (rt == RequestType.REST) {
					response.sendError(HttpStatus.FORBIDDEN.value(), "You do not have permissions to this resource");
				}
			} catch (IOException e) {
				log.error("IO error when sending response", e);
				// re-throw
				throw new RuntimeException("IO error when sending error response");
			}
		}

		//check if user has access to the organization
		if(null != matchingKeyId && ahp.getType() == ApiTypeEnum.USER){
			UserData ud = userService.getUserData(ahp.getObjUuid()).get();
			log.debug("is User authorized in checking for programmatic access");
			boolean authorized = organizationService.isUserAuthorizedOrgWide(ud, ahp.getOrgUuid(), response, ct);
			log.debug("completed is User authorized for programmatic access");
			if (!authorized) matchingKeyId = null;
		}

		Optional<ApiKeyData> oakd = getApiKeyData(matchingKeyId);
		if(oakd.isPresent()){
			ApiKeyData akd = oakd.get();
			apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), akd.getOrg(), ahp.getApiKeyId());

		}
		return matchingKeyId;
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

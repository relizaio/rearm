/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.NonNull;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ComponentData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.repositories.ApiKeyRepository;

@Slf4j
@Service
public class ApiKeyService {
	
	@Autowired
    private AuditService auditService;
	
	@Autowired
	private GetComponentService getComponentService;;

	private final ApiKeyRepository repository;

	/**
	 * Cache of successfully-verified (apiKeyUuid, presentedSecret) tuples
	 * so that subsequent authenticated requests can skip the Argon2
	 * verification entirely. Argon2 is memory-hard (~16 MB allocation per
	 * matches() call with the v5_8 defaults) and was OOMing the request
	 * thread under polling load. TTL is intentionally short (3 minutes)
	 * to bound the window during which a revoked key would still verify
	 * if invalidation somehow missed an entry; the deliberate revocation
	 * paths ({@link #deleteApiKey} and the regeneration branch of
	 * {@link #setObjectApiKey}) call {@link #invalidateVerificationCacheForKey}
	 * to evict immediately.
	 *
	 * <p>Cache value is just a sentinel {@link Boolean#TRUE} — the key
	 * carries everything we need to identify the verified combination.
	 * Cache key is {@code apiKeyUuid + ":" + sha256Hex(presentedSecret)};
	 * the SHA-256 keeps plaintext API keys out of the cache map.
	 *
	 * <p>Negatives are not cached — repeated wrong guesses still pay
	 * the Argon2 cost, naturally rate-limiting brute-force attempts.
	 */
	private static final Duration VERIFICATION_CACHE_TTL = Duration.ofMinutes(3);
	private final Cache<String, Boolean> verifiedKeyCache = Caffeine.newBuilder()
			.maximumSize(50_000)
			.expireAfterWrite(VERIFICATION_CACHE_TTL)
			.build();

    @Autowired
	public ApiKeyService(ApiKeyRepository repository) {
	    this.repository = repository;
	}

	private static String cacheKeyFor(UUID apiKeyUuid, String presentedSecret) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(presentedSecret.getBytes(StandardCharsets.UTF_8));
			return apiKeyUuid + ":" + HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed available on every JRE; this is unreachable.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/**
	 * Drop all cached verifications for the given stored ApiKey UUID.
	 * Called whenever the stored hash changes (revoke / regenerate) so
	 * that the new hash takes effect immediately rather than waiting
	 * for the cache TTL.
	 */
	private void invalidateVerificationCacheForKey(UUID apiKeyUuid) {
		if (apiKeyUuid == null) return;
		String prefix = apiKeyUuid + ":";
		verifiedKeyCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
	}

	public void deleteApiKey(UUID uuid, WhoUpdated wu){
		Optional<ApiKey> oak = getApiKey(uuid);
		ApiKey ak = oak.get();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		ak.setApiKey(null);

		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu);
		invalidateVerificationCacheForKey(uuid);
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
	
	private List<ApiKey> listApiKeyByObjUuidAndType(UUID uuid, ApiTypeEnum type, @NonNull UUID org) {
		return repository.findApiKeyByUuidAndType(uuid, type.toString(), org);
	}

	private Optional<ApiKey> getApiKeyByObjUuidTypeOrder(UUID uuid, ApiTypeEnum type, String keyOrder, UUID org) {
		return getApiKeyByObjUuidTypeOrder(uuid, type, keyOrder, org, false);
	}

	/**
	 * Resolve the api_keys row for a given object + type + (optional)
	 * keyOrder + org. When {@code includeRevoked} is true, tombstoned
	 * rows (api_key IS NULL — i.e., previously revoked / archived
	 * keys) are also returned. Callers other than the mint path
	 * ({@link #setObjectApiKey}) should pass false, so auth, listing,
	 * and archive-cleanup paths keep treating revoked keys as absent.
	 */
	private Optional<ApiKey> getApiKeyByObjUuidTypeOrder(UUID uuid, ApiTypeEnum type, String keyOrder, UUID org,
			boolean includeRevoked) {
		Optional<ApiKey> oak = Optional.empty();
		List<ApiKey> keyList = includeRevoked
				? repository.findApiKeyIncludingRevokedByUuidAndType(uuid, type.toString(), org)
				: listApiKeyByObjUuidAndType(uuid, type, org);
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
	
	public List<ApiKeyDto> listApiKeyDtoByOrgWithLastAccessDate(UUID orgUuid) {
		// last_access_date is denormalised onto api_keys (V29) and bumped in
		// ApiKeyAccessService write paths, so we no longer scan
		// api_key_access here. That table grows unboundedly with traffic and
		// the DISTINCT ON join used to dominate this query's runtime.
		return listApiKeyByOrg(orgUuid).stream().map(ak -> {
			ApiKeyDto akDto = ApiKeyDto.fromApiKey(ak);
			akDto.setAccessDate(ak.getLastAccessDate());
			return akDto;
		}).toList();
	}
	
	public String setObjectApiKey (UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, String notes, WhoUpdated wu) {
		// Resolve effective org up front so that presentKey lookup can find an existing row
		// even when caller passes suppliedOrgUuid=null (e.g. ComponentDataFetcher for COMPONENT).
		UUID effectiveOrgUuid = suppliedOrgUuid;
		if (effectiveOrgUuid == null && type == ApiTypeEnum.COMPONENT) {
			effectiveOrgUuid = getComponentService.getComponentData(uuid).map(ComponentData::getOrg).orElse(null);
		}
		// Look up any row in the natural-key slot, including revoked
		// (api_key IS NULL) tombstones. The unique index on
		// (object_uuid, object_type, org, key_order) means a tombstone
		// row blocks an INSERT — if we ignored revoked rows here, the
		// INSERT below would hit a DataIntegrityViolationException
		// surfaced to the UI as "Request violates data constraints".
		// Mint should be idempotent: re-take the existing row in place
		// (whether it currently has a usable key or is a tombstone left
		// behind by archiveInstance / archiveComponent / archiveOrganization
		// / deleteApiKey) and write a fresh key into it.
		Optional<ApiKey> presentKey = getApiKeyByObjUuidTypeOrder(uuid, type, keyOrder, effectiveOrgUuid, true);
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
			UUID orgUuid = effectiveOrgUuid;
			if (orgUuid == null) {
				switch (type) {
				case INSTANCE:
				case CLUSTER:
				case APPROVAL:
				case ORGANIZATION:
				case ORGANIZATION_RW:
				case FREEFORM:
					orgUuid = suppliedOrgUuid;
					break;
				case COMPONENT:
					// already attempted above; leave null so downstream fails loudly if unresolvable
					break;
				// no default case - will fail for any unknown types since org is required
				}
			}
			ak.setOrg(orgUuid);
			ak.setCreatedBy(wu.getLastUpdatedBy());
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
		// On regeneration the stored hash changes, so the old cached
		// verifications must not be honored. Safe to call even for
		// freshly-created keys (no cache entries to evict).
		invalidateVerificationCacheForKey(ak.getUuid());
		return apiKeyString;
	}

	/**
	 * Resolve the org for an authenticated key by looking up the stored row.
	 * Useful for key types whose auth header does not embed the org (FREEFORM).
	 */
	public UUID resolveOrgForKey(AuthHeaderParse ahp) {
		if (ahp == null || ahp.getObjUuid() == null || ahp.getType() == null) return null;
		return repository.findApiKeyByUuidAndTypeOnly(ahp.getObjUuid(), ahp.getType().toString(),
				StringUtils.isEmpty(ahp.getKeyOrder()) ? null : ahp.getKeyOrder())
				.map(ApiKey::getOrg).orElse(null);
	}

	/**
	 *
	 * @param ahp
	 * @return UUID of matching API Key if matches, otherwise null
	 */
	public UUID isMatchingApiKey(AuthHeaderParse ahp) {
		UUID matchingKeyId = null;
		UUID orgUuid = ahp.getOrgUuid();
		if (orgUuid == null && ahp.getType() == ApiTypeEnum.COMPONENT) {
			orgUuid = getComponentService.getComponentData(ahp.getObjUuid()).map(c -> c.getOrg()).orElse(null);
		}
		if (orgUuid == null && (ahp.getType() == ApiTypeEnum.INSTANCE || ahp.getType() == ApiTypeEnum.CLUSTER)) {
			orgUuid = repository.findApiKeyByUuidAndTypeOnly(ahp.getObjUuid(), ahp.getType().toString(),
					StringUtils.isEmpty(ahp.getKeyOrder()) ? null : ahp.getKeyOrder())
					.map(ApiKey::getOrg).orElse(null);
		}
		if (orgUuid == null && ahp.getType() == ApiTypeEnum.FREEFORM) {
			orgUuid = repository.findApiKeyByUuidAndTypeOnly(ahp.getObjUuid(), ahp.getType().toString(),
					StringUtils.isEmpty(ahp.getKeyOrder()) ? null : ahp.getKeyOrder())
					.map(ApiKey::getOrg).orElse(null);
		}
		if (orgUuid == null) {
			log.warn("SECURITY: programmatic auth failed - could not resolve org for type={} obj={} ip={}",
					ahp.getType(), ahp.getObjUuid(), ahp.getRemoteIp());
			return null;
		}
		Optional<ApiKey> oak = getApiKeyByObjUuidTypeOrder(ahp.getObjUuid(), ahp.getType(), ahp.getKeyOrder(), orgUuid);
		
		if (oak.isPresent()) {
			ApiKey ak = oak.get();
			// Cache lookup before paying the Argon2 cost. Argon2PasswordEncoder
			// .defaultsForSpringSecurity_v5_8() allocates ~16 MB per matches()
			// call (memory-hard KDF by design); under polling load that
			// dominated heap and OOMed the request thread. The cache lets
			// each (apiKeyUuid, presentedSecret) combination skip Argon2
			// for VERIFICATION_CACHE_TTL after a successful match.
			String cacheKey = cacheKeyFor(ak.getUuid(), ahp.getApiKey());
			Boolean cached = verifiedKeyCache.getIfPresent(cacheKey);
			if (Boolean.TRUE.equals(cached)) {
				matchingKeyId = ak.getUuid();
			} else {
				Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
				if (encoder.matches(ahp.getApiKey(), ak.getApiKey())) {
					matchingKeyId = ak.getUuid();
					verifiedKeyCache.put(cacheKey, Boolean.TRUE);
				}
				// Deliberately do NOT cache negatives — repeated wrong
				// guesses keep paying the Argon2 cost, which provides
				// natural rate-limiting against brute-force attempts.
			}
		}
		if (matchingKeyId == null) {
			log.warn("SECURITY: programmatic auth failed - invalid key for type={} obj={} keyId={} ip={}",
					ahp.getType(), ahp.getObjUuid(), ahp.getApiKeyId(), ahp.getRemoteIp());
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
	public ApiKeyDto setPermissionsOnApiKey(UUID keyUuid, PermissionType orgPermissionType,
			List<PermissionDto> permissions, WhoUpdated wu) throws RelizaException {
		Optional<ApiKey> oak = getApiKey(keyUuid);
		if (oak.isEmpty()) throw new RelizaException("API key not found");
		ApiKey ak = oak.get();
		if (ak.getObjectType() != ApiTypeEnum.FREEFORM) {
			throw new RelizaException("setPermissionsOnApiKey is only supported for FREEFORM keys");
		}
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.revokeAllOrgPermissions(ak.getOrg());
		if (orgPermissionType != null) {
			akd.setPermission(ak.getOrg(), PermissionScope.ORGANIZATION, ak.getOrg(), orgPermissionType, null);
		}
		for (PermissionDto p : permissions) {
			UUID permOrg = p.org() != null ? p.org() : ak.getOrg();
			akd.setPermission(permOrg, p.scope(), p.object(), p.type(),
				p.functions() != null ? p.functions() : List.of(),
				p.approvals() != null ? p.approvals() : List.of());
		}
		Map<String, Object> recordData = Utils.dataToRecord(akd);
		ak = saveApiKey(ak, recordData, wu);
		return ApiKeyDto.fromApiKey(ak);
	}

	/**
	 * Edit just the {@code notes} field on an API key — independent of
	 * the permissions edit path so a notes-only update doesn't have to
	 * round-trip the full permissions list. Caller is expected to be an
	 * org admin (the ws-layer datafetcher enforces that). Works for
	 * any key type, not only FREEFORM, since notes is a generic field
	 * on the api_keys recordData jsonb.
	 */
	@Transactional
	public ApiKeyDto setNotesOnApiKey(UUID keyUuid, String notes, WhoUpdated wu) throws RelizaException {
		Optional<ApiKey> oak = getApiKey(keyUuid);
		if (oak.isEmpty()) throw new RelizaException("API key not found");
		ApiKey ak = oak.get();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setNotes(notes);
		Map<String, Object> recordData = Utils.dataToRecord(akd);
		ak = saveApiKey(ak, recordData, wu);
		return ApiKeyDto.fromApiKey(ak);
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

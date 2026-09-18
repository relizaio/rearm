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
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.ApiKeyData.ApiKeySecret;
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
		akd.setSecrets(new LinkedList<>());
		akd.setStatus(ApiKeyStatus.REVOKED);

		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu);
		invalidateVerificationCacheForKey(uuid);
	}

	public Optional<ApiKey> getApiKey (UUID uuid) {
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

	public Optional<ApiKey> getApiKeyByObjUuidTypeOrder(UUID uuid, ApiTypeEnum type, String keyOrder, UUID org) {
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
	public Optional<ApiKey> getApiKeyByObjUuidTypeOrder(UUID uuid, ApiTypeEnum type, String keyOrder, UUID org,
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
	
	private record KeyRow(ApiKey ak, ApiKeyData akd) {}

	/**
	 * Create the key id without any secret (status ACTIVE, empty secrets list). Minting a secret is a
	 * separate operation: {@link #addApiKeySecret}. Reuses a tombstone in the same natural-key slot.
	 */
	public ApiKey createObjectApiKey(UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, String notes, WhoUpdated wu) {
		KeyRow row = ensureObjectApiKeyRow(uuid, type, suppliedOrgUuid, keyOrder, wu);
		row.akd().setNotes(notes);
		ApiKey saved = saveApiKey(row.ak(), Utils.dataToRecord(row.akd()), wu);
		invalidateVerificationCacheForKey(saved.getUuid());
		return saved;
	}

	/** Create-or-reuse the row and mint slot 1 in one go (component / instance keys and the legacy org mint). */
	public String setObjectApiKey (UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, String notes, WhoUpdated wu) {
		KeyRow row = ensureObjectApiKeyRow(uuid, type, suppliedOrgUuid, keyOrder, wu);
		ApiKey ak = row.ak();
		ApiKeyData akd = row.akd();
		// generate new key itself: slot 1 of the secrets list, mirrored into the legacy column
		String apiKeyString = newSecretString();
		List<ApiKeySecret> secrets = new LinkedList<>(akd.getSecrets().stream().filter(x -> x.getSlot() != 1).toList());
		secrets.add(0, new ApiKeySecret(1, hashSecret(apiKeyString), true, ZonedDateTime.now(), null));
		akd.setSecrets(secrets);
		mirrorPrimary(ak, akd);
		akd.setNotes(notes);
		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu);
		// On regeneration the stored hash changes, so the old cached
		// verifications must not be honored. Safe to call even for
		// freshly-created keys (no cache entries to evict).
		invalidateVerificationCacheForKey(ak.getUuid());
		return apiKeyString;
	}

	/** Find or build the row for a natural key; a reused tombstone comes back ACTIVE with its secrets cleared. Not saved. */
	private KeyRow ensureObjectApiKeyRow(UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, WhoUpdated wu) {
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
			if (akd.getStatus() == ApiKeyStatus.REVOKED) akd.setSecrets(new LinkedList<>());
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
				case USER:
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
		akd.setStatus(ApiKeyStatus.ACTIVE);
		return new KeyRow(ak, akd);
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
		if (ahp.getVerifiedKeyUuid() != null) {
			// access-token path: the programmatic authentication filter already verified the
			// token against this key row (status, signature, expiry, audience, secret fingerprint)
			return ahp.getVerifiedKeyUuid();
		}
		UUID matchingKeyId = null;
		UUID orgUuid = ahp.getOrgUuid();
		if (orgUuid == null && ahp.getType() == ApiTypeEnum.COMPONENT) {
			orgUuid = getComponentService.getComponentData(ahp.getObjUuid()).map(c -> c.getOrg()).orElse(null);
		}
		if (orgUuid == null && (ahp.getType() == ApiTypeEnum.INSTANCE || ahp.getType() == ApiTypeEnum.CLUSTER || ahp.isRbacKey())) {
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
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			if (akd.getStatus() != ApiKeyStatus.ACTIVE) {
				log.warn("SECURITY: programmatic auth refused - key {} is INACTIVE (ip={})", ak.getUuid(), ahp.getRemoteIp());
				return null;
			}
			// Try every active secret of the key id. Cache lookup before paying the Argon2 cost:
			// Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8() allocates ~16 MB per
			// matches() call (memory-hard KDF by design); under polling load that dominated heap
			// and OOMed the request thread. The cache lets each (apiKeyUuid, slot, presentedSecret)
			// combination skip Argon2 for VERIFICATION_CACHE_TTL after a successful match.
			for (ApiKeySecret sec : akd.effectiveSecrets(ak.getApiKey())) {
				if (!sec.isUsable() || sec.getHash() == null) continue;
				String cacheKey = cacheKeyFor(ak.getUuid(), sec.getSlot() + ":" + ahp.getApiKey());
				if (Boolean.TRUE.equals(verifiedKeyCache.getIfPresent(cacheKey))) {
					matchingKeyId = ak.getUuid();
					ahp.setMatchedSecretSlot(sec.getSlot());
					break;
				}
				Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
				if (encoder.matches(ahp.getApiKey(), sec.getHash())) {
					matchingKeyId = ak.getUuid();
					ahp.setMatchedSecretSlot(sec.getSlot());
					verifiedKeyCache.put(cacheKey, Boolean.TRUE);
					touchSecretLastUsed(ak, akd, sec.getSlot()); // at most once per cache TTL per secret
					break;
				}
				// Deliberately do NOT cache negatives - repeated wrong guesses keep paying the
				// Argon2 cost, which provides natural rate-limiting against brute-force attempts.
			}
		}
		if (matchingKeyId == null) {
			log.warn("SECURITY: programmatic auth failed - invalid key for type={} obj={} keyId={} ip={}",
					ahp.getType(), ahp.getObjUuid(), ahp.getApiKeyId(), ahp.getRemoteIp());
		}
		return matchingKeyId;
	}

	private void touchSecretLastUsed(ApiKey ak, ApiKeyData akd, int slot) {
		try {
			List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
			list.stream().filter(x -> x.getSlot() == slot).findFirst().ifPresent(x -> x.setLastUsedDate(ZonedDateTime.now()));
			akd.setSecrets(list);
			ak.setRecordData(Utils.dataToRecord(akd));
			repository.save(ak); // no audit row: usage bookkeeping, same as last_access_date
		} catch (RuntimeException e) {
			log.error("could not record last use of secret slot {} on key {}", slot, ak.getUuid(), e);
		}
	}

	// ------------------------------------------------------------------ secrets lifecycle

	/**
	 * A write user asks for a FREEFORM key: the row exists at once (status REQUESTED, holder = requester,
	 * proposed permissions) and stays unusable until an admin approves it.
	 */
	public ApiKey requestFreeformApiKey(UUID userUuid, UUID orgUuid, String notes, PermissionType permissionType,
			List<PermissionDto> permissions, WhoUpdated wu) throws RelizaException {
		ApiKey ak = createObjectApiKey(orgUuid, ApiTypeEnum.FREEFORM, orgUuid, UUID.randomUUID().toString(), notes, wu);
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setHolder(userUuid);
		akd.setStatus(ApiKeyStatus.REQUESTED);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		if (permissions != null && !permissions.isEmpty()) {
			setPermissionsOnApiKey(ak.getUuid(), permissionType, permissions, wu);
			ak = getApiKey(ak.getUuid()).orElseThrow();
		}
		return ak;
	}

	/** Admin decision on a REQUESTED key: approve makes it ACTIVE (still without a secret); deny keeps it visible as DENIED with the reason in the notes. */
	public ApiKeyDto resolveApiKeyRequest(UUID keyUuid, boolean approve, String reason, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (akd.getStatus() != ApiKeyStatus.REQUESTED) throw new RelizaException("This key is not a pending request");
		if (approve) {
			akd.setStatus(ApiKeyStatus.ACTIVE);
		} else {
			akd.setStatus(ApiKeyStatus.DENIED);
			String note = akd.getNotes() == null ? "" : akd.getNotes();
			akd.setNotes((note.isBlank() ? "" : note + " | ") + "Denied" + (StringUtils.isBlank(reason) ? "" : ": " + reason));
		}
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

	/** Reassign (or clear with null) the holder of a FREEFORM key, e.g. after the previous holder left the org. */
	public ApiKeyDto setApiKeyHolder(UUID keyUuid, UUID holder, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		if (ak.getObjectType() != ApiTypeEnum.FREEFORM) throw new RelizaException("Only FREEFORM keys have a holder");
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setHolder(holder);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		return ApiKeyDto.fromApiKey(ak);
	}

	/** Marks where a key came from (CLI browser login keys are deleted with their session). */
	public ApiKey setApiKeyOrigin(UUID keyUuid, ApiKeyData.ApiKeyOrigin origin, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setOrigin(origin);
		return saveApiKey(ak, Utils.dataToRecord(akd), wu);
	}

	/** Live keys this user owns (USER) or holds (FREEFORM with holder = user), across orgs; metadata only. */
	public List<ApiKeyDto> listUserKeyDtos(UUID userUuid) {
		return repository.findUserApiKeysByUserUuid(userUuid).stream().map(ApiKeyDto::fromApiKey).toList();
	}

	/** When a user leaves an org, their USER keys there are revoked: nothing could authorize them anyway. */
	public int revokeUserKeysInOrg(UUID userUuid, UUID orgUuid, WhoUpdated wu) {
		List<ApiKey> keys = repository.findUserApiKeyByUserUuidAndOrgUuid(userUuid, orgUuid);
		for (ApiKey ak : keys) deleteApiKey(ak.getUuid(), wu);
		return keys.size();
	}

	/** Key id string clients present as the Basic user name; stable across secret rotation. */
	public static String keyIdOf(ApiKey ak) {
		String id = ak.getObjectType() + "__" + ak.getObjectUuid();
		if (StringUtils.isNotEmpty(ak.getKeyOrder())) id = id + "__ord__" + ak.getKeyOrder();
		return id;
	}

	private static String newSecretString() {
		StringBuilder keyBuilder = new StringBuilder();
		for (int i = 0; i < 4; i++) keyBuilder.append(KeyGenerators.string().generateKey());
		return keyBuilder.toString();
	}

	private static String hashSecret(String cleartext) {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(cleartext);
	}

	/** Mirror slot 1 into the legacy column for readers of api_key; liveness itself is decided by status, not by the column. */
	private static void mirrorPrimary(ApiKey ak, ApiKeyData akd) {
		ak.setApiKey(akd.secret(1).map(ApiKeySecret::getHash).orElse(null));
	}

	/**
	 * Add the second secret of a key id (AWS-style rotation): returns the new cleartext once.
	 * Refused when both slots are taken; retire one first.
	 */
	public String addApiKeySecret(UUID keyUuid, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		if (list.size() >= ApiKeyData.MAX_SECRETS) {
			throw new RelizaException("This key already has " + ApiKeyData.MAX_SECRETS + " secrets; regenerate or retire one instead of adding");
		}
		int slot = list.stream().anyMatch(x -> x.getSlot() == 1) ? 2 : 1;
		String cleartext = newSecretString();
		list.add(new ApiKeySecret(slot, hashSecret(cleartext), true, ZonedDateTime.now(), null, expiresDate));
		list.sort((a, b) -> Integer.compare(a.getSlot(), b.getSlot()));
		akd.setSecrets(list);
		akd.setVersion(akd.getVersion() + 1);
		mirrorPrimary(ak, akd);
		saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return cleartext;
	}

	/** Replace one secret in place: the old hash stops matching and every token exchanged with it dies. */
	public String regenerateApiKeySecret(UUID keyUuid, int slot, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		ApiKeySecret target = list.stream().filter(x -> x.getSlot() == slot).findFirst()
				.orElseThrow(() -> new RelizaException("This key has no secret in slot " + slot));
		String cleartext = newSecretString();
		target.setHash(hashSecret(cleartext));
		target.setCreatedDate(ZonedDateTime.now());
		target.setLastUsedDate(null);
		target.setActive(true);
		target.setExpiresDate(expiresDate);
		akd.setSecrets(list);
		akd.setVersion(akd.getVersion() + 1);
		mirrorPrimary(ak, akd);
		saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return cleartext;
	}

	/** Remove one secret for good: its slot frees up, and every token exchanged with it dies. */
	public ApiKeyDto deleteApiKeySecret(UUID keyUuid, int slot, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		if (!list.removeIf(x -> x.getSlot() == slot)) {
			throw new RelizaException("This key has no secret in slot " + slot);
		}
		akd.setSecrets(list);
		akd.setVersion(akd.getVersion() + 1);
		mirrorPrimary(ak, akd);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

	/** Set or clear (null) the expiry of one secret; the change applies to the next check. */
	public ApiKeyDto setApiKeySecretExpiry(UUID keyUuid, int slot, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		ApiKeySecret target = list.stream().filter(x -> x.getSlot() == slot).findFirst()
				.orElseThrow(() -> new RelizaException("This key has no secret in slot " + slot));
		target.setExpiresDate(expiresDate);
		akd.setSecrets(list);
		mirrorPrimary(ak, akd);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

	/** Retire or re-enable one secret without deleting it; tokens exchanged with it follow. */
	public ApiKeyDto setApiKeySecretActive(UUID keyUuid, int slot, boolean active, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		ApiKeySecret target = list.stream().filter(x -> x.getSlot() == slot).findFirst()
				.orElseThrow(() -> new RelizaException("This key has no secret in slot " + slot));
		target.setActive(active);
		akd.setSecrets(list);
		mirrorPrimary(ak, akd);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

	/** Kill switch for the whole key id: every secret and every token stop working until re-activated. */
	/**
	 * Kill switch. An admin's deactivation is authoritative: it marks the key adminDisabled and the
	 * owner or holder cannot re-activate it; an admin re-activation clears the mark.
	 */
	public ApiKeyDto setApiKeyStatus(UUID keyUuid, ApiKeyStatus status, boolean byAdmin, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (status != ApiKeyStatus.ACTIVE && status != ApiKeyStatus.INACTIVE) throw new RelizaException("Only ACTIVE or INACTIVE can be set here");
		if (akd.getStatus() != ApiKeyStatus.ACTIVE && akd.getStatus() != ApiKeyStatus.INACTIVE) throw new RelizaException("This key is a request; approve or deny it instead");
		if (status == ApiKeyStatus.ACTIVE && akd.isAdminDisabled() && !byAdmin) {
			throw new RelizaException("This key was disabled by an organization administrator; only an administrator can re-enable it");
		}
		if (status == ApiKeyStatus.INACTIVE && byAdmin) akd.setAdminDisabled(true);
		if (status == ApiKeyStatus.ACTIVE) akd.setAdminDisabled(false);
		akd.setStatus(status);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

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
		if (ak.getObjectType() != ApiTypeEnum.FREEFORM && ak.getObjectType() != ApiTypeEnum.USER) {
			throw new RelizaException("setPermissionsOnApiKey is only supported for FREEFORM and USER keys");
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
	

	// ---- federated identities ------------------------------------------------------------------

	/** Natural key of the identity row for one repository of a provider within the org. */
	public static String federatedKeyOrder(io.reliza.model.FederatedIdentity fi) {
		return fi.getProvider() + ":" + (fi.getRepository() == null ? "" : fi.getRepository().toLowerCase());
	}

	/** The FEDERATED row for this repository in the org, tombstones included, without writing anything. */
	public Optional<ApiKey> getFederatedIdentity(UUID orgUuid, io.reliza.model.FederatedIdentity fi) {
		return getApiKeyByObjUuidTypeOrder(orgUuid, ApiTypeEnum.FEDERATED, federatedKeyOrder(fi), orgUuid, true);
	}

	/**
	 * The FEDERATED row for this repository in the org, created on first use. Ids are pinned when
	 * first seen; the caller compares later ones. A revoked row comes back active (nothing to clear,
	 * these rows never hold secrets). The last ref, run and actor are refreshed on every exchange.
	 */
	public ApiKey ensureFederatedIdentity(UUID orgUuid, io.reliza.model.FederatedIdentity fi, WhoUpdated wu) {
		String keyOrder = federatedKeyOrder(fi);
		Optional<ApiKey> present = getApiKeyByObjUuidTypeOrder(orgUuid, ApiTypeEnum.FEDERATED, keyOrder, orgUuid, true);
		ApiKey ak;
		ApiKeyData akd;
		if (present.isPresent()) {
			ak = present.get();
			akd = ApiKeyData.dataFromRecord(ak);
			// a deleted row is a tombstone: the rules still trust the repository, so it comes back; the admin kill switch (INACTIVE) is refused by the exchange before this
			if (akd.getStatus() == ApiKeyStatus.REVOKED) akd.setStatus(ApiKeyStatus.ACTIVE);
			io.reliza.model.FederatedIdentity stored = akd.getFederation() == null ? new io.reliza.model.FederatedIdentity() : akd.getFederation();
			stored.setProvider(fi.getProvider());
			stored.setIssuer(fi.getIssuer());
			stored.setOwner(fi.getOwner());
			stored.setRepository(fi.getRepository());
			stored.setRepositoryUri(fi.getRepositoryUri());
			if (stored.getRepositoryId() == null && fi.getRepositoryId() != null) {
				stored.setRepositoryId(fi.getRepositoryId());
				stored.setOwnerId(fi.getOwnerId());
				stored.setPinnedDate(ZonedDateTime.now());
			}
			stored.setLastRef(fi.getLastRef());
			stored.setLastRunId(fi.getLastRunId());
			stored.setLastActor(fi.getLastActor());
			akd.setFederation(stored);
			akd.setVersion(akd.getVersion() + 1);
		} else {
			ak = new ApiKey();
			ak.setObjectType(ApiTypeEnum.FEDERATED);
			ak.setObjectUuid(orgUuid);
			ak.setKeyOrder(keyOrder);
			ak.setOrg(orgUuid);
			ak.setCreatedBy(wu.getLastUpdatedBy());
			akd = ApiKeyData.apiKeyDataFactory(orgUuid);
			fi.setPinnedDate(fi.getRepositoryId() == null ? null : ZonedDateTime.now());
			akd.setFederation(fi);
			akd.setNotes(fi.getProvider() + ": " + fi.getRepository());
		}
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		return ak;
	}

	/** Accept whatever repository id the next exchange presents. Admin only; the caller gates. */
	public ApiKeyDto resetFederatedIdentityPin(UUID keyUuid, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("Identity not found"));
		if (ak.getObjectType() != ApiTypeEnum.FEDERATED) throw new RelizaException("Not a federated identity");
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (akd.getFederation() != null) {
			akd.getFederation().setRepositoryId(null);
			akd.getFederation().setOwnerId(null);
			akd.getFederation().setPinnedDate(null);
		}
		akd.setVersion(akd.getVersion() + 1);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		return ApiKeyDto.fromApiKey(ak);
	}
}

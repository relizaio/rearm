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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.TxUtils;
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
import io.reliza.repositories.CliSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Slf4j
@Service
public class ApiKeyService {
	
	@Autowired
    private AuditService auditService;

	/** The repository, not CliSessionService: that service depends on this one, and Spring refuses the cycle. */
	@Autowired
	private CliSessionRepository cliSessionRepository;

	@Autowired
	private ApiKeySecretUsageService apiKeySecretUsageService;

	@PersistenceContext
	private EntityManager entityManager;
	
	@Autowired
	private GetComponentService getComponentService;;

	private final ApiKeyRepository repository;

	/** Longest key note the server stores, in code points (four times the declared-name cap of 128); stored data depends on it. */
	public static final int MAX_NOTES_LENGTH = 512;

	/** Longest denial reason, in code points; a longer one is refused. */
	public static final int MAX_DENIAL_REASON_LENGTH = 256;

	/** The refusal when anyone but an organization administrator would re-enable a key an administrator disabled. */
	public static final String ADMIN_DISABLED_REFUSAL = "This key was disabled by an organization administrator; only an administrator can re-enable it";

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
	 * Cache key is {@code apiKeyUuid + ":" + sha256Hex(slot + ":" + storedHash + ":" + presentedSecret)};
	 * the SHA-256 keeps plaintext API keys out of the cache map, and the stored hash
	 * means an entry can only ever answer for the secret as it is stored now.
	 *
	 * <p>Negatives are not cached — repeated wrong guesses still pay
	 * the Argon2 cost, naturally rate-limiting brute-force attempts.
	 *
	 * <p>The usage touch after a match goes through {@link ApiKeySecretUsageService}, in a
	 * transaction of its own under a row lock it does not wait for: a key being written is
	 * skipped, and a stale read never writes a deleted, deactivated or regenerated record back.
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

	/**
	 * Key notes as the server stores them, or null for nothing. Notes are free text rendered as a key's
	 * label in the UI, written into committed YAML by the declarative export and printed to terminals by
	 * apiKeysProgrammatic, so every control, format (bidi, zero-width), line or paragraph separator and
	 * lone surrogate becomes a space, except the zero-width joiner and non-joiner that scripts and emoji
	 * need; runs of whitespace, no-break spaces included, collapse to one space; the ends are stripped.
	 * The result is cut to {@link #MAX_NOTES_LENGTH} code points, never through a surrogate pair.
	 */
	public static String normalizeNotes(String notes) {
		String cleaned = cleanNotes(notes);
		return null == cleaned ? null : cutToCodePoints(cleaned, MAX_NOTES_LENGTH);
	}

	/** Why these notes cannot be stored as written, or null: the cleaned text is longer than {@link #MAX_NOTES_LENGTH}. */
	public static String notesProblem(String notes) {
		return longerThan(cleanNotes(notes), MAX_NOTES_LENGTH) ? "notes are at most " + MAX_NOTES_LENGTH + " characters" : null;
	}

	/** The normalised notes for a caller's write, refusing over-long notes rather than cutting them. */
	public static String checkedNotes(String notes) throws RelizaException {
		String problem = notesProblem(notes);
		if (null != problem) throw new RelizaException(problem);
		return normalizeNotes(notes);
	}

	/** The normalised reason for a denial, refusing one longer than {@link #MAX_DENIAL_REASON_LENGTH}. */
	public static String checkedDenialReason(String reason) throws RelizaException {
		String cleaned = cleanNotes(reason);
		if (longerThan(cleaned, MAX_DENIAL_REASON_LENGTH)) {
			throw new RelizaException("a denial reason is at most " + MAX_DENIAL_REASON_LENGTH + " characters");
		}
		return cleaned;
	}

	private static boolean longerThan(String s, int max) {
		return null != s && s.codePointCount(0, s.length()) > max;
	}

	/** {@link #normalizeNotes} without the length cut; null when nothing is left. */
	private static String cleanNotes(String notes) {
		if (null == notes) return null;
		StringBuilder sb = new StringBuilder(Math.min(notes.length(), 4 * MAX_NOTES_LENGTH));
		boolean pendingSpace = false;
		for (int i = 0; i < notes.length(); ) {
			int cp = notes.codePointAt(i);
			i += Character.charCount(cp);
			if (isNotesSeparator(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				pendingSpace = true;
				continue;
			}
			if (pendingSpace && sb.length() > 0) sb.append(' ');
			pendingSpace = false;
			sb.appendCodePoint(cp);
		}
		return trimmedNotes(sb.toString());
	}

	private static boolean isNotesSeparator(int cp) {
		if (isJoiner(cp)) return false;
		int type = Character.getType(cp);
		return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE;
	}

	/** Zero-width non-joiner and joiner: kept inside a note, but never left dangling at either end. */
	private static boolean isJoiner(int cp) {
		return cp == 0x200C || cp == 0x200D;
	}

	/** {@code s} without spaces or joiners at either end; null when nothing is left. */
	private static String trimmedNotes(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && (s.charAt(start) == ' ' || isJoiner(s.charAt(start)))) start++;
		while (end > start && (s.charAt(end - 1) == ' ' || isJoiner(s.charAt(end - 1)))) end--;
		return start == end ? null : s.substring(start, end);
	}

	/** The first {@code max} code points of {@code s}, without a trailing space or joiner. */
	static String cutToCodePoints(String s, int max) {
		if (!longerThan(s, max)) return s;
		return trimmedNotes(s.codePoints().limit(max)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString());
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
		evictVerifications(apiKeyUuid);
		// and again once the change commits: until then a check reads the old row and may cache it
		TxUtils.afterCommitOrNow(() -> evictVerifications(apiKeyUuid));
	}

	private void evictVerifications(UUID apiKeyUuid) {
		String prefix = apiKeyUuid + ":";
		verifiedKeyCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
	}

	/**
	 * Revokes the key: no secrets, status REVOKED. A CLI session acts as its key, so a deleted key takes its
	 * active sessions with it, in the same transaction. The sessions are revoked first, so this path and
	 * {@link CliSessionService#revoke} (which locks its session, then deletes a CLI_SESSION-origin key) take
	 * their row locks in one order, sessions then key; revoke has saved its session REVOKED by then, so the
	 * session update matches no row or rewrites the same state, and nothing recurses. A key already deleted
	 * (which {@link #getApiKey} no longer finds) is left as it is and any session still active on it is
	 * revoked, so deleting twice is harmless.
	 */
	@Transactional
	public void deleteApiKey(UUID uuid, WhoUpdated wu){
		// sessions first, then the key: the lock order of CliSessionService.revoke and releaseHeldKeysInOrg
		int revoked = cliSessionRepository.revokeActiveByApiKey(uuid);
		Optional<ApiKey> oak = getApiKeyWriteLocked(uuid);
		if (oak.isEmpty()) log.warn("API key {} to delete is not live; revoked any session still active on it", uuid);
		if (oak.isPresent()) {
			ApiKey ak = oak.get();
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			akd.setSecrets(new LinkedList<>());
			akd.setAdminDisabled(akd.isSwitchedOffByAdmin()); // the tombstone keeps an administrator's switch, and only one that is on
			akd.setStatus(ApiKeyStatus.REVOKED);
			ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
			// cleared after saveApiKey's liveness read, so a key whose record has no status yet reads as live there
			// and gets its audit row
			ak.setApiKey(null);
			repository.save(ak);
		}
		invalidateVerificationCacheForKey(uuid);
		if (revoked > 0) log.info("API key {} deleted: revoked {} active CLI session(s)", uuid, revoked);
	}

	/**
	 * The key a caller names: its uuid ({@code apiKeys { uuid }}), or the key id it was issued with
	 * ({@code TYPE__objectUuid} or {@code TYPE__objectUuid__ord__keyOrder}). Someone scripting a
	 * cleanup only has the key id -- its trailing uuid is the key order, not the key's uuid -- so
	 * both have to name the key. Empty when nothing matches or the reference is malformed.
	 */
	public Optional<ApiKey> resolveApiKeyReference (String ref) {
		if (StringUtils.isBlank(ref)) return Optional.empty();
		String trimmed = ref.trim();
		try {
			return getApiKey(UUID.fromString(trimmed));
		} catch (IllegalArgumentException notAUuid) {
			// fall through to the key id form
		}
		// Split off the key order first, as auth header parsing does: an order may itself contain
		// "__" (a federated key's order is provider:owner/repo).
		String[] orderSplit = trimmed.split("__ord__", 2);
		String[] head = orderSplit[0].split("__");
		if (head.length != 2 || (orderSplit.length == 2 && StringUtils.isEmpty(orderSplit[1]))) return Optional.empty();
		try {
			ApiTypeEnum type = ApiTypeEnum.valueOf(head[0]);
			UUID objectUuid = UUID.fromString(head[1]);
			return orderSplit.length == 2
					? repository.findApiKeyByUuidAndTypeOnly(objectUuid, type.toString(), orderSplit[1])
					// No order in the id: it names the object's key that has none, not any of its keys.
					: repository.findApiKeyByUuidAndTypeWithoutOrder(objectUuid, type.toString());
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	public Optional<ApiKey> getApiKey (UUID uuid) {
		return repository.findByUUID(uuid);
	}
	
	/**
	 * FIND_API_KEY_BY_UUID in Java: a key is live while it has a legacy secret column or a status other than
	 * REVOKED; a deleted key (a tombstone) is not. The query's registry robot rows have no ApiTypeEnum value and
	 * never load as an ApiKey.
	 */
	public static boolean isLive(ApiKey ak) {
		return isLive(ak.getApiKey(), ak.getRecordData());
	}

	/** Whether saving this record makes the row live, read from the record about to be written. */
	private static boolean writesLive(ApiKey ak, Map<String, Object> recordData) {
		return isLive(ak.getApiKey(), recordData);
	}

	// the raw status, not ApiKeyData.getStatus(), which reads a missing status as ACTIVE: a key deleted before
	// statuses existed has none and is a tombstone, as the SQL rule says
	private static boolean isLive(String apiKeyColumn, Map<String, Object> recordData) {
		if (apiKeyColumn != null) return true;
		Object status = recordData == null ? null : recordData.get(CommonVariables.STATUS_FIELD);
		return status != null && !ApiKeyStatus.REVOKED.name().equals(status.toString());
	}

	/**
	 * The live key, its row locked until the caller's transaction ends; empty for a missing or deleted key.
	 * Re-read from the table even when this persistence context already holds the key, so a writer acts on
	 * the row as it is once the lock is held. Callers run in a transaction.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<ApiKey> getApiKeyWriteLocked(UUID uuid) {
		return lockAndRefresh(uuid).filter(ApiKeyService::isLive);
	}

	/** The key row in any status, deleted ones included, locked and re-read from the table. */
	private Optional<ApiKey> lockAndRefresh(UUID uuid) {
		Optional<ApiKey> oak = repository.findByIdWriteLocked(uuid);
		oak.ifPresent(entityManager::refresh);
		return oak;
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
		// last_access_date is denormalised onto api_keys (V29) and bumped through the ApiKeyAccessService
		// accumulator, so we no longer scan api_key_access here: that table grows unboundedly with traffic and
		// the DISTINCT ON join used to dominate this query's runtime.
		return listApiKeyByOrg(orgUuid).stream().map(ApiKeyDto::fromApiKey).toList();
	}
	
	private record KeyRow(ApiKey ak, ApiKeyData akd) {}

	/**
	 * Create the key id without any secret (status ACTIVE, empty secrets list). Minting a secret is a
	 * separate operation: {@link #addApiKeySecret}. Reuses a tombstone in the same natural-key slot; one an
	 * administrator switched off comes back INACTIVE and still marked (callers pass a fresh key order, so none does).
	 */
	@Transactional
	public ApiKey createObjectApiKey(UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, String notes, WhoUpdated wu) {
		KeyRow row = ensureObjectApiKeyRow(uuid, type, suppliedOrgUuid, keyOrder, wu);
		row.akd().setNotes(normalizeNotes(notes));
		ApiKey saved = saveApiKey(row.ak(), Utils.dataToRecord(row.akd()), wu, true);
		invalidateVerificationCacheForKey(saved.getUuid());
		return saved;
	}

	/**
	 * Create-or-reuse the row and mint slot 1 in one go (component / instance keys and the legacy org mint). A reused
	 * row comes back ACTIVE, except that a key an administrator disabled, deleted since or not, is re-enabled only when
	 * {@code byAdmin}, as {@link #setApiKeyStatus} would; anyone else is refused and the row is left as it was.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public String setObjectApiKey(UUID uuid, ApiTypeEnum type, UUID suppliedOrgUuid, String keyOrder, String notes,
			boolean byAdmin, WhoUpdated wu) throws RelizaException {
		KeyRow row = ensureObjectApiKeyRow(uuid, type, suppliedOrgUuid, keyOrder, wu);
		ApiKey ak = row.ak();
		ApiKeyData akd = row.akd();
		if (akd.isSwitchedOffByAdmin()) {
			if (!byAdmin) throw new RelizaException(ADMIN_DISABLED_REFUSAL);
			akd.setStatus(ApiKeyStatus.ACTIVE);
			akd.setAdminDisabled(false);
		}
		// generate new key itself: slot 1 of the secrets list, mirrored into the legacy column
		String apiKeyString = newSecretString();
		List<ApiKeySecret> secrets = new LinkedList<>(akd.getSecrets().stream().filter(x -> x.getSlot() != 1).toList());
		secrets.add(0, new ApiKeySecret(1, hashSecret(apiKeyString), true, ZonedDateTime.now(), null));
		akd.setSecrets(secrets);
		mirrorPrimary(ak, akd);
		akd.setNotes(normalizeNotes(notes));
		Map<String,Object> recordData = Utils.dataToRecord(akd);
		saveApiKey(ak, recordData, wu, true);
		// On regeneration the stored hash changes, so the old cached
		// verifications must not be honored. Safe to call even for
		// freshly-created keys (no cache entries to evict).
		invalidateVerificationCacheForKey(ak.getUuid());
		return apiKeyString;
	}

	/**
	 * Find or build the row for a natural key; a reused tombstone comes back ACTIVE with its secrets cleared, and any
	 * other reused row ACTIVE too, except one an administrator disabled, deleted since or not, which stays INACTIVE
	 * and marked for the caller to decide. Not saved.
	 */
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
		Optional<ApiKey> presentKey = getApiKeyByObjUuidTypeOrder(uuid, type, keyOrder, effectiveOrgUuid, true)
				.flatMap(found -> lockAndRefresh(found.getUuid()));
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
		// archiving a component or an instance deletes its keys at write level, so the switch must survive a delete
		boolean switchedOff = akd.isSwitchedOffByAdmin();
		akd.setStatus(switchedOff ? ApiKeyStatus.INACTIVE : ApiKeyStatus.ACTIVE);
		if (!switchedOff) akd.setAdminDisabled(false);
		akd.setDeniedReason(null);
		return new KeyRow(ak, akd);
	}

	/**
	 * Resolve the org for an authenticated key by looking up the stored row.
	 * Useful for key types whose auth header does not embed the org (FREEFORM).
	 */
	public UUID resolveOrgForKey(AuthHeaderParse ahp) {
		if (ahp == null || ahp.getObjUuid() == null || ahp.getType() == null) return null;
		if (presentsNoRequiredKeyOrder(ahp)) return null;
		return repository.findApiKeyByUuidAndTypeOnly(ahp.getObjUuid(), ahp.getType().toString(),
				StringUtils.isEmpty(ahp.getKeyOrder()) ? null : ahp.getKeyOrder())
				.map(ApiKey::getOrg).orElse(null);
	}

	/**
	 * True when the parse names a key type that always carries a key order
	 * ({@link ApiTypeEnum#requiresKeyOrder()}) but presents none or an empty one.
	 */
	private static boolean presentsNoRequiredKeyOrder(AuthHeaderParse ahp) {
		return ahp.getType() != null && ahp.getType().requiresKeyOrder() && StringUtils.isEmpty(ahp.getKeyOrder());
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
		if (presentsNoRequiredKeyOrder(ahp)) {
			// an id of an ordered type without its order names every key of its object: refused before any
			// key-table read, so neither the answer nor its cost depends on how many keys the object holds
			log.warn("SECURITY: programmatic auth failed - {} presented without its key order ip={}",
					ahp.canonicalId(), ahp.getRemoteIp());
			return null;
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
			// and OOMed the request thread. The cache lets each (apiKeyUuid, slot, stored hash, presented
			// secret) combination skip Argon2 for VERIFICATION_CACHE_TTL after a successful match.
			for (ApiKeySecret sec : akd.effectiveSecrets(ak.getApiKey())) {
				if (!sec.isUsable() || sec.getHash() == null) continue;
				// the stored hash is part of the key: a regenerated secret can never hit an entry of the old one
				String cacheKey = cacheKeyFor(ak.getUuid(), sec.getSlot() + ":" + sec.getHash() + ":" + ahp.getApiKey());
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
					touchSecretLastUsed(ak, sec.getSlot(), sec.getHash()); // at most once per cache TTL per secret
					break;
				}
				// Deliberately do NOT cache negatives - repeated wrong guesses keep paying the
				// Argon2 cost, which provides natural rate-limiting against brute-force attempts.
			}
		}
		if (matchingKeyId == null) {
			log.warn("SECURITY: programmatic auth failed - invalid key for {} ip={}", refusedIdentity(ahp, oak), ahp.getRemoteIp());
		}
		return matchingKeyId;
	}

	/**
	 * What a refused key check logs: the parse's canonical identity (never the presented id text, whose order part is
	 * client free text), then {@code row <uuid>} when a key row was found (a wrong secret on a real key) or
	 * {@code no row}.
	 */
	static String refusedIdentity(AuthHeaderParse ahp, Optional<ApiKey> row) {
		String id = ahp == null ? "" : ahp.canonicalId();
		return id + (row != null && row.isPresent() ? " row " + row.get().getUuid() : " no row");
	}

	private void touchSecretLastUsed(ApiKey ak, int slot, String hash) {
		// this catch also covers a failure at commit, thrown by the transaction proxy after touch returns
		try {
			apiKeySecretUsageService.touch(ak.getUuid(), slot, hash);
		} catch (RuntimeException e) {
			log.error("could not record last use of secret slot {} on key {}", slot, ak.getUuid(), e);
		}
	}

	// ------------------------------------------------------------------ secrets lifecycle

	/**
	 * A write user asks for a FREEFORM key: the row exists at once (status REQUESTED, holder = requester,
	 * proposed permissions) and stays unusable until an admin approves it.
	 */
	@Transactional
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

	/**
	 * Admin decision on a REQUESTED key: approve makes it ACTIVE (still without a secret); deny keeps it visible
	 * as DENIED with the reason in deniedReason, the empty string when none is given, and the notes as the
	 * requester wrote them. A reader takes the reason from the notes only when deniedReason is null (a key
	 * denied before the field existed), so a denial never leaves it null: a requester's note could pose as
	 * the reason otherwise.
	 */
	@Transactional
	public ApiKeyDto resolveApiKeyRequest(UUID keyUuid, boolean approve, String reason, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (akd.getStatus() != ApiKeyStatus.REQUESTED) throw new RelizaException("This key is not a pending request");
		if (approve) {
			akd.setStatus(ApiKeyStatus.ACTIVE);
			akd.setDeniedReason(null); // a request has none; kept explicit so only a DENIED key ever carries one
		} else {
			akd.setStatus(ApiKeyStatus.DENIED);
			String why = checkedDenialReason(reason);
			akd.setDeniedReason(null == why ? "" : why);
		}
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

	/**
	 * Reassign (or clear with null) the holder of a FREEFORM key, e.g. after the previous holder left the org.
	 * Only a holder can approve a CLI session on the key, so a change revokes the key's active sessions: they
	 * belong to earlier holders. Sessions go first, then the key, the same lock order as {@link #deleteApiKey}.
	 */
	@Transactional
	public ApiKeyDto setApiKeyHolder(UUID keyUuid, UUID holder, WhoUpdated wu) throws RelizaException {
		ApiKey seen = getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		if (seen.getObjectType() != ApiTypeEnum.FREEFORM) throw new RelizaException("Only FREEFORM keys have a holder");
		// sessions first, then the key, the lock order of deleteApiKey: a change revokes the sessions of earlier holders
		UUID seenHolder = ApiKeyData.dataFromRecord(seen).getHolder();
		int revoked = Objects.equals(seenHolder, holder) ? 0 : cliSessionRepository.revokeActiveByApiKey(keyUuid);
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		// the decision above was taken on the unlocked read; a holder changed in between asks for a retry
		if (!Objects.equals(ApiKeyData.dataFromRecord(ak).getHolder(), seenHolder)) throw new RelizaException("The key changed meanwhile; try again");
		if (revoked > 0) log.info("API key {} holder changed: revoked {} active CLI session(s)", keyUuid, revoked);
		return ApiKeyDto.fromApiKey(replaceHolder(ak, ApiKeyData.dataFromRecord(ak), holder, wu));
	}

	/** Sets the holder of a locked FREEFORM key; the caller has revoked the key's sessions first. */
	private ApiKey replaceHolder(ApiKey ak, ApiKeyData akd, UUID holder, WhoUpdated wu) {
		if (Objects.equals(akd.getHolder(), holder)) return ak;
		akd.setHolder(holder);
		return saveApiKey(ak, Utils.dataToRecord(akd), wu);
	}

	/**
	 * When a user leaves an org: their active CLI sessions on the org's keys are revoked, the FREEFORM keys they
	 * hold there lose their holder, and their key requests there, waiting or denied, are withdrawn. Sessions go
	 * before any key, the lock order of {@link #deleteApiKey}, so the caller runs this before
	 * {@link #revokeUserKeysInOrg} (their personal keys).
	 */
	@Transactional
	public void releaseHeldKeysInOrg(UUID userUuid, UUID orgUuid, WhoUpdated wu) {
		int revoked = cliSessionRepository.revokeActiveByUserAndOrg(userUuid, orgUuid);
		int released = 0;
		int withdrawn = 0;
		for (ApiKey found : repository.findHeldApiKeysByUserUuidAndOrgUuid(userUuid, orgUuid)) {
			// sessions first, then the key; decided on the row under its lock, skipping a key deleted or
			// reassigned meanwhile
			revoked += cliSessionRepository.revokeActiveByApiKey(found.getUuid());
			Optional<ApiKey> locked = getApiKeyWriteLocked(found.getUuid());
			if (locked.isEmpty()) continue;
			ApiKeyData akd = ApiKeyData.dataFromRecord(locked.get());
			if (!userUuid.equals(akd.getHolder())) continue;
			if (akd.getStatus() == ApiKeyStatus.REQUESTED || akd.getStatus() == ApiKeyStatus.DENIED) {
				deleteApiKey(found.getUuid(), wu);
				withdrawn++;
			} else {
				replaceHolder(locked.get(), akd, null, wu);
				released++;
			}
		}
		if (revoked + released + withdrawn > 0) {
			log.info("user {} left org {}: revoked {} active CLI session(s), released {} held key(s), withdrew {} key request(s)",
					userUuid, orgUuid, revoked, released, withdrawn);
		}
	}

	/** Marks where a key came from (CLI browser login keys are deleted with their session). */
	@Transactional
	public ApiKey setApiKeyOrigin(UUID keyUuid, ApiKeyData.ApiKeyOrigin origin, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setOrigin(origin);
		return saveApiKey(ak, Utils.dataToRecord(akd), wu);
	}

	/** Live keys this user owns (USER) or holds (FREEFORM with holder = user), across orgs; metadata only. */
	public List<ApiKeyDto> listUserKeyDtos(UUID userUuid) {
		return repository.findUserApiKeysByUserUuid(userUuid).stream().map(ApiKeyDto::fromApiKey).toList();
	}

	/** When a user leaves an org, their USER keys there are revoked: nothing could authorize them anyway. */
	// deleteApiKey is called on this instance, past its proxy: it runs in this method's transaction
	@Transactional
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
	@Transactional
	public String addApiKeySecret(UUID keyUuid, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		if (list.size() >= ApiKeyData.MAX_SECRETS) {
			throw new RelizaException("This key already has " + ApiKeyData.MAX_SECRETS + " secrets; regenerate or retire one instead of adding");
		}
		int slot = list.stream().anyMatch(x -> x.getSlot() == 1) ? 2 : 1;
		return addSecret(ak, akd, list, slot, expiresDate, wu);
	}

	/**
	 * Add a secret in the slot the caller names (task RD3-11): a declarative mint of slot 2 on a key with no
	 * secret yet must not land in slot 1. Returns the cleartext once; refused when the slot is taken.
	 */
	@Transactional
	public String addApiKeySecret(UUID keyUuid, int slot, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		if (slot < 1 || slot > ApiKeyData.MAX_SECRETS) throw new RelizaException("A key's secret slot is 1 or " + ApiKeyData.MAX_SECRETS);
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<ApiKeySecret> list = new LinkedList<>(akd.effectiveSecrets(ak.getApiKey()));
		if (list.stream().anyMatch(x -> x.getSlot() == slot)) {
			throw new RelizaException("This key already has a secret in slot " + slot + "; regenerate it instead");
		}
		return addSecret(ak, akd, list, slot, expiresDate, wu);
	}

	private String addSecret(ApiKey ak, ApiKeyData akd, List<ApiKeySecret> list, int slot, ZonedDateTime expiresDate,
			WhoUpdated wu) {
		String cleartext = newSecretString();
		list.add(new ApiKeySecret(slot, hashSecret(cleartext), true, ZonedDateTime.now(), null, expiresDate));
		list.sort((a, b) -> Integer.compare(a.getSlot(), b.getSlot()));
		akd.setSecrets(list);
		akd.setVersion(akd.getVersion() + 1);
		mirrorPrimary(ak, akd);
		saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(ak.getUuid());
		return cleartext;
	}

	/** Replace one secret in place: the old hash stops matching and every token exchanged with it dies. */
	@Transactional
	public String regenerateApiKeySecret(UUID keyUuid, int slot, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
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
	@Transactional
	public ApiKeyDto deleteApiKeySecret(UUID keyUuid, int slot, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
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
	@Transactional
	public ApiKeyDto setApiKeySecretExpiry(UUID keyUuid, int slot, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
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
	@Transactional
	public ApiKeyDto setApiKeySecretActive(UUID keyUuid, int slot, boolean active, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
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

	/**
	 * Kill switch for the whole key id: every secret and every token stop working until re-activated. An
	 * organization administrator's deactivation ({@code byAdmin}) is authoritative: it marks the key adminDisabled
	 * and nobody else (owner, holder or an org writer) can re-activate it; an administrator's re-activation clears
	 * the mark. Anyone else's deactivation leaves an administrator's switch on and sets none.
	 */
	@Transactional
	public ApiKeyDto setApiKeyStatus(UUID keyUuid, ApiKeyStatus status, boolean byAdmin, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (status != ApiKeyStatus.ACTIVE && status != ApiKeyStatus.INACTIVE) throw new RelizaException("Only ACTIVE or INACTIVE can be set here");
		if (akd.getStatus() != ApiKeyStatus.ACTIVE && akd.getStatus() != ApiKeyStatus.INACTIVE) throw new RelizaException("This key is a request; approve or deny it instead");
		boolean switchedOff = akd.isSwitchedOffByAdmin();
		if (status == ApiKeyStatus.ACTIVE && switchedOff && !byAdmin) {
			throw new RelizaException(ADMIN_DISABLED_REFUSAL);
		}
		akd.setAdminDisabled(status == ApiKeyStatus.INACTIVE && (byAdmin || switchedOff));
		akd.setStatus(status);
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ApiKeyDto.fromApiKey(ak);
	}

	@Transactional
	public ApiKeyDto setApprovalTypes(UUID keyUuid, Collection<String> approvals, WhoUpdated wu) {
		ApiKeyDto retAkd = null;
		Optional<ApiKey> oak = getApiKeyWriteLocked(keyUuid);
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
		Optional<ApiKey> oak = getApiKeyWriteLocked(keyUuid);
		if (oak.isPresent()) {
			ApiKey ak = oak.get();
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			akd.setNotes(normalizeNotes(notes));
			Map<String,Object> recordData = Utils.dataToRecord(akd);
			ak = saveApiKey(ak, recordData, wu);
			retAkd = ApiKeyDto.fromApiKey(ak);
		}
		return retAkd;
	}
	
	@Transactional
	public ApiKeyDto setPermissionsOnApiKey(UUID keyUuid, PermissionType orgPermissionType,
			List<PermissionDto> permissions, WhoUpdated wu) throws RelizaException {
		Optional<ApiKey> oak = getApiKeyWriteLocked(keyUuid);
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
	 * The device-login session bound of a key (task RD3-7): 1 to 129600 minutes, or null for none.
	 * Only keys that can back a device login take it. The caller is gated in the ws layer like notes.
	 */
	@Transactional
	public ApiKeyDto setSessionMaxMinutesOnApiKey(UUID keyUuid, Integer sessionMaxMinutes, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		if (ak.getObjectType() != ApiTypeEnum.USER && ak.getObjectType() != ApiTypeEnum.FREEFORM) {
			throw new RelizaException("Only personal and Free Form keys back device-login sessions");
		}
		if (!io.reliza.common.CliSessionCodes.isValidSessionMinutes(sessionMaxMinutes)) {
			throw new RelizaException("sessionMaxMinutes is 1 to " + io.reliza.common.CliSessionCodes.MAX_SESSION_MINUTES
					+ " minutes, or empty for no bound");
		}
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setSessionMaxMinutes(sessionMaxMinutes);
		return ApiKeyDto.fromApiKey(saveApiKey(ak, Utils.dataToRecord(akd), wu));
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
		Optional<ApiKey> oak = getApiKeyWriteLocked(keyUuid);
		if (oak.isEmpty()) throw new RelizaException("API key not found");
		ApiKey ak = oak.get();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setNotes(checkedNotes(notes));
		Map<String, Object> recordData = Utils.dataToRecord(akd);
		ak = saveApiKey(ak, recordData, wu);
		return ApiKeyDto.fromApiKey(ak);
	}

	/**
	 * What a declarative apply writes on a key (task RD3-11), in one save. A setting is written only when
	 * {@code declared} names it, so a file that leaves one out leaves it alone; the name and the provenance are
	 * always written. The declarative service has checked every value and the caller's right to write it, except
	 * that the administrator's kill switch is checked again here, on the locked row: {@code byAdmin} says whether
	 * the caller administers the organization.
	 */
	public record DeclaredSettings(String declaredName, io.reliza.model.DeclarativeProvenance provenance,
			Set<String> declared, String notes, ApiKeyStatus status, List<PermissionDto> permissions,
			Integer secretExpiresDays, Integer sessionMaxMinutes, boolean byAdmin) {}

	@Transactional(rollbackFor = RelizaException.class)
	public ApiKey writeDeclaredSettings(UUID keyUuid, DeclaredSettings d, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setDeclaredName(d.declaredName());
		akd.setDeclarative(d.provenance());
		if (d.declared().contains("notes")) akd.setNotes(normalizeNotes(d.notes()));
		if (d.declared().contains("status") && null != d.status()) {
			// the caller's check read the key unlocked: an administrator may have disabled it since
			boolean switchedOff = akd.isSwitchedOffByAdmin();
			if (d.status() == ApiKeyStatus.ACTIVE && switchedOff && !d.byAdmin()) throw new RelizaException(ADMIN_DISABLED_REFUSAL);
			akd.setStatus(d.status());
			// a declared deactivation is not the administrator's switch, but it leaves one that is on
			akd.setAdminDisabled(d.status() == ApiKeyStatus.INACTIVE && switchedOff);
		}
		if (d.declared().contains("permissions")) {
			akd.revokeAllOrgPermissions(ak.getOrg());
			for (PermissionDto p : d.permissions() == null ? List.<PermissionDto>of() : d.permissions()) {
				akd.setPermission(ak.getOrg(), p.scope(), p.object(), p.type(),
						p.functions() != null ? p.functions() : List.of(), p.approvals() != null ? p.approvals() : List.of());
			}
		}
		if (d.declared().contains("secretExpiresDays")) akd.setSecretExpiresDays(d.secretExpiresDays());
		if (d.declared().contains("sessionMaxMinutes")) akd.setSessionMaxMinutes(d.sessionMaxMinutes());
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu);
		invalidateVerificationCacheForKey(keyUuid);
		return ak;
	}

	/** The organization's live key (not REVOKED) declared under this name (task RD3-11), if any. */
	public Optional<ApiKey> findByDeclaredName(UUID orgUuid, String declaredName) {
		if (StringUtils.isBlank(declaredName)) return Optional.empty();
		return listApiKeyByOrg(orgUuid).stream().filter(ak -> {
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			return akd.getStatus() != ApiKeyStatus.REVOKED && declaredName.equals(akd.getDeclaredName());
		}).findFirst();
	}

	/**
	 * Declare a key under a name, or release its name with null (task RD3-11): what a person does to put a key
	 * made by hand under a file. The checks -- which keys may be declared, a name no other live key carries --
	 * are the declarative service's.
	 */
	@Transactional
	public ApiKey setDeclaredName(UUID keyUuid, String declaredName, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setDeclaredName(declaredName);
		if (null == declaredName) akd.setDeclarative(null);
		return saveApiKey(ak, Utils.dataToRecord(akd), wu);
	}

	private ApiKey saveApiKey (ApiKey ak, Map<String,Object> recordData, WhoUpdated wu) {
		return saveApiKey(ak, recordData, wu, false);
	}

	/**
	 * Every caller runs in a transaction: this takes the key row's lock, held until commit. Whether the row is
	 * live is read from the table, not from this persistence context, which may still hold the record as the
	 * caller loaded it: a live record is never written over a key deleted meanwhile, unless {@code revives}:
	 * the mint and federated paths that reuse a deleted row on purpose, after locking and re-reading it.
	 */
	private ApiKey saveApiKey (ApiKey ak, Map<String,Object> recordData, WhoUpdated wu, boolean revives) {
		// TODO: add validation
		Optional<Boolean> live = repository.lockAndReadLiveness(ak.getUuid());
		if (!revives && live.isPresent() && !live.get() && writesLive(ak, recordData)) {
			throw new IllegalStateException("API key " + ak.getUuid() + " was deleted meanwhile; not saving a stale record over it");
		}
		// a row this transaction inserted is not flushed yet, so the table read misses it: it is present all the same
		if (live.orElse(entityManager.contains(ak))) {
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
	@Transactional
	public ApiKey ensureFederatedIdentity(UUID orgUuid, io.reliza.model.FederatedIdentity fi, WhoUpdated wu) {
		String keyOrder = federatedKeyOrder(fi);
		Optional<ApiKey> present = getApiKeyByObjUuidTypeOrder(orgUuid, ApiTypeEnum.FEDERATED, keyOrder, orgUuid, true)
				.flatMap(found -> lockAndRefresh(found.getUuid()));
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
			akd.setNotes(normalizeNotes(fi.getProvider() + ": " + fi.getRepository()));
		}
		ak = saveApiKey(ak, Utils.dataToRecord(akd), wu, true);
		return ak;
	}

	/** Accept whatever repository id the next exchange presents. Admin only; the caller gates. */
	@Transactional
	public ApiKeyDto resetFederatedIdentityPin(UUID keyUuid, WhoUpdated wu) throws RelizaException {
		ApiKey ak = getApiKeyWriteLocked(keyUuid).orElseThrow(() -> new RelizaException("Identity not found"));
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

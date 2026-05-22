/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.SigningKeyFingerprintUtil;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.CommitterData;
import io.reliza.model.SigningKey;
import io.reliza.model.SigningKeyData;
import io.reliza.model.SigningKeyData.SignatureFormat;
import io.reliza.model.SigningKeyData.SigningKeyOwnerType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SigningKeyRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD for {@link SigningKey}. The verifier looks keys up by
 * fingerprint via {@link #findActiveByFingerprint}; revoked keys are
 * never returned for new verifications. Past
 * {@code signature_verifications} rows reference the key by uuid and
 * stay valid even after revocation.
 */
@Service
@Slf4j
public class SigningKeyService {

	private final SigningKeyRepository repository;
	private final AuditService auditService;
	private final CommitterService committerService;

	SigningKeyService(SigningKeyRepository repository, AuditService auditService, CommitterService committerService) {
		this.repository = repository;
		this.auditService = auditService;
		this.committerService = committerService;
	}

	public Optional<SigningKey> getSigningKey(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<SigningKeyData> getSigningKeyData(UUID uuid) {
		return getSigningKey(uuid).map(SigningKeyData::dataFromRecord);
	}

	public Optional<SigningKeyData> findActiveByFingerprint(UUID orgUuid, String fingerprint) {
		if (orgUuid == null || StringUtils.isBlank(fingerprint)) return Optional.empty();
		return repository.findActiveByOrgAndFingerprint(orgUuid.toString(), fingerprint)
				.map(SigningKeyData::dataFromRecord);
	}

	public List<SigningKeyData> listByOwner(UUID orgUuid, SigningKeyOwnerType ownerType, UUID ownerUuid) {
		if (orgUuid == null || ownerType == null || ownerUuid == null) return List.of();
		return repository.findByOwner(orgUuid.toString(), ownerType.name(), ownerUuid.toString()).stream()
				.map(SigningKeyData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Active-only variant of {@link #listByOwner}. The verifier uses
	 * this for SCE-derived scope narrowing — a revoked key never
	 * participates in a new verdict, only in historical re-checks
	 * that reference the row directly by uuid.
	 */
	public List<SigningKeyData> listActiveByOwner(UUID orgUuid, SigningKeyOwnerType ownerType, UUID ownerUuid) {
		return listByOwner(orgUuid, ownerType, ownerUuid).stream()
				.filter(k -> k.getRevokedAt() == null)
				.collect(Collectors.toList());
	}

	/**
	 * Active keys for the org filtered to a single owner-type bucket.
	 * Used by the verifier's committer-only path (no agentic trailer)
	 * so AGENT keys are excluded from non-agentic commits.
	 */
	public List<SigningKeyData> listActiveByOrgAndOwnerType(UUID orgUuid, SigningKeyOwnerType ownerType) {
		if (orgUuid == null || ownerType == null) return List.of();
		return repository.findActiveByOrgAndOwnerType(orgUuid.toString(), ownerType.name()).stream()
				.map(SigningKeyData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<SigningKeyData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(SigningKeyData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Enrol a new key for an owner. Idempotent on (org, fingerprint)
	 * — if an active enrolment already exists with the same
	 * fingerprint it's returned unchanged. A revoked enrolment with
	 * the same fingerprint does not block re-enrolment; the partial
	 * unique index on the table covers that.
	 */
	@Transactional
	public SigningKeyData enrol(SigningKeyData seed, WhoUpdated wu) throws RelizaException {
		if (seed.getOrg() == null) throw new RelizaException("SigningKey requires an org");
		if (seed.getFormat() == null) throw new RelizaException("SigningKey requires a format");
		if (seed.getOwnerType() == null) throw new RelizaException("SigningKey requires an ownerType");
		if (seed.getOwnerUuid() == null) throw new RelizaException("SigningKey requires an ownerUuid");
		if (StringUtils.isBlank(seed.getPubKey())) throw new RelizaException("SigningKey requires a pubKey");

		if (StringUtils.isBlank(seed.getFingerprint())) {
			seed.setFingerprint(seed.getFormat() == SignatureFormat.SSH
					? SigningKeyFingerprintUtil.deriveSshFingerprint(seed.getPubKey())
					: SigningKeyFingerprintUtil.deriveGpgLongKeyId(seed.getPubKey()));
		}

		if (seed.getFormat() == SignatureFormat.SSH && StringUtils.isBlank(seed.getIdentity())
				&& seed.getOwnerType() == SigningKeyOwnerType.COMMITTER) {
			Optional<CommitterData> committer = committerService.getCommitterData(seed.getOwnerUuid());
			committer.ifPresent(c -> seed.setIdentity(c.getEmail()));
		}
		if (seed.getFormat() == SignatureFormat.SSH && StringUtils.isBlank(seed.getIdentity())) {
			throw new RelizaException("SSH SigningKey requires an identity (allowed_signers principal)");
		}

		Optional<SigningKeyData> active = findActiveByFingerprint(seed.getOrg(), seed.getFingerprint());
		if (active.isPresent()) {
			return active.get();
		}

		SigningKey entity = new SigningKey();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		try {
			SigningKey saved = save(entity, recordData, wu);
			return SigningKeyData.dataFromRecord(saved);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent SigningKey enrol race for (org={}, fingerprint='{}')",
					seed.getOrg(), seed.getFingerprint());
			return findActiveByFingerprint(seed.getOrg(), seed.getFingerprint())
					.orElseThrow(() -> new RelizaException("SigningKey enrol race detected"));
		}
	}

	@Transactional
	public SigningKeyData revoke(UUID keyUuid, WhoUpdated wu) throws RelizaException {
		SigningKey k = repository.findByIdWriteLocked(keyUuid)
				.orElseThrow(() -> new RelizaException("SigningKey not found: " + keyUuid));
		SigningKeyData kd = SigningKeyData.dataFromRecord(k);
		if (kd.getRevokedAt() != null) return kd;
		kd.setRevokedAt(ZonedDateTime.now());
		Map<String, Object> recordData = Utils.dataToRecord(kd);
		SigningKey saved = save(k, recordData, wu);
		return SigningKeyData.dataFromRecord(saved);
	}

	private SigningKey save(SigningKey k, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<SigningKey> existing = repository.findById(k.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.SIGNING_KEYS, k);
			k.setRevision(k.getRevision() + 1);
			k.setLastUpdatedDate(ZonedDateTime.now());
		}
		k.setRecordData(recordData);
		k = (SigningKey) WhoUpdated.injectWhoUpdatedData(k, wu);
		return repository.save(k);
	}
}

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.SignatureVerification;
import io.reliza.model.SignatureVerificationData;
import io.reliza.model.SignatureVerificationData.SignatureSubjectType;
import io.reliza.model.SignatureVerificationData.SignatureVerificationState;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SignatureVerificationRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores verdicts written by the verifier. The actual verification
 * subprocess lives in rebom-backend and is invoked via
 * {@link RebomService#verifySignature}; this service is the
 * record-keeping side.
 *
 * Current verifier semantics version: 1. Bump
 * {@link #CURRENT_VERIFIER_VERSION} when the verification rules change
 * so re-verification logic can detect stale rows.
 */
@Service
@Slf4j
public class SignatureVerificationService {

	public static final int CURRENT_VERIFIER_VERSION = 1;

	private final SignatureVerificationRepository repository;

	SignatureVerificationService(SignatureVerificationRepository repository) {
		this.repository = repository;
	}

	public Optional<SignatureVerificationData> getData(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid).map(SignatureVerificationData::dataFromRecord);
	}

	public List<SignatureVerificationData> listBySubject(SignatureSubjectType subjectType, UUID subjectUuid) {
		if (subjectType == null || subjectUuid == null) return List.of();
		return repository.findBySubject(subjectType.name(), subjectUuid.toString()).stream()
				.map(SignatureVerificationData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public Optional<SignatureVerificationData> findLatestBySubject(SignatureSubjectType subjectType, UUID subjectUuid) {
		if (subjectType == null || subjectUuid == null) return Optional.empty();
		return repository.findLatestBySubject(subjectType.name(), subjectUuid.toString())
				.map(SignatureVerificationData::dataFromRecord);
	}

	@Transactional
	public SignatureVerificationData persistVerdict(SignatureVerificationData seed, WhoUpdated wu) throws RelizaException {
		if (seed.getOrg() == null) throw new RelizaException("Verdict requires an org");
		if (seed.getSubjectType() == null) throw new RelizaException("Verdict requires a subjectType");
		if (seed.getSubjectUuid() == null) throw new RelizaException("Verdict requires a subjectUuid");
		if (seed.getVerdict() == null) seed.setVerdict(SignatureVerificationState.PENDING);
		if (seed.getVerifierVersion() == null) seed.setVerifierVersion(CURRENT_VERIFIER_VERSION);
		if (seed.getVerdict() != SignatureVerificationState.PENDING && seed.getVerifiedAt() == null) {
			seed.setVerifiedAt(ZonedDateTime.now());
		}

		SignatureVerification entity = new SignatureVerification();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		entity.setRecordData(recordData);
		entity = (SignatureVerification) WhoUpdated.injectWhoUpdatedData(entity, wu);
		SignatureVerification saved = repository.save(entity);
		return SignatureVerificationData.dataFromRecord(saved);
	}
}

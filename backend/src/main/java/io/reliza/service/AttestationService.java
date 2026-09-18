/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Attestation;
import io.reliza.model.AttestationData;
import io.reliza.model.AttestationData.ActorType;
import io.reliza.model.AttestationData.RecordStatus;
import io.reliza.model.AttestationData.SubjectType;
import io.reliza.model.AttestationData.Type;
import io.reliza.model.AttestationData.Verdict;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AttestationRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * The append-only attestation log.
 *
 * <p>Writes only ever add a row. A correction is a new attestation plus a revocation of the one
 * it corrects, which is why there is no update method here: the value of the record is that it
 * cannot be quietly changed after the fact.
 */
@Slf4j
@Service
public class AttestationService {

	@Autowired private AttestationRepository repository;
	@Autowired private AuditService auditService;

	/**
	 * What a set of subjects has been attested to, in one query.
	 *
	 * <p>Batched because recognition asks per commit and the release activation asks for every
	 * commit of a release at once; a query per commit would be the kind of per-item read that
	 * only shows up in production.
	 */
	public Map<UUID, List<AttestationData>> attestationsBySubjects(UUID org, SubjectType subjectType,
			Collection<UUID> subjectUuids) {
		Map<UUID, List<AttestationData>> out = new HashMap<>();
		if (org == null || subjectType == null || subjectUuids == null || subjectUuids.isEmpty()) return out;
		String[] ids = subjectUuids.stream().filter(u -> u != null).map(UUID::toString).toArray(String[]::new);
		if (ids.length == 0) return out;
		for (Attestation a : repository.findBySubjects(org.toString(), subjectType.name(), ids)) {
			AttestationData d = AttestationData.dataFromRecord(a);
			out.computeIfAbsent(d.getSubjectUuid(), k -> new ArrayList<>()).add(d);
		}
		return out;
	}

	public List<AttestationData> attestationsOfSubject(UUID org, SubjectType subjectType, UUID subjectUuid) {
		if (org == null || subjectType == null || subjectUuid == null) return List.of();
		return repository.findBySubject(org.toString(), subjectType.name(), subjectUuid.toString())
				.stream().map(AttestationData::dataFromRecord).toList();
	}

	public List<AttestationData> activeOfType(UUID org, Type type) {
		if (org == null || type == null) return List.of();
		return repository.findByOrgAndType(org.toString(), type.name(), RecordStatus.ACTIVE.name())
				.stream().map(AttestationData::dataFromRecord).toList();
	}

	public Optional<AttestationData> getAttestationData(UUID uuid) {
		return repository.findById(uuid).map(AttestationData::dataFromRecord);
	}

	@Transactional
	public AttestationData acknowledgeCommit(UUID org, SubjectType subjectType, UUID subjectUuid,
			AttestationData.SubjectRef ref, ActorType actorType, UUID actorUuid, UUID agentSession,
			Verdict verdict, String note, WhoUpdated wu) {
		// The subject type comes from the caller: a claim can be about a commit or about a whole
		// build, and hard-coding SCE here filed release claims under a type nothing looks up.
		return write(AttestationData.create(org, Type.COMMIT_ACKNOWLEDGEMENT, subjectType, subjectUuid,
				ref, actorType, actorUuid, agentSession, verdict, note, false), wu);
	}

	@Transactional
	public AttestationData recordLockRelease(UUID org, UUID lockUuid, AttestationData.SubjectRef ref,
			ActorType actorType, UUID actorUuid, UUID agentSession, String reason, boolean override,
			WhoUpdated wu) {
		return write(AttestationData.create(org, Type.LOCK_RELEASE, SubjectType.LOCK, lockUuid, ref,
				actorType, actorUuid, agentSession, null, reason, override), wu);
	}

	/**
	 * Revoke a row. The row stays and is marked, because the fact that somebody once stood behind
	 * the claim is itself part of the record.
	 */
	@Transactional
	public AttestationData revoke(UUID uuid, UUID actorUuid, String reason, WhoUpdated wu)
			throws RelizaException {
		Attestation e = repository.findById(uuid)
				.orElseThrow(() -> new RelizaException("Attestation not found: " + uuid));
		AttestationData d = AttestationData.dataFromRecord(e);
		if (!d.isActive()) throw new RelizaException("Attestation is already revoked: " + uuid);
		d.setStatus(RecordStatus.REVOKED);
		d.setRevokedBy(actorUuid);
		d.setRevokedAt(ZonedDateTime.now());
		d.setRevokeReason(reason);
		return save(e, d, wu);
	}

	private AttestationData write(AttestationData d, WhoUpdated wu) {
		Attestation e = new Attestation();
		e.setUuid(d.getUuid());
		return save(e, d, wu);
	}

	private AttestationData save(Attestation e, AttestationData d, WhoUpdated wu) {
		if (repository.findById(e.getUuid()).isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.ATTESTATION, e);
			e.setRevision(e.getRevision() + 1);
			e.setLastUpdatedDate(ZonedDateTime.now());
		}
		e.setRecordData(d.toRecordData());
		e = (Attestation) WhoUpdated.injectWhoUpdatedData(e, wu);
		return AttestationData.dataFromRecord(repository.save(e));
	}
}

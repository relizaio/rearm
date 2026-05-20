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
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Committer;
import io.reliza.model.CommitterData;
import io.reliza.model.CommitterData.CommitterStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.CommitterRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD for {@link Committer}. The verifier resolves a commit author
 * header to a committer row via {@link #findByOrgAndEmail} +
 * {@link #findByOrgAndAlias}; both are case-insensitive.
 */
@Service
@Slf4j
public class CommitterService {

	private final CommitterRepository repository;
	private final AuditService auditService;

	CommitterService(CommitterRepository repository, AuditService auditService) {
		this.repository = repository;
		this.auditService = auditService;
	}

	public Optional<Committer> getCommitter(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<CommitterData> getCommitterData(UUID uuid) {
		return getCommitter(uuid).map(CommitterData::dataFromRecord);
	}

	public Optional<CommitterData> findByEmail(UUID orgUuid, String email) {
		if (orgUuid == null || StringUtils.isBlank(email)) return Optional.empty();
		Optional<Committer> primary = repository.findByOrgAndEmail(orgUuid.toString(), email);
		if (primary.isPresent()) return primary.map(CommitterData::dataFromRecord);
		return repository.findByOrgAndAlias(orgUuid.toString(), email)
				.map(CommitterData::dataFromRecord);
	}

	public List<CommitterData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(CommitterData::dataFromRecord)
				.collect(Collectors.toList());
	}

	@Transactional
	public CommitterData upsert(CommitterData seed, WhoUpdated wu) throws RelizaException {
		if (seed.getOrg() == null) throw new RelizaException("Committer requires an org");
		if (StringUtils.isBlank(seed.getEmail())) throw new RelizaException("Committer requires an email");
		seed.setEmail(seed.getEmail().toLowerCase());

		Committer entity;
		if (seed.getUuid() != null) {
			entity = repository.findByIdWriteLocked(seed.getUuid())
					.orElseThrow(() -> new RelizaException("Committer not found: " + seed.getUuid()));
		} else {
			Optional<Committer> existing = repository.findByOrgAndEmail(seed.getOrg().toString(), seed.getEmail());
			entity = existing.orElseGet(Committer::new);
		}

		if (seed.getStatus() == null) seed.setStatus(CommitterStatus.ACTIVE);
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		try {
			Committer saved = save(entity, recordData, wu);
			return CommitterData.dataFromRecord(saved);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent Committer upsert race for (org={}, email='{}')",
					seed.getOrg(), seed.getEmail());
			return repository.findByOrgAndEmail(seed.getOrg().toString(), seed.getEmail())
					.map(CommitterData::dataFromRecord)
					.orElseThrow(() -> new RelizaException("Committer upsert race detected"));
		}
	}

	@Transactional
	public CommitterData setStatus(UUID committerUuid, CommitterStatus status, WhoUpdated wu) throws RelizaException {
		Committer c = repository.findByIdWriteLocked(committerUuid)
				.orElseThrow(() -> new RelizaException("Committer not found: " + committerUuid));
		CommitterData cd = CommitterData.dataFromRecord(c);
		cd.setStatus(status);
		Map<String, Object> recordData = Utils.dataToRecord(cd);
		Committer saved = save(c, recordData, wu);
		return CommitterData.dataFromRecord(saved);
	}

	private Committer save(Committer c, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<Committer> existing = repository.findById(c.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.COMMITTERS, c);
			c.setRevision(c.getRevision() + 1);
			c.setLastUpdatedDate(ZonedDateTime.now());
		}
		c.setRecordData(recordData);
		c = (Committer) WhoUpdated.injectWhoUpdatedData(c, wu);
		return repository.save(c);
	}
}

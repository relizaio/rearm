/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Committer;
import io.reliza.model.CommitterData;
import io.reliza.model.CommitterData.CommitterStatus;
import io.reliza.model.UserData;
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
	private final UserService userService;

	CommitterService(CommitterRepository repository, AuditService auditService, @Lazy UserService userService) {
		this.repository = repository;
		this.auditService = auditService;
		this.userService = userService;
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
		Optional<Committer> aliasMatch = repository.findByOrgAndAlias(orgUuid.toString(), email);
		if (aliasMatch.isPresent()) return aliasMatch.map(CommitterData::dataFromRecord);
		// Linked-user live alias: any active committer whose linked user
		// currently owns this email. The user can add a new address after
		// the committer was created and we still resolve the signature.
		String lower = email.toLowerCase();
		for (Committer c : repository.findByOrg(orgUuid.toString())) {
			CommitterData cd = CommitterData.dataFromRecord(c);
			if (cd.getStatus() != CommitterStatus.ACTIVE || cd.getUser() == null) continue;
			Optional<UserData> u = userService.getUserData(cd.getUser());
			if (u.isPresent() && u.get().getAllEmailStrings().contains(lower)) {
				return Optional.of(cd);
			}
		}
		return Optional.empty();
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

		if (seed.getUser() != null) {
			repository.findActiveByOrgAndUser(seed.getOrg().toString(), seed.getUser().toString())
					.filter(other -> !other.getUuid().equals(entity.getUuid()))
					.ifPresent(other -> { throw new IllegalArgumentException(
							"ReARM user " + seed.getUser() + " is already linked to active committer "
									+ other.getUuid()); });
		}

		Set<String> claimed = new LinkedHashSet<>();
		claimed.add(seed.getEmail());
		if (seed.getAliases() != null) {
			for (String a : seed.getAliases()) if (StringUtils.isNotBlank(a)) claimed.add(a.toLowerCase());
		}
		if (seed.getUser() != null) {
			userService.getUserData(seed.getUser()).ifPresent(u -> claimed.addAll(u.getAllEmailStrings()));
		}
		for (String emailToClaim : claimed) {
			Optional<CommitterData> conflict = findEmailOwner(seed.getOrg(), emailToClaim);
			if (conflict.isPresent() && !conflict.get().getUuid().equals(entity.getUuid())) {
				throw new IllegalArgumentException("Email '" + emailToClaim
						+ "' is already claimed by committer " + conflict.get().getUuid());
			}
		}

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

	/**
	 * Like {@link #findByEmail} but used at validation time — finds any
	 * active committer in the org that already claims this email through
	 * its primary, alias, or linked-user emails.
	 */
	private Optional<CommitterData> findEmailOwner(UUID orgUuid, String email) {
		Optional<CommitterData> hit = findByEmail(orgUuid, email);
		return hit.filter(cd -> cd.getStatus() == CommitterStatus.ACTIVE);
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

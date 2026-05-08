/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.PullRequest;
import io.reliza.model.PullRequestData;
import io.reliza.model.PullRequestData.PullRequestUpdateAction;
import io.reliza.model.PullRequestData.PullRequestUpdateEvent;
import io.reliza.model.PullRequestData.PullRequestUpdateScope;
import io.reliza.model.PullRequestData.PullRequestValidationEvent;
import io.reliza.model.PullRequestData.ReleaseValidationEvent;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.PullRequestRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD + intake + event recording for first-class PullRequest entities.
 * The aggregator/dispatch step that turns release_validation_events into
 * an outbound pr_validation_event lives in
 * {@code io.reliza.service.oss.OssPullRequestAggregatorService} (the
 * SAAS dispatch path lives behind the OSS boundary; this service stays
 * shared).
 */
@Service
@Slf4j
public class PullRequestService {

	@Autowired
	private AuditService auditService;

	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	private final PullRequestRepository repository;

	PullRequestService(PullRequestRepository repository) {
		this.repository = repository;
	}

	public Optional<PullRequest> getPullRequest(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<PullRequestData> getPullRequestData(UUID uuid) {
		return getPullRequest(uuid).map(PullRequestData::dataFromRecord);
	}

	@Transactional
	public Optional<PullRequest> getPullRequestWriteLocked(UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}

	public Optional<PullRequestData> getByTargetRepoAndIdentity(UUID targetRepoUuid, String identity) {
		if (targetRepoUuid == null || StringUtils.isBlank(identity)) return Optional.empty();
		return repository.findByTargetRepoAndIdentity(targetRepoUuid.toString(), identity)
				.map(PullRequestData::dataFromRecord);
	}

	public List<PullRequestData> listByOrg(UUID orgUuid) {
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<PullRequestData> listByTargetRepository(UUID targetRepoUuid) {
		return repository.findByTargetRepository(targetRepoUuid.toString()).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<PullRequestData> findOpenByTargetRepoAndCommit(UUID targetRepoUuid, UUID sceUuid) {
		return repository.findOpenByTargetRepoAndCommit(targetRepoUuid.toString(), sceUuid.toString()).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Find-or-create by (targetVcsRepository, identity). For new rows, the
	 * caller-supplied PullRequestData seeds initial fields. For existing
	 * rows, the existing entity is returned untouched — callers update
	 * via {@link #updatePullRequest}.
	 */
	@Transactional
	public PullRequestData upsertByIdentity(PullRequestData seed, WhoUpdated wu) throws RelizaException {
		if (seed.getTargetVcsRepository() == null || StringUtils.isBlank(seed.getIdentity())) {
			throw new RelizaException("PullRequest requires both targetVcsRepository and identity");
		}
		Optional<PullRequest> existing = repository.findByTargetRepoAndIdentity(
				seed.getTargetVcsRepository().toString(), seed.getIdentity());
		if (existing.isPresent()) {
			return PullRequestData.dataFromRecord(existing.get());
		}
		if (seed.getOrg() == null) {
			throw new RelizaException("PullRequest requires an org on initial create");
		}
		if (seed.getState() == null) {
			seed.setState(PullRequestState.OPEN);
		}
		PullRequest pr = new PullRequest();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		PullRequest saved = save(pr, recordData, wu);
		PullRequestData persisted = PullRequestData.dataFromRecord(saved);
		recordUpdateEvent(persisted.getUuid(), PullRequestUpdateScope.PR_CREATED,
				PullRequestUpdateAction.ADDED, null, persisted.getUuid().toString(), persisted.getUuid(), wu);
		return persisted;
	}

	/**
	 * Apply a {@code pullRequest} input map (the same shape both
	 * {@code addReleaseProgrammatic} and {@code getNewVersion} accept) by
	 * upserting the entity keyed on {@code (targetVcsRepository, identity)}
	 * and, when {@code headSce} is non-null, advancing its head to that
	 * SCE in the same call. Tolerant of malformed input — logs and
	 * returns {@code Optional.empty()} so a misformatted PR field never
	 * fails the surrounding mutation.
	 *
	 * Resolves the target VCS from {@code headSce} (preferred) or
	 * {@code componentVcsFallback} (used when the PR was attached at
	 * getNewVersion time without an SCE on hand). At least one of the
	 * two must yield a VCS UUID or the upsert is skipped — the
	 * {@code (targetVcsRepository, identity)} key requires it.
	 */
	@Transactional
	public Optional<PullRequestData> applyFromInput(
			Map<String, Object> prInput, UUID orgUuid,
			UUID headSce, UUID componentVcsFallback, WhoUpdated wu) {
		if (prInput == null) return Optional.empty();
		String identity = (String) prInput.get("identity");
		String stateStr = (String) prInput.get("state");
		if (StringUtils.isBlank(identity) || StringUtils.isBlank(stateStr)) {
			log.warn("pullRequest input missing required identity/state — skipping (org {})", orgUuid);
			return Optional.empty();
		}
		try {
			PullRequestState prState = PullRequestState.valueOf(StringUtils.upperCase(stateStr));
			UUID targetVcs = null;
			if (headSce != null) {
				targetVcs = getSourceCodeEntryService.getSourceCodeEntryData(headSce)
						.map(io.reliza.model.SourceCodeEntryData::getVcs).orElse(null);
			}
			if (targetVcs == null) targetVcs = componentVcsFallback;
			if (targetVcs == null) {
				log.warn("pullRequest input cannot be applied — no VCS resolvable from SCE or component fallback (org {} identity {})",
						orgUuid, identity);
				return Optional.empty();
			}
			java.net.URI prEndpoint = null;
			String endpointStr = (String) prInput.get("endpoint");
			if (StringUtils.isNotBlank(endpointStr)) {
				try { prEndpoint = java.net.URI.create(endpointStr); } catch (Exception ignored) {}
			}
			PullRequestData seed = new PullRequestData();
			seed.setOrg(orgUuid);
			seed.setTargetVcsRepository(targetVcs);
			seed.setIdentity(identity);
			seed.setState(prState);
			seed.setTitle((String) prInput.get("title"));
			seed.setSourceBranchName((String) prInput.get("sourceBranchName"));
			seed.setTargetBranchName((String) prInput.get("targetBranchName"));
			seed.setEndpoint(prEndpoint);

			PullRequestData prd = upsertByIdentity(seed, wu);
			// upsertByIdentity returns the existing row untouched when one
			// already matches the (targetVcs, identity) key. Surface the
			// caller's mutable-field updates (state / title / branch names /
			// endpoint) so downstream readers don't lag behind the SCM.
			boolean changed = false;
			if (prd.getState() != seed.getState()) { prd.setState(seed.getState()); changed = true; }
			if (seed.getTitle() != null && !seed.getTitle().equals(prd.getTitle())) { prd.setTitle(seed.getTitle()); changed = true; }
			if (seed.getEndpoint() != null && !seed.getEndpoint().equals(prd.getEndpoint())) { prd.setEndpoint(seed.getEndpoint()); changed = true; }
			if (seed.getSourceBranchName() != null && !seed.getSourceBranchName().equals(prd.getSourceBranchName())) { prd.setSourceBranchName(seed.getSourceBranchName()); changed = true; }
			if (seed.getTargetBranchName() != null && !seed.getTargetBranchName().equals(prd.getTargetBranchName())) { prd.setTargetBranchName(seed.getTargetBranchName()); changed = true; }
			if (changed) prd = updatePullRequest(prd, wu);

			if (headSce != null) {
				prd = advanceHead(prd.getUuid(), headSce, wu);
			}
			return Optional.of(prd);
		} catch (Exception e) {
			log.warn("Failed to apply pullRequest input (org {} identity {}): {}", orgUuid, identity, e.getMessage());
			return Optional.empty();
		}
	}

	@Transactional
	public PullRequestData updatePullRequest(PullRequestData prd, WhoUpdated wu) throws RelizaException {
		Optional<PullRequest> oPr = repository.findById(prd.getUuid());
		if (oPr.isEmpty()) {
			throw new RelizaException("PullRequest not found: " + prd.getUuid());
		}
		Map<String, Object> recordData = Utils.dataToRecord(prd);
		PullRequest saved = save(oPr.get(), recordData, wu);
		return PullRequestData.dataFromRecord(saved);
	}

	/**
	 * Move the head of the PR to a new SCE. Appends to the commits list
	 * (de-duplicated on equal-and-already-tail) and records a HEAD update
	 * event. The aggregator should be re-run after this completes since
	 * the head SCE changed.
	 */
	@Transactional
	public PullRequestData advanceHead(UUID prUuid, UUID newHeadSce, WhoUpdated wu) throws RelizaException {
		Optional<PullRequest> oPr = repository.findByIdWriteLocked(prUuid);
		if (oPr.isEmpty()) {
			throw new RelizaException("PullRequest not found: " + prUuid);
		}
		PullRequestData prd = PullRequestData.dataFromRecord(oPr.get());
		List<UUID> commits = prd.getCommits() == null ? new LinkedList<>() : new LinkedList<>(prd.getCommits());
		UUID prevHead = commits.isEmpty() ? null : commits.get(commits.size() - 1);
		if (newHeadSce.equals(prevHead)) {
			return prd; // no-op
		}
		// Preserve insertion order while ensuring the new head is unique-tailed.
		LinkedHashSet<UUID> dedup = new LinkedHashSet<>(commits);
		dedup.remove(newHeadSce);
		dedup.add(newHeadSce);
		prd.setCommits(new LinkedList<>(dedup));
		Map<String, Object> recordData = Utils.dataToRecord(prd);
		PullRequest saved = save(oPr.get(), recordData, wu);
		recordUpdateEvent(prUuid, PullRequestUpdateScope.HEAD, PullRequestUpdateAction.CHANGED,
				prevHead == null ? null : prevHead.toString(), newHeadSce.toString(), newHeadSce, wu);
		return PullRequestData.dataFromRecord(saved);
	}

	@Transactional
	public void recordReleaseValidationEvent(UUID prUuid, UUID release, io.reliza.model.ValidationState result,
			WhoUpdated wu) throws RelizaException {
		ReleaseValidationEvent ev = new ReleaseValidationEvent(release, ZonedDateTime.now(), result, wu);
		try {
			repository.appendReleaseValidationEvent(prUuid, Utils.OM.writeValueAsString(ev));
		} catch (JsonProcessingException e) {
			throw new RelizaException("Failed to serialize ReleaseValidationEvent for PR " + prUuid
					+ ": " + e.getMessage());
		}
	}

	@Transactional
	public void recordPrValidationEvent(UUID prUuid, PullRequestValidationEvent ev) throws RelizaException {
		try {
			repository.appendPrValidationEvent(prUuid, Utils.OM.writeValueAsString(ev));
		} catch (JsonProcessingException e) {
			throw new RelizaException("Failed to serialize PullRequestValidationEvent for PR " + prUuid
					+ ": " + e.getMessage());
		}
	}

	@Transactional
	public void recordUpdateEvent(UUID prUuid, PullRequestUpdateScope scope, PullRequestUpdateAction action,
			String oldValue, String newValue, UUID objectId, WhoUpdated wu) {
		PullRequestUpdateEvent ev = new PullRequestUpdateEvent(scope, action, oldValue, newValue, objectId,
				ZonedDateTime.now(), wu);
		try {
			repository.appendUpdateEvent(prUuid, Utils.OM.writeValueAsString(ev));
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize PullRequestUpdateEvent for PR {}", prUuid, e);
		}
	}

	private PullRequest save(PullRequest pr, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<PullRequest> existing = repository.findById(pr.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.PULL_REQUESTS, pr);
			pr.setRevision(pr.getRevision() + 1);
			pr.setLastUpdatedDate(ZonedDateTime.now());
		}
		pr.setRecordData(recordData);
		pr = (PullRequest) WhoUpdated.injectWhoUpdatedData(pr, wu);
		return repository.save(pr);
	}
}

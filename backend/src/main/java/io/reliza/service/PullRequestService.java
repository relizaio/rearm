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
import io.reliza.model.ValidationState;
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

	/**
	 * State-filtered variant of {@link #listByOrg}. Empty/null
	 * {@code states} falls back to the unfiltered call so callers don't
	 * have to special-case "no filter". Unknown state strings are
	 * dropped; if every input is invalid the result is empty.
	 */
	public List<PullRequestData> listByOrg(UUID orgUuid, List<String> states) {
		List<String> normalized = normalizeStates(states);
		if (normalized == null) return listByOrg(orgUuid);
		if (normalized.isEmpty()) return List.of();
		return repository.findByOrgAndStates(orgUuid.toString(), normalized).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<PullRequestData> listByTargetRepository(UUID targetRepoUuid) {
		return repository.findByTargetRepository(targetRepoUuid.toString()).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<PullRequestData> listByTargetRepository(UUID targetRepoUuid, List<String> states) {
		List<String> normalized = normalizeStates(states);
		if (normalized == null) return listByTargetRepository(targetRepoUuid);
		if (normalized.isEmpty()) return List.of();
		return repository.findByTargetRepositoryAndStates(targetRepoUuid.toString(), normalized).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Normalize a caller-supplied state filter: returns null when the
	 * caller wants every state (so the ORM picks the unfiltered query),
	 * an empty list when the caller passed only invalid state names
	 * (callers short-circuit to an empty result), or a deduplicated list
	 * of valid {@link PullRequestState} names otherwise.
	 */
	private static List<String> normalizeStates(List<String> states) {
		if (states == null || states.isEmpty()) return null;
		LinkedHashSet<String> valid = new LinkedHashSet<>();
		for (String s : states) {
			if (StringUtils.isBlank(s)) continue;
			try {
				valid.add(PullRequestState.valueOf(StringUtils.upperCase(s)).name());
			} catch (IllegalArgumentException ignored) {
				// drop unknown values — surfaced empty rather than a 500
			}
		}
		return new LinkedList<>(valid);
	}

	public List<PullRequestData> findOpenByTargetRepoAndCommit(UUID targetRepoUuid, UUID sceUuid) {
		return repository.findOpenByTargetRepoAndCommit(targetRepoUuid.toString(), sceUuid.toString()).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Distinct PRs in the org whose commits[] contains any of the
	 * supplied SCE uuids. Used by {@code Session.pullRequests} —
	 * walks all session commits in one DB hit via the jsonb ?|
	 * operator.
	 */
	public List<PullRequestData> findByOrgAndAnyCommit(UUID orgUuid, String[] sceUuids) {
		if (orgUuid == null || sceUuids == null || sceUuids.length == 0) return List.of();
		return repository.findByOrgAndAnyCommit(orgUuid.toString(), sceUuids).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<PullRequestData> listOpenByTargetRepository(UUID targetRepoUuid) {
		return repository.findOpenByTargetRepository(targetRepoUuid.toString()).stream()
				.map(PullRequestData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Close every still-OPEN PR on the given VCS whose
	 * {@code sourceBranchName} is no longer in the SCM-live list. Called
	 * from {@code synchronizeLiveBranches}: when CI tells us the
	 * authoritative branch list for a repo, any PR rooted on a branch that
	 * no longer exists must have been closed/merged upstream — there is
	 * no SCM webhook in CE/SAAS to drive this otherwise, so the user's
	 * own sync call has to be the trigger.
	 *
	 * Conservative state choice: CLOSED, not MERGED. Branch deletion is
	 * the typical post-merge artifact, but we can't distinguish a clean
	 * merge from a rejected close from this signal alone. Callers that
	 * have authoritative info (a webhook, a `gh pr view --json state`
	 * lookup) can still upsert an explicit MERGED via the programmatic
	 * mutation; those overrides keep working because this path skips
	 * any PR already in a terminal state.
	 *
	 * Branch-name comparison mirrors {@code findDeadBranches}: cleaned
	 * via {@link Utils#cleanBranch} and lower-cased so refs/heads/foo,
	 * Foo, and foo collapse to the same key.
	 */
	@Transactional
	public int closeStalePrsForVcs(UUID vcsUuid, List<String> liveBranchNames, WhoUpdated wu) {
		if (vcsUuid == null) return 0;
		java.util.Set<String> live = (liveBranchNames == null ? List.<String>of() : liveBranchNames).stream()
				.filter(StringUtils::isNotBlank)
				.map(Utils::cleanBranch)
				.map(String::toLowerCase)
				.collect(Collectors.toSet());
		List<PullRequestData> openPrs = listOpenByTargetRepository(vcsUuid);
		int closed = 0;
		for (PullRequestData prd : openPrs) {
			String src = prd.getSourceBranchName();
			if (StringUtils.isBlank(src)) continue;
			String key = Utils.cleanBranch(src).toLowerCase();
			if (live.contains(key)) continue;
			try {
				prd.setState(PullRequestState.CLOSED);
				if (prd.getClosedDate() == null) prd.setClosedDate(ZonedDateTime.now());
				updatePullRequest(prd, wu);
				recordUpdateEvent(prd.getUuid(), PullRequestUpdateScope.STATE,
						PullRequestUpdateAction.CHANGED,
						PullRequestState.OPEN.name(), PullRequestState.CLOSED.name(),
						null, wu);
				closed++;
			} catch (Exception e) {
				log.warn("syncbranches: failed to close stale PR {} on VCS {}: {}",
						prd.getUuid(), vcsUuid, e.getMessage());
			}
		}
		return closed;
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
		// Terminal-state defaulting on the create path only. Webhook intake
		// already passes merged_at / closed_at; CLI upsert does not, so a
		// CLI-driven PR that is born in a terminal state still ends up with
		// the correct timestamp populated. Existing-row updates handle the
		// state-transition case in {@link #applyFromInput} and don't reset
		// a date that was already recorded.
		if (seed.getState() == PullRequestState.MERGED && seed.getMergedDate() == null) {
			seed.setMergedDate(ZonedDateTime.now());
		}
		if (seed.getState() == PullRequestState.CLOSED && seed.getClosedDate() == null) {
			seed.setClosedDate(ZonedDateTime.now());
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
			ZonedDateTime mergedDate = parseDate(prInput.get("mergedDate"));
			ZonedDateTime closedDate = parseDate(prInput.get("closedDate"));
			PullRequestData seed = new PullRequestData();
			seed.setOrg(orgUuid);
			seed.setTargetVcsRepository(targetVcs);
			seed.setIdentity(identity);
			seed.setState(prState);
			seed.setTitle((String) prInput.get("title"));
			seed.setSourceBranchName((String) prInput.get("sourceBranchName"));
			seed.setTargetBranchName((String) prInput.get("targetBranchName"));
			seed.setEndpoint(prEndpoint);
			seed.setMergedDate(mergedDate);
			seed.setClosedDate(closedDate);

			PullRequestData prd = upsertByIdentity(seed, wu);
			// upsertByIdentity returns the existing row untouched when one
			// already matches the (targetVcs, identity) key. Surface the
			// caller's mutable-field updates (state / title / branch names /
			// endpoint) so downstream readers don't lag behind the SCM.
			boolean changed = false;
			if (prd.getState() != seed.getState()) {
				prd.setState(seed.getState());
				// Default the terminal-state timestamp to "now" if the
				// caller didn't provide one (CLI flow has no merged_at /
				// closed_at parity with the GitHub webhook payload).
				if (seed.getState() == PullRequestState.MERGED && mergedDate == null && prd.getMergedDate() == null) {
					mergedDate = ZonedDateTime.now();
				}
				if (seed.getState() == PullRequestState.CLOSED && closedDate == null && prd.getClosedDate() == null) {
					closedDate = ZonedDateTime.now();
				}
				changed = true;
			}
			if (seed.getTitle() != null && !seed.getTitle().equals(prd.getTitle())) { prd.setTitle(seed.getTitle()); changed = true; }
			if (seed.getEndpoint() != null && !seed.getEndpoint().equals(prd.getEndpoint())) { prd.setEndpoint(seed.getEndpoint()); changed = true; }
			if (seed.getSourceBranchName() != null && !seed.getSourceBranchName().equals(prd.getSourceBranchName())) { prd.setSourceBranchName(seed.getSourceBranchName()); changed = true; }
			if (seed.getTargetBranchName() != null && !seed.getTargetBranchName().equals(prd.getTargetBranchName())) { prd.setTargetBranchName(seed.getTargetBranchName()); changed = true; }
			if (mergedDate != null && !mergedDate.equals(prd.getMergedDate())) { prd.setMergedDate(mergedDate); changed = true; }
			if (closedDate != null && !closedDate.equals(prd.getClosedDate())) { prd.setClosedDate(closedDate); changed = true; }
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

	/**
	 * Latest pr_validation_event whose sourceCodeEntry matches the PR's
	 * current head SCE. Mirrors the aggregator's
	 * {@code findLatestForHead} so the resolver field and the dispatch
	 * path read the verdict the same way. Returns null when the PR has
	 * no commits yet, no events at all, or no event for the current
	 * head — UI treats null as "pending / not validated yet".
	 */
	public ValidationState getCurrentValidationState(PullRequestData prd) {
		if (prd == null) return null;
		List<UUID> commits = prd.getCommits();
		if (commits == null || commits.isEmpty()) return null;
		UUID head = commits.get(commits.size() - 1);
		List<PullRequestValidationEvent> events = prd.getPrValidationEvents();
		if (events == null || events.isEmpty()) return null;
		ValidationState latest = null;
		for (PullRequestValidationEvent ev : events) {
			if (head.equals(ev.sourceCodeEntry())) latest = ev.validationState();
		}
		return latest;
	}

	private static ZonedDateTime parseDate(Object raw) {
		if (raw == null) return null;
		if (raw instanceof ZonedDateTime zdt) return zdt;
		String s = raw.toString();
		if (StringUtils.isBlank(s)) return null;
		try {
			return ZonedDateTime.parse(s);
		} catch (Exception e) {
			log.warn("Could not parse date {}: {}", s, e.getMessage());
			return null;
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

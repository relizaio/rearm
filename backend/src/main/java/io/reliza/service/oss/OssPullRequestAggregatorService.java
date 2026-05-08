/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.PullRequestData;
import io.reliza.model.PullRequestData.PullRequestValidationEvent;
import io.reliza.model.PullRequestData.ReleaseValidationEvent;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.ValidationState;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.PullRequestService;
import lombok.extern.slf4j.Slf4j;

/**
 * Community-edition copy of the PR-aggregator. Folds release-level
 * validation outcomes into PR-level state and records a
 * pr_validation_event for the audit trail. Pure data; the SCM
 * dispatch layer (GITHUB_VALIDATE check-runs, PR comments) is part
 * of ReARM Pro (saas/) and is intentionally absent here — together
 * with output events and approval policies that fed the aggregator's
 * VALIDATE_PR / INVALIDATE_PR / PR_COMMENT firings on the Pro side.
 *
 * What CE keeps:
 *   - {@link #attributedReleasesForPr} — newest-per-component
 *     attribution. Resolver field on the PR view. Pure read.
 *   - {@link #recomputeForPr} — recomputes aggregate state and
 *     records a pr_validation_event when it differs from the latest
 *     entry on the current head. No SCM dispatch.
 *   - {@link #recomputeForReleaseSce} — saveRelease hook; re-runs
 *     aggregation on every open PR whose commits[] includes the
 *     release's SCE so a release attached without an explicit
 *     trigger (or a lifecycle change to CANCELLED/REJECTED) still
 *     updates the PR verdict.
 *
 * What CE drops vs Pro:
 *   - {@code processReleaseValidationOutcome} — fired from
 *     VALIDATE_PR/INVALIDATE_PR output events; output events are
 *     not part of CE.
 *   - SCM dispatch helpers (check-runs / comments).
 */
@Service
@Slf4j
public class OssPullRequestAggregatorService {

	@Autowired
	private PullRequestService pullRequestService;

	@Autowired
	private ReleaseRepository releaseRepository;

	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	/**
	 * Force a re-aggregation on a specific PR. Records a
	 * pr_validation_event when the aggregate state changes for the
	 * current head SCE; never dispatches anywhere (CE has no SCM
	 * integration surface). Used by the head-advance path so the new
	 * head's state is observable in the UI's PR validation events
	 * timeline.
	 */
	@Transactional
	public void recomputeForPr(UUID prUuid, WhoUpdated wu) {
		try {
			aggregateAndRecord(prUuid, wu);
		} catch (RelizaException e) {
			log.warn("PR aggregation failed for PR {} (recompute): {}", prUuid, e.getMessage());
		}
	}

	/**
	 * Trigger aggregation on every open PR a release attributes to —
	 * called from {@code OssReleaseService.saveRelease} after every
	 * release create/update so the aggregator picks up cases where a
	 * release is attached to the PR's commits before any explicit
	 * validation event has been wired (CE has no VALIDATE_PR/INVALIDATE_PR
	 * triggers at all, so this is the primary path that keeps
	 * pr_validation_events live in CE).
	 */
	@Transactional
	public void recomputeForReleaseSce(ReleaseData rd, WhoUpdated wu) {
		if (rd == null || rd.getSourceCodeEntry() == null) return;
		Optional<SourceCodeEntryData> oSce = getSourceCodeEntryService
				.getSourceCodeEntryData(rd.getSourceCodeEntry());
		if (oSce.isEmpty() || oSce.get().getVcs() == null) return;
		UUID targetVcs = oSce.get().getVcs();
		List<PullRequestData> prs = pullRequestService.findOpenByTargetRepoAndCommit(targetVcs,
				rd.getSourceCodeEntry());
		for (PullRequestData prd : prs) {
			try {
				aggregateAndRecord(prd.getUuid(), wu);
			} catch (RelizaException e) {
				log.warn("PR aggregation failed for PR {} from release {} (recompute): {}",
						prd.getUuid(), rd.getUuid(), e.getMessage());
			}
		}
	}

	/**
	 * Compute the aggregate state and append a pr_validation_event when
	 * the verdict for the current head SCE changes. Returns the new
	 * aggregate state for callers that want to act on it.
	 */
	@Transactional
	public ValidationState aggregateAndRecord(UUID prUuid, WhoUpdated wu) throws RelizaException {
		PullRequestData prd = pullRequestService.getPullRequestData(prUuid)
				.orElseThrow(() -> new RelizaException("PR not found: " + prUuid));
		AggregationResult agg = computeAggregate(prd);

		ValidationState lastForHead = findLatestForHead(prd, agg.headSce);
		if (agg.state == lastForHead) {
			log.debug("PR {} aggregation unchanged at head {} (state={}); not recording event",
					prUuid, agg.headSce, agg.state);
			return agg.state;
		}

		PullRequestValidationEvent ev = new PullRequestValidationEvent(
				ZonedDateTime.now(), agg.state, agg.comment, agg.headSce,
				new LinkedList<>(agg.attributedReleases), wu);
		pullRequestService.recordPrValidationEvent(prUuid, ev);
		return agg.state;
	}

	/**
	 * Public attribution lookup — newest release per component across
	 * the PR's commits. Order: commits[] index (later = newer); tiebreak
	 * by createdDate desc when multiple releases share a commit (rebuild
	 * scenario). Same set the verdict computation considers.
	 */
	public List<ReleaseData> attributedReleasesForPr(PullRequestData prd) {
		List<UUID> commits = prd.getCommits();
		if (commits == null || commits.isEmpty()) return List.of();

		List<String> sceStrs = commits.stream().map(UUID::toString).collect(Collectors.toList());
		List<Release> all = releaseRepository.findReleasesBySces(sceStrs);

		// Index commit position so we can pick "latest commit per component".
		// Later index in commits[] = newer head advance.
		Map<UUID, Integer> commitPosition = new HashMap<>();
		for (int i = 0; i < commits.size(); i++) commitPosition.put(commits.get(i), i);

		Map<UUID, ReleaseData> latestPerComponent = new LinkedHashMap<>();
		for (Release r : all) {
			ReleaseData rd = ReleaseData.dataFromRecord(r);
			if (prd.getOrg() != null && !prd.getOrg().equals(rd.getOrg())) continue;
			UUID compId = rd.getComponent();
			if (compId == null) continue;
			Integer pos = commitPosition.get(rd.getSourceCodeEntry());
			if (pos == null) continue;
			ReleaseData incumbent = latestPerComponent.get(compId);
			if (incumbent == null || isReleaseNewer(rd, incumbent, commitPosition)) {
				latestPerComponent.put(compId, rd);
			}
		}
		return new ArrayList<>(latestPerComponent.values());
	}

	private boolean isReleaseNewer(ReleaseData candidate, ReleaseData incumbent, Map<UUID, Integer> commitPosition) {
		int candPos = commitPosition.getOrDefault(candidate.getSourceCodeEntry(), -1);
		int incPos = commitPosition.getOrDefault(incumbent.getSourceCodeEntry(), -1);
		if (candPos != incPos) return candPos > incPos;
		ZonedDateTime candDate = candidate.getCreatedDate();
		ZonedDateTime incDate = incumbent.getCreatedDate();
		if (candDate == null && incDate == null) return false;
		if (incDate == null) return true;
		if (candDate == null) return false;
		return candDate.isAfter(incDate);
	}

	private AggregationResult computeAggregate(PullRequestData prd) {
		AggregationResult result = new AggregationResult();
		List<UUID> commits = prd.getCommits();
		if (commits == null || commits.isEmpty()) {
			result.state = ValidationState.NEUTRAL;
			result.comment = "No commits attributed to this PR yet.";
			return result;
		}
		result.headSce = commits.get(commits.size() - 1);

		List<ReleaseData> rds = attributedReleasesForPr(prd);
		result.attributedReleases = rds.stream().map(ReleaseData::getUuid).collect(Collectors.toSet());

		if (rds.isEmpty()) {
			result.state = ValidationState.NEUTRAL;
			result.comment = "No releases attributed to this PR yet.";
			return result;
		}

		// Latest-wins folding of inbound release_validation_events keyed by release.
		Map<UUID, ValidationState> latestPerRelease = new HashMap<>();
		if (prd.getReleaseValidationEvents() != null) {
			List<ReleaseValidationEvent> events = new ArrayList<>(prd.getReleaseValidationEvents());
			events.sort(Comparator.comparing(ReleaseValidationEvent::date,
					Comparator.nullsFirst(Comparator.naturalOrder())));
			for (ReleaseValidationEvent ev : events) {
				latestPerRelease.put(ev.release(), ev.validationResult());
			}
		}

		boolean sawFailure = false;
		boolean sawPending = false;
		boolean allSuccess = true;
		for (ReleaseData r : rds) {
			ValidationState contrib = contributionFor(r, latestPerRelease.get(r.getUuid()));
			if (contrib == ValidationState.FAILURE || contrib == ValidationState.CANCELLED) {
				sawFailure = true;
				allSuccess = false;
			} else if (contrib == ValidationState.PENDING) {
				sawPending = true;
				allSuccess = false;
			} else if (contrib != ValidationState.SUCCESS) {
				allSuccess = false;
			}
		}
		if (sawFailure) {
			result.state = ValidationState.FAILURE;
			result.comment = "At least one release attributed to this PR head failed validation.";
		} else if (allSuccess) {
			result.state = ValidationState.SUCCESS;
			result.comment = "All " + rds.size() + " release(s) attributed to this PR head passed validation.";
		} else if (sawPending) {
			result.state = ValidationState.PENDING;
			result.comment = "Validation outcome pending for one or more releases attributed to this PR head.";
		} else {
			result.state = ValidationState.NEUTRAL;
			result.comment = "Mixed validation states across attributed releases.";
		}
		return result;
	}

	/**
	 * CANCELLED/REJECTED lifecycles count as implicit failures regardless
	 * of any logged event. Releases with no event yet contribute PENDING.
	 * In CE the inbound release_validation_events are typically empty
	 * (no VALIDATE_PR / INVALIDATE_PR output events to fire them) so
	 * everything except cancelled/rejected releases reads as PENDING.
	 */
	private ValidationState contributionFor(ReleaseData r, ValidationState explicit) {
		if (r.getLifecycle() == ReleaseLifecycle.CANCELLED || r.getLifecycle() == ReleaseLifecycle.REJECTED) {
			return ValidationState.FAILURE;
		}
		return explicit == null ? ValidationState.PENDING : explicit;
	}

	private ValidationState findLatestForHead(PullRequestData prd, UUID head) {
		if (prd.getPrValidationEvents() == null || head == null) return null;
		ValidationState latest = null;
		for (PullRequestValidationEvent ev : prd.getPrValidationEvents()) {
			if (head.equals(ev.sourceCodeEntry())) {
				latest = ev.validationState();
			}
		}
		return latest;
	}

	private static final class AggregationResult {
		ValidationState state = ValidationState.NEUTRAL;
		UUID headSce;
		Set<UUID> attributedReleases = new HashSet<>();
		String comment = "";
	}
}

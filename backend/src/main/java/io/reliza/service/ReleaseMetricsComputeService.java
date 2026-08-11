/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.repositories.ReleaseRepository;

/**
 * Service for computing release metrics.
 * Separated from ReleaseService to ensure @Transactional annotations are properly applied via Spring AOP proxies.
 */
@Service
public class ReleaseMetricsComputeService {

	private static final Logger log = LoggerFactory.getLogger(ReleaseMetricsComputeService.class);

	@Autowired
	private ReleaseRepository repository;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private ArtifactGatherService artifactGatherService;

	@Autowired
	private ArtifactService artifactService;

	@Autowired
	private VulnAnalysisService vulnAnalysisService;

	@Autowired
	private KevAssertionService kevAssertionService;

	@Transactional
	public Optional<Release> getReleaseWriteLocked(UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}

	@Transactional
	protected boolean computeReleaseMetricsOnRescan(Release r) {
		// Acquire write lock to prevent concurrent modifications
		Optional<Release> lockedRelease = getReleaseWriteLocked(r.getUuid());
		if (lockedRelease.isEmpty()) {
			log.warn("Release {} no longer exists, skipping metrics computation", r.getUuid());
			return false;
		}
		r = lockedRelease.get();
		ZonedDateTime lastScanned = ZonedDateTime.now();
		var rd = ReleaseData.dataFromRecord(r);
		var originalMetrics = null != rd.getMetrics() ? rd.getMetrics().clone() : null;
		if (null == originalMetrics || null == originalMetrics.getLastScanned() || lastScanned.isAfter(originalMetrics.getLastScanned())) {
			ReleaseMetricsDto rmd = new ReleaseMetricsDto();
			var allReleaseArts = artifactGatherService.gatherReleaseArtifacts(rd);
			final ZonedDateTime[] releaseFirstScanned = { null };
			final boolean[] hasAnyBomArtifact = { false };
			// All-or-nothing flag for every BOM gathered for this release —
			// release-direct, SCE-attached, and outbound-deliverable BOMs all
			// flow through the same `gatherReleaseArtifacts` set. As soon as
			// any one of them lacks firstScanned (still in flight, or never
			// submitted), the release's firstScanned must remain null.
			// Matches the parent/child rollup semantic enforced below and
			// makes `release.firstScanned` a reliable "scan complete" signal
			// for CEL conditions on policy-wide rules (notably PR_COMMENT).
			final boolean[] anyBomUnscanned = { false };
			allReleaseArts.forEach(aid -> {
				var ad = artifactService.getArtifactData(aid);
				if (ad.isPresent()) {
					ArtifactData artifactData = ad.get();
					if (artifactData.getType() == ArtifactType.BOM) {
						hasAnyBomArtifact[0] = true;
					}
					ReleaseMetricsDto artifactMetrics = artifactData.getMetrics();
					if (artifactMetrics != null) {
						// Set attributedAt to artifact creation date for findings that don't have it
						artifactMetrics.setAttributedAtFallback(artifactData.getCreatedDate());
						rmd.mergeWithByContent(artifactMetrics);
					}
					// Compute release firstScanned as max of artifact firstScanned values.
					// Only the real artifact-level firstScanned counts — set by the
					// scan-data ingestion path (SharedArtifactService) when the
					// scanner actually returns findings. No createdDate-based
					// fallback: synthesizing a firstScanned for an artifact that
					// has been submitted but not yet scanned would surface stale
					// "ready" circles in the UI before the initial scan completes.
					ZonedDateTime artFs = (artifactData.getMetrics() != null) ? artifactData.getMetrics().getFirstScanned() : null;
					if (artifactData.getType() == ArtifactType.BOM && artFs == null) {
						anyBomUnscanned[0] = true;
					}
					if (artFs != null && (releaseFirstScanned[0] == null || artFs.isAfter(releaseFirstScanned[0]))) {
						releaseFirstScanned[0] = artFs;
					}
				}
			});
			ReleaseMetricsDto rolledUp = rollUpProductReleaseMetrics(rd);
			rmd.mergeWithByContent(rolledUp);
			vulnAnalysisService.processReleaseMetricsDto(rd.getOrg(), r.getUuid(), AnalysisScope.RELEASE, rmd);
			// Stamp KEV membership onto each finding, then re-derive kevCount.
			// Done after all merges + vuln-analysis so the probe sees the final
			// alias-organized vulnerabilityDetails. This is the authoritative
			// KEV stamp for the persisted metrics. orgUuid scopes the probe to
			// this org's kev_assertions (V54 per-org refactor).
			stampKnownExploited(rd.getOrg(), rmd);
			// A finding cannot be attributed to this release before the release
			// existed. Artifact-borne findings can carry earlier stamps (a shared
			// SCE artifact attached to a newer release, or synthetic-bucket
			// findings stamped with the bucket's scan time), and product rollups
			// inherit children's stamps -- floor them all at release creation.
			// Runs on the final merged shape so every contribution is covered,
			// and is idempotent across recomputes (assembly is rebuilt fresh).
			rmd.clampAttributedAtFloor(rd.getCreatedDate());
			if (null == lastScanned) lastScanned = ZonedDateTime.now();
			// lastScanned is stamped further down, gated on scanIncomplete: a still-pending
			// scan must not record a lastScanned (see the gate after firstScanned resolves).
			// Merge artifact firstScanned into whatever rollUpProductReleaseMetrics contributed.
			// Do NOT unconditionally overwrite: for product releases with no artifacts of
			// their own, releaseFirstScanned[0] is null and would wipe the value propagated
			// from child releases. Gated on anyBomUnscanned: if any BOM on this release
			// (direct, SCE, or outbound deliverable) is still in flight we suppress the
			// merge rather than promote a partial timestamp.
			if (releaseFirstScanned[0] != null && !anyBomUnscanned[0]) {
				if (rmd.getFirstScanned() == null || releaseFirstScanned[0].isAfter(rmd.getFirstScanned())) {
					rmd.setFirstScanned(releaseFirstScanned[0]);
				}
			}
			// All-or-nothing on artifact-level scans: any unscanned BOM also clobbers
			// whatever firstScanned the product rollup contributed. Without this, a
			// product-style merge of a child release's firstScanned could land on a
			// release whose own BOMs aren't all scanned yet, and `release.firstScanned`
			// would read as "ready" too early.
			if (anyBomUnscanned[0]) {
				rmd.setFirstScanned(null);
			}
			// All-or-nothing: if any known child release lacks firstScanned, the product
			// release's firstScanned must remain null. rollUpProductReleaseMetrics signals
			// this by returning a metrics DTO with firstScanned=null when at least one
			// child is unscanned. Override here because mergeWithByContent above can't
			// distinguish "child rollup says null" from "no child contribution at all".
			boolean hasChildren = rd.getParentReleases() != null && !rd.getParentReleases().isEmpty();
			boolean childrenIncomplete = hasChildren && rolledUp.getFirstScanned() == null;
			if (childrenIncomplete) {
				rmd.setFirstScanned(null);
			}
			// Only stamp lastScanned for a *complete* scan. A scan is incomplete while a
			// BOM on this release, or a child release, still lacks firstScanned. Recording
			// a lastScanned in that state advances it past last_updated_date, which evicts
			// the release from the BY_UPDATE self-heal finder
			// (ReleaseService.computeMetricsForAllUnprocessedReleases) — and nothing would
			// ever re-derive it: the lagging input's own scan-completion recomputes that
			// input, not this parent. Leaving lastScanned null keeps the release "dirty" so
			// the every-minute sweep re-picks it until firstScanned can actually be set.
			// This is the single guard that makes stuck product/aggregate releases
			// self-heal; see the matching skip of touchReleaseLastScanned below.
			//
			// Lifecycle gate: only *scannable* (ASSEMBLED+) releases wait. A CANCELLED /
			// REJECTED / PENDING / DRAFT release with unscanned inputs has nothing to
			// wait for — its BOM will never be scanned (rejectPendingReleases
			// auto-cancels abandoned CI runs every tick, minting exactly these rows) —
			// so it settles (stamps lastScanned, leaves the finders) instead of
			// squatting at the head of the finders' ASC order forever. If it's ever
			// revived, the lifecycle transition bumps last_updated_date and it
			// re-enters the queue naturally.
			boolean scanIncomplete = (anyBomUnscanned[0] || childrenIncomplete)
					&& isScannableLifecycle(rd.getLifecycle());
			if (!scanIncomplete) {
				rmd.setLastScanned(lastScanned);
			}
			// No-BOM anchor: a release that has reached a scannable lifecycle
			// (ASSEMBLED or beyond) but has no BOM artifacts attached anywhere
			// (release-direct, SCE, or outbound deliverables) is trivially
			// "scan complete" — there is nothing for DTrack to scan.
			// Without this, releases with no scannable inputs would surface
			// "Scan pending" indefinitely, and product releases that depend
			// on them could never aggregate firstScanned under the
			// all-or-nothing rollup contract.
			//
			// Anchor to the release createdDate so the value is deterministic
			// (idempotent across rescans) and chronologically sane vs. any
			// real children's firstScanned that get max'd with it upstream.
			//
			// Skipped when childrenIncomplete is true so we don't override
			// an unscanned-child signal with a synthetic anchor.
			if (rmd.getFirstScanned() == null
					&& !childrenIncomplete
					&& !hasAnyBomArtifact[0]
					&& isScannableLifecycle(rd.getLifecycle())
					&& rd.getCreatedDate() != null) {
				rmd.setFirstScanned(rd.getCreatedDate());
			}
			// Final safety: if every other path left rmd.firstScanned null but
			// we previously had one persisted (set by an earlier scan, or by
			// the V34 backfill on pre-bridge rows), preserve the historical
			// value rather than reverting it to null. Without this, a release
			// that's since transitioned to a non-scannable lifecycle (e.g.
			// CANCELLED) loses its firstScanned on the next scheduler tick
			// because isScannableLifecycle gates the no-BOM anchor — and the
			// V34 backfill is silently undone on rows it just fixed.
			if (rmd.getFirstScanned() == null
					&& originalMetrics != null
					&& originalMetrics.getFirstScanned() != null
					&& !anyBomUnscanned[0]
					&& !childrenIncomplete) {
				rmd.setFirstScanned(originalMetrics.getFirstScanned());
			}
			// Backoff fence: an incomplete compute is waiting on an external event
			// (DTrack scan, child release completion). Fence the release out of the
			// metrics finders for an escalating interval so it stops consuming one of
			// the per-tick finder slots every minute — without a fence, permanently
			// waiting rows are the oldest entries in every finder's ASC order and
			// starve younger rows behind them. The first attempts are free (grace
			// window, fence of 0s) so the healthy path — DTrack returns within a few
			// minutes — keeps today's per-minute retry latency. A complete compute
			// drops the fence so any future wait starts fresh.
			if (scanIncomplete) {
				repository.recordMetricsComputeIncomplete(r.getUuid(),
						nextMetricsComputeBackoffSeconds(r.getFlowControl()));
				// Surface long-running stalls: the fence is by-design silent
				// (waiting on an external event is normal), but a release
				// that has been incomplete for ~a day of hourly retries is
				// stuck on something that will not arrive by itself — e.g. a
				// BOM artifact that never reached scanning (2026-07-12 prod
				// incident sat like this for 11 days / 266 attempts with no
				// signal). Warn once per ~24 capped-backoff attempts.
				FlowControl fcNow = r.getFlowControl();
				int attemptsNow = (fcNow != null && fcNow.metricsComputeFailureCount() != null)
						? fcNow.metricsComputeFailureCount() : 0;
				if (attemptsNow > 0 && attemptsNow % 24 == 0) {
					log.warn("Metrics compute for release {} has been incomplete for {} attempts — "
							+ "likely waiting on an artifact that never reached scanning; "
							+ "check its BOM artifacts' scan state", r.getUuid(), attemptsNow);
				}
			} else {
				repository.clearMetricsComputeBackoff(r.getUuid());
			}
			// Child-change push: ANY metrics delta on this release must reach its
			// containing products, not just the firstScanned transition. Products
			// re-derive their aggregate from children only when a finder selects
			// them, and a settled product (lastScanned stamped, row untouched) is
			// outside every finder pool — so without this push a product's
			// aggregate freezes at whatever its children looked like when it last
			// computed, silently ignoring later child rescans that add or resolve
			// findings. touchForMetricsRecompute bumps the product row back into
			// the BY_UPDATE pool and drops its fence in one statement; multi-level
			// products chain one level per tick, and the chain terminates as soon
			// as a level's aggregate comes out unchanged.
			rd.setMetrics(rmd);
			if (!rmd.equals(originalMetrics)) {
				sharedReleaseService.saveReleaseMetrics(r, rmd);
				markContainingProductsStale(rd);
				return true;
			} else if (!scanIncomplete) {
				// Complete + unchanged: settle by stamping lastScanned so the release
				// leaves the BY_UPDATE finder. While the scan is still incomplete we skip
				// the touch entirely — touchReleaseLastScanned bumps last_updated_date and
				// lastScanned together, which would evict the release before the pending
				// child/BOM finishes and strand it in "Scan pending".
				sharedReleaseService.touchReleaseLastScanned(r.getUuid());
			}
		}
		return false;
	}

	@Transactional
	protected boolean computeReleaseMetricsOnNonRescan(Release r) {
		// Acquire write lock to prevent concurrent modifications
		Optional<Release> lockedRelease = getReleaseWriteLocked(r.getUuid());
		if (lockedRelease.isEmpty()) {
			log.warn("Release {} no longer exists, skipping metrics computation", r.getUuid());
			return false;
		}
		r = lockedRelease.get();
		var rd = ReleaseData.dataFromRecord(r);
		if (null != rd.getMetrics()) {
			ReleaseMetricsDto originalMetrics = rd.getMetrics();
			ReleaseMetricsDto clonedMetrics = originalMetrics.clone();
			vulnAnalysisService.processReleaseMetricsDto(rd.getOrg(), r.getUuid(), AnalysisScope.RELEASE, clonedMetrics);
			stampKnownExploited(rd.getOrg(), clonedMetrics);
			if (!clonedMetrics.equals(originalMetrics)) {
				rd.setMetrics(clonedMetrics);
				sharedReleaseService.saveReleaseMetrics(r, clonedMetrics);
				return true;
			} else if (originalMetrics.getFirstScanned() != null) {
				// Settle only releases whose initial scan has completed. Touching a
				// still-scan-pending release stamps lastScanned + last_updated_date
				// together, which evicts it from the BY_UPDATE finder before its BOM /
				// children finish — and the rescan path (which owns firstScanned)
				// would never see it again. This non-rescan path runs on analysis
				// (triage) updates, which can land while the initial scan is pending.
				sharedReleaseService.touchReleaseLastScanned(r.getUuid());
			}
		}
		return false;
	}

	/**
	 * Batch-resolves KEV membership across every finding on {@code rmd} and
	 * rewrites each {@link VulnerabilityDto} with the probe result, then
	 * re-derives {@code kevCount} via {@code computeMetricsFromFacts()}.
	 *
	 * <p>One {@link KevAssertionService#filterKnownExploited} round trip for the
	 * whole release: candidate CVE ids (the primary id plus every CVE-shaped
	 * alias, normalized) are unioned across all findings, probed once, then a
	 * per-finding membership check stamps {@code knownExploited}. Mirrors the
	 * read-time batching in {@code KevDataFetcher}. Records are immutable, so we
	 * rebuild the list via {@link VulnerabilityDto#withKnownExploited}.
	 *
	 * <p>Guard: if no finding carries a CVE-shaped candidate id (e.g. a release
	 * with only GHSA/OSV findings), the probe is skipped entirely — every
	 * finding is stamped {@code FALSE} without a DB call.
	 */
	private void stampKnownExploited(java.util.UUID orgUuid, ReleaseMetricsDto rmd) {
		List<VulnerabilityDto> findings = rmd.getVulnerabilityDetails();
		if (findings == null || findings.isEmpty()) {
			rmd.computeMetricsFromFacts();
			return;
		}
		Set<String> allCandidates = new LinkedHashSet<>();
		for (VulnerabilityDto vuln : findings) {
			allCandidates.addAll(candidateCveIds(vuln));
		}
		Set<String> listed;
		if (orgUuid == null || allCandidates.isEmpty()) {
			// No org context or no CVE-shaped ids: nothing can match the
			// per-org KEV catalog.
			listed = Set.of();
		} else {
			listed = kevAssertionService.filterKnownExploited(orgUuid, allCandidates);
		}
		List<VulnerabilityDto> stamped = new ArrayList<>(findings.size());
		for (VulnerabilityDto vuln : findings) {
			boolean kev = false;
			for (String candidate : candidateCveIds(vuln)) {
				if (listed.contains(candidate)) {
					kev = true;
					break;
				}
			}
			stamped.add(vuln.withKnownExploited(kev));
		}
		rmd.setVulnerabilityDetails(stamped);
		// Re-derive kevCount (and the other tallies) from the freshly stamped findings.
		rmd.computeMetricsFromFacts();
	}

	/**
	 * Candidate CVE ids for one finding: normalized primary id plus each
	 * normalized CVE-shaped alias. Non-CVE ids normalize to null and are
	 * dropped, mirroring {@code KevDataFetcher.resolveKnownExploited}.
	 */
	private static Set<String> candidateCveIds(VulnerabilityDto vuln) {
		Set<String> candidates = new LinkedHashSet<>();
		String primary = KevAssertionService.normalizeCveId(vuln.vulnId());
		if (primary != null) candidates.add(primary);
		if (vuln.aliases() != null) {
			for (VulnerabilityAliasDto alias : vuln.aliases()) {
				String normalized = alias != null ? KevAssertionService.normalizeCveId(alias.aliasId()) : null;
				if (normalized != null) candidates.add(normalized);
			}
		}
		return candidates;
	}

	/**
	 * "Scannable lifecycle" = the release has reached a stage at which we
	 * expect scanning to have completed (or to be unnecessary). PENDING /
	 * DRAFT releases are still being assembled; CANCELLED / REJECTED
	 * releases never assemble. Anything ASSEMBLED-or-later is fair game
	 * for the no-BOM firstScanned anchor.
	 */
	private static boolean isScannableLifecycle(ReleaseLifecycle lc) {
		if (lc == null) return false;
		return lc.ordinal() >= ReleaseLifecycle.ASSEMBLED.ordinal();
	}

	// Escalating fence for incomplete metrics computes; mirrors the SBOM
	// reconcile backoff in SbomComponentService. The first GRACE attempts are
	// free (0s fence — per-minute retries) so the healthy path, where DTrack
	// returns within a few minutes, keeps today's latency. After the grace
	// window the fence doubles from BASE up to MAX, so a release waiting on
	// something that never arrives retries forever without occupying finder
	// slots: 5 free ticks, then 60, 120, 240, 480, 960, 1920, 3600, 3600...
	private static final int METRICS_BACKOFF_GRACE_ATTEMPTS = 5;
	private static final int METRICS_BACKOFF_BASE_SECONDS = 60;
	private static final int METRICS_BACKOFF_MAX_SECONDS = 3600;

	/**
	 * Next fence interval given the release's current flow_control. Package
	 * visible so the poison-pill catch in
	 * {@code ReleaseService.computeMetricsForReleaseList} escalates on the
	 * same schedule.
	 */
	static int nextMetricsComputeBackoffSeconds(FlowControl fc) {
		int priorAttempts = (fc != null && fc.metricsComputeFailureCount() != null)
				? fc.metricsComputeFailureCount() : 0;
		if (priorAttempts < METRICS_BACKOFF_GRACE_ATTEMPTS) return 0;
		int escalation = priorAttempts - METRICS_BACKOFF_GRACE_ATTEMPTS;
		return Math.min(METRICS_BACKOFF_BASE_SECONDS << Math.min(escalation, 7),
				METRICS_BACKOFF_MAX_SECONDS);
	}

	/**
	 * Push this release's metrics change to every product release that bundles
	 * {@code rd}: bump each product back into the {@code BY_UPDATE} finder pool
	 * (and drop its fence) so its aggregate is re-derived from current child
	 * state on the next scheduler tick. Replaces the old fence-only clear,
	 * which (a) fired only on the firstScanned transition and (b) did not
	 * touch {@code last_updated_date}, so a settled product stayed outside
	 * every finder and its aggregate went permanently stale. Best-effort: a
	 * failure here only delays the parent, never strands it — the next child
	 * change retries.
	 */
	private void markContainingProductsStale(ReleaseData rd) {
		try {
			List<Release> products = repository.findProductsByRelease(
					rd.getOrg().toString(), rd.getUuid().toString());
			// UUID order: these touches run inside the caller's open compute
			// transaction, and the parallel metrics workers can touch
			// overlapping product sets concurrently -- a consistent lock
			// acquisition order across workers makes deadlock impossible.
			products.sort(java.util.Comparator.comparing(Release::getUuid));
			for (Release p : products) {
				repository.touchForMetricsRecompute(p.getUuid());
			}
		} catch (Exception e) {
			log.error("Failed to mark containing products stale for release {}",
					rd.getUuid(), e);
		}
	}

	private ReleaseMetricsDto rollUpProductReleaseMetrics(ReleaseData rd) {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		var parents = rd.getParentReleases();
		if (parents == null || parents.isEmpty()) {
			return rmd;
		}
		// Track all-or-nothing for children's firstScanned: a product release's
		// "initial scan complete" signal should only fire once every known child
		// release has been scanned. If any child lacks firstScanned, the product's
		// firstScanned must stay null.
		final boolean[] allChildrenScanned = { true };
		final ZonedDateTime[] maxChildFirstScanned = { null };
		parents.forEach(r -> {
			ReleaseData parentRd = sharedReleaseService
					.getReleaseData(r.getRelease(), rd.getOrg()).get();
			ReleaseMetricsDto parentReleaseMetrics = parentRd.getMetrics();
			if (parentReleaseMetrics == null) {
				allChildrenScanned[0] = false;
				return;
			}
			parentReleaseMetrics.enrichSourcesWithRelease(r.getRelease());
			rmd.mergeWithByContent(parentReleaseMetrics);
			rmd.computeMetricsFromFacts();
			ZonedDateTime childFs = parentReleaseMetrics.getFirstScanned();
			if (childFs == null) {
				allChildrenScanned[0] = false;
			} else if (maxChildFirstScanned[0] == null || childFs.isAfter(maxChildFirstScanned[0])) {
				maxChildFirstScanned[0] = childFs;
			}
		});
		// mergeWithByContent above only takes max-of-non-null for firstScanned,
		// which is wrong for the rollup contract. Override with all-or-nothing.
		//
		// Plus a lifecycle gate: a product release that hasn't reached
		// scannable lifecycle (PENDING / DRAFT) is still being assembled —
		// surfacing firstScanned while the release is in flight would fire
		// scan-complete triggers (PR_COMMENT etc.) before the release is
		// officially formed. Until the product release itself transitions to
		// ASSEMBLED+, treat its rolled-up firstScanned as not-yet-scanned
		// regardless of how complete the children are.
		boolean productScannable = isScannableLifecycle(rd.getLifecycle());
		rmd.setFirstScanned((allChildrenScanned[0] && productScannable) ? maxChildFirstScanned[0] : null);
		vulnAnalysisService.processReleaseMetricsDto(rd.getOrg(), rd.getUuid(), AnalysisScope.RELEASE, rmd);
		return rmd;
	}
}

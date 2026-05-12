/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
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
			if (null == lastScanned) lastScanned = ZonedDateTime.now();
			rmd.setLastScanned(lastScanned);
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
			rd.setMetrics(rmd);
			if (!rmd.equals(originalMetrics)) {
				sharedReleaseService.saveReleaseMetrics(r, rmd);
				return true;
			} else {
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
			if (!clonedMetrics.equals(originalMetrics)) {
				rd.setMetrics(clonedMetrics);
				sharedReleaseService.saveReleaseMetrics(r, clonedMetrics);
				return true;
			} else {
				sharedReleaseService.touchReleaseLastScanned(r.getUuid());
			}
		}
		return false;
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

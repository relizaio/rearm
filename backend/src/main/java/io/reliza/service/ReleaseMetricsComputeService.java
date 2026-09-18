/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

	/** Diagnostics only: re-derives which artifacts the SCE contributed, which the union erases. */
	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	/** Bounds the uuid samples carried by the diagnostic lines. Matches RepairTally.SAMPLE_LIMIT. */
	private static final int SAMPLE_LIMIT = 5;

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
			// Diagnostic counters for the metrics-loss probe: when a release loses findings with every completeness
			// flag false, the merge had nothing to merge -- the question is whether the artifacts were not
			// gathered, did not resolve, or resolved with no metrics. anyBomUnscanned cannot answer it,
			// because it is only ever set INSIDE this loop: no artifacts means no flag.
			final int[] artsResolved = { 0 };
			final int[] artsFindings = { 0 };
			final List<UUID> artsUnresolved = new ArrayList<>();
			allReleaseArts.forEach(aid -> {
				var ad = artifactService.getArtifactData(aid);
				if (ad.isEmpty() && artsUnresolved.size() < SAMPLE_LIMIT) {
					artsUnresolved.add(aid);
				}
				if (ad.isPresent()) {
					artsResolved[0]++;
					ArtifactData artifactData = ad.get();
					if (artifactData.getType() == ArtifactType.BOM) {
						hasAnyBomArtifact[0] = true;
					}
					ReleaseMetricsDto artifactMetrics = artifactData.getMetrics();
					if (artifactMetrics != null) {
						artsFindings[0] += countFindings(artifactMetrics);
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
			// RELEASE-LEVEL HOLD. A release whose gathered artifacts contribute ZERO findings, but which
			// previously HAD findings, has not remediated -- its inputs are pending or missing. Two shapes,
			// one rule:
			//   * gathered=0 -- ALL_ARTIFACTS_GONE: the artifacts were dereferenced (a rebuild repointed the
			//     SCE / cleared the list before the replacement landed; see ai-agents/all-artifacts-gone-
			//     hold-design.md). Carry-forward (#433) cannot help -- there is no replacement to seed.
			//   * gathered>=1 but every gathered BOM is unscanned -- the CI-rebuild SWAP: the old BOM was
			//     replaced by a brand-new unscanned one. #433's artifact seed covers this ONLY when its
			//     deliverable pairing succeeds; when the pairing declines (an unstable/absent CI-supplied
			//     displayIdentifier), the release collapses. This holds it regardless, with NO pairing.
			//
			// Left to the normal path either shape re-derives to a confident ZERO and emits a phantom
			// all-RESOLVED cycle -- the emit_disagreed alert. Instead HOLD the last-known findings by not
			// overwriting them, and fence for retry. It self-heals the moment a scan lands (gathered
			// contributes findings, this branch no longer fires), and surfaces in [METRICS-STALLED] if it
			// never does.
			//
			// UNION-SAFE by construction, which is what sank the earlier in-compute guards ("additions must
			// never wait; only losses may"): the trigger is artsFindings==0 -- NO gathered artifact carries
			// ANY finding -- so there is nothing to withhold. The moment any artifact contributes a finding
			// (a partial rebuild where one BOM scanned and added a new CVE while another is pending), the
			// guard does NOT fire, the normal union write runs, and that addition is published immediately;
			// the still-pending BOM's granular carry is #433's job, not this net's. A genuinely clean scan
			// (a gathered BOM that scanned to zero -- remediation) also does not fire: artsFindings==0 but
			// the gather is non-empty AND nothing is unscanned, so neither arm of the OR is true.
			// LEAF only -- a PRODUCT derives from its children (the rollup below), not its own artifacts.
			boolean hasChildRels = rd.getParentReleases() != null && !rd.getParentReleases().isEmpty();
			if (!hasChildRels
					&& isScannableLifecycle(rd.getLifecycle())
					&& countFindings(originalMetrics) > 0
					&& artsFindings[0] == 0
					&& (allReleaseArts.isEmpty() || anyBomUnscanned[0])) {
				// Hold: return WITHOUT writing, so the persisted findings stay put -- no zero, no phantom
				// emit, no settle -- and fence for retry exactly like a scan-incomplete release.
				fenceIncompleteCompute(r);
				return false;
			}
			ReleaseMetricsDto rolledUp = rollUpProductReleaseMetrics(rd);
			// Counted separately from the artifact sum: a PRODUCT release's findings come from its children,
			// not its own artifacts, so a rollup collapse would otherwise read as "artifacts empty" on the
			// loss line and send the reader to the wrong subsystem entirely.
			int rollupFindings = countFindings(rolledUp);
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
				fenceIncompleteCompute(r);
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
			// Separate tag, separate condition: something in this release's artifact GRAPH points at a
			// row that is not there, whatever happened to the findings. NOT "unreachable" -- an earlier
			// comment claimed that on the grounds that ArtifactRepository declares no delete method, but
			// it extends CrudRepository, so deleteById exists, and there are no FOREIGN KEYs to constrain
			// record_data.artifacts either (house rule). It is observed in practice.
			// OUTSIDE the metrics-changed block on purpose: a release whose metrics are healthy and steady never
			// re-enters that block, which is exactly the standing population this probe exists to surface.
			// Deliberately does NOT say the RELEASE holds the bad reference: gatherReleaseArtifacts
			// unions three owners -- the release's own artifacts, the SCE's, and every variant's outbound
			// deliverables' -- so the dangling uuid may belong to any of them. The uuids are printed
			// precisely because this probe cannot name the owner and the reader has to go look.
			if (artsResolved[0] < allReleaseArts.size()) {
				log.error("[ARTIFACT-REF-MISSING] release {} v{}: {} of {} gathered artifact reference(s) "
						+ "do not resolve. The reference may be held by the release, its source code "
						+ "entry, or a variant's outbound deliverables -- unresolved (up to {}): {}",
						r.getUuid(), rd.getVersion(), allReleaseArts.size() - artsResolved[0],
						allReleaseArts.size(), SAMPLE_LIMIT, artsUnresolved);
			}
			rd.setMetrics(rmd);
			if (!rmd.equals(originalMetrics)) {
				// DIAGNOSTIC (prod incident 2026-08-13): rmd is re-derived by MERGING artifact metrics,
				// so it is only as complete as the artifacts are at this instant. When it comes out smaller
				// than what is already persisted, the release visibly loses findings -- and the resulting
				// flap is what makes the v3 live emit see an EMPTY pre-image on the next scan, misread a
				// re-appearance as a first scan, and disagree with the repair sweep.
				// rev= is the revision saveReleaseMetrics stamps THIS write's audit row with. It is NOT the
				// revision the repair sweep will report the resulting disagreement at -- hence
				// disagreesAtRev=rev+1, and do not "correct" it. An audit row holds the state its write
				// REPLACED, so the pair stamped @rev(R) has a NON-empty older side and produces no
				// disagreement; it is the RECOVERY write R+1, whose audit row holds the empty snapshot, that
				// gives the sweep an empty older side, makes the live emit misread a re-appearance as a first
				// scan, and shows up as emit_disagreed @rev(R+1). Verified against production: a sample
				// entry reading @rev3 firstScan=true corresponds to the loss logged at rev=2.
				// It is a LOWER BOUND, not an equality: if the release never recovers there is no
				// disagreement at all, and a write that stays at zero while changing firstScanned (which
				// IS in ReleaseMetricsDto.equals, unlike lastScanned) pushes the recovery out to rev+2.
				// ONE EXCEPTION: on the duplicate-revision repair path saveReleaseMetrics stamps the audit
				// row with maxAuditRevision + 1 instead, so BOTH numbers here are wrong for that write. It
				// logs its own "Duplicate metrics audit revision detected" ERROR, so it is detectable.
				// (updateMetrics bumps metrics_revision in the same statement as the write --
				// ReleaseRepository, "SET metrics_revision = metrics_revision + 1".)
				// artifactFindings vs rollupFindings says which SUBSYSTEM lost them -- a product release's
				// findings come from its children, so artifactFindings=0 is normal there and only a drop in
				// rollupFindings is meaningful.
				// READING THE LINE: gathered=0, or resolved below gathered, means the artifacts VANISHED
				// from the release rather than losing findings in place -- note anyBomUnscanned can only be
				// set for an artifact that was actually gathered, so it reads false when there is nothing to
				// flag. If instead the artifacts are all present with metrics, look for the truncation probe
				// on this release (search: was wiped by an authoritative scan result). artifactFindings is a
				// PRE-MERGE sum, so it legitimately exceeds the release total once mergeWithByContent
				// dedupes. Both completeness states have been observed in production, so a gate on
				// scanIncomplete would only ever catch some of these.
				// The completeness flags ANSWERED that question, so it is no longer open. Measured over one
				// production night (2026-08-13 overnight, 59 loss lines): 51 carried scanIncomplete=true
				// and 8 carried false, so a scan-completeness guard could address at most 51/59 ~= 86%.
				// Most of the 8 are the gathered=0 shape (7 lines), i.e. a release that lost its artifact
				// list entirely. Note that is a DATA observation, not a code property: scanIncomplete is
				// (anyBomUnscanned || childrenIncomplete) && isScannableLifecycle, so a product with no
				// artifacts and an incomplete child can be gathered=0 AND scanIncomplete=true. Keep the
				// flags -- they are what separates the two populations.
				int hadFindings = countFindings(originalMetrics);
				int nowFindings = countFindings(rmd);
				// TOTAL collapse, not any shrink. A partial drop is remediation, a VEX suppression, or the
				// alias/dedup collapse inside processReleaseMetricsDto -- all legitimate, all common, and
				// paging on them would drown the monitored channel. Everything reaching zero is the shape
				// that flaps, and it covers BOTH reachable causes: the artifact list emptied (gathered=0)
				// and the artifacts present but contributing nothing.
				boolean collapsedToZero = isTotalFindingCollapse(hadFindings, nowFindings);
				if (nowFindings < hadFindings) {
					recordFindingLoss(hadFindings, nowFindings, collapsedToZero, scanIncomplete,
							!isScannableLifecycle(rd.getLifecycle()));
				}
				// A dangling reference is a SEPARATE condition and gets its own line below -- it is not a
				// loss, and OR-ing it in here produced "[METRICS-LOSS] findings 451 -> 451" on releases that
				// lost nothing. Measured on the sandbox: 11 of 424 release->artifact references point at
				// rows that do not resolve, on releases with entirely healthy metrics.
				// ERROR-log a per-release loss ONLY for a SCANNABLE (ASSEMBLED+) release. A collapse on a
				// sub-ASSEMBLED release (PENDING = CI-created, DRAFT = manual edit) is expected pre-release
				// churn -- the artifact is dereferenced on rebuild before the replacement lands -- and is
				// deliberately NOT held (holding sub-ASSEMBLED would form the unbounded fenced-draft
				// population, all-artifacts-gone-hold-design.md 5.1). ERROR-logging it just pages on benign
				// churn: production showed 100% of collapses were lifecycle=PENDING (gauge collapses ==
				// subAssembledCollapses). The gauge still counts these via subAssembledCollapses (recorded
				// above, unconditionally) for aggregate visibility; a real ASSEMBLED+ loss still emits here.
				if (collapsedToZero && isScannableLifecycle(rd.getLifecycle())) {
					log.error("[METRICS-LOSS] {} v{} lifecycle={} rev={} disagreeAtRev>={} findings={}->{} "
							+ "| anyBomUnscanned={} childrenIncomplete={} scanIncomplete={} "
							+ "| gathered={} resolved={} artifactFindings={} rollupFindings={} hasAnyBom={}",
							r.getUuid(), rd.getVersion(), rd.getLifecycle(), r.getMetricsRevision(), r.getMetricsRevision() + 1,
							hadFindings, nowFindings,
							anyBomUnscanned[0], childrenIncomplete, scanIncomplete,
							allReleaseArts.size(), artsResolved[0], artsFindings[0],
							rollupFindings, hasAnyBomArtifact[0]);
					logLossProvenance(r, rd, originalMetrics, allReleaseArts);
				}
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

	// ---- hourly finding-loss gauge -------------------------------------------------------------
	// [METRICS-LOSS] fires only on a TOTAL collapse, deliberately: the "any shrink" version of that
	// predicate would have fired 4544 times against production history without once catching the shape
	// it exists for. That leaves PARTIAL losses completely invisible, so nobody knows how big that
	// population is. Since ERROR is the only log level this instance retains, per-release partial
	// reporting is not an option -- it would flood the sole alerting channel. An instance-wide gauge,
	// emitted at most hourly, sizes the population without naming releases. Same idiom as
	// [METRICS-BACKLOG] in ReleaseService.
	private final AtomicLong lossGaugeReleases = new AtomicLong();
	private final AtomicLong lossGaugeFindings = new AtomicLong();
	private final AtomicLong lossGaugeTotalCollapses = new AtomicLong();
	// Collapses on a sub-ASSEMBLED release (PENDING = CI-created, DRAFT = manual edit). These emit finding
	// changes but are deliberately NOT held -- holding them would form the unbounded fenced-draft population
	// (see all-artifacts-gone-hold-design.md 5.1). Counted as a subset of collapses so the gauge shows how
	// much of the loss volume is expected pre-release churn versus an ASSEMBLED+ collapse worth paging on.
	private final AtomicLong lossGaugeSubAssembledCollapses = new AtomicLong();
	private final AtomicLong lossGaugeScanIncomplete = new AtomicLong();
	private final AtomicReference<Instant> lastLossGaugeReport = new AtomicReference<>();
	private static final Duration LOSS_GAUGE_INTERVAL = Duration.ofHours(1);

	// Per-window budget for the PER-RELEASE loss pair ([METRICS-LOSS] + [METRICS-LOSS-PROVENANCE]).
	// Without it the pair is bounded only by the compute batch -- METRICS_COMPUTE_BATCH_LIMIT per
	// per-minute tick, i.e. thousands of releases an hour -- so a mass event (a Dependency-Track outage,
	// a sweep returning empty) would flood the ONLY alerting channel during exactly the incident the
	// probe exists to diagnose. Past the budget the releases are still counted and reported in aggregate
	// by the gauge, which also prints how many pairs were dropped.
	private static final int LOSS_PAIR_LINES_PER_WINDOW = 20;
	private final AtomicInteger lossPairBudget = new AtomicInteger(LOSS_PAIR_LINES_PER_WINDOW);
	private final AtomicLong lossPairSuppressed = new AtomicLong();

	// ---- hourly stall gauge --------------------------------------------------------------------
	// Same shape and the same reasoning as the loss gauge: bounded output regardless of how large the
	// stalled population turns out to be.
	private static final int STALL_ATTEMPTS_THRESHOLD = 24;
	private static final Duration STALL_GAUGE_INTERVAL = Duration.ofHours(1);
	private final AtomicLong stallObservations = new AtomicLong();
	private final AtomicInteger stallMaxAttempts = new AtomicInteger();
	private final Set<UUID> stallSample = ConcurrentHashMap.newKeySet();
	private final AtomicReference<Instant> lastStallReport = new AtomicReference<>();

	/**
	 * Count one stalled compute and emit the aggregate when the window elapses.
	 *
	 * <p>{@code stalledComputes} counts OBSERVATIONS, not distinct releases, and is named for what it
	 * is. With the backoff capped at an hour a stuck release is recomputed roughly once per hour, so
	 * over an hour-long window the two are close -- but a release whose fence keeps being cleared (every
	 * containing product is touched on each child metrics change) is counted more than once, so treat it
	 * as an upper bound on the population and read {@code sample} for identities.
	 */
	/**
	 * Fence a release that cannot finish its compute yet -- an unscanned BOM / incomplete child, or a
	 * held empty gather (ALL_ARTIFACTS_GONE) -- out of the metrics finders for an escalating interval,
	 * and surface the aggregate once it has been stuck past the threshold.
	 *
	 * <p>The fence itself is by-design silent (waiting on an external event is normal), but a release
	 * incomplete for ~a day of hourly retries is stuck on something that will not arrive by itself -- a
	 * BOM artifact that never reached scanning (a 2026-07-12 production incident sat like this for 11
	 * days / 266 attempts). This replaces a per-release WARN: it was invisible (the instance this matters
	 * on retains ERROR only), and the per-release form could not be raised to ERROR safely because its
	 * volume is the size of the stalled population -- the very thing nobody knows and this line exists to
	 * measure. An aggregate answers "how many are stuck?" at one line per window whatever the answer is.
	 *
	 * <p>Shared by the scan-incomplete path and the empty-gather hold so the two cannot drift; the
	 * attempt count is read AFTER recording the increment (the DB update does not mutate the in-memory
	 * entity), matching the previous inline behaviour.
	 */
	private void fenceIncompleteCompute(Release r) {
		repository.recordMetricsComputeIncomplete(r.getUuid(),
				nextMetricsComputeBackoffSeconds(r.getFlowControl()));
		FlowControl fc = r.getFlowControl();
		int attempts = (fc != null && fc.metricsComputeFailureCount() != null)
				? fc.metricsComputeFailureCount() : 0;
		if (attempts >= STALL_ATTEMPTS_THRESHOLD) {
			recordStalledCompute(r.getUuid(), attempts);
		}
	}

	private void recordStalledCompute(UUID releaseUuid, int attempts) {
		stallObservations.incrementAndGet();
		stallMaxAttempts.accumulateAndGet(attempts, Math::max);
		if (stallSample.size() < SAMPLE_LIMIT) {
			stallSample.add(releaseUuid);
		}
		Instant last = lastStallReport.get();
		Instant now = Instant.now();
		if (null == last) {
			// First observation since boot opens the window rather than emitting a one-sample summary.
			lastStallReport.compareAndSet(null, now);
			return;
		}
		if (last.isBefore(now.minus(STALL_GAUGE_INTERVAL)) && lastStallReport.compareAndSet(last, now)) {
			List<UUID> sample = List.copyOf(stallSample);
			stallSample.clear();
			log.error("[METRICS-STALLED] windowSec={} stalledComputes={} maxAttempts={} sample={}",
					Duration.between(last, now).toSeconds(), stallObservations.getAndSet(0),
					stallMaxAttempts.getAndSet(0), sample);
		}
	}

	/**
	 * Record one finding loss for the hourly gauge, then emit if the window has elapsed.
	 *
	 * <p>{@code releasesLosingFindings} is an UPPER BOUND and is labelled as one in the output: it counts
	 * any drop in raw list size, which the alias organizer and both dedup passes can produce on a release
	 * that lost nothing. {@code totalCollapses} is the reliable subset (the population [METRICS-LOSS]
	 * already reports per release), and {@code withScanIncomplete} is the discriminator worth watching --
	 * it is the shape the whole incomplete-scan investigation is about.
	 */
	private void recordFindingLoss(int had, int now, boolean totalCollapse, boolean scanIncomplete,
			boolean subAssembled) {
		lossGaugeReleases.incrementAndGet();
		lossGaugeFindings.addAndGet(Math.max(0, had - now));
		if (totalCollapse) {
			lossGaugeTotalCollapses.incrementAndGet();
			if (subAssembled) {
				lossGaugeSubAssembledCollapses.incrementAndGet();
			}
		}
		if (scanIncomplete) {
			lossGaugeScanIncomplete.incrementAndGet();
		}
		Instant last = lastLossGaugeReport.get();
		Instant now_ = Instant.now();
		if (null == last) {
			// First loss since boot: start the window rather than emitting a one-sample summary.
			lastLossGaugeReport.compareAndSet(null, now_);
			return;
		}
		if (last.isBefore(now_.minus(LOSS_GAUGE_INTERVAL)) && lastLossGaugeReport.compareAndSet(last, now_)) {
			long collapses = lossGaugeTotalCollapses.getAndSet(0);
			long scanIncompleteCount = lossGaugeScanIncomplete.getAndSet(0);
			long anyDrop = lossGaugeReleases.getAndSet(0);
			long findingsLost = lossGaugeFindings.getAndSet(0);
			long suppressed = lossPairSuppressed.getAndSet(0);
			long subAssembledCollapses = lossGaugeSubAssembledCollapses.getAndSet(0);
			lossPairBudget.set(LOSS_PAIR_LINES_PER_WINDOW);
			// Nothing worth paging for: releasesAnyDrop alone counts benign alias/dedup shrinkage, so
			// emitting on it would train the sole alerting channel on a healthy instance.
			if (0 == collapses && 0 == scanIncompleteCount) {
				return;
			}
			// windowSec is MEASURED, not the constant: the gauge emits on the next loss, so a quiet
			// period makes the real window longer than LOSS_GAUGE_INTERVAL and a fixed denominator
			// would under-report the rate. releasesAnyDrop names its own upper-bound semantics.
			log.error("[METRICS-LOSS-GAUGE] windowSec={} releasesAnyDrop={} collapses={} subAssembledCollapses={} "
					+ "scanIncomplete={} findingsLost={} suppressedPairs={}",
					Duration.between(last, now_).toSeconds(), anyDrop, collapses, subAssembledCollapses,
					scanIncompleteCount, findingsLost, suppressed);
		}
	}

	/**
	 * Second line of the finding-loss probe: WHERE the lost findings came from, and WHAT is attached now.
	 *
	 * <p>The customer instance has no SQL and no kubectl access, so log output is the only channel and the
	 * existing [METRICS-LOSS] line has taken the investigation as far as counts can. It establishes that a
	 * release with ONE gathered, resolved, never-scanned BOM dropped its whole finding set -- but not
	 * whether that BOM is the same artifact that used to carry the findings. Those two possibilities need
	 * opposite fixes: an artifact SWAP is a release/SCE wiring problem, while the same artifact losing its
	 * findings in place is an artifact-metrics problem.
	 *
	 * <p>The persisted findings already answer it. Every finding carries {@code sources[].artifact} -- the
	 * artifact that produced it -- so the artifacts behind the LOST findings can be compared against the
	 * set gathered now. Disjoint means swapped; overlapping means lost in place.
	 *
	 * <p>Also prints what {@code gatherReleaseArtifacts} deliberately forgets: which of its three owners
	 * (the release's own list, the source-code-entry, a variant's outbound deliverables) contributed each
	 * artifact. Production shows sibling components at IDENTICAL versions losing findings in the same
	 * second, which is the signature of a SHARED source-code-entry -- an SCE is canonical per (vcs, commit)
	 * -- but the current line cannot confirm it because ownership is erased by the union.
	 *
	 * <p>Fires only where [METRICS-LOSS] already fires (a total collapse), so it adds no new log volume,
	 * and every list is bounded by SAMPLE_LIMIT.
	 */
	private void logLossProvenance(Release r, ReleaseData rd, ReleaseMetricsDto originalMetrics,
			Set<UUID> gatheredNow) {
		try {
			// Artifacts credited with the findings that were just lost.
			Set<UUID> lostFrom = new LinkedHashSet<>();
			int[] attributed = { 0, 0 };
			collectSourceArtifacts(originalMetrics, lostFrom, attributed);
			// Artifacts that BOTH resolve and can carry findings: tells "replaced by another BOM" from
			// "the BOM went and only a VDR/signature remains", and a deleted row from a lost finding set.
			Set<UUID> resolvedFindingCapable = new LinkedHashSet<>();
			for (ArtifactData fad : artifactService.getArtifactDataListLight(gatheredNow)) {
				if (ArtifactType.BOM == fad.getType()) {
					resolvedFindingCapable.add(fad.getUuid());
				}
			}
			boolean rollupLoss = null != rd.getParentReleases() && !rd.getParentReleases().isEmpty();
			String verdict = lossVerdict(lostFrom, gatheredNow, resolvedFindingCapable, rollupLoss);
			// For a release that gathered nothing, the next question is whether the artifacts that used
			// to carry the findings still EXIST. Rows still present means the release was de-referenced
			// (its artifact list was emptied); rows gone means the artifacts were deleted underneath it.
			// Those are different bugs and the counts cannot separate them.
			String lostFromState = "";
			if ("ALL_ARTIFACTS_GONE".equals(verdict)) {
				int stillExist = 0;
				for (UUID aid : lostFrom) {
					if (artifactService.getArtifactData(aid).isPresent()) stillExist++;
				}
				lostFromState = " lostFromStillExist=" + stillExist + "/" + lostFrom.size()
						+ " (" + (stillExist == lostFrom.size() ? "DEREFERENCED" : "ROWS_DELETED") + ")";
			}

			// Ownership, re-derived: gatherReleaseArtifacts unions three owners and returns a flat set.
			Set<UUID> ownRelease = (null != rd.getArtifacts()) ? new LinkedHashSet<>(rd.getArtifacts())
					: new LinkedHashSet<>();
			Set<UUID> ownSce = new LinkedHashSet<>();
			if (null != rd.getSourceCodeEntry()) {
				getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry())
						.ifPresent(sce -> sce.getArtifacts().stream()
								.filter(a -> a.componentUuid() == null
										|| rd.getComponent().equals(a.componentUuid()))
								.forEach(a -> ownSce.add(a.artifactUuid())));
			}

			// Age of each gathered artifact against the release, which distinguishes "attached late"
			// from "there all along" -- the artifact/release timing question the counts cannot answer.
			List<String> artDetail = new ArrayList<>();
			for (UUID aid : gatheredNow) {
				if (artDetail.size() >= SAMPLE_LIMIT) break;
				var oad = artifactService.getArtifactData(aid);
				if (oad.isEmpty()) {
					artDetail.add(aid + ":UNRESOLVED");
					continue;
				}
				ArtifactData ad = oad.get();
				String owner = ownRelease.contains(aid) ? "REL" : (ownSce.contains(aid) ? "SCE" : "DELIV");
				ReleaseMetricsDto am = ad.getMetrics();
				// created vs touched vs the release's own dates: an artifact TOUCHED long after the
				// release was created is an attach/replace after the fact, which is a different story
				// from one that has been there since the build. The counts cannot separate them.
				artDetail.add(aid + ":" + owner + ":" + ad.getType()
						+ ":created=" + ad.getCreatedDate()
						+ ":touched=" + ad.getUpdatedDate()
						+ ":firstScanned=" + (null == am ? "n/a" : am.getFirstScanned())
						+ ":lastScanned=" + (null == am ? "n/a" : am.getLastScanned())
						+ ":findings=" + countFindings(am));
			}

			// Who touched the artifact wiring, from the release's own trail. WhoUpdated.createdType
			// separates a human (MANUAL) from CI (API) from the system (AUTO).
			//
			// READ THE ABSENCE CAREFULLY -- an earlier version of this comment got it backwards and the
			// sandbox calibration then confirmed the wrong reading. ReleaseService writes NO
			// ReleaseUpdateAction.REMOVED event anywhere: replaceArtifact removes the old uuid and records
			// only ADDED for the new one, and SCE- and deliverable-level artifact changes write no
			// release-scoped event at all. So "no recent event" does NOT mean a wholesale record_data
			// overwrite. It means the change did not come through a release-DIRECT path -- which leaves
			// an SCE or deliverable mutation (the shared-SCE fan-out hypothesis) or a direct write, and
			// those are NOT distinguished here. A recent ADDED naming the currently attached artifact is
			// the positive signal: that is a release-direct attach or replace, with its actor.
			List<String> artifactEvents = new ArrayList<>();
			if (null != rd.getUpdateEvents()) {
				rd.getUpdateEvents().stream()
						.filter(e -> ReleaseData.ReleaseUpdateScope.ARTIFACT == e.rus())
						.sorted((a, b) -> b.date().compareTo(a.date()))
						.limit(SAMPLE_LIMIT)
						.forEach(e -> artifactEvents.add(e.rua() + ":" + e.objectId() + ":" + e.date()
								+ ":by=" + (null == e.wu() ? "n/a" : e.wu().getCreatedType())
								+ "/" + (null == e.wu() ? "n/a" : e.wu().getLastUpdatedBy())));
			}

			// Field names are abbreviated deliberately: the line repeats per loss and every term is
			// defined on this method, not in the log. lostFrom = artifacts credited with the findings
			// that just went; now = what is attached at this instant; owners = r(elease)/s(ce)/d(eliverable).
			log.error("[METRICS-LOSS-PROVENANCE] {} v{} lifecycle={} verdict={} lostFrom={} now={} owners=r{}/s{}/d{} "
					+ "relCreated={} relTouched={} sce={} arts={} artEvents={}",
					r.getUuid(), rd.getVersion(), rd.getLifecycle(), verdict + lostFromState,
					bounded(lostFrom), bounded(gatheredNow),
					ownRelease.size(), ownSce.size(),
					Math.max(0, gatheredNow.size() - ownRelease.size() - ownSce.size()),
					rd.getCreatedDate(), r.getLastUpdatedDate(), rd.getSourceCodeEntry(), artDetail,
					artifactEvents.isEmpty() ? "NONE_RECORDED" : artifactEvents);
		} catch (Exception e) {
			// A diagnostic must never be able to fail the compute it is describing -- but it must also
			// not fail SILENTLY. This instance retains ERROR only, so at WARN a broken probe would show
			// up as a [METRICS-LOSS] line with no companion and no explanation. Fires only when the probe
			// itself is broken, so the volume is nil.
			log.error("[METRICS-LOSS-PROVENANCE] release {}: probe failed", r.getUuid(), e);
		}
	}

	/**
	 * The provenance verdict, as a pure function so a test can exercise the SHIPPED ladder rather than a
	 * copy of it. ORDER IS LOAD-BEARING: {@code disjoint(anything, EMPTY)} is trivially true, so the
	 * empty-gather case must be decided before disjointness or a release that lost its whole artifact
	 * list reports as a swap and sends the reader hunting a replacement that never existed.
	 */
	static String lossVerdict(Set<UUID> lostFrom, Set<UUID> gatheredNow, Set<UUID> resolvedFindingCapable,
			boolean rollupLoss) {
		// PRODUCT ROLLUP FIRST. A product's findings come from its children, and the rollup KEEPS each
		// child's sources[].artifact -- so lostFrom is a list of CHILD artifacts while gatheredNow is the
		// product's own set, normally empty. Without this rung every product loss reports
		// ALL_ARTIFACTS_GONE, i.e. "its artifact list was emptied", about a release that never had one,
		// and sends the reader to artifact wiring when the defect is one level down.
		if (rollupLoss) {
			return "PRODUCT_ROLLUP";
		}
		if (lostFrom.isEmpty()) {
			return "NO_SOURCE_ATTRIBUTION";
		}
		if (gatheredNow.isEmpty()) {
			// disjoint(anything, EMPTY) is trivially true, so this MUST precede the disjointness rung or
			// a release that lost its whole list reports as a swap.
			return "ALL_ARTIFACTS_GONE";
		}
		// Still listed but the ROW is gone. gatheredNow is the pre-resolution gather set, so without this
		// rung a deleted artifact whose uuid survives in the list satisfies containsAll and reports as
		// LOST_IN_PLACE -- pointing at artifact metrics for what is a row deletion.
		if (gatheredNow.containsAll(lostFrom) && Collections.disjoint(lostFrom, resolvedFindingCapable)) {
			return "LOST_ARTIFACT_ROW_MISSING";
		}
		if (gatheredNow.containsAll(lostFrom)) {
			return "SAME_ARTIFACT_LOST_IN_PLACE";
		}
		if (!Collections.disjoint(lostFrom, gatheredNow)) {
			return "PARTIAL_OVERLAP";
		}
		// Disjoint. Only call it a SWAP if something that could actually carry findings replaced it --
		// otherwise a leftover VDR snapshot or SCE signature reads as a replacement that never happened.
		return resolvedFindingCapable.isEmpty() ? "ARTIFACTS_GONE_NON_BOM_REMAIN" : "ARTIFACTS_SWAPPED";
	}

	/** Distinct artifacts credited as the SOURCE of any finding in the snapshot. */
	private void collectSourceArtifacts(ReleaseMetricsDto m, Set<UUID> out, int[] attributed) {
		if (null == m) {
			return;
		}
		if (null != m.getVulnerabilityDetails()) {
			m.getVulnerabilityDetails().forEach(v -> addSources(v.sources(), out, attributed));
		}
		if (null != m.getViolationDetails()) {
			m.getViolationDetails().forEach(v -> addSources(v.sources(), out, attributed));
		}
		if (null != m.getWeaknessDetails()) {
			m.getWeaknessDetails().forEach(w -> addSources(w.sources(), out, attributed));
		}
	}

	/**
	 * {@code attributed} is [withArtifact, total] over findings. Null-artifact sources are minted
	 * routinely (enrichSourcesWithRelease stamps a release-only source for rollup findings and for any
	 * finding arriving without sources), so a verdict can otherwise rest on a tiny attributed minority
	 * while reading as confident. The line prints the ratio so the reader can weigh it.
	 */
	private void addSources(Set<ReleaseMetricsDto.FindingSourceDto> sources, Set<UUID> out, int[] attributed) {
		attributed[1]++;
		if (null == sources) {
			return;
		}
		boolean any = false;
		for (ReleaseMetricsDto.FindingSourceDto src : sources) {
			if (null != src.artifact()) {
				out.add(src.artifact());
				any = true;
			}
		}
		if (any) {
			attributed[0]++;
		}
	}

	private static List<UUID> bounded(Set<UUID> uuids) {
		return uuids.stream().limit(SAMPLE_LIMIT).toList();
	}

	/**
	 * Does this transition wipe out every finding the release had?
	 *
	 * <p>Package-private so it can be asserted directly: this predicate is what protects the monitored
	 * ERROR channel, and it has already been wrong once -- it gated on "fewer than before", which is true of
	 * ordinary remediation, of a VEX suppression, and of the alias/dedup collapse that runs on this very
	 * path. Widening it back would flood production with nothing left to catch it.
	 *
	 * <p>Total collapse specifically, because a dedup pass cannot produce it: each collapse group yields at
	 * least one entry, so a non-empty set cannot dedup to empty.
	 */
	boolean isTotalFindingCollapse(int had, int now) {
		return now == 0 && had > 0;
	}

	/**
	 * Findings currently carried by a metrics snapshot, across all detail lists. Diagnostic use only.
	 *
	 * <p>Counts raw list SIZES, which is not the same as distinct findings: the same compute runs
	 * {@code organizeVulnerabilitiesWithAliases} / {@code deduplicateViolations} /
	 * {@code deduplicateWeaknesses}, all of which collapse entries. So a release that merely gained alias
	 * data can shrink here with nothing lost. That is why the loss probe fires only on a TOTAL collapse --
	 * a dedup pass cannot take a non-empty set to zero.
	 *
	 * <p>NOTE {@code computeReleaseMetricsOnNonRescan} is NOT probed, and the earlier claim that it "only
	 * stamps analysisState and cannot remove findings" is WRONG: it calls the same
	 * {@code processReleaseMetricsDto}, whose first three statements are the three collapses above. It can
	 * lose entries on a triage action. Left unprobed deliberately for now, but do not repeat that claim.
	 */
	int countFindings(ReleaseMetricsDto m) {
		if (null == m) {
			return 0;
		}
		int n = 0;
		if (null != m.getVulnerabilityDetails()) {
			n += m.getVulnerabilityDetails().size();
		}
		if (null != m.getViolationDetails()) {
			n += m.getViolationDetails().size();
		}
		if (null != m.getWeaknessDetails()) {
			n += m.getWeaknessDetails().size();
		}
		return n;
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
	private void stampKnownExploited(UUID orgUuid, ReleaseMetricsDto rmd) {
		applyKnownExploited(orgUuid, rmd);
		// Re-derive kevCount (and the other tallies) from the freshly stamped findings.
		rmd.computeMetricsFromFacts();
	}

	/**
	 * Stamps {@code knownExploited} onto {@code rmd}'s findings and NOTHING else.
	 *
	 * <p>Split out from {@link #stampKnownExploited} so read paths can stamp
	 * without the {@code computeMetricsFromFacts()} that follows it on the write
	 * path. That recompute is right when metrics are being (re)built, and wrong
	 * on a read: it rewrites the stored tallies from whatever detail lists happen
	 * to be loaded, and -- worse -- {@code computeMetricsFromFacts} ends with
	 * "if lastScanned is null, set it to now", which would invent a scan
	 * timestamp for an artifact that has never been scanned, changing on every
	 * request. See {@link #knownExploitedStampedCopy}.
	 */
	private void applyKnownExploited(UUID orgUuid, ReleaseMetricsDto rmd) {
		List<VulnerabilityDto> findings = rmd.getVulnerabilityDetails();
		if (findings == null || findings.isEmpty()) return;
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
	}

	/**
	 * A KEV-stamped COPY of {@code metrics}, for read paths that serve metrics
	 * which were never stamped at write time -- per-artifact metrics above all.
	 *
	 * <p>Artifact metrics are merged into the release aggregate and only the
	 * aggregate is stamped, so an artifact's own {@code vulnerabilityDetails}
	 * carry {@code knownExploited = false} even when the release says true for
	 * the same finding. Stamping here rather than persisting a second copy
	 * keeps a single source of truth and cannot go stale when CISA adds a CVE:
	 * {@code recomputeReleasesForNewlyKev} refreshes RELEASES, not artifacts, so
	 * a persisted artifact stamp would silently drift.
	 *
	 * <p>Clones first -- {@code ReleaseMetricsDto.clone()} goes through
	 * {@code Object.clone()}, so the caller's runtime type (e.g.
	 * {@code DependencyTrackIntegration}) is preserved -- because the argument
	 * belongs to the caller's entity and must not be mutated by a read.
	 *
	 * <p>Stamps ONLY. Deliberately does not run {@code computeMetricsFromFacts()}:
	 * that is a write-path operation which rewrites the stored tallies from the
	 * currently-loaded detail lists and defaults a null {@code lastScanned} to
	 * now -- on a read that would hand back an invented scan timestamp for an
	 * artifact that has never been scanned, different on every request.
	 */
	public ReleaseMetricsDto knownExploitedStampedCopy(UUID orgUuid, ReleaseMetricsDto metrics) {
		if (null == metrics) return null;
		ReleaseMetricsDto copy = metrics.clone();
		applyKnownExploited(orgUuid, copy);
		return copy;
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

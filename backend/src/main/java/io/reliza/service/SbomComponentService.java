/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceContext;

import io.reliza.common.HeapPressureGuard;
import io.reliza.common.Utils;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.DiffComponent;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.DeliverableData;
import io.reliza.model.FlowControl;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.Release;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentData;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.model.SbomComponentPage;
import io.reliza.model.SbomComponentSupport;
import io.reliza.model.SbomComponentSupportAudit;
import io.reliza.model.SupportAttestationFilter;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkOutcome;
import io.reliza.model.SupportBulkResult;
import io.reliza.model.SupportData;
import io.reliza.model.SupportExportState;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.model.SupportStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.ParsedBomComponent;
import io.reliza.model.tea.Rebom.ParsedBomDependency;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactCanonicalMapRepository.PendingCanonicalForm;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.repositories.SbomComponentSupportRepository.SupportPayloadRow;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains the SBOM component aggregation. As of V37 (artifact-keyed
 * shape), the per-component aggregation lives in
 * {@code artifact_sbom_components} keyed by canonical artifact — not per
 * (release, sbom_component). A release "owns" components by virtue of the
 * BOM artifacts it references (deliverables, source-code-entry artifacts,
 * release-attached artifacts), resolved through {@code artifact_canonical_map}
 * to canonical form. The per-release view is synthesized at read time.
 *
 * <p>The {@code release_artifact_index} table keeps a reverse mapping
 * release → canonical artifacts, rebuilt on every reconcile. It makes
 * impact analysis ("which releases reference this sbom_component") a
 * 1-join query instead of walking JSONB across the artifacts surface.
 *
 * <p>Reconciliation is queue-driven by {@code releases.flow_control}; the
 * every-minute Dependency-Track scheduler drains the queue under its
 * existing advisory lock. A release's reconcile is cheap when its
 * canonical artifacts have already been parsed by a prior reconcile —
 * existence check via {@code ArtifactSbomComponentRepository.existsByCanonicalArtifactUuid}
 * avoids re-parsing identical content across releases.
 */
@Slf4j
@Service
public class SbomComponentService {

	/**
	 * Generation cookie stamped onto {@code releases.sbom_schema_version}
	 * by every successful reconcile. Bumped to 3 on this V37 rewrite to
	 * the artifact-keyed shape. Future area migrations can either re-enqueue
	 * via flow_control (V25 / V27 / V28 / V37 pattern) or simply bump this
	 * constant — a catch-up scheduler can then find rows whose stored
	 * version is below the current value via the partial index.
	 */
	public static final int CURRENT_SBOM_SCHEMA_VERSION = 3;

	@Autowired private RebomService rebomService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ArtifactService artifactService;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private GetDeliverableService getDeliverableService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private VariantService variantService;
	// Phase 2b-2: BOM-diff now flows through the notification outbox via this
	// hook (SAAS impl writes a RELEASE_BOM_DIFF event). CE build has no impl
	// bean, so null-check before calling.
	@Autowired(required = false) private ReleaseChangeHook releaseChangeHook;
	// @Lazy: the post-reconcile pipeline calls back into AcollectionService for
	// the snapshot resolve + changelog cache; lazy keeps startup wiring cycle-safe.
	@Autowired @Lazy private AcollectionService acollectionService;
	@Autowired private ReleaseRepository releaseRepository;

	/**
	 * Self-injection so {@link #processPendingReconciles(int)} can call the
	 * {@code @Transactional} reconcile method through Spring's proxy, and so
	 * {@link #bulkSetSbomComponentSupport} can reach
	 * {@link #setSbomComponentSupportIsolated} and pick up its REQUIRES_NEW propagation.
	 *
	 * <p>A direct {@code this.*} call bypasses AOP entirely. For the bulk path that is not a
	 * nicety: without a transaction per item, one rejected component marks the whole batch
	 * rollback-only, and catching the exception does not undo that -- every component that had
	 * already succeeded would vanish at commit, which is the opposite of skip-and-report.
	 */
	@Autowired @Lazy private SbomComponentService self;

	private static final int BASE_BACKOFF_SECONDS = 30;
	private static final int MAX_BACKOFF_SECONDS = 3600;

	// ===================================================================
	// Enrichment puller tuning (see pullEnrichmentForOrg)
	// ===================================================================
	// How many BOMs we successfully pull enriched licenses from per org per tick.
	// Each pull stamps every un-enriched component in that BOM, so this covers far
	// more than 5 components.
	// Raised 5 -> 10 alongside the V75 index on the coordinate-candidate probe:
	// the pull budget is only safe to grow once the per-canonical candidate
	// lookup is indexed (it runs hundreds of times per pulled BOM during a
	// backlog drain, on the shared scheduler tick).
	// 10 -> 25 with the dedicated scheduler (the pass no longer shares a tick
	// with submit/ingest/fan-out, so its duration only delays itself), then 30
	// per operator tuning alongside MAX_BOMS_PROBED.
	private static final int ENRICHMENT_PULL_TARGET = 30;
	// Upper bound on un-enriched candidate components fetched per org per tick.
	// Generous so we can "add one more" past BOMs still PENDING and still reach
	// the pull target.
	private static final int ENRICHMENT_CANDIDATE_WINDOW = 400;
	// Upper bound on distinct BOMs probed per org per tick — caps rebom round-trips.
	// Deliberately EQUAL to the pull target (operator tuning): probe headroom only
	// pays off when not-ready statuses interleave the candidate list, and telemetry
	// shows none on the current estate ({COMPLETED} across the board). If a mixed-
	// status backlog ever shows pulls.ok < attempted-capacity with all boms probed,
	// restore the probe cap above the pull target.
	private static final int ENRICHMENT_MAX_BOMS_PROBED = 30;
	// Per-BOM pull backoff: after pulling a BOM, do not re-pull it for this long.
	// A head component whose stored coordinates drifted from its own BOM's fresh
	// parse cannot be stamped by that pull -- and without a backoff, that BOM is
	// re-selected and re-pulled EVERY tick, monopolizing the pull budget while
	// the candidate window never advances ({COMPLETED=N} persisting at the head
	// of the 2026-07-26 stall diagnostics). In-memory: a restart merely allows
	// one redundant pull per BOM.
	private static final java.time.Duration ENRICHMENT_PULL_BACKOFF = java.time.Duration.ofMinutes(30);
	private final Map<UUID, java.time.Instant> recentlyPulledBoms = new java.util.concurrent.ConcurrentHashMap<>();
	// Consecutive pull failures (parse null/empty or exception) per BOM. A BOM
	// whose content cannot be parsed is an equally terminal dead end for its
	// candidates as one that parses-but-does-not-match -- but a single failure
	// can be a transient rebom hiccup, so strikes fire only after
	// PULL_FAILURES_BEFORE_TERMINAL consecutive failures; any success resets.
	// In-memory: a restart merely re-counts from zero.
	private static final int PULL_FAILURES_BEFORE_TERMINAL = 3;

	/**
	 * Per-tick funnel telemetry for pullEnrichmentForOrg. Every exit path of the
	 * loop increments a counter -- this subsystem's failure history is
	 * specifically SILENT exits (three of them diagnosed live in one week), so
	 * the invariant is: no branch leaves the funnel uncounted. Emitted as ONE
	 * log.error line per org per tick whenever the window is non-empty (error
	 * level by explicit operator request -- their monitoring surfaces it).
	 */
	private static final class EnrichTickStats {
		int window; int alreadyEnriched; int twinStamped;
		int resolveOk; int unresolvable; int backoffSkip; int boms;
		int stCompleted; int stPending; int stFailed; int stSkipped; int stNullMeta; int probeErr;
		int pullAttempted; int pullOk; int parseNull; int parseErr;
		int byteStamped; int coordStamped;
		int strikeMatched; int strikeUnpullable;
		long msWindow; long msTwin; long msResolve; long msPullLoop;
	}
	private final Map<UUID, Integer> pullFailureCounts = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Persistence-context flush + clear granularity for bulk inserts. Keeps the
	 * Hibernate L1 cache bounded when a single BOM yields hundreds-to-thousands
	 * of {@link ArtifactSbomComponent} rows. Picked to amortize JDBC batch
	 * overhead while still letting GC reclaim the entity references between
	 * chunks.
	 */
	private static final int FLUSH_CHUNK = 500;

	@PersistenceContext
	private EntityManager entityManager;

	private final SbomComponentRepository sbomComponentRepository;
	private final ArtifactSbomComponentRepository artifactSbomComponentRepository;
	private final ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	private final ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	private final SbomComponentSupportRepository sbomComponentSupportRepository;
	private final SbomComponentSupportAuditRepository sbomComponentSupportAuditRepository;

	SbomComponentService(
			SbomComponentRepository sbomComponentRepository,
			ArtifactSbomComponentRepository artifactSbomComponentRepository,
			ReleaseArtifactIndexRepository releaseArtifactIndexRepository,
			ArtifactCanonicalMapRepository artifactCanonicalMapRepository,
			SbomComponentSupportRepository sbomComponentSupportRepository,
			SbomComponentSupportAuditRepository sbomComponentSupportAuditRepository) {
		this.sbomComponentRepository = sbomComponentRepository;
		this.artifactSbomComponentRepository = artifactSbomComponentRepository;
		this.releaseArtifactIndexRepository = releaseArtifactIndexRepository;
		this.artifactCanonicalMapRepository = artifactCanonicalMapRepository;
		this.sbomComponentSupportRepository = sbomComponentSupportRepository;
		this.sbomComponentSupportAuditRepository = sbomComponentSupportAuditRepository;
	}

	// ===================================================================
	// Queue API (unchanged from V25/V27/V28)
	// ===================================================================

	public void requestReconcile(UUID releaseUuid) {
		if (releaseUuid == null) return;
		releaseRepository.markSbomReconcileRequested(releaseUuid);
	}

	public void processPendingReconciles(int batchLimit) {
		// Load UUIDs only — the full Release has five JSONB columns
		// (recordData, metrics, approvalEvents, updateEvents, flowControl)
		// and Hibernate's dirty-checking snapshot deep-copies each via
		// serialize→bytes→deserialize. Batching full rows up front
		// allocated enough that the scheduler thread could OOM before
		// the per-iteration heap guard had a chance to fire. The
		// reconcile itself only needs the UUID; the FlowControl
		// failure-count read is on the rare exception path and pays
		// for one lazy findById there.
		List<UUID> pendingUuids = releaseRepository.findUuidsOfReleasesPendingSbomReconcile(batchLimit);
		if (pendingUuids.isEmpty()) return;
		log.debug("Draining {} pending SBOM reconciles", pendingUuids.size());
		int processed = 0;
		int total = pendingUuids.size();
		for (UUID releaseUuid : pendingUuids) {
			// Pre-flight free-heap guard. ParsedBom + aggregation Maps can
			// spike allocation by tens of MB per reconcile; if we're
			// already running hot, punting the next release back to the
			// queue lets the next scheduler tick try again after the GC
			// hint reclaims the previous reconcile's transient state.
			// Shared with DT batch loops via HeapPressureGuard.
			if (HeapPressureGuard.checkAndMaybeGc(log, "SBOM reconcile drain",
					String.format("before release %s (%d/%d done); remaining will be retried on the next scheduler tick.",
							releaseUuid, processed, total))) {
				// Post-GC free heap is still under the abort threshold. Before
				// abandoning the remaining batch until the next tick, give the
				// concurrent GC cycle a moment to finish and re-check once --
				// a transient spike should not cost a full batch of progress.
				try {
					Thread.sleep(2000L);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				if (HeapPressureGuard.checkAndMaybeGc(log, "SBOM reconcile drain (retry)",
						String.format("still hot after GC retry before release %s (%d/%d done); abandoning batch until next tick.",
								releaseUuid, processed, total))) {
					return;
				}
			}
			try {
				int skippedArts = self.reconcileReleaseSbomComponents(releaseUuid);
				if (skippedArts > 0) {
					// Incomplete pass: keep the queue marker and back off on
					// the same schedule as a thrown failure. Clearing the
					// marker here would permanently exclude the skipped
					// artifact(s) from mapping/bucketing/scanning.
					int nextSkipAttempt = releaseRepository.findById(releaseUuid)
							.map(SbomComponentService::currentReconcileFailureCount)
							.orElse(0) + 1;
					int skipBackoff = Math.min(BASE_BACKOFF_SECONDS << Math.min(nextSkipAttempt - 1, 7),
							MAX_BACKOFF_SECONDS);
					releaseRepository.recordSbomReconcileFailure(releaseUuid, skipBackoff);
					log.warn("SBOM reconcile of release {} skipped {} BOM artifact(s) (attempt {}); retrying in {}s",
							releaseUuid, skippedArts, nextSkipAttempt, skipBackoff);
					processed++;
					continue;
				}
				releaseRepository.clearSbomReconcileRequested(releaseUuid);
				// The release's full inventory is now rebuilt — the natural
				// "all BOMs reconciled" moment. Run the post-reconcile pipeline:
				// refresh the acollection snapshot, recompute the changelog cache,
				// and fire the once-per-release notification. Best-effort; won't
				// disturb the drain.
				postReconcileBomDiff(releaseUuid);
				processed++;
			} catch (Exception e) {
				int nextAttempt = releaseRepository.findById(releaseUuid)
						.map(SbomComponentService::currentReconcileFailureCount)
						.orElse(0) + 1;
				int backoff = Math.min(BASE_BACKOFF_SECONDS << Math.min(nextAttempt - 1, 7),
						MAX_BACKOFF_SECONDS);
				releaseRepository.recordSbomReconcileFailure(releaseUuid, backoff);
				log.error("SBOM reconcile failed for release {} (attempt {}, retry in {}s): {}",
						releaseUuid, nextAttempt, backoff, e.getMessage(), e);
			}
		}
	}

private static int currentReconcileFailureCount(Release r) {
		FlowControl fc = r.getFlowControl();
		if (fc == null || fc.sbomReconcileFailureCount() == null) return 0;
		return fc.sbomReconcileFailureCount();
	}

	// ===================================================================
	// Reconcile — artifact-keyed write path
	// ===================================================================

	/**
	 * Rebuild the SBOM aggregation for a release.
	 *
	 * <p>For each BOM artifact the release references, resolve to canonical
	 * via {@code artifact_canonical_map}. If the canonical hasn't been
	 * parsed yet (no rows in {@code artifact_sbom_components}), parse the
	 * BOM via rebom and write per-component rows. Then rebuild this release's
	 * {@code release_artifact_index} entries to point at the resolved
	 * canonical set, and stamp {@code releases.sbom_schema_version}.
	 *
	 * <p>The artifact rows are content-addressed and immutable. A reconcile
	 * on another release that shares the same BOM content reuses them
	 * without re-parsing.
	 *
	 * <p>This outer method is intentionally NOT {@code @Transactional}. Each
	 * artifact's parse + persist happens in its own short-lived transaction
	 * (via {@link #parseAndUpsertArtifactSbomComponents}), and the index
	 * rebuild + version-cookie stamp happen in a second short-lived
	 * transaction (via {@link #rebuildReleaseArtifactIndex}). The split
	 * keeps the Hibernate L1 cache bounded per artifact instead of letting
	 * it accumulate every entity loaded across all BOMs the release carries
	 * — important when one release contains many or unusually large BOMs.
	 * Idempotency across partial failure is preserved by the
	 * {@code existsByCanonicalArtifactUuid} short-circuit and by the
	 * single-transaction rebuild step at the tail.
	 *
	 * @return the number of BOM artifacts that were SKIPPED this pass (a
	 *   referenced artifact row that couldn't be read, or a BOM-type
	 *   artifact with no internalBom yet). A non-zero return means the
	 *   release's component inventory is incomplete: the caller must NOT
	 *   clear the reconcile-queue marker, so the pass is retried. Without
	 *   this, a transient read anomaly during one pass silently and
	 *   permanently excluded the artifact from canonical mapping,
	 *   bucketing, and scanning (observed in prod on 2026-07-12: one SCE
	 *   fs-BOM never scanned, its release stuck in metrics-compute
	 *   retries for 11 days, and every release sharing the commit showed
	 *   a forever-pending DTrack badge).
	 */
	public int reconcileReleaseSbomComponents(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) {
			log.warn("reconcileReleaseSbomComponents called for missing release {}", releaseUuid);
			return 0;
		}
		ReleaseData rd = ord.get();
		UUID orgUuid = rd.getOrg();
		if (orgUuid == null) {
			throw new IllegalStateException(
					"reconcileReleaseSbomComponents: release " + releaseUuid + " has no org");
		}

		Set<UUID> canonicalArtifactSet = new LinkedHashSet<>();
		int skipped = 0;

		for (UUID artifactUuid : collectBomArtifactUuids(rd)) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) {
				// The release/SCE/deliverable references an artifact row we
				// can't read — anomalous (references are written after the
				// row). Count as a skip so the pass is retried rather than
				// silently treating the inventory as complete.
				log.warn("SBOM reconcile of release {}: referenced artifact {} not readable — pass will be retried",
						releaseUuid, artifactUuid);
				skipped++;
				continue;
			}
			ArtifactData ad = oad.get();
			if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) {
				// Non-BOM artifact types (signatures, certificates, scan
				// results) legitimately have no internalBom — this branch is
				// their intended filter and stays silent. So do BOM artifacts
				// without a REARM-stored file: a metadata-only BOM
				// (downloadLinks, no upload) or an externally-stored one never
				// gets a rebom side, so "no internalBom" is its permanent,
				// correct state — counting it as a skip put the release in an
				// endless retry loop and deferred its bomDiff forever
				// (caught by the e2e harness's metadata-only-BOM scenario on
				// first deploy). Only a REARM-stored BOM whose rebom id is
				// missing indicates an incomplete upload worth retrying.
				if (ad.getType() == ArtifactData.ArtifactType.BOM
						&& ad.getStoredIn() == ArtifactData.StoredIn.REARM) {
					log.warn("SBOM reconcile of release {}: REARM-stored BOM artifact {} has no internalBom — pass will be retried",
							releaseUuid, artifactUuid);
					skipped++;
				}
				continue;
			}

			UUID canonicalArtifactUuid = resolveCanonicalArtifact(ad, orgUuid);
			canonicalArtifactSet.add(canonicalArtifactUuid);

			// Skip the parse if this canonical's component graph is already on disk.
			// The artifact_sbom_components rows are content-addressed and immutable;
			// BEAR enrichment never changes the component set, only licenses — which
			// are pulled into sbom_components out-of-band by the enrichment puller,
			// so a re-parse here is unnecessary.
			if (artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonicalArtifactUuid)) {
				continue;
			}

			// Each artifact's parse runs in its own @Transactional via the Spring
			// proxy. The local ParsedBom + aggregation Maps inside the call are
			// fully GC-eligible the moment that transaction commits — they don't
			// have to wait for the whole release reconcile to finish.
			if (!self.parseAndUpsertArtifactSbomComponents(ad, canonicalArtifactUuid, orgUuid)) {
				// rebom couldn't serve the parsed BOM this pass (already
				// warn-logged inside) — count as a skip so the pass retries.
				skipped++;
			}
		}

		// Index rebuild always runs (wholesale replacement is idempotent), but
		// the schema-version cookie is only stamped on a complete pass — a
		// stamped release is never revisited by cookie-driven catch-up, so
		// stamping over skips would make the gap permanent.
		self.rebuildReleaseArtifactIndex(releaseUuid, orgUuid, canonicalArtifactSet, skipped == 0);
		return skipped;
	}

	/**
	 * Wipe and rewrite {@code release_artifact_index} for one release, then
	 * stamp {@code releases.sbom_schema_version}. Runs as its own
	 * transaction so the entity manager's L1 cache is fresh — separate
	 * from any per-artifact parse transactions that ran earlier in the
	 * release's reconcile.
	 *
	 * <p>The release's BOM artifact set may have shifted since the last
	 * reconcile (a deliverable detached, a new SCE artifact added, etc.) —
	 * wholesale replacement keeps the index in sync with the current
	 * artifact set.
	 */
	@Transactional
	public void rebuildReleaseArtifactIndex(UUID releaseUuid, UUID orgUuid, Set<UUID> canonicalArtifactSet,
			boolean complete) {
		releaseArtifactIndexRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
		if (canonicalArtifactSet != null && !canonicalArtifactSet.isEmpty()) {
			List<ReleaseArtifactIndex> rows = new ArrayList<>(canonicalArtifactSet.size());
			for (UUID canonical : canonicalArtifactSet) {
				ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
				idx.setOrg(orgUuid);
				idx.setReleaseUuid(releaseUuid);
				idx.setCanonicalArtifactUuid(canonical);
				rows.add(idx);
			}
			saveAllChunked(rows, releaseArtifactIndexRepository::saveAll);
		}
		// Only stamp the cookie when every BOM artifact was ingested — see
		// reconcileReleaseSbomComponents' return contract.
		if (complete) markReleaseReconciled(releaseUuid);
	}

	// ===================================================================
	// Enrichment puller — pulls BEAR-enriched licenses into sbom_components
	// ===================================================================

	/**
	 * Pull BEAR-enriched licenses for an org's un-enriched matchable components,
	 * stamping {@code sbom_components.enriched_at} so the synthetic Dependency-Track
	 * gate can ship them. The front step of the every-minute synthetic tick.
	 *
	 * <p>Skips entirely for orgs without BEAR configured — their components ship
	 * un-gated (see {@link SyntheticSbomService#submitOrg}) and would otherwise
	 * churn here forever (enriched_at would never be set).
	 *
	 * <p>Picks the oldest un-enriched matchable components, dedupes them to the
	 * BOMs that declare them, and probes rebom for each BOM's enrichment status.
	 * COMPLETED → parse it and stamp every component it carries; PENDING / FAILED
	 * (rebom's own scheduler retries those) / SKIPPED → skip and move to the next
	 * BOM ("add one more") until {@value #ENRICHMENT_PULL_TARGET} BOMs are pulled
	 * or candidates run out. Each pull stamps the whole BOM, so a single tick
	 * covers far more than the target component count.
	 */
	public void pullEnrichmentForOrg(UUID orgUuid) {
		if (orgUuid == null) return;
		boolean bearConfigured;
		try {
			bearConfigured = rebomService.isEnrichmentConfigured(orgUuid);
		} catch (Exception e) {
			log.error("Enrichment puller: unable to determine BEAR config for org {}: {}",
					orgUuid, e.getMessage(), e);
			return;
		}
		if (!bearConfigured) return;

		EnrichTickStats t = new EnrichTickStats();
		long ph = System.currentTimeMillis();
		List<SbomComponent> candidates = sbomComponentRepository
				.findUnenrichedMatchableByOrgOrdered(orgUuid.toString(), ENRICHMENT_CANDIDATE_WINDOW);
		t.msWindow = System.currentTimeMillis() - ph;
		t.window = candidates.size();
		if (candidates.isEmpty()) return;

		// Twin-copy first: anything in this window that shares COORDINATES with an
		// already-enriched row inherits its enrichment DB-side -- license metadata
		// is qualifier-invariant (see Utils.purlsSameCoordinates), so no rebom
		// pull is needed for those. On CI-heavy estates this collapses the bulk
		// of a historical backlog without parsing a single BOM, and shrinks the
		// residue the pull path must handle.
		ph = System.currentTimeMillis();
		t.twinStamped = twinCopyEnrichment(orgUuid, candidates);
		t.msTwin = System.currentTimeMillis() - ph;

		// Dedupe the STILL-un-enriched candidates to distinct BOMs, oldest-first,
		// bounded. BOMs in the pull backoff are skipped HERE, before they consume
		// one of the probe slots: residue components head the oldest-first window
		// and all resolve to recently-pulled BOMs, so filtering only at pull time
		// let dead BOMs fill the entire probe set and starve pullable ones deeper
		// in the window (drain decays, then cliffs to zero pulls per tick).
		ph = System.currentTimeMillis();
		Set<UUID> bomIds = new LinkedHashSet<>();
		Map<UUID, List<SbomComponent>> candidatesByBom = new HashMap<>();
		for (SbomComponent sc : candidates) {
			if (bomIds.size() >= ENRICHMENT_MAX_BOMS_PROBED) break;
			if (sc.getEnrichedAt() != null) { t.alreadyEnriched++; continue; } // twin-copy above
			UUID bomId = resolveBomForComponent(orgUuid, sc.getUuid());
			if (bomId == null) { t.unresolvable++; continue; }
			if (isInPullBackoff(bomId)) { t.backoffSkip++; continue; }
			t.resolveOk++;
			bomIds.add(bomId);
			candidatesByBom.computeIfAbsent(bomId, k -> new ArrayList<>()).add(sc);
		}
		t.boms = bomIds.size();
		t.msResolve = System.currentTimeMillis() - ph;

		ph = System.currentTimeMillis();
		int pulls = 0;
		for (UUID bomId : bomIds) {
			if (pulls >= ENRICHMENT_PULL_TARGET) break;
			if (isInPullBackoff(bomId)) { t.backoffSkip++; continue; } // belt-and-braces
			try {
				RebomService.BomMeta meta = rebomService.getBomMetadataById(bomId, orgUuid);
				RebomService.EnrichmentStatus st = meta != null ? meta.enrichmentStatus() : null;
				if (st == null) { t.stNullMeta++; continue; }
				switch (st) {
					case COMPLETED -> t.stCompleted++;
					case PENDING -> t.stPending++;
					case FAILED -> t.stFailed++;
					case SKIPPED -> t.stSkipped++;
				}
				if (st != RebomService.EnrichmentStatus.COMPLETED) {
					// PENDING / FAILED / SKIPPED — not ready; try the next BOM.
					continue;
				}
				t.pullAttempted++;
				ParsedBom parsed = rebomService.parseBom(bomId, orgUuid);
				if (parsed == null || parsed.components() == null) {
					// Previously a SILENT continue: no stamp, no strike, no
					// backoff, no pulls++, no log -- so an unparseable head BOM
					// was re-attempted every tick forever while its candidates
					// squatted in the window ({COMPLETED=N} with terminal=0 and
					// an unchanged drain rate, observed live 2026-07-26 right
					// after the terminal-state deploy).
					t.parseNull++;
					t.strikeUnpullable += handlePullFailure(orgUuid, bomId, candidatesByBom,
							"parse returned no components");
					continue;
				}
				Map<String, List<Map<String, Object>>> licByCanonical = new LinkedHashMap<>();
				for (ParsedBomComponent pc : parsed.components()) {
					if (pc == null || pc.canonicalPurl() == null) continue;
					licByCanonical.putIfAbsent(pc.canonicalPurl(), pc.licenses());
				}
				int[] stamped = self.stampEnrichedLicenses(orgUuid, licByCanonical);
				t.byteStamped += stamped[0];
				t.coordStamped += stamped[1];
				recentlyPulledBoms.put(bomId, java.time.Instant.now());
				pullFailureCounts.remove(bomId);
				pulls++;
				t.pullOk++;
				// One-strike terminal: this candidate's OWN representative BOM has
				// now been fully pulled and stamped (byte + coordinate passes), and
				// twin-copy already ran this tick. A candidate that is STILL
				// un-enriched here is provably at a dead end -- no mechanism can
				// ever enrich it -- and without a terminal state it squats at the
				// head of the oldest-first window forever, decaying the drain
				// (observed as persistent {COMPLETED=N} residue). Terminal rows
				// keep enriched_at NULL and leave the matchable universe entirely
				// (candidate window, buckets, fan-out gate, stall counts) -- see
				// V75 / SbomComponentFlowControl.
				int terminal = 0;
				for (SbomComponent cand : candidatesByBom.getOrDefault(bomId, List.of())) {
					SbomComponent fresh = sbomComponentRepository.findById(cand.getUuid()).orElse(null);
					if (fresh == null || fresh.getEnrichedAt() != null || fresh.isEnrichmentTerminal()) continue;
					terminal += sbomComponentRepository.markEnrichmentTerminal(
							fresh.getUuid(), "OWN_BOM_PULLED_UNMATCHED");
				}
				t.strikeMatched += terminal;
				if (terminal > 0) {
					log.warn("Enrichment terminal: {} component(s) of bom {} (org {}) marked "
							+ "unenrichable after their own BOM pulled without matching them",
							terminal, bomId, orgUuid);
				}
			} catch (Exception e) {
				t.parseErr++;
				log.error("Enrichment pull failed for bom {} (org {}): {}",
						bomId, orgUuid, e.getMessage(), e);
				t.strikeUnpullable += handlePullFailure(orgUuid, bomId, candidatesByBom, e.getMessage());
			}
		}
		t.msPullLoop = System.currentTimeMillis() - ph;
		// debug level (operator request, after the line served its diagnostic
		// purpose at error level): enable via logger config when investigating.
		log.debug("[ENRICH-TICK] org={} window={} alreadyEnriched={} twinStamped={} "
				+ "resolve={{ok:{}, unresolvable:{}, backoffSkip:{}}} boms={} "
				+ "probeStatus={{COMPLETED:{}, PENDING:{}, FAILED:{}, SKIPPED:{}, nullMeta:{}, probeErr:{}}} "
				+ "pulls={{attempted:{}, ok:{}, parseNull:{}, parseErr:{}}} "
				+ "stamped={{byte:{}, coordinate:{}}} strikes={{matched:{}, unpullable:{}, failCounters:{}}} "
				+ "ms={{window:{}, twin:{}, resolve:{}, pullLoop:{}}}",
				orgUuid, t.window, t.alreadyEnriched, t.twinStamped,
				t.resolveOk, t.unresolvable, t.backoffSkip, t.boms,
				t.stCompleted, t.stPending, t.stFailed, t.stSkipped, t.stNullMeta, t.probeErr,
				t.pullAttempted, t.pullOk, t.parseNull, t.parseErr,
				t.byteStamped, t.coordStamped, t.strikeMatched, t.strikeUnpullable, pullFailureCounts.size(),
				t.msWindow, t.msTwin, t.msResolve, t.msPullLoop);
	}

	/**
	 * Shared dead-end handling for a BOM whose pull could not complete (parse
	 * null/empty or exception): back it off so it stops burning attempts every
	 * tick, and after {@link #PULL_FAILURES_BEFORE_TERMINAL} consecutive
	 * failures mark its window candidates enrichment-terminal
	 * (OWN_BOM_UNPULLABLE) -- they are exactly as dead-ended as
	 * pulled-but-unmatched candidates, which the one-strike path already
	 * covers. A successful pull resets the counter (see the success branch).
	 */
	private int handlePullFailure(UUID orgUuid, UUID bomId,
			Map<UUID, List<SbomComponent>> candidatesByBom, String cause) {
		recentlyPulledBoms.put(bomId, java.time.Instant.now());
		int failures = pullFailureCounts.merge(bomId, 1, Integer::sum);
		if (failures < PULL_FAILURES_BEFORE_TERMINAL) {
			log.warn("Enrichment pull for bom {} (org {}) failed ({} of {} before terminal): {}",
					bomId, orgUuid, failures, PULL_FAILURES_BEFORE_TERMINAL, cause);
			return 0;
		}
		int terminal = 0;
		for (SbomComponent cand : candidatesByBom.getOrDefault(bomId, List.of())) {
			SbomComponent fresh = sbomComponentRepository.findById(cand.getUuid()).orElse(null);
			if (fresh == null || fresh.getEnrichedAt() != null || fresh.isEnrichmentTerminal()) continue;
			terminal += sbomComponentRepository.markEnrichmentTerminal(
					fresh.getUuid(), "OWN_BOM_UNPULLABLE");
		}
		pullFailureCounts.remove(bomId);
		log.warn("Enrichment terminal: {} component(s) of UNPULLABLE bom {} (org {}) marked "
				+ "unenrichable after {} consecutive failed pulls ({})",
				terminal, bomId, orgUuid, PULL_FAILURES_BEFORE_TERMINAL, cause);
		return terminal;
	}

	/**
	 * Stall-report diagnostics ONLY (no behavioral change): probe the BOMs behind
	 * the oldest {@code sampleSize} un-enriched components and summarize their
	 * rebom enrichment statuses, plus estimate how many distinct BOMs back the
	 * whole backlog. Interpretation guide, since this exists to pick the fix:
	 *
	 * <ul>
	 *   <li>{@code PENDING} dominant -- rebom never enriched these (pre-BEAR
	 *       uploads) and nothing requests enrichment on the synthetic path: an
	 *       active backfill is the fix, sized by the distinct-BOM estimate.</li>
	 *   <li>{@code SKIPPED} / {@code FAILED} dominant -- terminal statuses being
	 *       treated as retry-later: terminal-status handling is the fix.</li>
	 *   <li>{@code COMPLETED} appearing at all -- the BOM was pulled but these
	 *       components did not stamp: canonical-purl encoding-era mismatch
	 *       between the fresh parse and the stored rows is the prime suspect.</li>
	 * </ul>
	 *
	 * Called only at the rate-limited (~2h) stall-report cadence; the distinct-BOM
	 * estimate is capped at {@code BOM_ESTIMATE_COMPONENT_CAP} components to bound
	 * cost on very large backlogs (reported as "&gt;=" when capped).
	 */
	public String summarizeEnrichmentStall(UUID orgUuid, int sampleSize) {
		try {
			List<SbomComponent> candidates = sbomComponentRepository
					.findUnenrichedMatchableByOrgOrdered(orgUuid.toString(), sampleSize);
			Map<String, Integer> byStatus = new LinkedHashMap<>();
			int unresolvable = 0;
			Set<UUID> seen = new HashSet<>();
			for (SbomComponent sc : candidates) {
				UUID bomId = resolveBomForComponent(orgUuid, sc.getUuid());
				if (bomId == null) { unresolvable++; continue; }
				if (!seen.add(bomId)) continue;
				try {
					RebomService.BomMeta meta = rebomService.getBomMetadataById(bomId, orgUuid);
					String st = meta != null && meta.enrichmentStatus() != null
							? meta.enrichmentStatus().name() : "UNKNOWN";
					byStatus.merge(st, 1, Integer::sum);
				} catch (Exception e) {
					byStatus.merge("PROBE_ERROR", 1, Integer::sum);
				}
			}
			String completedNote = byStatus.containsKey("COMPLETED")
					? " (COMPLETED = pullable; healthy while the head advances between reports -- "
					+ "only suspect a wedge if the oldest-created stamp is frozen across reports)"
					: "";
			// Distinct-BOM estimate: how many representative canonical artifacts
			// back the un-enriched backlog -- the unit any backfill would work in.
			long[] est = estimateDistinctBomsBehindBacklog(orgUuid);
			String bomEstimate = est[1] == 1
					? (">=" + est[0] + " (capped sample)")
					: String.valueOf(est[0]);
			long unresolvableTotal = sbomComponentRepository
					.countUnresolvableUnenriched(orgUuid.toString());
			long terminalTotal = sbomComponentRepository.countEnrichmentTerminal(orgUuid.toString());
			return String.format("oldest-%d sample -> distinct-BOM statuses %s%s%s; "
					+ "distinct BOMs behind whole backlog: %s; "
					+ "unresolvable (orphaned, GC-pending) across backlog: %d; terminal (unenrichable, excluded): %d",
					sampleSize, byStatus, completedNote,
					unresolvable > 0 ? (", " + unresolvable + " of sample with no resolvable BOM") : "",
					bomEstimate, unresolvableTotal, terminalTotal);
		} catch (Exception e) {
			return "diagnostics unavailable: " + e.getMessage();
		}
	}

	/** Cap for the distinct-BOM estimate scan, bounding report cost on huge backlogs. */
	private static final int BOM_ESTIMATE_COMPONENT_CAP = 20000;

	/** @return [distinctCount, capped(0/1)] */
	private long[] estimateDistinctBomsBehindBacklog(UUID orgUuid) {
		List<SbomComponent> backlog = sbomComponentRepository
				.findUnenrichedMatchableByOrgOrdered(orgUuid.toString(), BOM_ESTIMATE_COMPONENT_CAP);
		Set<UUID> canonicalArtifacts = new HashSet<>();
		for (SbomComponent sc : backlog) {
			var asc = artifactSbomComponentRepository
					.findFirstByOrgAndSbomComponentUuid(orgUuid, sc.getUuid());
			asc.ifPresent(a -> canonicalArtifacts.add(a.getCanonicalArtifactUuid()));
		}
		return new long[]{canonicalArtifacts.size(),
				backlog.size() >= BOM_ESTIMATE_COMPONENT_CAP ? 1 : 0};
	}

	/**
	 * Bounded GC of orphaned canonical components -- see
	 * {@code SbomComponentRepository.deleteOrphanedUnbucketedComponents} for the
	 * full safety argument (unreferenced = attributes to nothing; unbucketed =
	 * unknown to buckets/ref_maps/content hashes).
	 *
	 * @return rows deleted this pass.
	 */
	public int gcOrphanedComponents(int limit) {
		return sbomComponentRepository.deleteOrphanedUnbucketedComponents(limit);
	}

	private boolean isInPullBackoff(UUID bomId) {
		java.time.Instant pulledAt = recentlyPulledBoms.get(bomId);
		return pulledAt != null
				&& pulledAt.isAfter(java.time.Instant.now().minus(ENRICHMENT_PULL_BACKOFF));
	}

	/**
	 * Inherit enrichment across coordinate classes, DB-side: for each un-enriched
	 * candidate, if ANY already-enriched row of the org shares its COORDINATES
	 * (type/namespace/name/version -- {@link Utils#purlsSameCoordinates}, license
	 * scope only), copy licenses + stamp {@code enriched_at}. One batched lookup
	 * for the whole candidate window; matching in Java because name+version alone
	 * is NOT sufficient (same name+version under a different purl type is a
	 * different package -- the type/namespace check is what makes this safe).
	 *
	 * <p>Fill-once semantics preserved: only rows with {@code enriched_at IS NULL}
	 * are written, and a later real pull never overwrites an inherited stamp
	 * (same accepted approximation as {@code stampEnrichedLicenses}).
	 *
	 * @return components stamped by inheritance this pass.
	 */
	int twinCopyEnrichment(UUID orgUuid, List<SbomComponent> candidates) {
		Map<String, List<SbomComponent>> unenrichedByName = new HashMap<>();
		for (SbomComponent sc : candidates) {
			if (sc.getEnrichedAt() != null) continue;
			String name = coordinateName(sc);
			if (name != null) unenrichedByName.computeIfAbsent(name, k -> new ArrayList<>()).add(sc);
		}
		if (unenrichedByName.isEmpty()) return 0;
		int stamped = 0;
		try {
			for (SbomComponent twin :
					sbomComponentRepository.findCandidatesByOrgAndNames(orgUuid, unenrichedByName.keySet())) {
				if (twin.getEnrichedAt() == null) continue; // only enriched rows can donate
				String name = coordinateName(twin);
				List<SbomComponent> takers = name != null ? unenrichedByName.get(name) : null;
				if (takers == null) continue;
				for (SbomComponent taker : takers) {
					if (taker.getEnrichedAt() != null) continue;
					if (!Utils.purlsSameCoordinates(taker.getCanonicalPurl(), twin.getCanonicalPurl())) continue;
					if (twin.getLicenses() != null && !twin.getLicenses().isEmpty()) {
						taker.setLicenses(twin.getLicenses());
					}
					ZonedDateTime now = ZonedDateTime.now();
					taker.setEnrichedAt(now);
					taker.setLastUpdatedDate(now);
					try {
						sbomComponentRepository.save(taker);
						stamped++;
					} catch (OptimisticLockingFailureException | DataIntegrityViolationException ignored) {
					}
				}
			}
		} catch (Exception e) {
			log.error("Enrichment twin-copy failed for org {}: {}", orgUuid, e.getMessage(), e);
		}
		return stamped;
	}

	/** Component name for coordinate grouping: record_data first, canonical parse fallback. */
	private String coordinateName(SbomComponent sc) {
		if (sc.getRecordData() != null && sc.getRecordData().get("name") instanceof String n) return n;
		if (sc.getCanonicalPurl() == null) return null;
		try {
			return new com.github.packageurl.PackageURL(
					sc.getCanonicalPurl().replace("+", "%2B")).getName();
		} catch (com.github.packageurl.MalformedPackageURLException e) {
			return null;
		}
	}

	/**
	 * Resolve a representative BOM id for a canonical sbom_component: pick any
	 * artifact that declares it, map to its canonical artifact, and read that
	 * artifact's internal BOM id. Null when no such artifact / BOM exists.
	 */
	private UUID resolveBomForComponent(UUID orgUuid, UUID sbomComponentUuid) {
		return artifactSbomComponentRepository
				.findFirstByOrgAndSbomComponentUuid(orgUuid, sbomComponentUuid)
				.map(ArtifactSbomComponent::getCanonicalArtifactUuid)
				.flatMap(artifactService::getArtifactData)
				.map(ad -> ad.getInternalBom() != null ? ad.getInternalBom().id() : null)
				.orElse(null);
	}

	/**
	 * Stamp enriched licenses + {@code enriched_at} for the components of one
	 * COMPLETED BOM, in place (UPDATE, never delete+reinsert). Fill-once: a
	 * component already enriched (by a prior pull of another BOM sharing it) is
	 * left untouched, so we never re-stamp or overwrite.
	 */
	@Transactional
	public int[] stampEnrichedLicenses(UUID orgUuid, Map<String, List<Map<String, Object>>> licByCanonical) {
		int byteStamped = 0;
		int coordStamped = 0;
		if (licByCanonical == null || licByCanonical.isEmpty()) return new int[]{0, 0};
		List<String> canonicals = new ArrayList<>(licByCanonical.keySet());
		// Byte-match first; coordinate-variant rows are stamped by the pass below.
		Set<String> byteMatched = new HashSet<>();
		for (SbomComponent sc :
				sbomComponentRepository.findByOrgAndCanonicalPurlIn(orgUuid.toString(), canonicals)) {
			byteMatched.add(sc.getCanonicalPurl());
			// Fill-once: never re-stamp or overwrite an already-enriched component.
			// TODO: revisit for skip-patterned components — one skipped during this
			// BOM's enrichment keeps raw licenses but is still stamped here, so a
			// later BOM that does enrich it won't update it (accepted limitation).
			if (sc.getEnrichedAt() != null) continue;
			List<Map<String, Object>> lic = licByCanonical.get(sc.getCanonicalPurl());
			if (lic != null && !lic.isEmpty()) sc.setLicenses(lic);
			ZonedDateTime now = ZonedDateTime.now();
			sc.setEnrichedAt(now);
			sc.setLastUpdatedDate(now);
			try {
				sbomComponentRepository.save(sc);
				byteStamped++;
			} catch (OptimisticLockingFailureException | DataIntegrityViolationException ex) {
				// Lost a race with a concurrent writer (reconcile / another pull);
				// the other write wins. Re-evaluated next tick if still un-enriched.
			}
		}
		// Coordinate pass: stored canonicals may be qualifier-stripped (pre-#281
		// eras, V66 skipped) or encoding-era variants of the fresh parse's
		// canonical, so the byte match above can stamp NOTHING from a pulled BOM
		// -- diagnosed live 2026-07-26 as {COMPLETED=N} heading a 63k un-enriched
		// backlog: the same BOMs pulled every tick, zero rows stamped, window
		// never advancing. License metadata is qualifier-invariant, so stamping
		// may cross qualifier/encoding variants (purlsSameCoordinates -- license
		// scope ONLY; identity logic keeps the strict comparator).
		//
		// Batched per review: canonicals already byte-matched are skipped, and
		// the remaining names are resolved in ONE query per pulled BOM
		// (findCandidatesByOrgAndNames) with version+coordinate matching done
		// here -- the per-canonical unindexed probe was ~300 org-wide scans per
		// BOM on the shared tick during a drain.
		Map<String, List<String>> unmatchedByName = new HashMap<>();
		for (String canonical : canonicals) {
			if (byteMatched.contains(canonical)) continue;
			try {
				com.github.packageurl.PackageURL parsed =
						new com.github.packageurl.PackageURL(canonical.replace("+", "%2B"));
				if (parsed.getName() == null) continue;
				unmatchedByName.computeIfAbsent(parsed.getName(), k -> new ArrayList<>()).add(canonical);
			} catch (com.github.packageurl.MalformedPackageURLException ignored) {
			}
		}
		if (unmatchedByName.isEmpty()) return new int[]{byteStamped, coordStamped};
		try {
			for (SbomComponent candidate :
					sbomComponentRepository.findCandidatesByOrgAndNames(orgUuid, unmatchedByName.keySet())) {
				if (candidate.getEnrichedAt() != null) continue;
				if (byteMatched.contains(candidate.getCanonicalPurl())) continue;
				// Name from record_data, falling back to parsing the row's own
				// canonical -- legacy rows can have sparse record_data.
				String candName = candidate.getRecordData() != null
						&& candidate.getRecordData().get("name") instanceof String cn ? cn : null;
				if (candName == null && candidate.getCanonicalPurl() != null) {
					try {
						candName = new com.github.packageurl.PackageURL(
								candidate.getCanonicalPurl().replace("+", "%2B")).getName();
					} catch (com.github.packageurl.MalformedPackageURLException ignored) {
					}
				}
				List<String> sameName = candName != null ? unmatchedByName.get(candName) : null;
				if (sameName == null) continue;
				for (String canonical : sameName) {
					if (!Utils.purlsSameCoordinates(canonical, candidate.getCanonicalPurl())) continue;
					List<Map<String, Object>> lic = licByCanonical.get(canonical);
					if (lic != null && !lic.isEmpty()) candidate.setLicenses(lic);
					ZonedDateTime now = ZonedDateTime.now();
					candidate.setEnrichedAt(now);
					candidate.setLastUpdatedDate(now);
					try {
						sbomComponentRepository.save(candidate);
						coordStamped++;
					} catch (OptimisticLockingFailureException | DataIntegrityViolationException ignored) {
					}
					break;
				}
			}
		} catch (Exception e) {
			log.error("Coordinate-pass enrichment stamp failed (org {}): {}", orgUuid, e.getMessage(), e);
		}
		return new int[]{byteStamped, coordStamped};
	}

	/**
	 * Parse one canonical artifact's BOM via rebom and write the
	 * per-component {@code artifact_sbom_components} rows. Called only
	 * when the canonical's rows don't already exist on disk.
	 *
	 * <p>Annotated {@code @Transactional} so each canonical's persist runs
	 * in its own short-lived session — see {@link #reconcileReleaseSbomComponents}
	 * for why the outer method dropped the transaction wrapper. Must be
	 * called through Spring's proxy ({@code self.*}) for the annotation to
	 * fire.
	 */
	/**
	 * @return true when the parse output was upserted (an empty BOM counts —
	 *   zero components is a legitimate parse); false when the parsed BOM
	 *   could not be fetched from rebom, so the caller can treat the
	 *   artifact as skipped-this-pass instead of silently complete.
	 */
	@Transactional
	public boolean parseAndUpsertArtifactSbomComponents(
			ArtifactData ad, UUID canonicalArtifactUuid, UUID orgUuid) {

		ParsedBom parsed;
		try {
			parsed = rebomService.parseBom(ad.getInternalBom().id(), orgUuid);
		} catch (Exception e) {
			log.error("Unable to fetch parsed BOM for artifact {} (bom {}): {}",
					ad.getUuid(), ad.getInternalBom().id(), e.getMessage(), e);
			return false;
		}
		if (parsed == null) return false;

		// Aggregate the components this BOM declares, keyed by canonical purl.
		Map<String, ComponentAggregation> componentAggs = new LinkedHashMap<>();
		if (parsed.components() != null) {
			for (ParsedBomComponent pc : parsed.components()) {
				if (pc == null || pc.canonicalPurl() == null) continue;
				ComponentAggregation agg = componentAggs.computeIfAbsent(
						pc.canonicalPurl(), k -> new ComponentAggregation(pc));
				agg.mergeSample(pc);
				agg.setExactPurl(pc.fullPurl());
			}
		}

		// Aggregate the in-edges (parents) keyed by target canonical purl.
		Map<String, Map<ParentKey, ParentEdge>> parentAggs = new LinkedHashMap<>();
		if (parsed.dependencies() != null) {
			for (ParsedBomDependency pd : parsed.dependencies()) {
				if (pd == null || pd.sourceCanonicalPurl() == null
						|| pd.targetCanonicalPurl() == null) continue;
				ParentKey key = new ParentKey(pd.sourceCanonicalPurl(), relationshipType(pd));
				ParentEdge edge = new ParentEdge(pd.sourceFullPurl(), pd.targetFullPurl());
				parentAggs.computeIfAbsent(pd.targetCanonicalPurl(), k -> new LinkedHashMap<>())
						.putIfAbsent(key, edge);
			}
		}

		// The raw ParsedBom can be tens of MB for large BOMs. We've extracted
		// everything we need into the smaller aggregation maps; releasing the
		// reference here lets the JVM mark it for collection while we move on
		// to the persist phase rather than waiting for the method to exit.
		parsed = null;

		// Empty component list is a legitimate parse (a BOM with no deps) —
		// the artifact is fully processed, not skipped.
		if (componentAggs.isEmpty()) return true;

		// Upsert canonical sbom_components and get back canonical→uuid map.
		Map<String, UUID> canonicalToUuid = upsertSbomComponents(componentAggs.values(), orgUuid);

		// Write one artifact_sbom_components row per component in this BOM.
		List<ArtifactSbomComponent> rows = new ArrayList<>(componentAggs.size());
		for (Map.Entry<String, ComponentAggregation> e : componentAggs.entrySet()) {
			String canonical = e.getKey();
			UUID componentUuid = canonicalToUuid.get(canonical);
			if (componentUuid == null) continue;

			List<Map<String, Object>> parentsJson = renderParents(
					parentAggs.get(canonical), canonicalToUuid);

			ArtifactSbomComponent row = new ArtifactSbomComponent();
			row.setOrg(orgUuid);
			row.setCanonicalArtifactUuid(canonicalArtifactUuid);
			row.setSbomComponentUuid(componentUuid);
			row.setExactPurl(e.getValue().getExactPurl() != null
					? e.getValue().getExactPurl() : canonical);
			row.setParents(parentsJson);
			rows.add(row);
		}

		// Drop the aggregation maps — no longer needed once rows are built.
		componentAggs = null;
		parentAggs = null;
		canonicalToUuid = null;

		try {
			saveAllChunked(rows, artifactSbomComponentRepository::saveAll);
		} catch (DataIntegrityViolationException dive) {
			// Lost the race with a concurrent reconcile of the same canonical —
			// per-row save in case some were inserted, ignore conflicts. Chunked
			// flush already happened on prior batches; no harm.
			for (ArtifactSbomComponent row : rows) {
				try {
					artifactSbomComponentRepository.save(row);
				} catch (DataIntegrityViolationException ignored) {
				}
			}
		}
		return true;
	}

	/**
	 * Batch-save with intermediate {@code flush() + clear()} so the L1 cache
	 * doesn't hold every entity inserted by a single call. Important when
	 * one canonical artifact's BOM yields thousands of {@link ArtifactSbomComponent}
	 * rows.
	 */
	private <T> void saveAllChunked(List<T> rows, java.util.function.Function<List<T>, Iterable<T>> saver) {
		if (rows == null || rows.isEmpty()) return;
		int size = rows.size();
		for (int i = 0; i < size; i += FLUSH_CHUNK) {
			List<T> chunk = rows.subList(i, Math.min(i + FLUSH_CHUNK, size));
			saver.apply(chunk);
			entityManager.flush();
			entityManager.clear();
		}
	}

	private void markReleaseReconciled(UUID releaseUuid) {
		releaseRepository.recordSbomReconciledAtVersion(releaseUuid, CURRENT_SBOM_SCHEMA_VERSION);
	}

	// ===================================================================
	// Canonical artifact resolution (lazy, BOM artifacts only, org-scoped)
	// ===================================================================

	/**
	 * Pure canonical resolution — no persistence. Digest-matches against
	 * existing artifacts, then chases the match's own mapping one hop so
	 * repeated same-content artifacts converge on a single canonical root.
	 * Without the hop (plus the deterministic oldest-first pick in
	 * findArtifactByStoredDigest), the arbitrary row the digest lookup
	 * returned became the "canonical", fragmenting identical content across
	 * several roots — observed as 8 identical BOMs split over 2 canonicals,
	 * each parsed and bucketed separately.
	 */
	private UUID computeCanonicalArtifact(ArtifactData ad, UUID orgUuid) {
		String rearmDigest = extractRearmDigest(ad);
		if (rearmDigest == null) return ad.getUuid();
		Optional<Artifact> match = sharedArtifactService.findArtifactByStoredDigest(orgUuid, rearmDigest);
		if (match.isEmpty()) return ad.getUuid();
		UUID matchUuid = match.get().getUuid();
		return artifactCanonicalMapRepository.findByArtifactUuid(matchUuid)
				.map(ArtifactCanonicalMap::getCanonicalArtifactUuid)
				.orElse(matchUuid);
	}

	private UUID resolveCanonicalArtifact(ArtifactData ad, UUID orgUuid) {
		UUID artifactUuid = ad.getUuid();

		Optional<ArtifactCanonicalMap> existing =
				artifactCanonicalMapRepository.findByArtifactUuid(artifactUuid);
		if (existing.isPresent()) {
			return existing.get().getCanonicalArtifactUuid();
		}

		UUID canonical = computeCanonicalArtifact(ad, orgUuid);

		ArtifactCanonicalMap row = new ArtifactCanonicalMap();
		row.setOrg(orgUuid);
		row.setArtifactUuid(artifactUuid);
		row.setCanonicalArtifactUuid(canonical);
		try {
			artifactCanonicalMapRepository.save(row);
		} catch (DataIntegrityViolationException dive) {
			return artifactCanonicalMapRepository.findByArtifactUuid(artifactUuid)
					.map(ArtifactCanonicalMap::getCanonicalArtifactUuid)
					.orElse(artifactUuid);
		}
		return canonical;
	}

	private static String extractRearmDigest(ArtifactData ad) {
		Set<DigestRecord> drs = ad.getDigestRecords();
		if (drs == null || drs.isEmpty()) return null;
		for (DigestRecord dr : drs) {
			if (dr.scope() == DigestScope.REARM && dr.digest() != null) return dr.digest();
		}
		return null;
	}

	// ===================================================================
	// Unmapped-BOM sweep — safety net for artifacts no reconcile covered
	// ===================================================================

	/** Age before an unmapped BOM artifact is considered orphaned, not in-flight. */
	private static final int SWEEP_MIN_AGE_MINUTES = 90;

	/** Watermark scan window; bounds the rows one sweep query can touch. */
	private static final int SWEEP_WINDOW_DAYS = 30;

	/** Max empty windows advanced per tick — paces the historical catch-up. */
	private static final int SWEEP_MAX_WINDOWS_PER_TICK = 12;

	/**
	 * Heal BOM artifacts that slipped through reconcile entirely: BOM-typed,
	 * internalBom present, older than {@link #SWEEP_MIN_AGE_MINUTES}, but no
	 * {@code artifact_canonical_map} row. Fan-out and synthetic bucketing are
	 * map-driven (not release-driven), so mapping + parsing the artifact here
	 * is sufficient for it to reach DTrack scanning and receive metrics; the
	 * owning release's {@code release_artifact_index} converges on its next
	 * natural reconcile. Safety net for the 2026-07-12 prod incident class,
	 * where one SCE BOM missed every reconcile pass and sat unscanned for 11
	 * days with no signal anywhere.
	 *
	 * <p>Order matters: parse FIRST, persist the map only on parse success —
	 * a map row without component rows would stop the sweep from ever
	 * retrying the artifact while leaving it unscannable.
	 */
	/** @return number of orphan heal attempts this tick (observability + tests). */
	public int sweepUnmappedBomArtifacts(int batchLimit) {
		// Watermark-windowed scan. The old single query walked the WHOLE BOM
		// history every tick: in steady state nothing is unmapped, so the
		// LIMIT never filled and every BOM artifact ever created was
		// heap-fetched, jsonb-detoasted and anti-join-probed each minute —
		// which outgrew the 120s query timeout in production. The persisted
		// watermark (advisory-locked tick, so single-writer) records how far
		// history has been verified; each tick advances it over empty
		// windows and stops at the first window holding orphans.
		//
		// Advance rules keep it safe: an empty window advances the watermark
		// to the window end; a non-empty window does NOT advance it, so a
		// row the heal path leaves unmapped (rebom down, heap guard) is
		// rescanned next tick. Healed rows make the window empty and the
		// watermark moves past them one tick later.
		ZonedDateTime cutoff = ZonedDateTime.now().minusMinutes(SWEEP_MIN_AGE_MINUTES);
		ZonedDateTime watermark = systemInfoService.getUnmappedBomSweepWatermark();
		if (watermark == null) {
			watermark = artifactService.getOldestBomArtifactCreatedDate();
			if (watermark == null) return 0; // no BOM artifacts at all
		}
		List<UUID> orphans = List.of();
		ZonedDateTime scannedUpTo = watermark;
		int windows = 0;
		while (watermark.isBefore(cutoff) && windows++ < SWEEP_MAX_WINDOWS_PER_TICK) {
			ZonedDateTime windowEnd = watermark.plusDays(SWEEP_WINDOW_DAYS);
			if (windowEnd.isAfter(cutoff)) windowEnd = cutoff;
			orphans = artifactService.listUnmappedBomArtifactUuidsInWindow(watermark, windowEnd, batchLimit);
			scannedUpTo = windowEnd;
			if (!orphans.isEmpty()) break;
			watermark = windowEnd;
			systemInfoService.setUnmappedBomSweepWatermark(watermark);
		}
		// Tail guarantee: a window the heal path cannot empty (a BOM rebom
		// permanently fails to parse) pins the watermark, and NEW orphans —
		// this sweep's primary quarry — would otherwise wait behind it
		// indefinitely. Whenever the loop did not reach the cutoff (pinned,
		// or catch-up budget exhausted), additionally probe up to the newest
		// window, from max(scannedUpTo, cutoff - window) so the ranges stay
		// disjoint from the pinned window. The lower bound matters: an
		// earlier scannedUpTo < cutoff-window guard left a dead band at pin
		// age ~30-60d where fresh orphans were beyond the pinned window yet
		// the tail probe was suppressed. Steady state (scannedUpTo == cutoff)
		// costs zero extra queries. Accepted residual: orphans BETWEEN the
		// pinned window and this tail range wait until the poison is
		// resolved (it warn-logs every tick, so it is visible).
		ZonedDateTime tailFrom = cutoff.minusDays(SWEEP_WINDOW_DAYS);
		if (scannedUpTo.isAfter(tailFrom)) tailFrom = scannedUpTo;
		if (tailFrom.isBefore(cutoff)) {
			List<UUID> tailOrphans = artifactService.listUnmappedBomArtifactUuidsInWindow(tailFrom, cutoff, batchLimit);
			if (!tailOrphans.isEmpty()) {
				List<UUID> combined = new ArrayList<>(orphans);
				combined.addAll(tailOrphans);
				orphans = combined;
			}
		}
		for (UUID artifactUuid : orphans) {
			if (HeapPressureGuard.checkAndMaybeGc(log, "unmapped-BOM sweep",
					String.format("before artifact %s; remaining retried next tick.", artifactUuid))) {
				return orphans.size();
			}
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) continue;
			ArtifactData ad = oad.get();
			if (ad.getOrg() == null || ad.getInternalBom() == null || ad.getInternalBom().id() == null) continue;
			UUID canonical = computeCanonicalArtifact(ad, ad.getOrg());
			if (!artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonical)
					&& !self.parseAndUpsertArtifactSbomComponents(ad, canonical, ad.getOrg())) {
				// rebom parse unavailable (warn-logged inside) — leave the
				// artifact unmapped so the next sweep tick retries it.
				continue;
			}
			resolveCanonicalArtifact(ad, ad.getOrg());
			log.warn("Unmapped-BOM sweep healed artifact {} (org {}, canonical {}) — no release reconcile pass had covered it",
					artifactUuid, ad.getOrg(), canonical);
		}
		return orphans.size();
	}

	// ===================================================================
	// Stale-canonical-qualifier sweep - repoint mappings onto qualifier-bearing canonicals
	// ===================================================================

	/**
	 * Canonical-purl form this build writes. Bump whenever
	 * {@code Utils.CANONICAL_PRESERVED_QUALIFIERS} (and rebom's mirrored
	 * {@code PRESERVED_QUALIFIERS}) gains a type or a qualifier: every
	 * {@code artifact_canonical_map} row then falls below the current version and
	 * is re-verified lazily, without a migration.
	 */
	// v1 (never shipped to main): derived the target via Utils.canonicalizePurl,
	// whose Java PackageURL round-trip is not byte-identical to rebom's
	// packageurl-js form; on the live validation run it minted encoding-variant
	// duplicates. v2 derives byte-preserving and compares semantically, and
	// re-verifying the v1-stamped estate is precisely what heals the rows v1
	// mis-repaired.
	public static final int CANONICAL_FORM_VERSION = 2;

	/**
	 * Repoint {@code artifact_sbom_components} rows written under the old
	 * qualifier-stripping canonicalization onto the correct qualifier-bearing
	 * {@code sbom_components} row.
	 *
	 * <p>A stripped canonical merges distinct identities - {@code ...?distro=alpine-3.18}
	 * and {@code ...?distro=alpine-3.19} collapsed into one row - so Dependency-Track
	 * sees a single component and applies advisories fixed in another distro branch to
	 * both (the OSV {@code fixed:0} false-positive class).
	 *
	 * <p>The corrected canonical is derived locally by {@link Utils#canonicalizePurl}
	 * from {@code exact_purl}, which every row preserves, so this needs no rebom
	 * round-trip and no re-parse. Stripped rows left behind unreferenced are
	 * deliberately NOT deleted: {@code sbom_components} is derived data, and once no
	 * mapping points at one its findings attribute to no artifact. That is what lets
	 * this run incrementally where V66's DELETE plus org-wide release re-enqueue
	 * timed out on large instances.
	 *
	 * <p>Bounded and self-clearing: each canonical is stamped with
	 * {@link #CANONICAL_FORM_VERSION} once verified and never revisited, so the pass
	 * converges and then costs one empty index range scan per tick.
	 */
	public void sweepStaleCanonicalQualifiers(int batchLimit) {
		List<PendingCanonicalForm> pending = artifactCanonicalMapRepository
				.findPendingCanonicalForm(CANONICAL_FORM_VERSION, batchLimit);
		for (PendingCanonicalForm p : pending) {
			if (HeapPressureGuard.checkAndMaybeGc(log, "stale-canonical-qualifier sweep",
					String.format("before canonical %s; remaining retried next tick.",
							p.getCanonicalArtifactUuid()))) {
				return;
			}
			try {
				int repaired = self.repointCanonicalArtifactQualifiers(
						p.getOrg(), p.getCanonicalArtifactUuid());
				// Stamped only after a clean pass - on failure the marker stays below
				// the current version so the next tick retries this canonical.
				artifactCanonicalMapRepository.markCanonicalFormVerified(
						p.getCanonicalArtifactUuid(), CANONICAL_FORM_VERSION);
				if (repaired > 0) {
					log.warn("Stale-canonical sweep repointed {} mapping(s) of canonical artifact {} (org {}) onto qualifier-bearing canonicals",
							repaired, p.getCanonicalArtifactUuid(), p.getOrg());
				}
			} catch (Exception e) {
				log.error("Stale-canonical sweep failed for canonical artifact {} (org {}): {}",
						p.getCanonicalArtifactUuid(), p.getOrg(), e.getMessage(), e);
			}
		}
	}

	/**
	 * Verify and repair one canonical artifact's component mappings.
	 *
	 * @return how many rows were repointed (0 when the canonical was already
	 *   correct, which is the steady-state outcome).
	 */
	@Transactional
	public int repointCanonicalArtifactQualifiers(UUID orgUuid, UUID canonicalArtifactUuid) {
		List<ArtifactSbomComponent> rows = artifactSbomComponentRepository
				.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifactUuid);
		if (rows.isEmpty()) return 0;

		Set<UUID> componentUuids = new HashSet<>();
		for (ArtifactSbomComponent r : rows) componentUuids.add(r.getSbomComponentUuid());
		Map<UUID, SbomComponent> byUuid = new HashMap<>();
		for (SbomComponent sc : sbomComponentRepository.findAllById(componentUuids)) {
			byUuid.put(sc.getUuid(), sc);
		}

		Map<String, UUID> resolved = new HashMap<>();
		List<ArtifactSbomComponent> dirty = new ArrayList<>();
		int repaired = 0;
		for (ArtifactSbomComponent r : rows) {
			boolean changed = false;
			SbomComponent current = byUuid.get(r.getSbomComponentUuid());
			// Encoding-preserving derivation + semantic trigger, both load-bearing:
			// Utils.canonicalizePurl round-trips through Java PackageURL, whose
			// toString() is not byte-identical to the packageurl-js form rebom
			// persists (epoch colons come back %3A). String-comparing that form
			// declared correct rows stale and minted encoding-variant duplicates
			// when this first ran live. The semantic guard makes encoding-variant
			// pairs compare EQUAL so only a real identity difference is repaired.
			String want = Utils.canonicalizePurlPreservingEncoding(r.getExactPurl());
			if (want != null && current != null
					&& !Utils.purlsSemanticallyEqual(want, current.getCanonicalPurl())) {
				UUID target = resolveCorrectedCanonical(orgUuid, want, current, resolved);
				if (target != null && !target.equals(r.getSbomComponentUuid())) {
					r.setSbomComponentUuid(target);
					changed = true;
					repaired++;
				}
			}
			// Parent edges embed the parent's canonical uuid, so they go stale by the
			// same mechanism and are rewritten from their own sourceExactPurl.
			if (rewriteStaleParents(orgUuid, r, resolved)) changed = true;
			if (changed) dirty.add(r);
		}
		if (!dirty.isEmpty()) saveAllChunked(dirty, artifactSbomComponentRepository::saveAll);
		return repaired;
	}

	/**
	 * Resolve the {@code sbom_components} row for a corrected canonical, creating it
	 * from {@code seed} (the stripped row the mapping currently points at) when it
	 * does not exist yet.
	 *
	 * <p>Licenses and {@code enriched_at} are carried across deliberately. The
	 * corrected canonical describes the same package - only the identity-bearing
	 * qualifier differs - and a cold row would be withheld by {@code submitOrg}'s
	 * enrichment gate until the puller happened to stamp it, opening a coverage gap
	 * and a burst of {@code [SYNTHETIC-STALL]} noise. This inherits the same
	 * fill-once approximation {@code stampEnrichedLicenses} already documents.
	 */
	private UUID resolveCorrectedCanonical(
			UUID orgUuid, String canonicalPurl, SbomComponent seed, Map<String, UUID> cache) {
		UUID cached = cache.get(canonicalPurl);
		if (cached != null) return cached;
		Optional<SbomComponent> existing =
				sbomComponentRepository.findByOrgAndCanonicalPurl(orgUuid, canonicalPurl);
		if (existing.isPresent()) {
			cache.put(canonicalPurl, existing.get().getUuid());
			return existing.get().getUuid();
		}
		// Byte-equality missed; before minting, look for an existing row whose
		// canonical is the SAME identity in a different percent-encoding era.
		// rebom's persisted canonicals are not byte-consistent (raw vs %2B '+',
		// %40 '@' namespaces), so minting on a byte miss would split the identity
		// between encoding variants -- the same duplicate class the encoding-drift
		// incident produced, just from mixed data instead of a Java round-trip.
		SbomComponent variant = findSemanticallyEqualCanonical(orgUuid, canonicalPurl);
		if (variant != null) {
			cache.put(canonicalPurl, variant.getUuid());
			return variant.getUuid();
		}
		if (seed == null) return null;
		SbomComponent created = new SbomComponent();
		created.setOrg(orgUuid);
		created.setCanonicalPurl(canonicalPurl);
		created.setRecordData(seed.getRecordData() != null
				? new HashMap<>(seed.getRecordData()) : null);
		created.setIdentities(retargetIdentities(
				seed.getIdentities(), seed.getCanonicalPurl(), canonicalPurl));
		created.setLicenses(seed.getLicenses());
		created.setEnrichedAt(seed.getEnrichedAt());
		stampTerminalIfUnmatchablePurlType(created);
		try {
			created = sbomComponentRepository.save(created);
		} catch (DataIntegrityViolationException dive) {
			// Lost the race with a concurrent reconcile or another sweep tick.
			Optional<SbomComponent> raced =
					sbomComponentRepository.findByOrgAndCanonicalPurl(orgUuid, canonicalPurl);
			if (raced.isEmpty()) return null;
			created = raced.get();
		}
		cache.put(canonicalPurl, created.getUuid());
		return created.getUuid();
	}

	/**
	 * Existing {@code sbom_components} row denoting the same identity as
	 * {@code canonicalPurl} in any percent-encoding, or null. Candidates come from
	 * the decoded name/version in record_data (encoding-independent), then each is
	 * compared semantically.
	 */
	private SbomComponent findSemanticallyEqualCanonical(UUID orgUuid, String canonicalPurl) {
		String name = null;
		String version = null;
		try {
			com.github.packageurl.PackageURL parsed =
					new com.github.packageurl.PackageURL(canonicalPurl.replace("+", "%2B"));
			name = parsed.getName();
			version = parsed.getVersion();
		} catch (com.github.packageurl.MalformedPackageURLException e) {
			return null;
		}
		if (name == null) return null;
		for (SbomComponent candidate :
				sbomComponentRepository.findCandidatesByOrgNameVersion(orgUuid, name, version)) {
			if (Utils.purlsSemanticallyEqual(canonicalPurl, candidate.getCanonicalPurl())) {
				return candidate;
			}
		}
		return null;
	}

	/** Point the canonical primary identity at the corrected purl, leaving CPEs untouched. */
	private List<ComponentIdentity> retargetIdentities(
			List<ComponentIdentity> identities, String oldCanonical, String newCanonical) {
		if (identities == null || identities.isEmpty()) return identities;
		List<ComponentIdentity> out = new ArrayList<>(identities.size());
		for (ComponentIdentity ci : identities) {
			out.add(ci != null && oldCanonical != null && oldCanonical.equals(ci.value())
					? new ComponentIdentity(ci.scheme(), newCanonical)
					: ci);
		}
		return out;
	}

	/** @return true when any parent edge was repointed. */
	private boolean rewriteStaleParents(
			UUID orgUuid, ArtifactSbomComponent row, Map<String, UUID> cache) {
		List<Map<String, Object>> parents = row.getParents();
		if (parents == null || parents.isEmpty()) return false;
		boolean changed = false;
		List<Map<String, Object>> out = new ArrayList<>(parents.size());
		for (Map<String, Object> parent : parents) {
			Map<String, Object> entry = new LinkedHashMap<>(parent);
			String declared = asNonBlankString(entry.get("sourceCanonicalPurl"));
			String want = Utils.canonicalizePurlPreservingEncoding(
					asNonBlankString(entry.get("sourceExactPurl")));
			if (want != null && declared != null && !Utils.purlsSemanticallyEqual(want, declared)) {
				SbomComponent seed = null;
				String rawUuid = asNonBlankString(entry.get("sourceSbomComponentUuid"));
				if (rawUuid != null) {
					try {
						seed = sbomComponentRepository.findById(UUID.fromString(rawUuid)).orElse(null);
					} catch (IllegalArgumentException ignored) {
						// Malformed uuid in legacy jsonb - leave the edge alone.
					}
				}
				UUID target = resolveCorrectedCanonical(orgUuid, want, seed, cache);
				if (target != null) {
					entry.put("sourceCanonicalPurl", want);
					entry.put("sourceSbomComponentUuid", target.toString());
					changed = true;
				}
			}
			out.add(entry);
		}
		if (changed) row.setParents(out);
		return changed;
	}

	private static String asNonBlankString(Object o) {
		return o instanceof String s && !s.isBlank() ? s : null;
	}

	// ===================================================================
	// Force-reconcile (operator GraphQL mutation)
	// ===================================================================

	public void forceReconcileWithDeps(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) {
			log.warn("forceReconcileWithDeps called for missing release {}", releaseUuid);
			return;
		}
		ReleaseData rd = ord.get();
		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);
		if (isProduct) {
			for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
				try {
					self.reconcileReleaseSbomComponents(dep.getUuid());
				} catch (Exception e) {
					log.warn("Cascade reconcile of dep {} for product {} failed: {}",
							dep.getUuid(), releaseUuid, e.getMessage(), e);
				}
			}
		}
		self.reconcileReleaseSbomComponents(releaseUuid);
	}

	// ===================================================================
	// Reconcile-driven BOM-diff: acollection changelog cache,
	// snapshot safety net, and once-per-release notification
	// ===================================================================

	/**
	 * Post-reconcile pipeline, run off the drain once a release's component
	 * inventory has been rebuilt. Three responsibilities, all best-effort:
	 *
	 * <ol>
	 * <li><b>Snapshot safety net (Phase 4a):</b> re-resolve the acollection
	 *     snapshot so the artifact list TEA reads stays current. This is the
	 *     home for the catch-all that {@code ReleaseFinalizerService.finalize
	 *     Release} used to provide before finalize became a no-op.</li>
	 * <li><b>Changelog cache (Phase 2):</b> recompute the SBOM-components
	 *     changelog as a set-difference of reconcile inventories and cache it
	 *     onto {@code acollection.artifactComparison} — for both this release
	 *     (vs its predecessor) and the next release (vs this one), since this
	 *     release's inventory change invalidates the successor's diff too.
	 *     Ungated by lifecycle so DRAFT releases also show a current changelog
	 *     in the UI.</li>
	 * <li><b>Notification (Phase 1):</b> fire the once-per-release BOM-diff
	 *     alert, gated on lifecycle {@code >= ASSEMBLED} and the one-shot
	 *     {@code flow_control.bomDiffNotifiedAt} flag, reusing the self-diff
	 *     computed for the cache.</li>
	 * </ol>
	 */
	public void postReconcileBomDiff(UUID releaseUuid) {
		// (1) Snapshot safety net — independent failure domain from the diff work.
		// Cheap-on-no-change: skips the per-artifact rebom resolve when the
		// artifact set is unchanged (the common case, since mutations already
		// resolve the snapshot synchronously via saveRelease).
		try {
			acollectionService.resolveReleaseCollectionIfArtifactsChanged(releaseUuid, WhoUpdated.getAutoWhoUpdated());
		} catch (Exception e) {
			log.warn("post-reconcile acollection resolve failed for release {}: {}",
					releaseUuid, e.getMessage());
		}

		try {
			// Light read: this path only needs record-data fields (branch,
			// lifecycle, org, component) for the diff + notification gate — no
			// metrics detail arrays or approval/update events.
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseDataLight(releaseUuid);
			if (ord.isEmpty()) return;
			ReleaseData rd = ord.get();
			UUID branch = rd.getBranch();

			Map<String, DiffComponent> selfInventory = collectReleaseInventory(releaseUuid);

			// (2a) This release vs its lineage predecessor → cache + notification baseline.
			ArtifactChangelog selfDiff = null;
			UUID prevReleaseUuid = sharedReleaseService
					.findPreviousReleasesOfBranchForRelease(branch, releaseUuid);
			if (prevReleaseUuid != null) {
				Map<String, DiffComponent> prevInventory = collectReleaseInventory(prevReleaseUuid);
				// Only diff against a populated baseline — an empty predecessor
				// inventory usually means it just isn't reconciled yet, and
				// caching an all-"added" diff would be misleading.
				if (!selfInventory.isEmpty() && !prevInventory.isEmpty()) {
					selfDiff = diffInventories(selfInventory, prevInventory);
					acollectionService.cacheReleaseChangelog(releaseUuid, prevReleaseUuid, selfDiff);
				}
			}

			// (2b) Next release vs this one → keep the successor's cached diff fresh.
			UUID nextReleaseUuid = sharedReleaseService
					.findNextReleasesOfBranchForRelease(branch, releaseUuid);
			if (nextReleaseUuid != null) {
				Map<String, DiffComponent> nextInventory = collectReleaseInventory(nextReleaseUuid);
				if (!nextInventory.isEmpty() && !selfInventory.isEmpty()) {
					ArtifactChangelog nextDiff = diffInventories(nextInventory, selfInventory);
					acollectionService.cacheReleaseChangelog(nextReleaseUuid, releaseUuid, nextDiff);
				}
			}

			// (3) Once-per-release notification, reusing the self-diff.
			maybeFireBomDiffNotification(rd, selfDiff);
		} catch (Exception e) {
			log.warn("post-reconcile BOM-diff cache/notification failed for release {}: {}",
					releaseUuid, e.getMessage(), e);
		}
	}

	/**
	 * Once-per-release BOM-diff notification. Gate: lifecycle {@code >=
	 * ASSEMBLED} (the all-BOMs-uploaded signal) and the one-shot
	 * {@code flow_control.bomDiffNotifiedAt} flag unset. A valid {@code
	 * selfDiff} (non-null — i.e. a populated baseline was available) is
	 * required, so we don't burn the one-shot claim before a comparable
	 * predecessor inventory exists; a later reconcile retries. The atomic
	 * claim then fires {@link ReleaseChangeHook#onReleaseBomDiff} (whose
	 * SAAS impl no-ops unless the diff has both additions and removals).
	 */
	private void maybeFireBomDiffNotification(ReleaseData rd, ArtifactChangelog selfDiff) {
		if (selfDiff == null) return;
		ReleaseLifecycle lifecycle = rd.getLifecycle();
		if (lifecycle == null || lifecycle.ordinal() < ReleaseLifecycle.ASSEMBLED.ordinal()) return;
		// Atomic one-shot claim: 0 rows affected => a prior reconcile already notified.
		if (releaseRepository.claimBomDiffNotification(rd.getUuid()) == 0) return;
		if (releaseChangeHook != null) releaseChangeHook.onReleaseBomDiff(rd, selfDiff);
	}

	/**
	 * Set-difference two release inventories into an {@link ArtifactChangelog}:
	 * components present in {@code curr} but not {@code prev} are added,
	 * those present in {@code prev} but not {@code curr} are removed. Both maps
	 * are keyed by canonical purl (version-included), so a version bump shows
	 * as a remove of the old + add of the new — matching rebom's prior diff.
	 */
	private static ArtifactChangelog diffInventories(
			Map<String, DiffComponent> curr, Map<String, DiffComponent> prev) {
		Set<DiffComponent> added = new LinkedHashSet<>();
		for (Map.Entry<String, DiffComponent> e : curr.entrySet()) {
			if (!prev.containsKey(e.getKey())) added.add(e.getValue());
		}
		Set<DiffComponent> removed = new LinkedHashSet<>();
		for (Map.Entry<String, DiffComponent> e : prev.entrySet()) {
			if (!curr.containsKey(e.getKey())) removed.add(e.getValue());
		}
		return new ArtifactChangelog(added, removed);
	}

	/**
	 * Lean inventory for a release: maps each canonical purl (version-included)
	 * the release's BOM artifacts declare to a {@link DiffComponent} carrying
	 * that same canonical purl plus the version — matching the shape rebom's
	 * {@code bomDiff} produced (full purl in {@code purl}, version alongside),
	 * so cached changelogs and the UI rendering are unchanged. Avoids the full
	 * per-component participation/parent synthesis of
	 * {@link #listReleaseSbomComponents}; resolution mirrors that method
	 * (product releases fold in their transitive dependency inventories).
	 */
	public Map<String, DiffComponent> collectReleaseInventory(UUID releaseUuid) {
		// Light read: only org / component-type / dependency record-data fields are
		// used to resolve the canonical-purl set — no metrics detail or events.
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseDataLight(releaseUuid);
		if (ord.isEmpty()) return Map.of();
		ReleaseData rd = ord.get();
		UUID orgUuid = rd.getOrg();
		if (orgUuid == null) return Map.of();

		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);

		Set<UUID> sourceReleaseUuids = new LinkedHashSet<>();
		sourceReleaseUuids.add(releaseUuid);
		if (isProduct) {
			for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
				sourceReleaseUuids.add(dep.getUuid());
			}
		}

		Set<UUID> canonicalSet = new LinkedHashSet<>();
		for (ReleaseArtifactIndex idx :
				releaseArtifactIndexRepository.findByOrgAndReleaseUuidIn(orgUuid, sourceReleaseUuids)) {
			canonicalSet.add(idx.getCanonicalArtifactUuid());
		}
		if (canonicalSet.isEmpty()) return Map.of();

		List<ArtifactSbomComponent> rows = artifactSbomComponentRepository
				.findByOrgAndCanonicalArtifactUuidIn(orgUuid, canonicalSet);
		if (rows.isEmpty()) return Map.of();

		Set<UUID> componentIds = new LinkedHashSet<>();
		for (ArtifactSbomComponent r : rows) componentIds.add(r.getSbomComponentUuid());

		Map<UUID, SbomComponent> comps = findSbomComponentsByIds(componentIds, orgUuid);
		Map<String, DiffComponent> out = new LinkedHashMap<>();
		for (SbomComponent sc : comps.values()) {
			// Root components are the release's own top-level identity (the BOM's
			// metadata.component), not consumed dependencies — exclude them so a
			// release's own purl doesn't surface as an added/removed component.
			if (isMarkedRoot(sc)) continue;
			String canonicalPurl = sc.getCanonicalPurl();
			if (canonicalPurl == null) continue;
			String version = SbomComponentData.dataFromRecord(sc).version();
			out.put(canonicalPurl, new DiffComponent(canonicalPurl, version));
		}
		return out;
	}

	// ===================================================================
	// Read API — synthesize per-release view from artifact-keyed rows
	// ===================================================================

	/**
	 * The {@code artifact_sbom_components} rows a release resolves to: itself, plus every
	 * transitive dependency when it is a PRODUCT.
	 *
	 * <p>Extracted so the release-scoped support-coverage count walks THE SAME PATH as the
	 * component list it sits above. That path is not a SQL join and cannot be made into one
	 * -- the product dependency unwind happens in Java -- so a coverage query that
	 * approximated it with "components in the org that appear in this release" would drift
	 * from the list beneath it. Reusing this method makes the two agree by construction
	 * rather than by inspection, and a test asserts the equality regardless.
	 */
	public List<ArtifactSbomComponent> resolveReleaseArtifactComponents(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) return List.of();
		ReleaseData rd = ord.get();
		UUID orgUuid = rd.getOrg();
		if (orgUuid == null) return List.of();

		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);

		Set<UUID> sourceReleaseUuids = new LinkedHashSet<>();
		sourceReleaseUuids.add(releaseUuid);
		if (isProduct) {
			for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
				sourceReleaseUuids.add(dep.getUuid());
			}
		}

		// Resolve the union of canonical artifacts referenced by the release
		// (and, for products, every transitive dep).
		Set<UUID> canonicalSet = new LinkedHashSet<>();
		for (ReleaseArtifactIndex idx :
				releaseArtifactIndexRepository.findByOrgAndReleaseUuidIn(orgUuid, sourceReleaseUuids)) {
			canonicalSet.add(idx.getCanonicalArtifactUuid());
		}
		if (canonicalSet.isEmpty()) return List.of();

		return artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuidIn(orgUuid, canonicalSet);
	}

	/** The distinct canonical component uuids a release resolves to. */
	public Set<UUID> findReleaseComponentUuids(UUID releaseUuid) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (ArtifactSbomComponent r : resolveReleaseArtifactComponents(releaseUuid)) {
			ids.add(r.getSbomComponentUuid());
		}
		return ids;
	}

	public List<ReleaseSbomComponent> listReleaseSbomComponents(UUID releaseUuid) {
		return listReleaseSbomComponents(releaseUuid, null);
	}

	/**
	 * The release's merged component rows, optionally narrowed to {@code restrictTo}.
	 *
	 * <p>{@code null} means the whole release -- the graph view, and the historical
	 * behaviour. A non-null set is a PAGE, and the narrowing happens before the bulk entity
	 * load, not after the merge: that load is the reason the unpaged call gets expensive on
	 * a large PRODUCT unwind, so filtering its result would page the output while still
	 * paying the whole BOM's cost.
	 *
	 * <p>Parents of the retained rows are still fetched even when their own row is off-page,
	 * because a parent edge renders its source's canonical purl. Dropping them would show a
	 * page whose dependency column is blank wherever the parent happened to sort onto
	 * another page.
	 */
	public List<ReleaseSbomComponent> listReleaseSbomComponents(UUID releaseUuid, Set<UUID> restrictTo) {
		return listReleaseSbomComponents(releaseUuid, restrictTo, null);
	}

	/**
	 * As above, but reusing artifact rows the caller has already resolved.
	 *
	 * <p>{@code preResolved} exists because resolving a release is NOT cheap and the paged
	 * path was doing it twice per request: once in {@code listReleaseSbomComponentPage} to
	 * get the id set the SQL slices, and again here. Each resolution loads the release, walks
	 * the PRODUCT dependency unwind, and fetches EVERY artifact_sbom_components row for the
	 * whole release -- work that the restriction then discards down to one page. Narrowing
	 * the hydration while paying for the unwind twice would have made the paged query slower
	 * than the unpaged one it replaces on exactly the large PRODUCT releases it exists for.
	 */
	public List<ReleaseSbomComponent> listReleaseSbomComponents(UUID releaseUuid,
			Set<UUID> restrictTo, List<ArtifactSbomComponent> preResolved) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) return List.of();
		UUID orgUuid = ord.get().getOrg();
		if (orgUuid == null) return List.of();
		if (restrictTo != null && restrictTo.isEmpty()) return List.of();

		List<ArtifactSbomComponent> rows = null != preResolved
				? preResolved
				: resolveReleaseArtifactComponents(releaseUuid);
		if (rows.isEmpty()) return List.of();

		// Group rows by sbom_component_uuid to build the per-release view.
		Map<UUID, List<ArtifactSbomComponent>> byComponent = new LinkedHashMap<>();
		for (ArtifactSbomComponent r : rows) {
			if (restrictTo != null && !restrictTo.contains(r.getSbomComponentUuid())) continue;
			byComponent.computeIfAbsent(r.getSbomComponentUuid(), k -> new ArrayList<>()).add(r);
		}
		if (byComponent.isEmpty()) return List.of();

		// Bulk-fetch canonical components referenced by RETAINED rows + their parents
		// (we need their canonical purls for the rendered parent entries).
		Set<UUID> referencedIds = new HashSet<>(byComponent.keySet());
		for (List<ArtifactSbomComponent> group : byComponent.values()) {
			for (ArtifactSbomComponent r : group) {
				if (r.getParents() == null) continue;
				for (Map<String, Object> p : r.getParents()) {
					UUID src = parseUuid(p.get("sourceSbomComponentUuid"));
					if (src != null) referencedIds.add(src);
				}
			}
		}
		Map<UUID, SbomComponent> sbomCompByUuid = findSbomComponentsByIds(referencedIds, orgUuid);

		List<ReleaseSbomComponent> result = new ArrayList<>(byComponent.size());
		for (Map.Entry<UUID, List<ArtifactSbomComponent>> e : byComponent.entrySet()) {
			result.add(mergeReleaseRow(orgUuid, releaseUuid, e.getKey(), e.getValue(), sbomCompByUuid));
		}
		return result;
	}

	/**
	 * Build one synthesized per-(release, sbom_component) view by unioning
	 * the contributing canonical artifacts' rows. Each row contributes one
	 * artifact participation; each row's parents array contributes a set of
	 * declarations to the unioned parent edges.
	 */
	private ReleaseSbomComponent mergeReleaseRow(
			UUID orgUuid,
			UUID releaseUuid,
			UUID sbomComponentUuid,
			List<ArtifactSbomComponent> sourceRows,
			Map<UUID, SbomComponent> sbomCompByUuid) {

		// artifactParticipations: one entry per contributing canonical artifact.
		Map<UUID, Map<String, Object>> participationsByArtifact = new LinkedHashMap<>();
		// parents: keyed by (sourceSbomComponentUuid, relationshipType) — one
		// entry per logical edge, with declaringArtifacts collected from each
		// contributing artifact row.
		Map<String, Map<String, Object>> parentsByKey = new LinkedHashMap<>();
		ZonedDateTime earliestParsed = null;
		ZonedDateTime latestParsed = null;

		for (ArtifactSbomComponent src : sourceRows) {
			if (src.getParsedAt() != null) {
				if (earliestParsed == null || src.getParsedAt().isBefore(earliestParsed)) earliestParsed = src.getParsedAt();
				if (latestParsed == null || src.getParsedAt().isAfter(latestParsed)) latestParsed = src.getParsedAt();
			}

			// Participation: this artifact (canonical) referenced the component
			// using exact_purl.
			Map<String, Object> participation = participationsByArtifact
					.computeIfAbsent(src.getCanonicalArtifactUuid(), k -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("artifact", k.toString());
						m.put("exactPurls", new ArrayList<String>());
						return m;
					});
			@SuppressWarnings("unchecked")
			List<String> exactPurls = (List<String>) participation.get("exactPurls");
			if (!exactPurls.contains(src.getExactPurl())) exactPurls.add(src.getExactPurl());

			// Parents: each entry in the artifact's parents array becomes a
			// declaringArtifacts entry under the unioned edge.
			if (src.getParents() != null) {
				for (Map<String, Object> p : src.getParents()) {
					if (p == null) continue;
					String sourceCanonicalPurl = String.valueOf(p.get("sourceCanonicalPurl"));
					String relationshipType = String.valueOf(p.get("relationshipType"));
					String key = sourceCanonicalPurl + " " + relationshipType;
					Map<String, Object> publicEntry = parentsByKey.computeIfAbsent(key, k -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("sourceSbomComponentUuid", p.get("sourceSbomComponentUuid"));
						m.put("sourceCanonicalPurl", sourceCanonicalPurl);
						m.put("relationshipType", relationshipType);
						m.put("declaringArtifacts", new ArrayList<Map<String, Object>>());
						return m;
					});
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> decls = (List<Map<String, Object>>) publicEntry.get("declaringArtifacts");
					Map<String, Object> decl = new LinkedHashMap<>();
					decl.put("artifact", src.getCanonicalArtifactUuid().toString());
					decl.put("sourceExactPurl", p.get("sourceExactPurl"));
					decl.put("targetExactPurl", p.get("targetExactPurl"));
					String declKey = declarationKey(decl);
					boolean seen = false;
					for (Map<String, Object> existing : decls) {
						if (declarationKey(existing).equals(declKey)) { seen = true; break; }
					}
					if (!seen) decls.add(decl);
				}
			}
		}

		// Stable sort: participations by artifact uuid, parents by source
		// canonical purl + relationship.
		List<Map<String, Object>> participationsSorted = new ArrayList<>(participationsByArtifact.values());
		participationsSorted.sort((a, b) -> String.valueOf(a.get("artifact")).compareTo(String.valueOf(b.get("artifact"))));
		// Sort exact purls inside each participation entry deterministically.
		for (Map<String, Object> part : participationsSorted) {
			@SuppressWarnings("unchecked")
			List<String> purls = (List<String>) part.get("exactPurls");
			Collections.sort(purls);
		}

		List<Map<String, Object>> parentsSorted = new ArrayList<>(parentsByKey.values());
		parentsSorted.sort((a, b) -> {
			String sa = String.valueOf(a.get("sourceCanonicalPurl"));
			String sb = String.valueOf(b.get("sourceCanonicalPurl"));
			int by = sa.compareTo(sb);
			if (by != 0) return by;
			return String.valueOf(a.get("relationshipType")).compareTo(String.valueOf(b.get("relationshipType")));
		});

		ReleaseSbomComponent merged = new ReleaseSbomComponent();
		merged.setUuid(syntheticReleaseRowUuid(releaseUuid, sbomComponentUuid));
		merged.setOrg(orgUuid);
		merged.setReleaseUuid(releaseUuid);
		merged.setSbomComponentUuid(sbomComponentUuid);
		merged.setArtifactParticipations(participationsSorted);
		merged.setParents(parentsSorted);
		if (earliestParsed != null) merged.setCreatedDate(earliestParsed);
		if (latestParsed != null) merged.setLastUpdatedDate(latestParsed);
		return merged;
	}

	private static UUID syntheticReleaseRowUuid(UUID releaseUuid, UUID sbomComponentUuid) {
		String key = releaseUuid.toString() + ":" + sbomComponentUuid.toString();
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	private static String declarationKey(Map<String, Object> declaration) {
		return String.valueOf(declaration.get("artifact"))
				+ " " + String.valueOf(declaration.get("sourceExactPurl"))
				+ " " + String.valueOf(declaration.get("targetExactPurl"));
	}

	private static UUID parseUuid(Object value) {
		if (value == null) return null;
		if (value instanceof UUID u) return u;
		if (value instanceof String s && !s.isBlank()) {
			try { return UUID.fromString(s); } catch (IllegalArgumentException iae) { return null; }
		}
		return null;
	}

	// ===================================================================
	// Misc public API
	// ===================================================================

	public Optional<SbomComponent> getSbomComponent(UUID uuid) {
		return sbomComponentRepository.findById(uuid);
	}

	public Map<UUID, SbomComponent> findSbomComponentsByIds(Collection<UUID> ids, UUID orgUuid) {
		if (ids == null || ids.isEmpty() || orgUuid == null) return Map.of();
		Map<UUID, SbomComponent> out = new LinkedHashMap<>();
		// Array-bound, not findAllById. A derived IN list binds one parameter per element
		// against the 65,535-parameter protocol cap, and this is the load the BULK write path
		// runs -- the same bug this codebase already fixed one method away on the read side.
		// The org filter moved into the query with it: the previous version read every
		// requested row and discarded the foreign ones in Java, which is the right answer
		// obtained by reading rows belonging to other organizations.
		sbomComponentRepository.findByOrgAndUuidIn(orgUuid.toString(), joinUuids(ids))
				.forEach(sc -> out.put(sc.getUuid(), sc));
		return out;
	}

	/**
	 * Bulk-load support attestations for a batch of components -- one query for an entire
	 * release's component list (or any other multi-component read), never per component.
	 * Mirrors {@link #findSbomComponentsByIds}. Components with no attestation are simply
	 * absent from the result, as are components whose attestation this build cannot read
	 * (logged, never downgraded to "not assessed"). Org-scoped in the query, not merely by
	 * caller convention.
	 */
	public Map<UUID, SupportData> findSupportByComponentIds(UUID orgUuid, Collection<UUID> ids) {
		if (orgUuid == null || ids == null || ids.isEmpty()) return Map.of();
		Map<UUID, SupportData> out = new HashMap<>();
		for (SupportPayloadRow row : sbomComponentSupportRepository
				.findRawByComponentUuids(orgUuid.toString(), joinUuids(ids))) {
			try {
				out.put(row.getComponentUuid(), SupportData.parse(row.getPayload()));
			} catch (RuntimeException e) {
				// Excluded, not downgraded: a payload this build cannot read is NOT the same
				// fact as "never assessed" and must not be served as one. The row is left
				// untouched so a newer build can still read it.
				log.warn("Unreadable support attestation for sbom component {}; excluded from"
						+ " this read: {}", row.getComponentUuid(), e.getMessage(), e);
			}
		}
		return out;
	}

	/**
	 * No persisted per-(release, sbom_component) row exists since V37 —
	 * the synthesized view is built on demand. Returns empty for code paths
	 * that still call this lookup directly; the GraphQL surface is served
	 * via {@link #listReleaseSbomComponents(UUID)}.
	 */
	public Optional<ReleaseSbomComponent> getReleaseSbomComponent(UUID uuid) {
		return Optional.empty();
	}

	public record SbomComponentSearchQuery(String name, String version) {}

	public record ComponentPurlToSbom(String purl, List<UUID> sbomComponents) {}

	/**
	 * Org-wide search backing the "which releases ship this?" lookup. Each
	 * query term is resolved one of two ways:
	 *
	 * <ul>
	 * <li><b>Purl term</b> (starts with {@code pkg:}) -- resolved against
	 *     {@code canonical_purl}. A purl that pins a version resolves to that
	 *     one canonical row; a versionless purl resolves to every version of
	 *     the coordinate.</li>
	 * <li><b>Anything else</b> -- exact match on the component name, with the
	 *     optional version filter, as before.</li>
	 * </ul>
	 *
	 * <p>The purl arm exists because the search box has always advertised
	 * "Name or Purl" while only the name path was wired up: pasting
	 * {@code pkg:npm/lodash@4.17.21} matched nothing, since a purl is never
	 * equal to a bare {@code record_data->>'name'}.
	 *
	 * <p>A purl term is self-contained, so the separate {@code version}
	 * argument is ignored for it -- splicing a version into a purl that may
	 * already carry qualifiers produces malformed input more often than it
	 * helps. Pin the version inside the purl instead.
	 *
	 * <p><b>Purl is the only identifier scheme routed here.</b>
	 * {@code canonical_purl} also stores {@code cpe:} (and, via
	 * {@link #schemeOf}, other CDX identity schemes), but those are
	 * deliberately out of scope: a pasted CPE falls through to the name path
	 * and finds nothing. Adding one is a new arm on the scheme test plus its
	 * own canonicalization -- not a behaviour change to the purl arm.
	 */
	public List<ComponentPurlToSbom> searchSbomComponentsBatch(
			List<SbomComponentSearchQuery> queries, UUID orgUuid) {
		if (queries == null || queries.isEmpty() || orgUuid == null) return List.of();
		String orgUuidStr = orgUuid.toString();
		Map<String, Set<UUID>> byCanonical = new LinkedHashMap<>();
		for (SbomComponentSearchQuery q : queries) {
			if (q == null || q.name() == null || q.name().isBlank()) continue;
			String term = q.name().strip();
			List<SbomComponent> matches = Utils.isPurl(term)
					? searchByPurlTerm(term, orgUuid, orgUuidStr)
					: sbomComponentRepository
							.searchByOrgAndNameAndOptionalVersion(orgUuidStr, term, q.version());
			for (SbomComponent sc : matches) {
				byCanonical.computeIfAbsent(sc.getCanonicalPurl(), k -> new LinkedHashSet<>())
						.add(sc.getUuid());
			}
		}
		List<ComponentPurlToSbom> out = new ArrayList<>(byCanonical.size());
		for (Map.Entry<String, Set<UUID>> e : byCanonical.entrySet()) {
			out.add(new ComponentPurlToSbom(e.getKey(), new ArrayList<>(e.getValue())));
		}
		return out;
	}

	/**
	 * Resolves a purl search term. Version-pinned purls take the unique-index
	 * lookup; versionless ones fan out over the coordinate. An unparseable
	 * purl yields no matches rather than falling back to a name lookup -- a
	 * string starting with {@code pkg:} is never a package name, so a name
	 * match would only ever produce confusing noise.
	 */
	private List<SbomComponent> searchByPurlTerm(String term, UUID orgUuid, String orgUuidStr) {
		String base = Utils.purlCoordinateBase(term);
		if (base == null) return List.of();
		String pinnedVersion = Utils.purlVersion(term);
		if (pinnedVersion == null || pinnedVersion.isEmpty()) {
			return searchCoordinate(orgUuidStr, base);
		}
		String canonical = Utils.canonicalizePurl(term);
		if (canonical != null) {
			Optional<SbomComponent> exact = sbomComponentRepository
					.findByOrgAndCanonicalPurl(orgUuid, canonical);
			if (exact.isPresent()) return List.of(exact.get());
		}
		// The exact arm only hits when the pasted purl carries the same identity
		// qualifiers the stored canonical does. Advisories quote the bare
		// coordinate -- 'pkg:deb/debian/attr@1:2.5.2-3' against a stored
		// '...@1:2.5.2-3?distro=debian-13' -- so fall back to the coordinate and
		// match the version semantically. Comparing parsed versions rather than
		// raw strings also absorbs encoding drift between eras of writer (a
		// Debian epoch colon persists as both ':' and '%3A').
		return searchCoordinate(orgUuidStr, base).stream()
				.filter(sc -> pinnedVersion.equals(Utils.purlVersion(sc.getCanonicalPurl())))
				.toList();
	}

	private List<SbomComponent> searchCoordinate(String orgUuidStr, String base) {
		return sbomComponentRepository.searchByOrgAndCanonicalPurlCoordinate(
				orgUuidStr, base, Utils.escapeSqlLikeLiteral(base));
	}

	public UUID searchSbomComponentByPurl(String purl, UUID orgUuid) {
		if (orgUuid == null) return null;
		String canonical = Utils.canonicalizePurl(purl);
		if (canonical == null) return null;
		return sbomComponentRepository.findByOrgAndCanonicalPurl(orgUuid, canonical)
				.map(SbomComponent::getUuid)
				.orElse(null);
	}

	public boolean releaseHasSbomComponents(UUID releaseUuid, UUID orgUuid) {
		if (orgUuid == null || releaseUuid == null) return false;
		return releaseArtifactIndexRepository.existsByOrgAndReleaseUuid(orgUuid, releaseUuid);
	}

	/**
	 * Impact analysis: distinct release UUIDs that reference any canonical
	 * artifact whose BOM contains any of the given canonical sbom_components.
	 * Walks upward through {@code locateAllProductsOfRelease} so product
	 * releases bundling affected component releases also surface.
	 */
	public Set<UUID> findReleaseUuidsBySbomComponents(Collection<UUID> sbomComponentUuids, UUID orgUuid) {
		if (sbomComponentUuids == null || sbomComponentUuids.isEmpty() || orgUuid == null) return Set.of();
		List<UUID> directReleaseUuids = artifactSbomComponentRepository
				.findDistinctReleaseUuidsByOrgAndSbomComponentUuidIn(orgUuid, sbomComponentUuids);
		if (directReleaseUuids.isEmpty()) return Set.of();
		Set<UUID> all = new LinkedHashSet<>(directReleaseUuids);
		Set<UUID> productCircleBreaker = new HashSet<>();
		for (UUID seed : directReleaseUuids) {
			sharedReleaseService.getReleaseData(seed, orgUuid).ifPresent(rd -> {
				for (ReleaseData product : sharedReleaseService.locateAllProductsOfRelease(rd, productCircleBreaker, orgUuid)) {
					all.add(product.getUuid());
				}
			});
		}
		return all;
	}

	// ===================================================================
	// BOM artifact collection (unchanged from V25 — same set of sources)
	// ===================================================================

	private Set<UUID> collectBomArtifactUuids(ReleaseData rd) {
		Set<UUID> artifactUuids = new LinkedHashSet<>();

		List<UUID> deliverableUuids = new ArrayList<>();
		if (rd.getInboundDeliverables() != null) deliverableUuids.addAll(rd.getInboundDeliverables());
		variantService.findBaseVariantForRelease(rd.getUuid())
				.ifPresent(v -> deliverableUuids.addAll(v.getOutboundDeliverables()));
		for (DeliverableData dd : getDeliverableService.getDeliverableDataList(deliverableUuids)) {
			if (dd.getArtifacts() != null) artifactUuids.addAll(dd.getArtifacts());
		}

		if (rd.getSourceCodeEntry() != null) {
			getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry())
					.ifPresent(sce -> {
						if (sce.getArtifacts() != null) {
							sce.getArtifacts().stream()
									.filter(scea -> rd.getComponent().equals(scea.componentUuid()))
									.forEach(scea -> artifactUuids.add(scea.artifactUuid()));
						}
					});
		}
		if (rd.getArtifacts() != null) artifactUuids.addAll(rd.getArtifacts());
		return artifactUuids;
	}

	// ===================================================================
	// Canonical sbom_components upsert (unchanged from V25)
	// ===================================================================

	private Map<String, UUID> upsertSbomComponents(
			Collection<ComponentAggregation> aggs, UUID orgUuid) {
		List<String> canonicals = new ArrayList<>();
		for (ComponentAggregation agg : aggs) canonicals.add(agg.sample.canonicalPurl());

		Map<String, UUID> canonicalToUuid = new HashMap<>();
		Map<String, SbomComponent> existingByCanonical = new HashMap<>();
		for (SbomComponent sc :
				sbomComponentRepository.findByOrgAndCanonicalPurlIn(orgUuid.toString(), canonicals)) {
			existingByCanonical.put(sc.getCanonicalPurl(), sc);
			canonicalToUuid.put(sc.getCanonicalPurl(), sc.getUuid());
		}

		for (ComponentAggregation agg : aggs) {
			String canonical = agg.sample.canonicalPurl();
			SbomComponent existing = existingByCanonical.get(canonical);
			if (existing != null) {
				boolean changed = false;
				if (agg.isRoot && !isMarkedRoot(existing)) {
					Map<String, Object> rec = existing.getRecordData() != null
							? new HashMap<>(existing.getRecordData())
							: new HashMap<>();
					rec.put("isRoot", true);
					existing.setRecordData(rec);
					changed = true;
				}
				// Union any newly-asserted identities (e.g. a CPE a prior ingest
				// lacked); only write when the set actually grew.
				List<ComponentIdentity> mergedIds = mergeIdentities(
						existing.getIdentities(), buildIdentities(canonical, agg.cpes));
				if (mergedIds != null) {
					existing.setIdentities(mergedIds);
					changed = true;
					// This merge is the single place a late CPE lands, and a CPE
					// is exactly what rescues an UNMATCHABLE_PURL_TYPE row (NVD
					// matches CPEs regardless of purl type). Re-evaluate ONLY
					// that reason -- V75's genuinely-unrecoverable terminal
					// reasons stay terminal.
					if (rescuesUnmatchableTerminal(existing, mergedIds)) {
						existing.setFlowControl(null);
						log.info("Cleared UNMATCHABLE_PURL_TYPE terminal on {} (org {}): "
								+ "a later ingest asserted a CPE, so the row is NVD-matchable now",
								existing.getCanonicalPurl(), orgUuid);
					}
				}
				// Licenses: reconcile only fills when the row has none — it never
				// overwrites. The enrichment puller is the sole writer of enriched
				// licenses (alongside enriched_at), so a raw re-parse must not clobber
				// a value the puller already set.
				if (agg.getLicenses() != null && !agg.getLicenses().isEmpty()) {
					boolean empty = existing.getLicenses() == null || existing.getLicenses().isEmpty();
					if (empty) {
						existing.setLicenses(agg.getLicenses());
						changed = true;
					}
				}
				if (changed) {
					existing.setLastUpdatedDate(ZonedDateTime.now());
					try { sbomComponentRepository.save(existing); }
					catch (DataIntegrityViolationException ignored) {}
				}
				continue;
			}
			SbomComponent sc = buildSbomComponent(agg, orgUuid);
			try {
				sc = sbomComponentRepository.save(sc);
				canonicalToUuid.put(canonical, sc.getUuid());
			} catch (DataIntegrityViolationException dive) {
				sbomComponentRepository.findByOrgAndCanonicalPurl(orgUuid, canonical)
						.ifPresent(rec -> canonicalToUuid.put(canonical, rec.getUuid()));
			}
		}
		return canonicalToUuid;
	}

	private boolean isMarkedRoot(SbomComponent sc) {
		return sc.isRoot();
	}

	private SbomComponent buildSbomComponent(ComponentAggregation agg, UUID orgUuid) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(orgUuid);
		sc.setCanonicalPurl(agg.sample.canonicalPurl());
		SbomComponentData data = new SbomComponentData(
				agg.sample.type(), agg.sample.group(), agg.sample.name(),
				agg.sample.version(), agg.isRoot ? Boolean.TRUE : null);
		sc.setRecordData(data.toRecordMap());
		sc.setIdentities(buildIdentities(agg.sample.canonicalPurl(), agg.cpes));
		if (agg.getLicenses() != null && !agg.getLicenses().isEmpty()) {
			sc.setLicenses(agg.getLicenses());
		}
		stampTerminalIfUnmatchablePurlType(sc);
		return sc;
	}

	/** Terminal reason for purl types no vulnerability source can ever match. */
	static final String TERMINAL_REASON_UNMATCHABLE_PURL_TYPE = "UNMATCHABLE_PURL_TYPE";

	/**
	 * True for canonical purl types that are unmatchable BY CONSTRUCTION:
	 * {@code pkg:generic} has no upstream registry, so no vulnerability source
	 * (OSS Index / OSV / GHSA ecosystems, NVD-by-CPE) indexes it and BEAR has
	 * nothing to resolve it against. Filesystem-cataloguing scanners emit
	 * thousands of such per-file rows (pkg:generic/&lt;file&gt;?path=...); left
	 * matchable they clog the enrichment candidate window, gate fan-out
	 * coverage on components that can never yield a finding, and ship inert
	 * rows to Dependency-Track.
	 *
	 * <p>A CPE identity rescues the row: NVD matching works regardless of purl
	 * type, so a CPE-bearing generic component stays matchable.
	 */
	static boolean isUnmatchablePurlType(String canonicalPurl, List<ComponentIdentity> identities) {
		if (canonicalPurl == null || !canonicalPurl.startsWith("pkg:generic/")) return false;
		if (identities != null) {
			for (ComponentIdentity id : identities) {
				if (id != null && "cpe".equals(id.scheme())) return false;
			}
		}
		return true;
	}

	/**
	 * Same timestamp shape as the SQL writers of this field
	 * ({@code markEnrichmentTerminal}, V79: {@code to_char(now(),
	 * 'YYYY-MM-DD"T"HH24:MI:SSOF')} -> e.g. {@code 2026-08-12T21:20:43+00}):
	 * second precision, minimal offset. Nothing parses the value back, but two
	 * writers of one field should not disagree on its format.
	 */
	private static final java.time.format.DateTimeFormatter TERMINAL_TS_FORMAT =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssx");

	/**
	 * Mint-time twin of the V79 backfill: mark an unmatchable-by-construction
	 * row enrichment-terminal BEFORE first save, so it never enters the
	 * matchable universe (candidate window, buckets, coverage gate) at all.
	 * Same flow_control shape as {@code markEnrichmentTerminal}.
	 */
	static void stampTerminalIfUnmatchablePurlType(SbomComponent sc) {
		if (sc.isEnrichmentTerminal()) return;
		if (!isUnmatchablePurlType(sc.getCanonicalPurl(), sc.getIdentities())) return;
		sc.setFlowControl(new SbomComponentFlowControl(
				ZonedDateTime.now().format(TERMINAL_TS_FORMAT),
				TERMINAL_REASON_UNMATCHABLE_PURL_TYPE));
	}

	/**
	 * True when an identity union just made an {@code UNMATCHABLE_PURL_TYPE}
	 * terminal row matchable again -- i.e. the merged identities now carry a
	 * CPE, so the mint/backfill criterion no longer holds. Scoped to that one
	 * reason: every other terminal reason describes an unrecoverable dead end
	 * (own BOM pulled unmatched / unpullable), which a new identity cannot fix.
	 */
	static boolean rescuesUnmatchableTerminal(SbomComponent sc, List<ComponentIdentity> mergedIds) {
		return sc.getFlowControl() != null
				&& TERMINAL_REASON_UNMATCHABLE_PURL_TYPE.equals(sc.getFlowControl().enrichmentTerminalReason())
				&& !isUnmatchablePurlType(sc.getCanonicalPurl(), mergedIds);
	}

	/**
	 * Assemble the flat {scheme,value} identity union for a canonical component:
	 * the canonical primary identity (its scheme inferred from the prefix) plus
	 * every distinct CPE. De-duped by {scheme,value}, insertion-ordered so the
	 * primary stays first. Synthesised backend-side for now; will be replaced by
	 * rebom's own identities array (see Rebom.ParsedBomComponent TODO).
	 */
	private static List<ComponentIdentity> buildIdentities(
			String canonicalPurl, java.util.Collection<String> cpes) {
		List<ComponentIdentity> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		if (canonicalPurl != null && !canonicalPurl.isBlank()) {
			addIdentity(out, seen, schemeOf(canonicalPurl), canonicalPurl);
		}
		if (cpes != null) {
			for (String cpe : cpes) {
				if (cpe != null && !cpe.isBlank()) addIdentity(out, seen, "cpe", cpe);
			}
		}
		return out;
	}

	private static void addIdentity(List<ComponentIdentity> out,
			java.util.Set<String> seen, String scheme, String value) {
		String key = scheme + '\u0001' + value;
		if (!seen.add(key)) return;
		out.add(new ComponentIdentity(scheme, value));
	}

	/** Infer the identity scheme from a self-namespacing canonical identity. */
	private static String schemeOf(String canonical) {
		if (canonical.startsWith("pkg:")) return "purl";
		if (canonical.startsWith("cpe:")) return "cpe";
		if (canonical.startsWith("swid:")) return "swid";
		if (canonical.startsWith("swhid:")) return "swhid";
		if (canonical.startsWith("gitoid:")) return "omniborid";
		if (canonical.startsWith("cdx:")) return "cdx";
		return "purl";
	}

	/**
	 * Union {@code incoming} identity entries into {@code existing} (de-dupe by
	 * {scheme,value}), returning the merged list when it grew, or null when no
	 * new identity was added (so callers can skip a no-op write).
	 */
	private static List<ComponentIdentity> mergeIdentities(
			List<ComponentIdentity> existing, List<ComponentIdentity> incoming) {
		if (incoming == null || incoming.isEmpty()) return null;
		List<ComponentIdentity> merged = existing != null
				? new ArrayList<>(existing) : new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (ComponentIdentity e : merged) {
			seen.add(e.scheme() + '\u0001' + e.value());
		}
		boolean changed = false;
		for (ComponentIdentity e : incoming) {
			String key = e.scheme() + '\u0001' + e.value();
			if (seen.add(key)) { merged.add(e); changed = true; }
		}
		return changed ? merged : null;
	}

	/**
	 * Render the parents JSONB for one target component within one BOM:
	 * one entry per (source canonical, relationshipType). The
	 * declaringArtifacts wrapper from the prior per-release shape is gone
	 * — the row's {@code canonical_artifact_uuid} field IS the declaring
	 * artifact. Source canonical UUIDs are resolved from the canonical→uuid
	 * map; entries whose source can't be resolved are dropped.
	 */
	private List<Map<String, Object>> renderParents(
			Map<ParentKey, ParentEdge> edges,
			Map<String, UUID> canonicalToUuid) {
		if (edges == null || edges.isEmpty()) return new ArrayList<>();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<ParentKey, ParentEdge> e : edges.entrySet()) {
			UUID sourceUuid = canonicalToUuid.get(e.getKey().sourceCanonical);
			if (sourceUuid == null) continue;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sourceSbomComponentUuid", sourceUuid.toString());
			entry.put("sourceCanonicalPurl", e.getKey().sourceCanonical);
			entry.put("relationshipType", e.getKey().relationshipType);
			entry.put("sourceExactPurl", e.getValue().sourceFullPurl);
			entry.put("targetExactPurl", e.getValue().targetFullPurl);
			out.add(entry);
		}
		out.sort((a, b) -> {
			int bySource = String.valueOf(a.get("sourceCanonicalPurl"))
					.compareTo(String.valueOf(b.get("sourceCanonicalPurl")));
			if (bySource != 0) return bySource;
			return String.valueOf(a.get("relationshipType"))
					.compareTo(String.valueOf(b.get("relationshipType")));
		});
		return out;
	}

	private static String relationshipType(ParsedBomDependency pd) {
		String raw = pd.relationshipType();
		if (raw == null || raw.isBlank()) return "DEPENDS_ON";
		return raw.toUpperCase();
	}

	// ===================================================================
	// Aggregation buckets (per artifact)
	// ===================================================================

	private static final class ComponentAggregation {
		final ParsedBomComponent sample;
		boolean isRoot;
		private String exactPurl;
		// Union of distinct CPE coordinates seen for this canonical across the
		// BOM's components (NVD aliases / divergent assertions). Insertion-ordered
		// so the first-seen CPE stays the primary on the synthetic component.
		final java.util.LinkedHashSet<String> cpes = new java.util.LinkedHashSet<>();
		// First non-empty declared licenses (exact CycloneDX shape), carried
		// transiently for re-emission to Dependency-Track.
		private List<Map<String, Object>> licenses;

		ComponentAggregation(ParsedBomComponent sample) {
			this.sample = sample;
			this.isRoot = Boolean.TRUE.equals(sample.isRoot());
			mergeSample(sample);
		}

		void mergeSample(ParsedBomComponent other) {
			if (Boolean.TRUE.equals(other.isRoot())) this.isRoot = true;
			if (other.cpe() != null && !other.cpe().isBlank()) this.cpes.add(other.cpe());
			if ((this.licenses == null || this.licenses.isEmpty())
					&& other.licenses() != null && !other.licenses().isEmpty()) {
				this.licenses = other.licenses();
			}
		}

		void setExactPurl(String purl) {
			if (this.exactPurl == null || purl != null) this.exactPurl = purl;
		}

		String getExactPurl() { return exactPurl; }

		List<Map<String, Object>> getLicenses() { return licenses; }
	}

	private record ParentKey(String sourceCanonical, String relationshipType) {}

	private record ParentEdge(String sourceFullPurl, String targetFullPurl) {}

	/**
	 * Manufacturer (MANUAL) attestation of a component's support level and milestone dates.
	 * The only public write path; SUPPLIER / ENRICHED writers in later slices call
	 * {@link #applySupport} with their own source so they cannot drift on the merge, save
	 * and audit semantics. Runs in one tx.
	 *
	 * @param assertedBy the authenticated user making the attestation
	 *
	 * <p>Writes a null batch id: a single-component attestation is not a sweep, and recording
	 * a batch of one would destroy the distinction the column exists to draw.
	 */
	@Transactional
	public SbomComponentSupport setSbomComponentSupport(UUID sbomComponentUuid,
			SupportAttestationRequest request, UUID assertedBy) {
		return applySupport(sbomComponentUuid, request, SupportSource.MANUAL, assertedBy, null);
	}

	/**
	 * Shared write core for every support provenance. Loads the component's current
	 * attestation (if any), MERGES the staged milestone dates onto it, validates the
	 * ordering across the EFFECTIVE dates -- the value staged this call where supplied, else
	 * whatever is already stored for a milestone this call does not touch -- and appends the
	 * whole after-image to the audit history. A partial edit can therefore never leave an
	 * incoherent combination on record.
	 *
	 * <p>The attestation lives in its own row, so a concurrent BOM reconcile CANNOT conflict
	 * with it: reconcile only ever touches {@code sbom_components}. The optimistic-lock retry
	 * that the caller still performs now guards one thing only -- two attestation writes to
	 * the SAME component racing each other.
	 *
	 * <p>Runs in the caller's tx.
	 *
	 * @param batchId the sweep this write belongs to, or null when it is not part of one. Only
	 *        reaches the audit row; it is never part of the attestation itself, so it cannot
	 *        appear in an exported BOM.
	 */
	private SbomComponentSupport applySupport(UUID sbomComponentUuid,
			SupportAttestationRequest request, SupportSource source, UUID assertedBy,
			UUID batchId) {
		SbomComponent sc = sbomComponentRepository.findById(sbomComponentUuid)
				.orElseThrow(() -> new IllegalArgumentException(
						"sbom component not found: " + sbomComponentUuid));

		SbomComponentSupport row = sbomComponentSupportRepository
				.findBySbomComponentUuid(sbomComponentUuid)
				.orElseGet(() -> {
					SbomComponentSupport fresh = new SbomComponentSupport();
					fresh.setSbomComponentUuid(sbomComponentUuid);
					fresh.setOrg(sc.getOrg());
					return fresh;
				});
		SupportData existing = row.getSupportData();

		// A FRESH row must carry at least one substantive fact. Without this, an entirely
		// empty request creates an attestation -- assessmentSource=MANUAL, assessedAt=now --
		// which counts toward coverage and which the injector exports as an assessedAt
		// disclosure. A caller who sent nothing would have a record asserting a human
		// assessed the component. That is the same conjured assessment the
		// cannot-clear-an-unset-milestone guard prevents, reached through another door.
		//
		// JUSTIFICATION ALONE IS SUFFICIENT AND MUST STAY SO. "I looked, upstream publishes
		// nothing" is the case this whole storage shape exists for, and the tempting
		// shorthand -- require a level or a date -- rejects exactly it. The paired test
		// asserts BOTH directions for that reason.
		//
		// Scoped to fresh rows only: a partial write against an EXISTING attestation is the
		// PATCH contract and must keep working with any subset of fields, including none.
		if (null == existing
				&& null == request.levelOfSupport()
				&& null == request.endOfGuaranteedSupportDate()
				&& null == request.endOfSupportDate()
				&& null == request.endOfLifeDate()
				&& (null == request.justification() || request.justification().isBlank())) {
			throw new IllegalArgumentException(
					"a first attestation must record something: supply a levelOfSupport, a"
							+ " milestone date, or a justification");
		}
		Map<SupportMilestoneType, SupportMilestoneFact> existingByType =
				existing == null ? Map.of() : existing.milestones();

		Map<SupportMilestoneType, LocalDate> staged = new EnumMap<>(SupportMilestoneType.class);
		if (request.endOfGuaranteedSupportDate() != null) {
			staged.put(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT, request.endOfGuaranteedSupportDate());
		}
		if (request.endOfSupportDate() != null) {
			staged.put(SupportMilestoneType.END_OF_SUPPORT, request.endOfSupportDate());
		}
		if (request.endOfLifeDate() != null) {
			staged.put(SupportMilestoneType.END_OF_LIFE, request.endOfLifeDate());
		}
		// A CHANGED level must not INHERIT the previous claim's basis. Scoped to exactly that:
		// only when a level already exists, changes, and a basis is on the row that would
		// otherwise carry over. A FIRST attestation needs no such guard -- there is nothing to
		// inherit -- and requiring one there would be over-strict for a positive claim, which
		// FDA asks no basis for.
		//
		// A justification is the basis for one specific claim, so a changed claim invalidates
		// it. Gating this on the NEW level needing a justification was not enough: flipping
		// ABANDONED -> ACTIVELY_MAINTAINED skipped both checks, the old text survived by
		// supplied-wins merge, and the served BOM then carried "actively maintained" next to
		// "upstream archived the repository". This PR is what made that visible -- before it,
		// the field never left the database.
		boolean levelChanges = null != request.levelOfSupport() && null != existing
				&& request.levelOfSupport() != existing.levelOfSupport();
		boolean wouldInheritABasis = null != existing && null != existing.justification()
				&& !existing.justification().isBlank();
		if (levelChanges && wouldInheritABasis && null == request.justification()) {
			throw new IllegalArgumentException(
					"changing levelOfSupport to " + request.levelOfSupport()
							+ " requires a justification, or an empty one to clear the previous"
							+ " basis: a basis belongs to the claim it was written for");
		}
		if (!request.clearMilestones().isEmpty()
				&& (null == request.reason() || request.reason().isBlank())) {
			// The audit keeps after-images only, so "milestone X was removed" is recoverable
			// only by diffing consecutive rows -- and nothing there says WHY. Requiring the
			// reason at the point of removal is what makes a deletion from a regulatory
			// record attributable rather than merely traceable.
			//
			// It goes on the AUDIT ROW, not into support_data.justification. Those are
			// different facts, and an earlier revision conflated them: the removal note
			// overwrote the basis for any negative level attestation on the same row, which
			// PR 3 is about to start exporting.
			throw new IllegalArgumentException(
					"reason is required when clearing a milestone");
		}
		// Setting and clearing the same milestone in one call is a caller bug, not something
		// to resolve by precedence -- silently honouring one of them writes a regulatory fact
		// the caller did not unambiguously ask for. Clearing something that is not set is
		// rejected for a sharper reason: it would take the fresh-row branch below and CREATE
		// an attestation, stamping assessmentSource=MANUAL and assessedAt=now, which then
		// counts toward coverage and exports as "a human looked" -- an assessment conjured
		// out of a deletion nobody could have performed.
		for (SupportMilestoneType clear : request.clearMilestones()) {
			if (staged.containsKey(clear)) {
				throw new IllegalArgumentException(
						"cannot set and clear the same milestone in one call: " + clear);
			}
			if (!existingByType.containsKey(clear)) {
				throw new IllegalArgumentException(
						"cannot clear a milestone that is not set: " + clear);
			}
		}

		// Validate against the dates that will actually be STORED -- staged where supplied,
		// existing where untouched, and absent where cleared. Validating before the clears
		// were applied would let a bad date block its own removal.
		Map<SupportMilestoneType, SupportMilestoneFact> effectiveExisting =
				new EnumMap<>(SupportMilestoneType.class);
		effectiveExisting.putAll(existingByType);
		effectiveExisting.keySet().removeAll(request.clearMilestones());
		validateSupportOrdering(
				effectiveDate(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT, staged, effectiveExisting),
				effectiveDate(SupportMilestoneType.END_OF_SUPPORT, staged, effectiveExisting));

		ZonedDateTime now = ZonedDateTime.now();
		// CALLER-SUPPLIED assessment instant. "I read the vendor advisory on 12 August and
		// recorded it on 30 August" has to be expressible: forcing these equal makes ALCOA
		// Contemporaneous unsatisfiable. The system's own record time is the audit row's
		// assertedDate, written below from `now`.
		//
		// assessedAt DATES THE LEVEL-OF-SUPPORT CLAIM, so it is PRESERVED unless the caller
		// either supplies one or re-asserts the level. An earlier revision re-stamped it on
		// every write while preserving the level itself, so editing an unrelated date silently
		// moved a months-old "abandoned" attestation to today -- a claim nobody re-assessed,
		// now carrying a fresh date. A falsely dated claim is worse than an undated one: P2's
		// whole staleness answer is that a reviewer can judge a claim's age, which requires
		// the date to be true. Per-milestone lastAssessed is a different fact and DOES move,
		// because a staged date genuinely was assessed on this call.
		// TWO DIFFERENT INSTANTS. Conflating them is what this fix is about.
		//
		// thisAssessment: when THIS call's assessment happened. Every milestone staged by
		// this call gets it, because a date supplied now genuinely was assessed now.
		// Passing the preserved record-level value here instead would stamp a brand-new
		// milestone with a months-old assessment date -- asserting a human assessed a fact
		// before that fact existed, which is the same fabrication as a re-stamped level.
		String thisAssessment = utcInstant(null != request.assessedAt() ? request.assessedAt() : now);
		//
		// assessedAt: when the LEVEL-OF-SUPPORT claim was assessed. Preserved only while
		// there IS a level and this call does not re-assert it. On a row with no level the
		// field dates the assessment generally -- and the injector now publishes it as a
		// standalone disclosure -- so freezing it there would publish a stale date forever.
		String assessedAt;
		if (null != request.assessedAt()
				|| null == existing || null == existing.assessedAt()
				|| null == existing.levelOfSupport() || null != request.levelOfSupport()) {
			assessedAt = thisAssessment;
		} else {
			assessedAt = existing.assessedAt();
		}

		Map<SupportMilestoneType, SupportMilestoneFact> merged =
				new EnumMap<>(SupportMilestoneType.class);
		merged.putAll(existingByType);
		merged.keySet().removeAll(request.clearMilestones());
		for (Map.Entry<SupportMilestoneType, LocalDate> entry : staged.entrySet()) {
			// Omitted notes leave the existing note in place, like every other omitted
			// field. Correcting a date must not silently delete the text that justified
			// it -- that text is often the only evidence of what the assessor checked.
			SupportMilestoneFact prior = existingByType.get(entry.getKey());
			String notes = request.supportNotes() != null ? request.supportNotes()
					: (prior == null ? null : prior.notes());
			merged.put(entry.getKey(), new SupportMilestoneFact(
					entry.getValue().toString(), source, thisAssessment, assertedBy, notes));
		}

		SupportData data = new SupportData(
				request.levelOfSupport() != null || existing == null
						? request.levelOfSupport() : existing.levelOfSupport(),
				// STATE IS PRESERVED, NOT DEFAULTED, when the caller omits it -- same rule
				// as every other field here. Hard-defaulting to ATTESTED meant any later
				// partial write (correcting a date, a future SUPPLIER/ENRICHED writer)
				// silently un-retracted a WITHDRAWN attestation and republished a claim
				// the manufacturer had taken back into every exported BOM.
				request.state() != null ? request.state()
						: (existing == null ? SupportState.ATTESTED : existing.state()),
				request.party() != null || existing == null ? request.party() : existing.party(),
				// NOTE for whoever adds the first non-MANUAL writer: this overwrites the
				// record-level assessment source unconditionally. An ENRICHED touch on a
				// human-attested "nothing published" row would erase the only evidence a
				// human attested it, and drop it out of countAttestedNonRootByOrg. Decide
				// the precedence rule there; today MANUAL is the only writer.
				source,
				assessedAt,
				assertedBy,
				// justification is the level-of-support basis ONLY. A milestone removal's
				// reason goes on the audit row, so a clear can no longer overwrite it.
				// Supplied-wins, with blank normalised to null so an explicit clear does not
				// store whitespace that would later read as a basis.
				request.justification() != null || existing == null
						? blankToNull(request.justification()) : existing.justification(),
				merged);

		// INVARIANT ON THE STORED RESULT, not on the request. A negative claim about a named
		// third party's project must carry its basis: that is the substantiation record, the
		// per-component corroboration of the 7f narrative, and the only thing separating a
		// considered judgement from "no commits lately". FDA defines neither term.
		//
		// Checking the REQUEST was not enough, and failed in the direction that matters. A
		// partial write omitting levelOfSupport but sending justification:"" passed the
		// request check, then the merge rule (supplied-wins) blanked the stored justification
		// on an ABANDONED row -- leaving exactly the unsupported negative claim the rule
		// exists to prevent. Validate what will actually be persisted.
		if (null != data.levelOfSupport() && data.levelOfSupport().requiresJustification()
				&& (null == data.justification() || data.justification().isBlank())) {
			throw new IllegalArgumentException(
					"justification is required for levelOfSupport " + data.levelOfSupport());
		}

		row.setCanonicalPurl(sc.getCanonicalPurl());
		row.setSupportData(data);
		row.setLastUpdatedDate(now);
		SbomComponentSupport saved = sbomComponentSupportRepository.save(row);
		// Flush now so a concurrent write's conflict surfaces here rather than at commit.
		// TWO different conflicts are possible and both must reach the caller's retry as
		// the SAME type, because the caller cannot sensibly distinguish them:
		//
		//   * UPDATE of an existing attestation -> @Version optimistic lock failure. A
		//     mid-@Service flush throws the untranslated jakarta OptimisticLockException.
		//   * INSERT of the FIRST attestation for a component, raced by another writer ->
		//     @Version cannot help (there is no prior row); both callers take the
		//     orElseGet branch above and insert with distinct random PKs, and the
		//     sbom_component_support_component_unique constraint rejects the loser.
		//
		// The second case used to escape as a raw Hibernate ConstraintViolationException:
		// Spring's exception translation does not run here because the flush is on the
		// directly-injected EntityManager rather than a repository proxy. Two users filing
		// the first attestation on one component at the same time saw a raw database error.
		try {
			entityManager.flush();
		} catch (OptimisticLockException ole) {
			throw new ObjectOptimisticLockingFailureException(
					SbomComponentSupport.class, sbomComponentUuid, ole);
		} catch (PersistenceException pe) {
			if (isConcurrentFirstAttestation(pe)) {
				throw new ObjectOptimisticLockingFailureException(
						SbomComponentSupport.class, sbomComponentUuid, pe);
			}
			throw pe;
		}
		writeSupportAudit(saved, assertedBy, now, request.reason(), batchId);
		return saved;
	}

	/**
	 * Is this flush failure a lost race to insert the FIRST attestation for a component,
	 * as opposed to any other integrity violation? Matched on the named constraint rather
	 * than on the exception type alone, because a flush covers the whole persistence
	 * context: with open-in-view the caller may have other pending work, and treating an
	 * unrelated violation as retryable would silently retry something that can never
	 * succeed.
	 */
	private static boolean isConcurrentFirstAttestation(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof ConstraintViolationException cve
					&& null != cve.getConstraintName()
					&& cve.getConstraintName().contains("sbom_component_support_component_unique")) {
				return true;
			}
			if (c.getCause() == c) break;
		}
		return false;
	}

	private static String blankToNull(String value) {
		return (null == value || value.isBlank()) ? null : value;
	}

	/**
	 * UUIDs as one comma-joined parameter for the {@code = ANY(string_to_array(...))} queries.
	 * See those queries for why an {@code IN} list is not used: it binds one parameter per
	 * element against a 65,535 protocol cap that a large release reaches.
	 */
	/**
	 * Public because the uuid[] cast convention now has more than one caller, and a second
	 * open-coded copy of the join is a silent must-stay-in-step pair with the SQL that parses
	 * it. See the array-bound queries in SbomComponentSupportRepository for why the ids
	 * travel as one parameter.
	 */
	public static String joinUuids(Collection<UUID> uuids) {
		return uuids.stream().map(UUID::toString).collect(Collectors.joining(","));
	}

	/** RFC-3339 UTC instant. Never ZonedDateTime.toString(), which leaks a zone id. */
	private static String utcInstant(ZonedDateTime zdt) {
		return zdt.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
	}

	private static LocalDate effectiveDate(SupportMilestoneType type,
			Map<SupportMilestoneType, LocalDate> staged,
			Map<SupportMilestoneType, SupportMilestoneFact> existing) {
		if (staged.containsKey(type)) {
			return staged.get(type);
		}
		SupportMilestoneFact f = existing.get(type);
		return f == null ? null : f.dateValue();
	}

	/**
	 * EOGS &lt;= EOS, nulls skipped -- across the EFFECTIVE dates.
	 *
	 * <p><b>END-OF-LIFE IS NOT ORDERED AGAINST EITHER</b> (operator decision D5,
	 * 2026-09-03). CycloneDX defines end-of-life as END OF SALE, which routinely precedes
	 * end of support by years, so the old EOS &lt;= EOL rule was not merely unnecessary --
	 * it REJECTED conformant data. A supplier BOM whose milestones follow the published
	 * CycloneDX definitions was refused on ingest, and the SUPPLIER writer slice calls this
	 * same path, so the guard would have blocked it outright.
	 */
	private static void validateSupportOrdering(LocalDate eogs, LocalDate eos) {
		if (eogs != null && eos != null && eogs.isAfter(eos)) {
			throw new IllegalArgumentException("endOfGuaranteedSupportDate must not be after endOfSupportDate");
		}
	}

	/**
	 * Append the whole after-image to the history table. {@code assertedDate} is the SYSTEM's
	 * record of when this write happened, deliberately distinct from
	 * {@code supportData.assessedAt} (when the human assessed). {@code reason} is why the
	 * write happened, deliberately distinct from {@code supportData.justification} (the basis
	 * for the level-of-support claim) -- see {@link SupportAttestationRequest}.
	 * {@code batchId} correlates the rows of one sweep and is null for a single-component
	 * write. All three are audit-only: none reaches {@code support_data}, which is the
	 * after-image an export ships.
	 */
	private void writeSupportAudit(SbomComponentSupport row, UUID assertedBy,
			ZonedDateTime assertedDate, String reason, UUID batchId) {
		SbomComponentSupportAudit audit = new SbomComponentSupportAudit();
		audit.setSbomComponentUuid(row.getSbomComponentUuid());
		audit.setOrg(row.getOrg());
		audit.setSupportRevision(row.getRevision());
		audit.setSupportData(row.getSupportData());
		audit.setAssertedBy(assertedBy);
		audit.setAssertedDate(assertedDate);
		audit.setReason(reason);
		audit.setBatchId(batchId);
		sbomComponentSupportAuditRepository.save(audit);
	}

	/**
	 * Support-disclosure coverage for an org: how many non-root components carry a
	 * support attestation, of the total. A mostly-unattested export is an
	 * incomplete disclosure, so this feeds a pre-submission readiness signal.
	 */
	public SupportCoverage getSupportCoverage(UUID orgUuid) {
		return getSupportCoverage(orgUuid, null);
	}

	/**
	 * Support-disclosure coverage, org-wide when {@code releaseUuid} is null and scoped to one
	 * release's components when it is not. Both scopes are intended and both have a consumer:
	 * org-wide is the readiness dashboard; release-scoped is what the export gauge needs,
	 * because the number has to describe the BOM actually being served.
	 *
	 * <p>The release scope resolves its component set through
	 * {@link #findReleaseComponentUuids}, the SAME path {@code listReleaseSbomComponents}
	 * walks -- including the PRODUCT dependency unwind. Deriving it any other way would let
	 * the gauge disagree with the component list directly beneath it, which is the in-step
	 * failure this feature has already hit twice by other routes.
	 *
	 * <p>An empty release yields 0/0 rather than falling back to org scope: a release with no
	 * components has no coverage to report, and answering with the org's number would be
	 * confidently wrong.
	 */
	public SupportCoverage getSupportCoverage(UUID orgUuid, UUID releaseUuid) {
		if (null == releaseUuid) {
			long total = sbomComponentRepository.countNonRootByOrg(orgUuid.toString());
			long attested = sbomComponentSupportRepository.countAttestedNonRootByOrg(
					orgUuid.toString(),
					names(LevelOfSupport.values()), names(SupportParty.values()),
					names(SupportState.values()), names(SupportSource.values()),
					names(SupportMilestoneType.values()));
			return new SupportCoverage(total, attested);
		}
		Set<UUID> componentUuids = findReleaseComponentUuids(releaseUuid);
		if (componentUuids.isEmpty()) return new SupportCoverage(0, 0);
		// Joined into ONE parameter rather than an IN list: a PRODUCT unwind over a large
		// release can reach thousands of components, and the Postgres JDBC protocol caps a
		// statement at 65,535 bound parameters. Safe as text because these are UUIDs.
		String ids = joinUuids(componentUuids);
		long total = sbomComponentRepository.countNonRootByOrgAndComponentUuidIn(
				orgUuid.toString(), ids);
		long attested = sbomComponentSupportRepository.countAttestedNonRootByOrgAndComponentUuidIn(
				orgUuid.toString(), ids,
				names(LevelOfSupport.values()), names(SupportParty.values()),
				names(SupportState.values()), names(SupportSource.values()),
				names(SupportMilestoneType.values()));
		return new SupportCoverage(total, attested);
	}

	/**
	 * One filtered, ordered page of a release's non-root component ids.
	 *
	 * <p>Ids, deliberately. The caller hydrates only what the page contains: the merged
	 * release rows carry uuids and edges, and everything a table shows -- purl, name,
	 * version, level of support, milestone dates -- comes from two further loads keyed by
	 * component id. Filtering and slicing after those loads would mean paying the whole
	 * BOM's cost to display fifty rows, which is the cost pagination exists to avoid.
	 *
	 * <p>The release scope resolves through {@link #findReleaseComponentUuids}, the same path
	 * the unpaged list and the coverage gauge walk, PRODUCT unwind included. The gauge stays
	 * RELEASE-scoped and is not recomputed per page: "34 of 1,240 disclosed" describes the
	 * BOM being served, and a page-scoped version of it would change as the operator clicked
	 * through pages while the BOM it describes did not.
	 *
	 * @param limit page size; clamped to the range 1..{@value #RELEASE_PAGE_MAX_LIMIT}
	 * @param after uuid of the last item seen, or null for the first page; an unrecognised
	 *        cursor is rejected rather than restarting the walk
	 */
	public SbomComponentPage listReleaseSbomComponentPage(UUID orgUuid, UUID releaseUuid,
			SupportAttestationFilter attestation, String search, int limit, UUID after)
			throws RelizaException {
		int lim = Math.min(Math.max(limit, 1), RELEASE_PAGE_MAX_LIMIT);
		SupportAttestationFilter filter =
				null == attestation ? SupportAttestationFilter.ALL : attestation;

		// The cursor's sort position. Looked up rather than carried on the wire so the cursor
		// stays an opaque id: encoding the purl into it would publish a second, redundant
		// copy of the sort key that a client could hand back inconsistently.
		//
		// A cursor that does not resolve is REJECTED, not treated as "start from the
		// beginning". Silently restarting is how a "select all unattested" walk becomes an
		// infinite loop that re-attests the first page forever. Note the component only has
		// to still EXIST -- it does not have to still match the filter, which is the whole
		// point: under UNATTESTED the caller has just attested it out of the set, and the
		// cursor must still know where it sat.
		//
		// RelizaException, not IllegalArgumentException: the latter has no handler and lands
		// in handleGeneric, which returns "Internal server error" and logs an ERROR stack
		// trace. A stale cursor is a ROUTINE event -- leave the tab open while the SBOM is
		// re-uploaded and the old canonical row is reaped -- so that would be both alert
		// noise and useless to the client. "Never a silent restart" only helps if the caller
		// can TELL a stale cursor from an outage and decide to restart the walk itself.
		//
		// Validated BEFORE the empty-release short-circuit below, so "a bad cursor always
		// errors" holds on every release rather than every non-empty one.
		String afterPurl = null;
		if (null != after) {
			SbomComponent anchor = sbomComponentRepository.findById(after)
					.filter(c -> orgUuid.equals(c.getOrg()))
					.orElseThrow(() -> new RelizaException(
							"unknown pagination cursor: " + after));
			afterPurl = anchor.getCanonicalPurl();
		}

		// Resolved ONCE here and handed back on the page, rather than via
		// findReleaseComponentUuids, so the caller's follow-up merge does not resolve the
		// release a second time. See SbomComponentPage#resolvedArtifactRows.
		List<ArtifactSbomComponent> artifactRows = resolveReleaseArtifactComponents(releaseUuid);
		Set<UUID> componentUuids = new LinkedHashSet<>();
		for (ArtifactSbomComponent r : artifactRows) componentUuids.add(r.getSbomComponentUuid());
		if (componentUuids.isEmpty()) return SbomComponentPage.empty(lim);
		String ids = joinUuids(componentUuids);

		// Wrapped here rather than at the wire so the caller never has to know the match is a
		// LIKE, and so a search containing % or _ cannot silently widen the match.
		String searchLike = (null == search || search.isBlank())
				? null
				: "%" + search.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";

		String code = sqlCode(filter);
		long total = sbomComponentRepository.countReleaseComponentPage(
				orgUuid.toString(), ids, searchLike, code,
				names(LevelOfSupport.values()), names(SupportParty.values()),
				names(SupportState.values()), names(SupportSource.values()),
				names(SupportMilestoneType.values()));
		// One more than the page, to learn whether another page exists without a second
		// query -- and without comparing against totalCount, which under UNATTESTED describes
		// a population that changes as the caller writes.
		List<UUID> fetched = sbomComponentRepository.findReleaseComponentPage(
				orgUuid.toString(), ids, searchLike, code,
				names(LevelOfSupport.values()), names(SupportParty.values()),
				names(SupportState.values()), names(SupportSource.values()),
				names(SupportMilestoneType.values()), afterPurl, after, lim + 1);
		boolean hasMore = fetched.size() > lim;
		List<UUID> page = hasMore ? List.copyOf(fetched.subList(0, lim)) : List.copyOf(fetched);
		UUID endCursor = page.isEmpty() ? null : page.get(page.size() - 1);
		return new SbomComponentPage(page, total, lim, endCursor, hasMore, artifactRows);
	}

	/**
	 * The SQL literal for a filter value.
	 *
	 * <p>A switch EXPRESSION with no default, deliberately. The page SQL branches on three
	 * literals and has no ELSE, so a fourth enum constant added without a matching SQL branch
	 * would make every branch false: an empty page and a zero total, with no error and no log,
	 * indistinguishable from "nothing matched". Exhaustiveness moves that from a silent
	 * runtime wrong answer to a compile error at the moment the constant is added.
	 */
	private static String sqlCode(SupportAttestationFilter f) {
		return switch (f) {
			case ALL -> SupportAttestationFilter.CODE_ALL;
			case ATTESTED -> SupportAttestationFilter.CODE_ATTESTED;
			case UNATTESTED -> SupportAttestationFilter.CODE_UNATTESTED;
		};
	}

	/**
	 * Page-size ceiling. A caller asking for the whole BOM in one page is asking for the
	 * unpaged query, which still exists for the graph view; this bound keeps a mistyped
	 * limit from turning the paged endpoint back into the thing it replaced.
	 */
	public static final int RELEASE_PAGE_MAX_LIMIT = 500;

	/**
	 * Most components one bulk support attestation may carry.
	 *
	 * <p>Public and enforced HERE, next to {@link #RELEASE_PAGE_MAX_LIMIT}, for the same two
	 * reasons that one is: a caller that is not the GraphQL resolver is bounded too, and a
	 * test can assert against the constant instead of retyping the number.
	 *
	 * <p>What it bounds is amplification, not volume. {@code RateLimitingFilter} charges one
	 * token per REQUEST, not per item, so an unbounded call buys N components of work -- each
	 * in its own REQUIRES_NEW transaction -- for the price of a one-component call, and holds
	 * a connection long enough to starve the pool for the whole tenant. Per-item rate limiting
	 * is deliberately not the answer: the amplification factor is the problem, so bound it.
	 *
	 * <p>Not the client's 5,000-id walk cap, which is ergonomics on a UI that this mutation is
	 * directly callable around. Our client batches at 200, so it never comes near this.
	 */
	public static final int BULK_SUPPORT_MAX_IDS = 1000;

	/**
	 * The names this build understands for an enum, for the coverage query's validity
	 * predicates. Derived rather than hardcoded so the SQL cannot drift from the Java.
	 */
	private static List<String> names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).toList();
	}

	/**
	 * Attest many components in one pass, reporting PER COMPONENT rather than as a count.
	 *
	 * <p>Three rules, carried from the deleted bulk-accept path:
	 * <ul>
	 *   <li>ROOT components are skipped -- the application itself is not a third-party
	 *       dependency to attest, and it is excluded from the coverage denominator too.</li>
	 *   <li>Already-attested components are SKIPPED, never overwritten. A bulk sweep must not
	 *       flatten a considered per-component judgement someone made earlier.</li>
	 *   <li>Therefore idempotent: a second run over the same set applies nothing.</li>
	 * </ul>
	 *
	 * <p>Each component is written in its OWN transaction, so a rejection -- a negative level
	 * with no justification, say -- fails that component and no other. A single shared
	 * transaction would mark itself rollback-only on the first failure and discard the
	 * successful writes at commit, which is the opposite of skip-and-report.
	 */
	public List<SupportBulkResult> bulkSetSbomComponentSupport(UUID orgUuid,
			Collection<UUID> sbomComponentUuids, SupportAttestationRequest request, UUID assertedBy,
			UUID batchId) {
		if (null == sbomComponentUuids || sbomComponentUuids.isEmpty()) return List.of();
		// Bound here as well as at the resolver. The resolver's check exists to refuse before
		// authorization and before any DB touch, with a message a GraphQL caller can act on;
		// this one exists so the bound is a property of the WRITE, not of one entry point into
		// it. A future caller that is not the resolver would otherwise be unbounded.
		if (sbomComponentUuids.size() > BULK_SUPPORT_MAX_IDS) {
			throw new IllegalArgumentException("a bulk support attestation is limited to "
					+ BULK_SUPPORT_MAX_IDS + " components per call; " + sbomComponentUuids.size()
					+ " were supplied");
		}

		// Deduplicated: a uuid repeated in the input would otherwise be written twice, and the
		// second pass re-asserts and re-dates the attestation the first one just made.
		Set<UUID> requested = new LinkedHashSet<>(sbomComponentUuids);
		Map<UUID, SbomComponent> components = findSbomComponentsByIds(requested, orgUuid);

		// "Already attested" MUST mean what the coverage gauge means, or a bulk sweep cannot
		// close exactly the gap the gauge reports. Row-existence is NOT that definition: a
		// WITHDRAWN attestation, or one carrying only machine-sourced data, is UNATTESTED on
		// the gauge, and skipping it here would leave the operator unable to fill it in.
		// An earlier revision reused the export read for this and claimed it avoided a second
		// definition of "attested" -- it did the opposite, because that read has no definition
		// at all. Parse and apply the same predicate the SQL applies.
		Set<UUID> alreadyAttested = new HashSet<>();
		// Rows this pass would UN-RETRACT. Tracked separately because they need a reason and
		// an ordinary fresh attestation does not -- see the guard in the loop.
		Set<UUID> withdrawn = new HashSet<>();
		Map<UUID, String> unreadable = new LinkedHashMap<>();
		for (SupportPayloadRow existing : sbomComponentSupportRepository
				.findRawByComponentUuids(orgUuid.toString(), joinUuids(components.keySet()))) {
			try {
				SupportData sd = SupportData.parse(existing.getPayload());
				if (sd.hasManualAttestation() && SupportState.WITHDRAWN != sd.state()) {
					alreadyAttested.add(existing.getComponentUuid());
				} else if (SupportState.WITHDRAWN == sd.state()) {
					withdrawn.add(existing.getComponentUuid());
				}
			} catch (RuntimeException e) {
				// Never treat an unreadable payload as "not assessed" -- that is the read-path
				// rule, and silently overwriting a claim this build cannot parse is exactly
				// the destruction it exists to prevent.
				//
				// Logged WITH the cause, per SupportData.parse's contract: the message that
				// reaches the wire is parse's own wrapper, which deliberately names no field
				// or value, so the cause here is the only record of WHICH field failed. The
				// sibling bulk read in findSupportByComponentIds does the same.
				log.warn("bulk support write: unreadable existing attestation on component {}"
						+ " in org {}; reporting FAILED and leaving it untouched",
						existing.getComponentUuid(), orgUuid, e);
				unreadable.put(existing.getComponentUuid(), e.getMessage());
			}
		}

		List<SupportBulkResult> results = new ArrayList<>();
		for (UUID uuid : requested) {
			SbomComponent sc = components.get(uuid);
			if (null == sc) {
				results.add(SupportBulkResult.failed(uuid, "component not found in this organization"));
				continue;
			}
			// Root first: root-ness is a property of the COMPONENT, not of its payload, so a
			// root with an unreadable attestation is still a root and reporting it as FAILED
			// would put a component that never needs attesting into the operator's error list.
			if (SbomComponentData.dataFromRecord(sc).root()) {
				results.add(SupportBulkResult.skipped(uuid, SupportBulkOutcome.SKIPPED_ROOT));
				continue;
			}
			if (unreadable.containsKey(uuid)) {
				results.add(SupportBulkResult.failed(uuid,
						"existing attestation cannot be read by this build: " + unreadable.get(uuid)));
				continue;
			}
			if (alreadyAttested.contains(uuid)) {
				results.add(SupportBulkResult.skipped(uuid, SupportBulkOutcome.SKIPPED_ATTESTED));
				continue;
			}
			// Un-retracting needs a reason, for the same argument that makes clearing need
			// one. This pass forces state=ATTESTED, so a withdrawn row silently becomes a
			// live claim again -- and the audit row is the ONLY trace that the withdrawal was
			// ever reversed. Without a reason that row records a state change and no why,
			// which for a regulatory record is the part that matters. Fresh attestations are
			// unaffected: there is nothing being reversed.
			if (withdrawn.contains(uuid)
					&& (null == request.reason() || request.reason().isBlank())) {
				results.add(SupportBulkResult.failed(uuid,
						"this component's attestation was withdrawn; supply a reason to"
								+ " re-attest it"));
				continue;
			}
			try {
				// state is forced to ATTESTED, not left null. Null PRESERVES the stored state,
				// and the row this pass is most likely to touch is a WITHDRAWN one -- the gauge
				// counts those as missing, which is why bulk re-attests them at all. Leaving it
				// null would APPLY the write and leave the component still uncounted, so the
				// sweep would report success while the number it exists to move stayed put.
				//
				// This is not the incoherent case the mutation refuses: a bulk WITHDRAWN would
				// conjure an assessment and retract it in one call. Asserting ATTESTED is just
				// what "attest these components" means.
				self.setSbomComponentSupportIsolated(uuid, withAttestedState(request), assertedBy,
						batchId);
				results.add(SupportBulkResult.applied(uuid));
			} catch (IllegalArgumentException e) {
				// Validation messages are written FOR a user and name the field at fault.
				results.add(SupportBulkResult.failed(uuid, e.getMessage()));
			} catch (RuntimeException e) {
				// Everything else -- a DataAccessException, a constraint name, a driver
				// message -- is written for an operator reading logs, not for a UI. Log the
				// detail and return something a person can act on without leaking internals.
				log.error("Bulk support attestation failed for sbom component {}: {}",
						uuid, e.getMessage(), e);
				results.add(SupportBulkResult.failed(uuid,
						"could not be written; see server logs for this component"));
				// Reset the persistence context before the next component.
				//
				// REQUIRES_NEW does NOT give each item a fresh one in production. Spring's
				// open-in-view is on (Boot default; nothing here disables it), so a
				// non-transactional EntityManager is already bound to the request thread,
				// and JpaTransactionManager.doBegin reuses a bound unsynchronized holder
				// rather than creating one. Each item gets its own TRANSACTION; all of them
				// share one persistence context.
				//
				// That matters only for a failure at flush -- an optimistic-lock conflict or
				// a unique-constraint violation on a first attestation, both translated a few
				// hundred lines above. JPA requires an EntityManager to be discarded after a
				// failed flush, so without this clear the items AFTER the failure can fail
				// spuriously against a poisoned context: one real conflict reported as a
				// whole tail of failures, which is precisely the batch-wide collapse
				// per-item isolation exists to prevent.
				//
				// NOT reachable from the test suite: tests are not web requests, so no
				// open-in-view holder is bound, so REQUIRES_NEW really does create a fresh
				// EntityManager per item there and the poisoned-context path cannot occur.
				// Safe to call unconditionally -- nothing else in a bulk mutation request is
				// holding managed entities across the loop.
				entityManager.clear();
			}
		}
		return results;
	}

	/** The request as a positive attestation, whatever the stored state was. */
	private static SupportAttestationRequest withAttestedState(SupportAttestationRequest r) {
		return new SupportAttestationRequest(r.levelOfSupport(), SupportState.ATTESTED, r.party(),
				r.justification(), r.assessedAt(), r.endOfGuaranteedSupportDate(),
				r.endOfSupportDate(), r.endOfLifeDate(), r.supportNotes(), r.clearMilestones(),
				r.reason());
	}

	/**
	 * One component's write, in its own transaction. Called only through the self proxy from
	 * {@link #bulkSetSbomComponentSupport}; see the note on that method for why the isolation
	 * is load-bearing rather than defensive.
	 *
	 * @param batchId the sweep every component in this loop shares. REQUIRES_NEW is a
	 *        transaction boundary, not a thread one, so the id passes through unchanged.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SbomComponentSupport setSbomComponentSupportIsolated(UUID sbomComponentUuid,
			SupportAttestationRequest request, UUID assertedBy, UUID batchId) {
		return applySupport(sbomComponentUuid, request, SupportSource.MANUAL, assertedBy, batchId);
	}

	/** Coverage counts for the support-disclosure completeness metric. */
	public record SupportCoverage(long total, long attested) {}

	/**
	 * Whether BOM exports for this org actually carry the support properties the coverage
	 * counts describe.
	 *
	 * <p>A CONSTANT {@code PARTIAL} today, which is the honest answer and not the one this
	 * started as. An earlier revision returned ENABLED, reasoning that injection is un-gated
	 * -- true, but of a NARROWER surface than the claim. {@code injectCurrentSupport} has
	 * exactly ONE production call site, the native CycloneDX single-artifact download; the
	 * SPDX-augmented branch beside it serves the BOM un-injected, and the release-level SBOM
	 * export never injects at all. So an org at 100% coverage can export a release SBOM
	 * carrying no disclosure while a gauge reports exports are on -- the identical
	 * lie-by-omission a default-off toggle would create, and it would have shipped on day one.
	 *
	 * <p>Reporting a hopeful DISABLED because a decision says the toggle should default off
	 * would be the mirror-image error: this must describe the running system, not the
	 * intended one.
	 *
	 * <p>THIS METHOD IS THE SEAM the export toggle replaces. When it lands, this reads the
	 * org setting instead and every caller keeps working unchanged.
	 * {@code SupportExportGateTest} is the test that names the gate: it asserts BOTH this
	 * method's value and that NO class under {@code src/main/java} outside this one mentions
	 * it, so it fails the moment a gate appears anywhere and has to be updated by whoever adds
	 * it. That is how this stops being a constant rather than quietly staying one.
	 *
	 * <p>An earlier revision of this javadoc claimed the forged-provenance strip "runs on
	 * every egress regardless of what this returns". THAT IS FALSE, and worth correcting here
	 * rather than quietly: the strip runs inside {@code SupportBomInjector.inject}, so it
	 * reaches exactly the paths injection reaches. The release-level SBOM export calls
	 * neither, so a forged {@code reliza:support:*} baked into an uploaded component BOM
	 * survives into the merged export untouched -- while {@code SupportBomInjector}'s javadoc
	 * asserts any such property provably came from us. An anti-spoofing control that holds on
	 * one egress and not another is worse than none, because the documentation says it holds.
	 *
	 * <p>Tracked as board task {@code t20260826-172851-10180}, open since 2026-08-26. It is
	 * the same gap that makes this method return PARTIAL, and closing it is what lets this
	 * return ENABLED or DISABLED: one setting gating every egress, strip included.
	 */
	public SupportExportState supportExportState(UUID orgUuid) {
		return SupportExportState.PARTIAL;
	}
}

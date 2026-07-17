/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import io.reliza.model.Branch;
import io.reliza.model.ComponentData;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.repositories.FindingChangeV3BranchSeedRepository;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.FindingComparisonService.EventAttribution;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds and MAINTAINS the branch-grain "events-lite" {@code finding_change_events_v3} store from
 * {@code metrics_audit} history (board task #38 + the v3 follow-on; the v1/v2 fact tables it once also
 * seeded were dropped in V64, so v3 is the sole store). Two duties: the RESUMABLE branch-chained backfill
 * DRAIN ({@link #drainV3Backfill} -- every re-scan transition that predates the write-time emit becomes a
 * v3 fact, with inherited APPEAREDs dropped) and the DAILY {@link #repairSweepV3} (bounded reseed that
 * heals dropped-emit holes and vacuously v3-certifies never-re-scanned orgs). Certifies each org's v3
 * watermark on clean completion -- the gate the posture-diff read path consults.
 *
 * <p><b>Source of truth.</b> Per release it reuses the SAME shared diff
 * ({@link FindingComparisonService#diffAuditPairToEventsV3} / {@code diffPairToEvents}) and the SAME
 * idempotent {@code ON CONFLICT DO NOTHING} v3 insert ({@link FindingDimBackfillService#writeEventsToV3})
 * that live emission uses -- so backfilled rows are byte-identical to live-emitted rows and any overlap
 * is deduped.
 *
 * <p><b>Chunking + resilience.</b> Iterates by org, then branch, then release; each release is ONE unit
 * of work in its OWN transaction (via {@link TransactionTemplate} -- a self-invoked {@code @Transactional}
 * method would bypass the proxy, mirroring {@link NotificationRetentionService}). One release's failure is
 * logged at ERROR and does not roll back or block the rest of the batch. Idempotent + restartable via the
 * unique dedup index + per-org watermark.
 *
 * <p><b>Scope.</b> RELEASE only -- the changelog over-time feature is release-scoped; ARTIFACT audit
 * rows are never backfilled.
 */
@Service
@Slf4j
public class FindingChangeEventBackfillService {

	@Autowired
	private MetricsAuditRepository metricsAuditRepository;

	@Autowired
	private FindingDimBackfillService findingDimBackfillService;

	@Autowired
	private FindingComparisonService findingComparisonService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private FindingChangeV3BranchSeedRepository seedRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private static final String ENTITY_TYPE = MetricsEntityType.RELEASE.name();

	/**
	 * Rows per window for the full-range per-release diff -- bounds backfill memory to one window of
	 * snapshot JSONBs regardless of a release's history length (the integration test shrinks it to
	 * force multi-window paging over a small fixture).
	 *
	 * <p>SMALLER is faster AND lighter, counter-intuitively: profiling a deep history (120 revisions x 600
	 * findings) showed window 20 -> 33s / 292MB peak vs window 121 -> 218s / 942MB. A bigger window keeps
	 * more managed MetricsAudit entities (plus Hibernate's mutable-JSONB dirty-check copies) live per flush
	 * -> GC pressure + OOM risk with NO speed benefit (the seed is diff/insert-bound, not round-trip-bound).
	 * So the default is 25, not 100. The windowed output is byte-identical at any page size (proven), so
	 * this is a pure perf/memory knob; raise it only on a memory-rich box with small metrics.
	 */
	@Value("${relizaprops.findingChangeBackfillRevisionPage:25}")
	private int backfillRevisionPage;

	// ==================== v3 (events-lite) branch-chained backfill ====================

	/** Aggregate counters for the branch-chained v3 backfill. */
	public record V3BackfillResult(int branchesProcessed, int releasesProcessed, int releasesFailed,
			int factsInserted) {
		V3BackfillResult add(V3BackfillResult o) {
			return new V3BackfillResult(branchesProcessed + o.branchesProcessed,
					releasesProcessed + o.releasesProcessed, releasesFailed + o.releasesFailed,
					factsInserted + o.factsInserted);
		}
	}

	private static final V3BackfillResult V3_EMPTY = new V3BackfillResult(0, 0, 0, 0);

	/**
	 * RESUMABLE, bounded per-tick DRAIN of the branch-grain v3 (events-lite) backfill (board task #38
	 * follow-on) -- the scheduled entry point (replaced the former one-shot boot backfill, which re-walked
	 * every uncertified org from zero on each boot and never certified a large instance).
	 * Whereas the boot walk re-processed every uncertified org's branches from ZERO on each boot (org-grain
	 * all-or-nothing certification discarded all progress on any interruption, so a large instance never
	 * certified and spiked CPU), the drain processes at most {@code batchPerTick} branches this tick and
	 * DURABLY marks each cleanly-completed branch in {@code finding_change_v3_branch_seed}, so the next tick
	 * RESUMES from the un-marked remainder instead of restarting.
	 *
	 * <p>Per uncertified org (skips orgs where {@code needsV3Backfill} is false): load the org's already-marked
	 * branches at the current {@link FindingDimKey#KEY_VERSION}, and for each UN-marked branch (while the
	 * per-tick budget lasts) run the existing {@link #backfillBranchV3} (unchanged -- own per-release txs). A
	 * branch that completes with zero per-release failures is MARKED (so it is skipped next tick); a branch
	 * that had a failure stays un-marked and retries next tick (its writes are {@code ON CONFLICT DO NOTHING},
	 * so a re-run only fills genuinely-missing rows). Either way the branch consumes one budget unit. When an
	 * org has every branch marked (and has >= 1 branch), it is certified via
	 * {@link OrganizationService#certifyFindingChangeV3Backfill} -- reads flip to v3 for it at that moment.
	 * Zero-branch orgs are NOT vacuously certified here (the repair sweep owns that, matching the boot walk).
	 *
	 * <p>The {@code KEY_VERSION} stamp on the marker means a vocabulary bump makes all prior markers stale
	 * (queried at the new version they are absent), so the drain re-processes every branch, still resumably.
	 * Robust: each branch's work is wrapped so one branch's failure cannot abort the whole tick.
	 *
	 * @param batchPerTick max branches to process this tick across all orgs (the per-tick CPU bound)
	 */
	public V3BackfillResult drainV3Backfill(int batchPerTick) {
		log.info("finding_change_events v3 drain: starting (batchPerTick={})", batchPerTick);
		V3BackfillResult total = V3_EMPTY;
		int budget = batchPerTick;
		int orgsCertified = 0;
		if (budget <= 0) {
			log.info("finding_change_events v3 drain: batchPerTick <= 0, nothing to do");
			return total;
		}
		// The marker upsert is @Modifying, so it needs a transaction; the drain itself is not @Transactional
		// (and could not be -- backfillBranchV3 runs its own per-release txs), so each mark commits in its own
		// small tx AFTER its branch drained cleanly. Mirrors the per-unit TransactionTemplate style above.
		TransactionTemplate markTxTemplate = new TransactionTemplate(transactionManager);
		for (UUID org : metricsAuditRepository.findDistinctOrgsWithAudits(ENTITY_TYPE)) {
			if (budget <= 0) {
				break; // leave the rest for the next tick
			}
			if (org == null) {
				continue; // legacy NULL-org audit rows have no org settings to certify
			}
			if (!findingDimBackfillService.needsV3Backfill(org)) {
				continue; // already v3-certified at the current key version
			}
			Set<UUID> marked;
			List<Branch> branches;
			try {
				marked = new HashSet<>(seedRepository.findSeededBranchUuids(org, FindingDimKey.KEY_VERSION));
				branches = branchService.listBranchesOfOrg(org);
			} catch (RuntimeException e) {
				log.error("finding_change_events v3 drain: failed to load branches/marks for org {}; skipping "
						+ "this org this tick", org, e);
				continue;
			}
			for (Branch b : branches) {
				if (budget <= 0) {
					break;
				}
				UUID branchUuid = b.getUuid();
				if (marked.contains(branchUuid)) {
					continue; // already drained cleanly at this key version
				}
				budget--; // a branch consumes one unit whether it succeeds or fails (a failure retries next tick)
				try {
					V3BackfillResult branchResult = backfillBranchV3(org, branchUuid);
					total = total.add(branchResult);
					if (branchResult.releasesFailed() == 0) {
						final UUID markBranch = branchUuid;
						markTxTemplate.executeWithoutResult(status -> seedRepository.markSeeded(
								markBranch, org, FindingDimKey.KEY_VERSION,
								ZonedDateTime.now().toInstant().toString()));
						marked.add(branchUuid);
					}
				} catch (RuntimeException e) {
					// backfillBranchV3 isolates per-release failures, but guard the mark/aggregate too so one
					// branch cannot abort the tick. The branch stays un-marked and retries next tick.
					log.error("finding_change_events v3 drain: branch {} (org {}) failed; leaving un-marked to "
							+ "retry next tick", branchUuid, org, e);
				}
			}
			// Certify only when EVERY branch of the org is now marked AND the org actually has branches -- a
			// zero-branch org is left to the repair sweep's vacuous path (mirrors the boot walk). If the budget
			// ran out mid-org, some branch is still un-marked, so this correctly does NOT certify yet.
			if (!branches.isEmpty() && marked.containsAll(
					branches.stream().map(Branch::getUuid).collect(Collectors.toSet()))) {
				try {
					organizationService.certifyFindingChangeV3Backfill(org, ZonedDateTime.now());
					orgsCertified++;
				} catch (Exception e) {
					log.error("finding_change_events v3 drain: failed to certify watermark for org {}; it stays "
							+ "uncertified until the next tick", org, e);
				}
			}
		}
		log.info("finding_change_events v3 drain: finished tick -- {} branch(es), {} release(s), {} failed, "
				+ "{} fact(s) inserted, {} org(s) certified, budget left {}", total.branchesProcessed(),
				total.releasesProcessed(), total.releasesFailed(), total.factsInserted(), orgsCertified, budget);
		return total;
	}

	/**
	 * Backfill the branch-grain {@code finding_change_events_v3} for one org: every branch, each in the
	 * branch-chained walk below, certifying the org's v3 watermark on a clean run. Invoked whole-org by the
	 * admin backfill mutation (the scheduled {@link #drainV3Backfill} instead calls {@link #backfillBranchV3}
	 * per branch so it can bound + resume); the live emit keeps v3 current via {@code emitV3}. Certifies on
	 * zero per-release failures. (v3's work set is the live branch/release graph, so there is no
	 * concurrent-historical-insert race to guard against.)
	 */
	public V3BackfillResult backfillOrgV3(UUID org) {
		if (org == null) {
			return V3_EMPTY;
		}
		V3BackfillResult total = V3_EMPTY;
		for (Branch b : branchService.listBranchesOfOrg(org)) {
			total = total.add(backfillBranchV3(org, b.getUuid()));
		}
		// Certify the org's v3 watermark ONLY on a fully clean run (no per-release failure) -- a failure
		// leaves the org uncertified so the read flip never trusts an incompletely-backfilled org and the
		// next run retries (idempotent via ON CONFLICT). Mirrors the v1/v2 backfill's certify-on-clean gate.
		if (total.releasesFailed() == 0) {
			try {
				organizationService.certifyFindingChangeV3Backfill(org, ZonedDateTime.now());
			} catch (Exception e) {
				log.error("finding_change_events v3 backfill: failed to certify watermark for org {}; it stays "
						+ "uncertified until the next run", org, e);
			}
		}
		log.info("finding_change_events v3 backfill: org {} done -- {} branch(es), {} release(s), {} failed, "
				+ "{} fact(s) inserted, certified={}", org, total.branchesProcessed(), total.releasesProcessed(),
				total.releasesFailed(), total.factsInserted(), total.releasesFailed() == 0);
		return total;
	}

	/**
	 * BRANCH-CHAINED backfill of one branch: walk its releases in CREATION order, threading each release's
	 * PREDECESSOR-terminal metrics (the previous non-CANCELLED/REJECTED release on the SAME branch) as the
	 * "inherited findings", so a dependency carried forward unchanged emits no APPEARED (the ~148x fan-out
	 * collapse). The first release on the branch inherits NOTHING (empty -> byte-identical to the
	 * per-release backfill), confining dedup to same-branch successors -- reconstruction anchors per-branch,
	 * so a base-branch fork point is never compared and must not be inherited from here. CANCELLED/REJECTED
	 * releases never emit and are not predecessors (matches the live emit + the reconstruction anchor).
	 *
	 * <p>Each release is one unit of work in its own transaction; a failure is logged + isolated. The
	 * predecessor metrics are carried in memory across transactions (read-only, not tx-bound).
	 */
	public V3BackfillResult backfillBranchV3(UUID org, UUID branchUuid) {
		// STREAMED predecessor walk (bounded heap): order the branch by a LIGHT projection (uuid +
		// created_date, NO heavy metrics JSONB), then load ONE release's full data at a time inside its own
		// tx and carry only the predecessor's finding-KEY SET across iterations. Peak heap is ~one release's
		// metrics + one key set, regardless of branch length -- NOT the whole branch (which OOMs a long-lived
		// branch, the exact shape backfillReleaseWindowed was re-engineered away from). The full chain is
		// REQUIRED for correctness (a truncated list makes the earliest included release look branch-first and
		// wrongly re-declare inherited findings), so we still order ALL releases -- just without their metrics.
		// Integer.MAX_VALUE renders a finite SQL LIMIT (the lite query accepts a big limit; the "ALL" sentinel
		// only works on the lite path). getReleaseData is the ONLY source of real live metrics (the
		// record_data 'metrics' is an EMPTY DECOY) and a reliable created_date/lifecycle.
		List<ReleaseData> ordered = new ArrayList<>(
				sharedReleaseService.listReleaseDataOfBranchLight(branchUuid, Integer.MAX_VALUE, false));
		ordered.sort(Comparator.comparing(ReleaseData::getCreatedDate));

		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
		int releasesProcessed = 0;
		int releasesFailed = 0;
		int factsInserted = 0;
		// CUMULATIVE, DRIFT-PROOF "already seen on this branch" set: every finding key that has appeared on an
		// EARLIER release of this branch. A release's initial APPEARED is dropped only for a finding already in
		// this set. Sourced from the releases' own metrics_audit snapshots (which never change) rather than a
		// predecessor's CURRENT metrics -- a predecessor re-scanned long after a child forked has a drifted
		// current state that no longer reflects what the child inherited, which silently defeated the dedup.
		// Fresh per branch (backfillBranchV3 is one branch), so cross-branch fan-out is correctly NOT deduped.
		Set<String> seenKeys = new HashSet<>();
		for (ReleaseData liteRd : ordered) {
			ReleaseData rd = sharedReleaseService.getReleaseData(liteRd.getUuid()).orElse(null);
			if (rd == null) {
				continue; // deleted between the list and the fetch -- skip
			}
			if (rd.getLifecycle() == ReleaseLifecycle.CANCELLED
					|| rd.getLifecycle() == ReleaseLifecycle.REJECTED) {
				continue; // never contributed posture; not a predecessor either
			}
			// Snapshot of what earlier branch releases carried (this release does NOT dedup against itself).
			final Set<String> inheritedKeys = seenKeys.isEmpty() ? Set.of() : new HashSet<>(seenKeys);
			final ReleaseData frd = rd;
			// Collect THIS release's full historical finding-key set (all audit snapshots + current) so the
			// NEXT release inherits it -- drift-proof, unlike reading a stale current predecessor snapshot.
			final Set<String> releaseKeys = new HashSet<>();
			try {
				Integer inserted = txTemplate.execute(
						status -> backfillReleaseV3(org, branchUuid, frd, inheritedKeys, releaseKeys));
				releasesProcessed++;
				factsInserted += inserted != null ? inserted : 0;
				// Fold this release's keys into the branch's cumulative set ONLY on success: a rolled-back
				// release wrote no rows, so it should not drive dedup for later releases (they re-declare --
				// under-dedup is safe/reconstruction-neutral; the failed release retries next run).
				seenKeys.addAll(releaseKeys);
			} catch (RuntimeException e) {
				releasesFailed++;
				log.error("finding_change_events v3 backfill failed for release {} (branch {}, org {}); "
						+ "continuing", rd.getUuid(), branchUuid, org, e);
			}
		}
		return new V3BackfillResult(1, releasesProcessed, releasesFailed, factsInserted);
	}

	/**
	 * Produce + write the v3 branch-grain events for one release, inside the caller's transaction, in
	 * bounded-memory WINDOWS over its {@code metrics_audit} history (the v3 analogue of
	 * {@link #backfillReleaseWindowed}). REQUIRED for customer-scale: a release re-scanned for months has
	 * hundreds/thousands of KB..MB metrics snapshots, and loading them all at once (as the whole-list
	 * {@link FindingComparisonService#backfillEventsForReleaseV3} does) would OOM a normal pod. Walks the
	 * audit timeline {@value #backfillRevisionPage} rows at a time, carrying the previous window's last
	 * snapshot so cross-window pairs diff exactly like the whole-list path, detaching each window so the
	 * managed entities (+ Hibernate's mutable-type dirty-check copies of each metrics JSONB) don't
	 * accumulate. The inherited-drop's per-release {@code appearedOnThisRelease} set is carried ACROSS
	 * windows, so the branch-chained dedup is identical to the whole-list producer.
	 *
	 * <p>{@code inheritedKeys} (caller-owned, read-only): findings already seen on EARLIER branch releases;
	 * this release's initial APPEARED is dropped for those. {@code seenKeysSink} (caller-owned, filled here):
	 * every finding key this release carried across its full audit history + current -- the caller unions it
	 * into the branch's cumulative "seen" set for the NEXT release, drift-proof because archived snapshots
	 * never change.
	 */
	private int backfillReleaseV3(UUID org, UUID branchUuid, ReleaseData rd, Set<String> inheritedKeys,
			Set<String> seenKeysSink) {
		String componentName = getComponentService.getComponentData(rd.getComponent())
				.map(ComponentData::getName)
				.orElse("");
		EventAttribution attr = new EventAttribution(
				org, rd.getUuid(), rd.getVersion(), rd.getComponent(), componentName, branchUuid);
		Set<String> appearedOnThisRelease = new HashSet<>();
		// Born-with = the finding keys of the FIRST NON-EMPTY snapshot (birth scan); filled lazily on the first
		// non-empty page row (the walk runs oldest-first from cursor=-1, so that IS the birth scan). Only these
		// inherited APPEAREDs are dropped -- trickle-ins keep theirs (board task F1). Carried across windows.
		Set<String> bornWithKeys = new HashSet<>();
		int inserted = 0;
		MetricsAudit prev = null;
		int cursor = -1;
		while (true) {
			List<MetricsAudit> page = metricsAuditRepository.findRevisionsForEntityAfterRevision(
					ENTITY_TYPE, rd.getUuid(), cursor, backfillRevisionPage);
			if (page.isEmpty()) {
				break;
			}
			List<FindingChangeEvent> events = new ArrayList<>();
			MetricsAudit older = prev;
			for (MetricsAudit newer : page) {
				// Accumulate every finding this release EVER carried (drift-proof: archived snapshots never
				// change) so the next branch release inherits the complete set, not a stale current snapshot.
				Set<String> newerKeys = findingComparisonService.findingKeysOfRawMetrics(newer.getMetrics());
				if (bornWithKeys.isEmpty() && !newerKeys.isEmpty()) {
					bornWithKeys.addAll(newerKeys); // first non-empty snapshot -> birth scan
				}
				if (older != null) {
					events.addAll(findingComparisonService.diffAuditPairToEventsV3(
							older, newer, attr, inheritedKeys, bornWithKeys, appearedOnThisRelease));
				}
				seenKeysSink.addAll(newerKeys);
				older = newer;
			}
			inserted += findingDimBackfillService.writeEventsToV3(org, events);
			page.forEach(entityManager::detach);
			prev = older;
			cursor = older.getMetricsRevision();
			if (page.size() < backfillRevisionPage) {
				break;
			}
		}
		// Include the current (live) finding set too: covers the narrow case where the release's LAST live
		// scan introduced a finding that never got its own archived snapshot before the next release forked,
		// so the next release still inherits it. Mostly a subset of the historical union above; cheap.
		seenKeysSink.addAll(findingComparisonService.findingKeysOf(rd.getMetrics()));
		if (prev == null) {
			return 0; // never re-scanned -> no history to seed (the head is the live emit's job)
		}
		// Edge: every archived snapshot was empty (findings only ever in live) -> treat the live set as birth.
		if (bornWithKeys.isEmpty()) {
			bornWithKeys.addAll(findingComparisonService.findingKeysOf(rd.getMetrics()));
		}
		// Terminal (last archived snapshot -> live current metrics) pair.
		List<FindingChangeEvent> tail = findingComparisonService.diffAuditToLiveEventsV3(
				prev, rd.getMetrics(), attr, inheritedKeys, bornWithKeys, appearedOnThisRelease);
		inserted += findingDimBackfillService.writeEventsToV3(org, tail);
		return inserted;
	}

	/**
	 * DAILY v3 REPAIR SWEEP -- the v3 analogue of {@link #repairSweep}, REQUIRED for {@code V3_ONLY} where
	 * v3 is the only store (a dropped best-effort live-emit v3 row is otherwise a permanent, invisible hole
	 * reverse-replay reconstructs across). Bounded reseed: for every release re-scanned in the last
	 * {@code lookbackDays}, re-diff its recent {@code metrics_audit} revisions into v3 (idempotent via
	 * ON CONFLICT -- only genuinely missing rows land). Also vacuously v3-certifies never-re-scanned orgs
	 * (closes the "no-audit org uncertified" gap the boot backfill leaves). Cost scales with recent scan
	 * volume, not fleet size. The date-bounded slice is small, so the whole-list producer is used. Its
	 * inherited-drop MAY now remove an inherited finding's first appearance within the slice (the
	 * empty-older guard is gone), which is harmless here: v3 writes are insert-only (ON CONFLICT DO NOTHING),
	 * so the re-diff can only ADD genuinely-missing rows, never delete a row a live emit already wrote.
	 */
	public V3BackfillResult repairSweepV3(int lookbackDays) {
		ZonedDateTime since = ZonedDateTime.now().minusDays(lookbackDays);
		log.info("finding_change_events v3 repair sweep: starting (lookback {} day(s), since {})",
				lookbackDays, since);
		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
		int releasesProcessed = 0;
		int releasesFailed = 0;
		int factsInserted = 0;
		for (UUID org : metricsAuditRepository.findDistinctOrgsWithAuditsSince(ENTITY_TYPE, since)) {
			if (org == null) {
				continue; // legacy NULL-org rows are ancient -- nothing recent to repair
			}
			for (UUID releaseUuid : metricsAuditRepository.findDistinctReleaseUuidsByOrgSince(
					ENTITY_TYPE, org, since)) {
				try {
					Integer inserted = txTemplate.execute(status -> repairReleaseV3(org, releaseUuid, since));
					releasesProcessed++;
					int ins = inserted != null ? inserted : 0;
					factsInserted += ins;
					if (ins > 0) {
						// A non-zero insert on a re-diff = a hole the live emit dropped. ERROR (operator
						// alerting) -- repaired now, but a recurring pattern points at emit failures.
						log.error("finding_change_events v3 repair sweep: release {} (org {}) was missing {} "
								+ "event(s) -- repaired; investigate dropped live emits if recurring",
								releaseUuid, org, ins);
					}
				} catch (RuntimeException e) {
					releasesFailed++;
					log.error("finding_change_events v3 repair sweep failed for release {} (org {}); continuing",
							releaseUuid, org, e);
				}
			}
		}
		// Vacuous v3 certification of never-re-scanned orgs (best-effort, isolated per org).
		try {
			for (UUID org : organizationService.listOrgsEligibleForVacuousFindingChangeV3Certification(
					MetricsEntityType.RELEASE)) {
				try {
					organizationService.certifyFindingChangeV3Backfill(org, ZonedDateTime.now());
				} catch (Exception e) {
					log.error("finding_change_events v3 repair sweep: vacuous v3 certification failed for org {}; "
							+ "it stays uncertified until the next sweep", org, e);
				}
			}
		} catch (Exception e) {
			log.error("finding_change_events v3 repair sweep: vacuous certification candidate query failed", e);
		}
		log.info("finding_change_events v3 repair sweep: done -- {} release(s), {} failed, {} event(s) repaired",
				releasesProcessed, releasesFailed, factsInserted);
		return new V3BackfillResult(0, releasesProcessed, releasesFailed, factsInserted);
	}

	/** Re-diff a release's recent (since) metrics_audit slice into v3, inside the caller's tx. Idempotent. */
	private int repairReleaseV3(UUID org, UUID releaseUuid, ZonedDateTime since) {
		List<MetricsAudit> auditRows = metricsAuditRepository.findAllRevisionsForEntitySince(
				ENTITY_TYPE, releaseUuid, since);
		if (auditRows.isEmpty()) {
			return 0;
		}
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid).orElse(null);
		if (rd == null || rd.getLifecycle() == ReleaseLifecycle.CANCELLED
				|| rd.getLifecycle() == ReleaseLifecycle.REJECTED || rd.getBranch() == null) {
			return 0;
		}
		String componentName = getComponentService.getComponentData(rd.getComponent())
				.map(ComponentData::getName)
				.orElse("");
		EventAttribution attr = new EventAttribution(
				org, releaseUuid, rd.getVersion(), rd.getComponent(), componentName, rd.getBranch());
		// firstScanInheritedKeys is consistent with the live emit. The inherited-drop MAY now fire within
		// this slice (the empty-older guard is gone), but v3 writes are insert-only (ON CONFLICT DO NOTHING),
		// so the re-diff only fills genuinely-missing rows -- recent transitions re-diffed, holes repaired.
		Set<String> inheritedKeys = findingComparisonService.firstScanInheritedKeys(rd);
		List<FindingChangeEvent> events = findingComparisonService.backfillEventsForReleaseV3(
				auditRows, rd.getMetrics(), attr, inheritedKeys);
		return findingDimBackfillService.writeEventsToV3(org, events);
	}
}

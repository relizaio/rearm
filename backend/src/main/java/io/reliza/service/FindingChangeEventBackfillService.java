/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.repositories.FindingChangeV3BranchSeedRepository;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.FindingComparisonService.EventAttribution;
import io.reliza.service.FindingComparisonService.RevisionProduction;
import io.reliza.service.FindingComparisonService.V3Production;
import io.reliza.service.FindingDimBackfillService.RevisionWrite;
import io.reliza.service.FindingDimBackfillService.V3WriteResult;
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
	 * PREDECESSOR-terminal metrics (the previous emit-eligible release on the SAME branch, see
	 * {@link ReleaseLifecycle#isFindingChangeEmitSuppressed}) as the
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
			if (ReleaseLifecycle.isFindingChangeEmitSuppressed(rd.getLifecycle())) {
				// Never contributed posture, and not a predecessor either: a release whose own events
				// were never emitted must not anchor another release's inheritance.
				continue;
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
	 *
	 * <p><b>How to read what this sweep reports.</b> Repairing something is not by itself a fault, so the
	 * severity is chosen by the ORG's v3 backfill watermark rather than by anything about the repaired rows:
	 * <ul>
	 *   <li>Org NOT yet v3-certified: its history is still being seeded, so missing rows are simply work the
	 *       drain has not reached. Expected during rollout -- logged at INFO.</li>
	 *   <li>Org v3-CERTIFIED: the live emit was responsible for every transition since certification, so a
	 *       missing row is worth an operator's attention. It does NOT follow that the emit was lost -- see
	 *       {@code RepairCause} for the three things it can be, which the alert reports per revision.
	 *       Logged at ERROR.</li>
	 * </ul>
	 *
	 * <p>The severity deliberately does NOT key off the shape of the repaired rows (kind mix, or whether the
	 * slice reaches the release's birth). A lost emit on a release's FIRST scan produces an all-APPEARED,
	 * birth-anchored repair -- indistinguishable in shape from a benign inherited-key disagreement -- so a
	 * shape-based rule routes the single most important signal into the quiet arm. The kind mix is still
	 * reported as evidence; it just does not decide severity. (Since the drift fix the two resolve inherited
	 * keys identically AS OF THE SAME INSTANT, but they still apply the drop differently -- the emit only on
	 * a first scan, this producer on any re-scan -- so disagreement remains a cause at
	 * all; the branch backfill's cumulative rule remains a third, quieter one.)
	 */
	public V3BackfillResult repairSweepV3(int lookbackDays) {
		ZonedDateTime since = ZonedDateTime.now().minusDays(lookbackDays);
		log.info("finding_change_events v3 repair sweep: starting (lookback {} day(s), since {})",
				lookbackDays, since);
		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
		int releasesProcessed = 0;
		int releasesFailed = 0;
		int factsInserted = 0;
		RepairTally settled = new RepairTally();
		RepairTally backfilling = new RepairTally();
		for (UUID org : metricsAuditRepository.findDistinctOrgsWithAuditsSince(ENTITY_TYPE, since)) {
			if (org == null) {
				continue; // legacy NULL-org rows are ancient -- nothing recent to repair
			}
			// The org's v3 watermark is what separates the sweep's two causes, so it is read ONCE per org
			// (not per release) and decides which tally the org's repairs land in. Fail-safe: needsV3Backfill
			// already reads a lookup failure as "needs backfill", which lands repairs in the quiet tally --
			// the right way round, since we would rather under-alert than cry wolf on an unknown watermark.
			boolean settledOrg = !findingDimBackfillService.needsV3Backfill(org);
			RepairTally tally = settledOrg ? settled : backfilling;
			for (UUID releaseUuid : metricsAuditRepository.findDistinctReleaseUuidsByOrgSince(
					ENTITY_TYPE, org, since)) {
				try {
					RepairDetail detail = txTemplate.execute(status -> repairReleaseV3(org, releaseUuid, since));
					releasesProcessed++;
					int ins = detail != null ? detail.inserted() : 0;
					factsInserted += ins;
					if (ins > 0) {
						tally.add(org, releaseUuid, detail);
						// Per-release detail is INFO: on a large instance this fires for hundreds of
						// releases in one sweep. The aggregate below is the alert.
						log.info("finding_change_events v3 repair sweep: release {} (org {}) re-diffed {} "
								+ "missing event(s) (v3_backfill_settled={}, kinds={})",
								releaseUuid, org, ins, settledOrg, detail.landed().byKind());
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
		// ONE aggregate line per run per cause, never one per release. Severity is decided by the ORG's v3
		// watermark, not by the shape of the repaired rows -- see this method's javadoc for why the shape
		// cannot carry that distinction.
		if (backfilling.releases > 0) {
			log.info("finding_change_events v3 repair sweep: seeded {} event(s) across {} release(s) in orgs "
					+ "whose v3 backfill has not completed -- kinds={}. Expected during rollout; these orgs "
					+ "have no live-emit guarantee yet",
					backfilling.events, backfilling.releases, backfilling.byKind);
		}
		if (settled.releases > 0) {
			// The ONE line an ERROR-only channel receives, so it has to carry the evidence, not a verdict.
			// emit_never_ran vs emit_disagreed is the split that matters: the sweep only ever inserts a row
			// that was absent, and whether the REST of that revision was already present says whether the
			// emit failed to run or ran and produced a different set. Deliberately no change-date span --
			// every repaired transition is inside the lookback by construction (the slice is selected on
			// metrics_audit.revision_created_date), so a span could only ever restate the window.
			log.error("finding_change_events v3 repair sweep: repaired {} event(s) across {} of {} release(s) "
					+ "in the last {} day(s) on orgs whose v3 backfill has completed -- kinds={}, and per "
					+ "repaired metrics revision: "
					+ "emit_never_ran={}, emit_disagreed={}, emit_skipped_lifecycle={}. e.g. org/release {}. "
					+ "Counts are per REVISION, not per release: a revision is one metrics save, so it is one "
					+ "emit that either ran or did not, and a release can contribute to more than one bucket. "
					+ "emit_never_ran = nothing was stored for that revision "
					+ "and the emit's own rule would have written something, so it did not run (it is "
					+ "best-effort: a pod dying between the metrics commit and the afterCommit callback drops "
					+ "it silently; an emit that ran and THREW logs 'Failed to emit finding_change_events' "
					+ "instead). NB \"nothing stored\" means no row THIS SWEEP offered for that revision was "
					+ "already present. emit_skipped_lifecycle = the release was CANCELLED/REJECTED at that "
					+ "transition, so the emit's early return was correct and this is benign. emit_disagreed "
					+ "= rows were already present, or the emit's rule would have written nothing -- either "
					+ "way the two producers derive different sets, which is not a lost write. An emit that "
					+ "ran and THREW is a fourth case and logs its own ERROR.",
					settled.events, settled.releases, releasesProcessed, lookbackDays, settled.byKind,
					settled.revisions(RepairCause.EMIT_NEVER_RAN),
					settled.revisions(RepairCause.EMIT_DISAGREED),
					settled.revisions(RepairCause.EMIT_SKIPPED_LIFECYCLE), settled.sample);
		}
		if (!settled.disagreementSample.isEmpty()) {
			// SEPARATE line, deliberately, despite this sweep's one-line-per-cause habit. The aggregate line
			// above is ~1.5KB of legend before its arguments, so a payload appended to it is the first thing a
			// log forwarder truncates -- and this payload is the only part an operator can actually act on.
			// Its own line, evidence first, keeps it intact. The general sample above is drawn from ALL
			// repaired releases, so where one cause dominates the counts a minority cause never gets an
			// example; this is that example.
			log.error("finding_change_events v3 repair sweep: emit_disagreed sample (one per release, "
					+ "max {}): {}. produced vs emitRule EQUAL = the producers agreed and rows are merely "
					+ "partly absent; produced > emitRule = they derived different sets. keptAsReappearance "
					+ "= the release's metrics flapped to empty and back WITHIN THE LOOKBACK and the emit "
					+ "misread the return as a first scan (correlate with the metrics-loss probe at rev N-1, "
					+ "not rev N). A flap whose pre-loss snapshot predates the lookback shows BOTH counters "
					+ "zero, so zero does not mean no flap. "
					+ "keptAsNotBornWith may be a lookback artefact rather than a real difference.",
					RepairTally.SAMPLE_LIMIT, settled.disagreementSample);
		}
		if (releasesFailed > 0) {
			// Reported separately and unconditionally: a run where every release throws inserts nothing, so
			// gating this on repairs would make a total failure the quietest outcome of all.
			log.error("finding_change_events v3 repair sweep: {} of {} release(s) failed to repair -- the v3 "
					+ "store may still have holes; see the per-release errors above",
					releasesFailed, releasesFailed + releasesProcessed);
		}
		log.info("finding_change_events v3 repair sweep: done -- {} release(s), {} failed, {} event(s) repaired",
				releasesProcessed, releasesFailed, factsInserted);
		return new V3BackfillResult(0, releasesProcessed, releasesFailed, factsInserted);
	}

	/**
	 * Why the sweep had to insert rows for one metrics revision. A revision is the unit an emit runs on, so
	 * it is also the only unit at which this question has a single answer.
	 */
	enum RepairCause {
		/** The release was CANCELLED/REJECTED then, so the emit's early return was correct. Benign. */
		EMIT_SKIPPED_LIFECYCLE,
		/** No row the sweep offered was already present, and the emit's rule would have kept some. It did not run. */
		EMIT_NEVER_RAN,
		/** Rows were already present, or the emit's rule would have written nothing. Producers differ. */
		EMIT_DISAGREED
	}

	/** What one release's re-diff repaired, and why, per revision. */
	private record RepairDetail(V3WriteResult landed, Map<RepairCause, Integer> revisionsByCause,
			List<String> disagreementSample) {
		static final RepairDetail NOTHING = new RepairDetail(V3WriteResult.NOTHING, Map.of(), List.of());

		int inserted() {
			return landed.landed();
		}
	}

	/**
	 * Running totals for ONE of the sweep's two causes. The sweep keeps a pair of these -- one for orgs whose
	 * v3 backfill has completed, one for orgs still being seeded -- because those two need different
	 * severities and must not be summed into a single number that means neither thing.
	 */
	private static final class RepairTally {
		/**
		 * Bounds BOTH samples the alert carries: the org/release pairs, and the wider per-revision disagreed
		 * lines. Enough to start an investigation, not a wall of text.
		 */
		static final int SAMPLE_LIMIT = 5;

		private int events;
		private int releases;
		private final Map<FindingChangeKind, Integer> byKind = new EnumMap<>(FindingChangeKind.class);
		/** org/release pairs, because a bare release uuid is not resolvable by whoever reads the alert. */
		private final List<String> sample = new ArrayList<>();
		/** Repaired REVISIONS by cause -- revision grain, because that is where an emit either ran or did not. */
		private final Map<RepairCause, Integer> revisionsByCause = new EnumMap<>(RepairCause.class);
		/**
		 * Sampled DISAGREED revisions, kept separately from {@link #sample}. The general sample is drawn from
		 * every repaired release, so where one cause dominates the counts the other cause never gets an
		 * example -- exactly the case an operator needs one for.
		 */
		private final List<String> disagreementSample = new ArrayList<>();

		int revisions(RepairCause c) {
			return revisionsByCause.getOrDefault(c, 0);
		}

		void add(UUID org, UUID releaseUuid, RepairDetail detail) {
			V3WriteResult landed = detail.landed();
			events += landed.landed();
			releases++;
			landed.byKind().forEach((k, v) -> byKind.merge(k, v, Integer::sum));
			detail.revisionsByCause().forEach((c, n) -> revisionsByCause.merge(c, n, Integer::sum));
			if (sample.size() < SAMPLE_LIMIT) {
				sample.add(org + "/" + releaseUuid);
			}
			// One line per RELEASE, not per revision: the point of this sample is the VARIETY of the disagreed
			// bucket, and a single multi-revision release would otherwise take every slot.
			if (disagreementSample.size() < SAMPLE_LIMIT && !detail.disagreementSample().isEmpty()) {
				disagreementSample.add(detail.disagreementSample().get(0));
			}
		}
	}


	/**
	 * Why each repaired revision had to be repaired.
	 *
	 * <p>Extracted and package-private so it can be tested directly: this expression IS the verdict an
	 * operator reads off the alert, and it was previously buried in {@code repairReleaseV3} where no test
	 * could observe it -- swapping two of its arms passed the entire suite.
	 *
	 * <p>Per REVISION, never per release. A release's slice can span several revisions, and each is a
	 * separate emit that either ran or did not; classifying per release would let one benign revision excuse
	 * a genuine lost emit beside it, and would read one dropped emit among healthy ones as a disagreement.
	 */
	Map<RepairCause, Integer> classifyRepairedRevisions(ReleaseData rd, V3WriteResult landed,
			V3Production produced) {
		Map<RepairCause, Integer> byCause = new EnumMap<>(RepairCause.class);
		causeByRevision(rd, landed, produced).values().forEach(c -> byCause.merge(c, 1, Integer::sum));
		return byCause;
	}

	/**
	 * The verdict for EACH repaired revision -- the SINGLE place the cause is decided.
	 *
	 * <p>Both the alert's counts and its per-revision diagnostic derive from this one map. An earlier revision
	 * of this change had the diagnostic re-deriving "is this the disagreed arm" from the same inputs, and it
	 * shipped already drifted: it omitted the lifecycle arm, so a benign CANCELLED/REJECTED skip printed as a
	 * disagreement and could consume the whole bounded sample. That is the same failure
	 * {@link #classifyRepairedRevisions}' own history records -- two copies of one expression, one untested.
	 * Classify once; format from the result.
	 */
	Map<Integer, RepairCause> causeByRevision(ReleaseData rd, V3WriteResult landed, V3Production produced) {
		Map<Integer, RepairCause> byRevision = new LinkedHashMap<>();
		landed.byRevision().forEach((revision, write) -> {
			if (write.landed() <= 0) {
				return; // nothing was repaired at this revision
			}
			// Unreachable by construction -- every written event carries the revision of the pair that
			// produced it, and that pair registered the same key. Kept, and deliberately resolved to the
			// LOUD arm: a revision we cannot explain must not be filed as the benign "not a lost write".
			RevisionProduction production = produced.byRevision().get(revision);
			if (production == null) {
				byRevision.put(revision, RepairCause.EMIT_NEVER_RAN);
				return;
			}
			ReleaseLifecycle atTransition = production.changeDate() != null
					? lifecycleAt(rd, production.changeDate())
					: rd.getLifecycle();
			RepairCause cause;
			if (ReleaseLifecycle.isFindingChangeEmitSuppressed(atTransition)) {
				// MUST use the same predicate as the live emitter. If the two ever diverge, revisions
				// the emitter correctly skipped get classified EMIT_NEVER_RAN instead, and the nightly
				// sweep reports a benign skip to the operator as a lost write -- which is the exact
				// alert this whole line of work exists to silence.
				cause = RepairCause.EMIT_SKIPPED_LIFECYCLE;
			} else if (write.noOfferedRowWasAlreadyPresent() && production.emitRuleWouldHaveProduced() > 0) {
				cause = RepairCause.EMIT_NEVER_RAN;
			} else {
				cause = RepairCause.EMIT_DISAGREED;
			}
			byRevision.put(revision, cause);
		});
		return byRevision;
	}

	/**
	 * Per-revision evidence for the DISAGREED bucket, which the aggregate counts cannot supply.
	 *
	 * <p>Exists because {@code emit_disagreed} is the sweep's CATCH-ALL arm -- "rows were already present, or
	 * the emit's rule would have written nothing" covers several distinct divergences, and the alert's
	 * example list is drawn from ALL repaired releases, so on an instance whose repairs are overwhelmingly
	 * one cause the other cause's examples never appear. An operator with ERROR-only logs and no SQL then has
	 * a count they cannot act on. Each line carries what separates the candidate divergences:
	 * {@code produced} vs {@code emitRule} (how far apart the two producers' sets are -- EQUAL means they
	 * agreed and the rows are simply partly absent, which is a durability question, not a rule one),
	 * {@code offered}/{@code landed} (how much was already stored), and as CONTEXT ONLY {@code firstScan},
	 * {@code inheritedNow} and {@code bornWithInSlice}. Read the last three with their caveats: the producers
	 * are NOT limited to differing at {@code firstScan=true}, {@code inheritedNow} is computed at sweep time
	 * rather than emit time, and {@code bornWithInSlice} is the slice-start snapshot for any release born
	 * before the lookback -- see {@link V3Production}.
	 *
	 * <p>Takes the already-decided causes rather than re-deriving them: see {@link #causeByRevision}.
	 * {@code budget} stops the per-release formatting once the run's bounded sample is full -- the caller
	 * discards the overflow anyway, and this runs inside every repaired release's transaction.
	 */
	List<String> describeDisagreements(UUID org, UUID releaseUuid, EventAttribution attr,
			V3WriteResult landed, V3Production produced, Map<Integer, RepairCause> causes, int budget) {
		if (budget <= 0) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Map.Entry<Integer, RepairCause> e : causes.entrySet()) {
			if (out.size() >= budget) {
				break;
			}
			if (e.getValue() != RepairCause.EMIT_DISAGREED) {
				continue;
			}
			Integer revision = e.getKey();
			RevisionWrite write = landed.byRevision().get(revision);
			RevisionProduction p = produced.byRevision().get(revision);
			if (write == null || p == null) {
				continue; // cannot happen for a DISAGREED verdict, which is only reachable with both present
			}
			out.add(org + "/" + releaseUuid + " " + attr.componentName() + " v" + attr.version()
					+ " @rev" + revision
					+ " at=" + (p.changeDate() == null ? "?" : p.changeDate().toInstant())
					+ " produced=" + p.producedRows()
					+ " emitRule=" + p.emitRuleWouldHaveProduced()
					+ " offered=" + write.offered()
					+ " landed=" + write.landed()
					+ " firstScan=" + p.firstScanPair()
					+ " inheritedNow=" + produced.inheritedNowSize()
					+ " bornWithInSlice=" + produced.bornWithInSliceSize()
					+ " keptAsReappearance=" + p.keptAsReappearance()
					+ " keptAsNotBornWith=" + p.keptAsNotBornWith());
		}
		return out;
	}

	/**
	 * The release's lifecycle as it stood at {@code at}, reconstructed from its recorded {@code LIFECYCLE}
	 * update events.
	 *
	 * <p>Needed because the live emit returns early for an emit-suppressed lifecycle, so a release that
	 * was cancelled or rejected when it was scanned and settled afterwards legitimately has NO events -- and the sweep,
	 * seeing a healthy release with rows missing, would otherwise report a lost emit for something the emit
	 * got right. Reconstructed rather than read live for exactly that reason: the CURRENT lifecycle is not
	 * the one the emit saw.
	 *
	 * <p>Falls back to the current lifecycle when the release records no lifecycle history, which reads as
	 * "not cancelled" for the common case and keeps the classification conservative -- it will call such a
	 * release a lost emit rather than silently excuse it.
	 */
	ReleaseLifecycle lifecycleAt(ReleaseData rd, ZonedDateTime at) {
		List<ReleaseUpdateEvent> lifecycleEvents = (rd.getUpdateEvents() == null) ? List.of()
				: rd.getUpdateEvents().stream()
						.filter(e -> e.rus() == ReleaseUpdateScope.LIFECYCLE && e.date() != null)
						.sorted(Comparator.comparing(ReleaseUpdateEvent::date))
						.toList();
		if (lifecycleEvents.isEmpty()) {
			return rd.getLifecycle();
		}
		ReleaseLifecycle atInstant = null;
		for (ReleaseUpdateEvent e : lifecycleEvents) {
			if (e.date().isAfter(at)) {
				// First transition after `at`: whatever it moved AWAY from is what was in effect.
				return atInstant != null ? atInstant : parseLifecycle(e.oldValue(), rd.getLifecycle());
			}
			atInstant = parseLifecycle(e.newValue(), atInstant);
		}
		return atInstant != null ? atInstant : rd.getLifecycle();
	}

	private ReleaseLifecycle parseLifecycle(String raw, ReleaseLifecycle fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return ReleaseLifecycle.valueOf(raw.trim());
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	/** Re-diff a release's recent (since) metrics_audit slice into v3, inside the caller's tx. Idempotent. */
	private RepairDetail repairReleaseV3(UUID org, UUID releaseUuid, ZonedDateTime since) {
		List<MetricsAudit> auditRows = metricsAuditRepository.findAllRevisionsForEntitySince(
				ENTITY_TYPE, releaseUuid, since);
		if (auditRows.isEmpty()) {
			return RepairDetail.NOTHING;
		}
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid).orElse(null);
		if (rd == null || ReleaseLifecycle.isFindingChangeEmitSuppressed(rd.getLifecycle())
				|| rd.getBranch() == null) {
			return RepairDetail.NOTHING;
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
		V3Production produced = findingComparisonService.produceForReleaseV3(
				auditRows, rd.getMetrics(), attr, inheritedKeys);
		List<FindingChangeEvent> events = produced.events();
		// By-kind because the kind mix is what discriminates the sweep's causes (see repairSweepV3's
		// javadoc). Counts what LANDED, not what was produced: a re-diff re-produces the release's whole
		// recent slice, so the produced mix would describe mostly already-present events.
		V3WriteResult landed = findingDimBackfillService.writeEventsToV3ByKind(org, events);
		if (landed.isEmpty()) {
			return RepairDetail.NOTHING;
		}
		Map<Integer, RepairCause> causes = causeByRevision(rd, landed, produced);
		Map<RepairCause, Integer> byCause = new EnumMap<>(RepairCause.class);
		causes.values().forEach(c -> byCause.merge(c, 1, Integer::sum));
		return new RepairDetail(landed, byCause,
				describeDisagreements(org, releaseUuid, attr, landed, produced, causes,
						RepairTally.SAMPLE_LIMIT));
	}
}

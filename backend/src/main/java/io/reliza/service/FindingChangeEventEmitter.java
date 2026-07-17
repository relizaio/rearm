/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.ComponentData;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;

/**
 * Best-effort, transaction-isolated emitter of the branch-grain {@code finding_change_events_v3}
 * "events-lite" store (board task #38 normalization + the v3 events-lite follow-on; v1/v2 dropped in
 * V64). Extracted into its own bean so the write-time diff-emit runs in a SEPARATE
 * ({@link Propagation#REQUIRES_NEW}) transaction, physically decoupled from the customer-critical
 * metrics + metrics_audit write in {@code SharedReleaseService.saveReleaseMetrics}.
 *
 * <p><b>Why a separate bean.</b> A self-invoked {@code @Transactional} method does not pass through
 * the Spring proxy, so {@code REQUIRES_NEW} would be silently ignored. The emit therefore lives here
 * and is invoked across the bean boundary.
 *
 * <p><b>How it is wired.</b> {@code saveReleaseMetrics} registers a
 * {@code TransactionSynchronization.afterCommit} callback that calls {@link #emit}. This guarantees:
 * <ul>
 *   <li>the emit fires ONLY if the metrics transaction actually COMMITTED -- a rolled-back metrics
 *       write leaves NO orphan finding-change rows;</li>
 *   <li>{@code REQUIRES_NEW} makes it a physically separate transaction, so it can never mark the
 *       metrics transaction rollback-only (the same-tx poison trap the original write-time emit had);</li>
 *   <li>any failure here is caught, logged at ERROR (operator alerting fires on ERROR) and swallowed
 *       -- it can never propagate back into the metrics save.</li>
 * </ul>
 * The {@code ON CONFLICT DO NOTHING} v3 insert keeps the emit idempotent across a scanner retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingChangeEventEmitter {

	private final FindingComparisonService findingComparisonService;
	private final GetComponentService getComponentService;
	private final FindingDimBackfillService findingDimBackfillService;

	/**
	 * Diffs the pre-overwrite ("old live") metrics against the incoming metrics via the SHARED
	 * {@link FindingComparisonService#diffPairToEvents} (same diff the #252 changelog read path uses)
	 * and writes the branch-grain v3 events via {@link #emitV3}.
	 *
	 * <p>Runs in its OWN transaction ({@link Propagation#REQUIRES_NEW}). The caller invokes this from
	 * an afterCommit synchronization and swallows any exception, so a diff-emit failure NEVER rolls
	 * back the metrics/audit write and NEVER blocks ingestion.
	 *
	 * <p>Gates (parity with #252 / the original emit): the caller already guards first-metrics
	 * ({@code oldLiveRaw} is non-null). CANCELLED / REJECTED releases skip emission entirely (their
	 * metrics may be incomplete). The change rows are bucketed at {@code changeDate}
	 * (== the audit row's {@code revisionCreatedDate}) and stamped with {@code toRevision} so dates
	 * line up with backfill and the dedup tuple is unchanged.
	 *
	 * @param r              the release whose metrics were just saved (used for lifecycle + attribution)
	 * @param oldLiveRaw     the pre-overwrite live metrics map (already read before the metrics write)
	 * @param newMetrics     the metrics that were just persisted
	 * @param changeDate     the moment the change took effect (== audit revisionCreatedDate)
	 * @param toRevision     the metrics revision the newer snapshot produced
	 * @throws JacksonException on alias serialization failure (caught by caller)
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void emit(Release r, Map<String, Object> oldLiveRaw, ReleaseMetricsDto newMetrics,
			ZonedDateTime changeDate, int toRevision) throws JacksonException {
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		if (rd.getLifecycle() == ReleaseLifecycle.CANCELLED
				|| rd.getLifecycle() == ReleaseLifecycle.REJECTED) {
			return;
		}
		ReleaseMetricsDto oldLive = (oldLiveRaw == null || oldLiveRaw.isEmpty())
				? new ReleaseMetricsDto()
				: Utils.OM.convertValue(oldLiveRaw, ReleaseMetricsDto.class);
		String componentName = getComponentService.getComponentData(rd.getComponent())
				.map(ComponentData::getName)
				.orElse("");
		FindingComparisonService.EventAttribution attr = new FindingComparisonService.EventAttribution(
				rd.getOrg(), r.getUuid(), rd.getVersion(), rd.getComponent(), componentName, rd.getBranch());
		List<FindingChangeEvent> events = findingComparisonService.diffPairToEvents(
				oldLive, newMetrics, attr, changeDate, toRevision);
		emitV3(rd, oldLive, events);
	}

	/**
	 * LIVE branch-grain v3 emit. On a release's FIRST scan (empty {@code oldLive}) it drops the APPEARED for
	 * findings INHERITED from the branch predecessor (the ~148x fan-out collapse); on a re-scan it writes
	 * the within-release transitions unchanged. Writes v3 in the caller's tx (source of truth).
	 *
	 * <p>This live emit drops inherited APPEARED ONLY on a first scan ({@code oldLive} empty). The backfill
	 * producer ({@code dropInheritedInitialAppearance}) drops each inherited finding's first appearance on
	 * ANY re-scan (trickle-in), so for the common trickle-in shape the live path KEEPS an inherited APPEARED
	 * the backfill would drop. This is still reconstruction-safe (a kept inherited APPEARED == v1/v2, and
	 * writes are insert-only ON CONFLICT DO NOTHING so neither path can delete the other's row), but it means
	 * the trickle-in dedup win is realized by a fresh re-backfill, NOT incrementally on an already-live
	 * instance. Keeping the live emit's first-scan-only rule is deliberate (cheap, no per-scan branch
	 * walk); the periodic v3 repair sweep + a re-backfill converge the store to the deduped set.
	 */
	private void emitV3(ReleaseData rd, ReleaseMetricsDto oldLive, List<FindingChangeEvent> events) {
		List<FindingChangeEvent> v3events = events;
		boolean firstScan = findingComparisonService.findingKeysOf(oldLive).isEmpty();
		if (firstScan) {
			Set<String> inheritedKeys = findingComparisonService.firstScanInheritedKeys(rd);
			if (!inheritedKeys.isEmpty()) {
				v3events = events.stream()
						.filter(ev -> !(ev.getChangeKind() == FindingChangeKind.APPEARED
								&& inheritedKeys.contains(ev.getFindingKey())))
						.collect(Collectors.toList());
			}
		}
		findingDimBackfillService.writeEventsToV3(rd.getOrg(), v3events);
	}
}

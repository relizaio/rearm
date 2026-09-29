/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.reliza.common.SchedulerGuard;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.service.IntegrationService.AffectedRangesFetchResult;
import io.reliza.service.VulnerabilityRecordService.AffectedRangesWindow;
import io.reliza.service.VulnerabilityRecordService.FetchedAffectedRanges;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the affected ranges on {@code vulnerability_records} current: the
 * nightly sweep behind {@link SchedulingService#recomputeVulnerabilityRecords}.
 * The drains fetch ranges for the records they create (see
 * {@code IntegrationService.fetchAffectedRangesInline}); this catches
 * everything else -- records from before ranges existed, records a drain left
 * past its limit, records that gained a GitHub / OSV id or whose advisory was
 * edited upstream, and records due for a refresh by age. Shared, not
 * {@code saas/}: records are shared code, so CE fetches ranges too.
 */
@Slf4j
@Service
public class AffectedRangesService {

	@Autowired
	private IntegrationService integrationService;

	@Autowired
	private VulnerabilityRecordService vulnerabilityRecordService;

	/** Rows per window. Same value as the score conversion's windows, chosen for the same reason. */
	private static final int WINDOW_SIZE = 500;

	/** Wall-clock cap per run, checked between records and windows; the next run resumes. */
	private static final Duration TIME_BUDGET = Duration.ofMinutes(30);

	/**
	 * Unreachable answers in a row after which an org is left alone for the
	 * rest of the run: a Dependency-Track that is down would otherwise cost a
	 * connection attempt or a request timeout on every one of its records. A
	 * failure for one vulnerability does not count -- Dependency-Track
	 * answered -- so a few records that always fail cannot starve the rest of
	 * their org; they stay due and are tried again the next run.
	 */
	private static final int ORG_UNREACHABLE_LIMIT = 3;

	/**
	 * Records fetched from Dependency-Track per run; a record that needs no
	 * request (no GitHub or OSV source, or an org without Dependency-Track)
	 * does not count. Sized for the refresh by age: with the weekly refresh of
	 * records without an {@code updated} date (every Dependency-Track 5
	 * record), 2000 covers about 14,000 records with a GitHub or OSV id per
	 * instance, and a larger instance should raise it. Records due for another
	 * reason go first (see {@link #refreshDueAffectedRanges}), so a limit that
	 * is too small delays refreshes by age only, and the run logs a warning. A
	 * first run over a large backlog still finishes over several nights.
	 * Configurable via {@code relizaprops.affectedRangesFetchLimit}.
	 */
	@Value("${relizaprops.affectedRangesFetchLimit:2000}")
	private int fetchLimit;

	/**
	 * Ranges older than this many days are fetched again whatever else holds.
	 * The backstop for what the other triggers miss: an advisory can be
	 * edited upstream before Dependency-Track mirrors it, and an org can add
	 * Dependency-Track back. Configurable via
	 * {@code relizaprops.affectedRangesRefreshDays}.
	 */
	@Value("${relizaprops.affectedRangesRefreshDays:90}")
	private int refreshDays;

	/**
	 * The same for a record without an upstream {@code updated} date, whose
	 * advisory edits nothing else reveals: every record drained from
	 * Dependency-Track 5, whose finding rows carry no such date. A fix
	 * published for such a record shows within this many days. Configurable
	 * via {@code relizaprops.affectedRangesUndatedRefreshDays}.
	 */
	@Value("${relizaprops.affectedRangesUndatedRefreshDays:7}")
	private int undatedRefreshDays;

	/**
	 * Where the last run's first pass stopped, so the next one goes on from
	 * there rather than reading the start of the table again: without it a
	 * backlog larger than one run's limit would never reach its end, and
	 * records whose fetch keeps failing (they stay due) would take every
	 * run's limit. Per process; null starts at the beginning.
	 */
	private volatile UUID firstPassResumeAfter;

	/** The same for the second pass, the refreshes by age. */
	private volatile UUID secondPassResumeAfter;

	/** What one run has done so far. */
	private static final class Run {
		final long deadline;
		int fetched;
		int changed;
		int failed;
		/** The run ended at the limit or the time budget with records still due. */
		boolean stopped;
		final Set<UUID> skippedOrgs = new HashSet<>();
		final Map<UUID, Integer> unreachableInARow = new HashMap<>();
		/** Records whose fetch failed, so the second pass does not try them again. */
		final Set<UUID> failedRecords = new HashSet<>();

		Run(long deadline) {
			this.deadline = deadline;
		}
	}

	/**
	 * Fetch and store the affected ranges of the records that are due (see
	 * {@code VulnerabilityRecordRepository.findUuidsDueForAffectedRangesIn}),
	 * up to {@link #fetchLimit} fetches and {@link #TIME_BUDGET}, in two
	 * passes, each starting where the last run's stopped: first the records
	 * due for another reason than age (never fetched, marked due, or edited
	 * upstream), then the refreshes by age. On a large instance the
	 * refreshes by age can fill the limit, and would otherwise hold the
	 * others back. An org whose Dependency-Track refuses its key, or cannot
	 * be reached {@link #ORG_UNREACHABLE_LIMIT} times in a row, is skipped
	 * for the rest of the run and its records stay due. An org without
	 * Dependency-Track has its records stamped with the ranges they already
	 * hold, so they are not selected again every night.
	 *
	 * @return records whose stored ranges changed
	 */
	public int refreshDueAffectedRanges() {
		Instant now = Instant.now();
		Instant staleBefore = now.minus(Duration.ofDays(Math.max(1, refreshDays)));
		Instant undatedStaleBefore = now.minus(Duration.ofDays(Math.max(1, undatedRefreshDays)));
		Run run = new Run(System.nanoTime() + TIME_BUDGET.toNanos());
		// Nothing is fetched before the epoch, so these thresholds leave out every refresh by age.
		firstPassResumeAfter = walk(firstPassResumeAfter, Instant.EPOCH, Instant.EPOCH, run);
		if (!run.stopped) secondPassResumeAfter = walk(secondPassResumeAfter, staleBefore, undatedStaleBefore, run);
		String summary = "Affected ranges sweep: {} records fetched, {} changed, {} failed, {} orgs skipped";
		if (run.stopped) {
			log.warn(summary + "; stopped at the fetch limit or time budget with records still due, the rest next run"
					+ " (relizaprops.affectedRangesFetchLimit)",
					run.fetched, run.changed, run.failed, run.skippedOrgs.size());
		} else if (run.fetched > 0 || run.changed > 0 || !run.skippedOrgs.isEmpty()) {
			log.info(summary, run.fetched, run.changed, run.failed, run.skippedOrgs.size());
		}
		return run.changed;
	}

	/**
	 * Fetch the due records of each window from {@code from} on, until the
	 * table ends or the run reaches its limit or budget.
	 *
	 * @return where to go on from next time; null at the end of the table
	 */
	private UUID walk(UUID from, Instant staleBefore, Instant undatedStaleBefore, Run run) {
		UUID cursor = from;
		do {
			AffectedRangesWindow window = vulnerabilityRecordService.nextAffectedRangesWindow(cursor, WINDOW_SIZE,
					staleBefore, undatedStaleBefore);
			for (UUID uuid : window.due()) {
				if (run.fetched >= fetchLimit || System.nanoTime() > run.deadline) {
					run.stopped = true;
					return cursor;
				}
				SchedulerGuard.runIsolated("affected ranges of vulnerability record " + uuid,
						() -> refreshOne(uuid, run));
				// Resume after the last record looked at, so a window cut short is finished next run.
				cursor = uuid;
			}
			cursor = window.nextCursor();
			if (cursor != null && System.nanoTime() > run.deadline) {
				run.stopped = true;
				return cursor;
			}
		} while (cursor != null);
		return null;
	}

	private void refreshOne(UUID uuid, Run run) {
		if (run.failedRecords.contains(uuid)) return;
		Optional<VulnerabilityRecordData> oData = vulnerabilityRecordService.getRecord(uuid)
				.map(VulnerabilityRecordData::dataFromRecord);
		if (oData.isEmpty() || run.skippedOrgs.contains(oData.get().getOrg())) return;
		VulnerabilityRecordData data = oData.get();
		UUID org = data.getOrg();
		AffectedRangesFetchResult result = integrationService.fetchAffectedRanges(org, data);
		if (result.requests() > 0) run.fetched++;
		boolean reachable = switch (result.status()) {
			case FETCHED -> {
				store(uuid, result.fetched(), run);
				yield true;
			}
			case NO_INTEGRATION -> {
				// Nothing to fetch from: keep what the record holds, and stamp it so
				// it is not selected again until the refresh age.
				store(uuid, new FetchedAffectedRanges(data.getAffectedRanges(), ZonedDateTime.now(), null), run);
				yield true;
			}
			case FAILED -> {
				// Dependency-Track answered, just not for this vulnerability; it stays due.
				run.failed++;
				run.failedRecords.add(uuid);
				yield true;
			}
			case AUTH_REJECTED -> {
				run.skippedOrgs.add(org);
				yield false;
			}
			case UNREACHABLE -> {
				if (run.unreachableInARow.merge(org, 1, Integer::sum) >= ORG_UNREACHABLE_LIMIT) {
					run.skippedOrgs.add(org);
				}
				yield false;
			}
		};
		if (reachable) run.unreachableInARow.remove(org);
	}

	private void store(UUID uuid, FetchedAffectedRanges fetched, Run run) {
		if (vulnerabilityRecordService.storeAffectedRanges(uuid, fetched)) run.changed++;
	}
}

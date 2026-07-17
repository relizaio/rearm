/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.AnalyticsMetrics;
import io.reliza.model.AnalyticsMetricsData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.AnalyticsMetricsRepository;
import io.reliza.service.AnalyticsMetricsService;
import io.reliza.service.OrganizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SharedReleaseService.OrgMetricsSignal;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OssAnalyticsMetricsService {

	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private AnalyticsMetricsService analyticsMetricsService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	private final AnalyticsMetricsRepository repository;

	OssAnalyticsMetricsService(AnalyticsMetricsRepository repository) {
		this.repository = repository;
	}

	public void computeAndRecordAnalyticsMetricsForAllOrgs () {
		// Compute analytics for previous day (yesterday). seedToday=true also
		// copies each freshly-finalized row under today's dateKey: at 00:00 UTC
		// "today's" metrics are identical to yesterday-EOD by construction, and
		// the seed guarantees the chart never has a hole at its right edge
		// between midnight and the first change-driven refresh (the refresh
		// signal doesn't move at day rollover, so without the seed an idle org
		// would have no today row until its next release activity).
		ZonedDateTime yesterday = ZonedDateTime.now().minusDays(1);

		var orgs = organizationService.listAllOrganizationData();
		orgs.forEach(org -> {
			if (org.getStatus() != StatusEnum.ARCHIVED &&
					!CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(org.getUuid())) {
				// per-org isolation: one bad org (or a unique-index race with the
				// concurrent today-refresh tick on the seed insert) must not abort
				// the whole nightly pass -- with the hot-recompute fallback gone,
				// aborted orgs would show right-edge chart holes all day
				try {
					computeAndRecordAnalyticsMetricsForOrg(org.getUuid(), yesterday,
							WhoUpdated.getAutoWhoUpdated(), true);
				} catch (Exception e) {
					log.error("daily analytics compute failed for org {} -- continuing with batch", org.getUuid(), e);
				}
			}
		});
	}

	/**
	 * In-memory snapshot of each org's change signal as of its last successful
	 * "today" analytics refresh. Deliberately NOT persisted: on restart or
	 * advisory-lock failover the map is empty, so the next tick recomputes every
	 * org once (~ one midnight-job's worth) and then returns to steady state.
	 * The refreshed rows live in the shared analytics_metrics table, so replica
	 * map divergence can cause a redundant recompute but never staleness.
	 */
	private final Map<UUID, OrgMetricsSignal> todayAnalyticsSignals = new ConcurrentHashMap<>();

	/**
	 * Change-driven refresh of the "today" analytics rows -- the write side of
	 * the DB-only findings-over-time chart. Runs on the
	 * every-{@code relizaprops.analyticsTodayRefreshRate} scheduler tick under
	 * the REFRESH_TODAY_ANALYTICS advisory lock.
	 *
	 * <p>At-least-once ordering: the signal is captured BEFORE the recompute and
	 * stored only after it succeeds, so a write landing mid-recompute shows as a
	 * fresh delta next tick (one redundant recompute, never a lost update). A
	 * failed org keeps its old snapshot and is retried next tick.
	 */
	public void refreshTodayAnalyticsForChangedOrgs() {
		long startedAt = System.currentTimeMillis();
		Map<UUID, OrgMetricsSignal> currentSignals = sharedReleaseService.getOrgMetricsSignals();
		Set<UUID> eligibleOrgs = organizationService.listAllOrganizationData().stream()
				.filter(o -> o.getStatus() != StatusEnum.ARCHIVED
						&& !CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(o.getUuid()))
				.map(od -> od.getUuid())
				.collect(Collectors.toSet());
		int refreshed = 0;
		int failed = 0;
		ZonedDateTime now = ZonedDateTime.now();
		for (Map.Entry<UUID, OrgMetricsSignal> entry : currentSignals.entrySet()) {
			UUID org = entry.getKey();
			if (!eligibleOrgs.contains(org)) continue;
			OrgMetricsSignal signalBeforeCompute = entry.getValue();
			if (signalBeforeCompute.equals(todayAnalyticsSignals.get(org))) continue;
			try {
				computeAndRecordAnalyticsMetricsForOrg(org, now,
						WhoUpdated.getAutoWhoUpdated(), false);
				todayAnalyticsSignals.put(org, signalBeforeCompute);
				refreshed++;
			} catch (Exception e) {
				// keep the stale snapshot -> retried next tick
				failed++;
				log.error("today-analytics refresh failed for org {} -- continuing with batch", org, e);
			}
		}
		if (refreshed > 0 || failed > 0) {
			log.info("today-analytics refresh: {} org(s) recomputed, {} failed, {} ms",
					refreshed, failed, System.currentTimeMillis() - startedAt);
		}
	}

	@Transactional
	public ReleaseMetricsDto computeAndRecordAnalyticsMetricsForOrgAndDate(UUID org, String dateKey, WhoUpdated wu) {
		ZonedDateTime createdDate = java.time.LocalDate.parse(dateKey).atStartOfDay(java.time.ZoneOffset.UTC);
		return computeAndRecordAnalyticsMetricsForOrg(org, createdDate, wu, false);
	}

	/**
	 * Compute + upsert the org-wide analytics row for {@code targetDate}. When
	 * {@code seedTodayFromResult} is set (the midnight yesterday-finalizer),
	 * the computed row is additionally copied under today's dateKey if no
	 * today row exists yet -- values at 00:00 equal yesterday-EOD, so this is
	 * a free seed, and only-if-missing means a fresher change-driven row is
	 * never clobbered.
	 */
	private ReleaseMetricsDto computeAndRecordAnalyticsMetricsForOrg(UUID org, ZonedDateTime targetDate,
			WhoUpdated wu, boolean seedTodayFromResult) {
		String dateKey = AnalyticsMetricsData.obtainAnalyticsDateKey(targetDate);
		ZonedDateTime now = ZonedDateTime.now();
		String todayKey = AnalyticsMetricsData.obtainAnalyticsDateKey(now);

		// Compute for org-wide
		Optional<AnalyticsMetrics> existingAm = repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
		AnalyticsMetrics am = existingAm.orElse(new AnalyticsMetrics());
		AnalyticsMetricsData amd = analyticsMetricsService.computeActualAnalyticsMetricsDataForOrg(org, targetDate);
		save(am, Utils.dataToRecord(amd), wu);
		if (seedTodayFromResult && !todayKey.equals(dateKey)
				&& repository.findAnalyticsMetricsByOrgDateKey(org.toString(), todayKey).isEmpty()) {
			AnalyticsMetricsData todaySeed = AnalyticsMetricsData.analyticsMetricsDataFactory(org, null, amd.getMetrics(), now);
			save(new AnalyticsMetrics(), Utils.dataToRecord(todaySeed), wu);
		}

		// Per-perspective analytics - PART OF REARM PRO

		return amd.getMetrics();
	}

	public void recomputePerspectiveAnalyticsIfNeeded() {
		// PART OF REARM PRO
	}

	@Transactional
	private AnalyticsMetrics save (AnalyticsMetrics am, Map<String,Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty() ||
				null == recordData.get(CommonVariables.ORGANIZATION_FIELD)) {
			throw new IllegalStateException("Analytics metrics must have organization in record data");
		}

		am.setRecordData(recordData);
		am.setOrg(UUID.fromString((String) recordData.get(CommonVariables.ORGANIZATION_FIELD)));
		am.setDateKey((String) recordData.get("dateKey"));
		String perspStr = (String) recordData.get("perspective");
		am.setPerspective((perspStr != null && !perspStr.isEmpty()
				&& !"00000000-0000-0000-0000-000000000000".equals(perspStr))
				? UUID.fromString(perspStr) : null);
		@SuppressWarnings("unchecked")
		Map<String, Object> metricsMap = (Map<String, Object>) recordData.get("metrics");
		if (metricsMap != null) {
			Map<String, Object> nm = new java.util.LinkedHashMap<>();
			for (String field : List.of("critical", "high", "medium", "low", "unassigned",
					"policyViolationsLicenseTotal", "policyViolationsOperationalTotal",
					"policyViolationsSecurityTotal")) {
				Object v = metricsMap.get(field);
				if (v != null) nm.put(field, v);
			}
			am.setNumericMetrics(nm);
		}
		// entity field initializer only stamps construction time; on re-save
		// (the today-row upsert path) it must be bumped explicitly, matching
		// the service-side convention used across the codebase
		am.setLastUpdatedDate(ZonedDateTime.now());
		am = (AnalyticsMetrics) WhoUpdated.injectWhoUpdatedData(am, wu);
		return repository.save(am);
	}

}

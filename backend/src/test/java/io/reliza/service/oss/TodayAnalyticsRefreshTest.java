/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.AnalyticsMetrics;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import io.reliza.repositories.AnalyticsMetricsRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.AnalyticsMetricsService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SharedReleaseService.OrgMetricsSignal;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the DB-only findings-over-time chart redesign:
 *
 * <ul>
 *   <li>the per-org change signal (SUM of per-row metrics_revision counters +
 *       count of scannable-lifecycle releases) that gates the every-N-min
 *       "today" refresh -- including the DRAFT->ASSEMBLED-with-unchanged-metrics
 *       case that a pure revision sum would miss;</li>
 *   <li>the change-driven refresh itself: creates the today row on first
 *       signal, skips unchanged orgs (no write), re-refreshes on a metrics
 *       revision bump (at-least-once semantics);</li>
 *   <li>the read path: chart data comes exclusively from analytics_metrics
 *       rows -- no hot recompute fallback for today.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class TodayAnalyticsRefreshTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private AnalyticsMetricsRepository analyticsMetricsRepository;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private AnalyticsMetricsService analyticsMetricsService;
	@Autowired private OssAnalyticsMetricsService ossAnalyticsMetricsService;
	@Autowired private TestInitializer testInitializer;

	@Test
	public void orgMetricsSignal_sumsRevisions_andCountsScannableLifecycles() {
		UUID org = UUID.randomUUID(); // raw org: signal query groups by record_data org
		saveRelease(org, "DRAFT", 3);
		saveRelease(org, "ASSEMBLED", 5);
		saveRelease(org, "GENERAL_AVAILABILITY", 2); // scannable: counts toward assembled_count
		saveRelease(org, "REJECTED", 7);             // not scannable

		OrgMetricsSignal signal = sharedReleaseService.getOrgMetricsSignals().get(org);
		assertNotNull(signal, "org with releases must have a signal row");
		assertEquals(17, signal.revSum(), "revision sum covers every lifecycle");
		assertEquals(2, signal.assembledCount(), "ASSEMBLED + GA count; DRAFT/REJECTED don't");

		// The gap a pure revision-sum watermark would miss: a release entering the
		// scannable set WITHOUT a metrics write still moves the composite signal.
		Release draft = saveRelease(org, "DRAFT", 0);
		OrgMetricsSignal before = sharedReleaseService.getOrgMetricsSignals().get(org);
		setLifecycle(draft, "ASSEMBLED"); // no metrics_revision bump
		OrgMetricsSignal after = sharedReleaseService.getOrgMetricsSignals().get(org);
		assertEquals(before.revSum(), after.revSum(), "no metrics write happened");
		assertEquals(before.assembledCount() + 1, after.assembledCount(),
				"assembled_count must move on silent assembly");
		assertFalse(after.equals(before), "composite signal must differ");
	}

	@Test
	public void refresh_createsTodayRow_skipsUnchanged_reRefreshesOnRevisionBump() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		String todayKey = LocalDate.now(ZoneOffset.UTC).toString();
		// one release so the org has a signal row (no branch graph needed: the
		// org-wide compute over zero active branches yields an empty-metrics row,
		// which is exactly what the refresh should persist for it)
		Release r = saveRelease(orgUuid, "ASSEMBLED", 1);

		ossAnalyticsMetricsService.refreshTodayAnalyticsForChangedOrgs();
		Optional<AnalyticsMetrics> row = analyticsMetricsRepository
				.findAnalyticsMetricsByOrgDateKey(orgUuid.toString(), todayKey);
		assertTrue(row.isPresent(), "first refresh must create the org's today row");
		ZonedDateTime afterFirst = row.get().getLastUpdatedDate();
		UUID rowUuid = row.get().getUuid();

		// unchanged signal -> the org must be skipped entirely (no save)
		Thread.sleep(15); // ensure a timestamp delta would be observable
		ossAnalyticsMetricsService.refreshTodayAnalyticsForChangedOrgs();
		AnalyticsMetrics unchanged = analyticsMetricsRepository
				.findAnalyticsMetricsByOrgDateKey(orgUuid.toString(), todayKey).orElseThrow();
		assertEquals(rowUuid, unchanged.getUuid());
		assertEquals(afterFirst, unchanged.getLastUpdatedDate(),
				"unchanged org must not be recomputed or re-saved");

		// a metrics write moves the signal -> refresh must pick the org up again
		releaseRepository.bumpMetricsRevision(r.getUuid());
		Thread.sleep(15);
		ossAnalyticsMetricsService.refreshTodayAnalyticsForChangedOrgs();
		AnalyticsMetrics refreshed = analyticsMetricsRepository
				.findAnalyticsMetricsByOrgDateKey(orgUuid.toString(), todayKey).orElseThrow();
		assertEquals(rowUuid, refreshed.getUuid(), "same (org, dateKey) row is upserted, not duplicated");
		assertTrue(refreshed.getLastUpdatedDate().isAfter(afterFirst),
				"revision bump must trigger a re-refresh of the today row");
	}

	@Test
	public void chartReadPath_isDbOnly_noHotRecomputeForToday() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		String todayKey = LocalDate.now(ZoneOffset.UTC).toString();
		ZonedDateTime from = ZonedDateTime.now(ZoneOffset.UTC).minusDays(7);
		ZonedDateTime to = ZonedDateTime.now(ZoneOffset.UTC);

		// no analytics rows at all -> empty chart, even though "today" is in range
		// (pre-redesign this would have hot-computed a today point)
		assertTrue(analyticsMetricsService.getVulnViolationByOrgChartData(orgUuid, from, to).isEmpty(),
				"without stored rows the chart must be empty -- no hot recompute");
		assertTrue(analyticsMetricsService.getFindingsPerDay(orgUuid, todayKey).isEmpty(),
				"findingsPerDay(today) must not fall back to a hot recompute");

		// with a stored today row, its values are served as-is
		AnalyticsMetrics am = new AnalyticsMetrics();
		am.setOrg(orgUuid);
		am.setDateKey(todayKey);
		Map<String, Object> nm = new HashMap<>();
		nm.put("critical", 3);
		nm.put("high", 1);
		am.setNumericMetrics(nm);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("dateKey", todayKey);
		am.setRecordData(recordData);
		analyticsMetricsRepository.save(am);

		var chart = analyticsMetricsService.getVulnViolationByOrgChartData(orgUuid, from, to);
		assertFalse(chart.isEmpty());
		int critical = chart.stream()
				.filter(dto -> "Critical Vulnerabilities".equals(seriesOf(dto)))
				.mapToInt(TodayAnalyticsRefreshTest::countOf).sum();
		assertEquals(3, critical, "today's chart point must come from the stored row");
	}

	// ---- helpers ----

	private static String seriesOf(VulnViolationsChartDto dto) {
		return dto.type();
	}

	private static int countOf(VulnViolationsChartDto dto) {
		return dto.num() == null ? 0 : dto.num();
	}

	private Release saveRelease(UUID orgUuid, String lifecycle, int metricsRevision) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		r.setMetricsRevision(metricsRevision);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", lifecycle);
		recordData.put("artifacts", new java.util.ArrayList<String>());
		r.setRecordData(recordData);
		// metrics with lastScanned so the release doesn't churn in the metrics finders
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("firstScanned", 1_700_000_000.0);
		metrics.put("lastScanned", 9_000_000_000.0);
		r.setMetrics(metrics);
		return releaseRepository.save(r);
	}

	private void setLifecycle(Release r, String lifecycle) {
		Release fresh = releaseRepository.findById(r.getUuid()).orElseThrow();
		Map<String, Object> recordData = fresh.getRecordData();
		recordData.put("lifecycle", lifecycle);
		fresh.setRecordData(recordData);
		releaseRepository.save(fresh);
	}
}

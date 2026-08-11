/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalyticsMetrics;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.AnalyticsMetricsRepository;
import io.reliza.service.oss.OssAnalyticsMetricsService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the KEV series on the findings-over-time charts across its two data
 * paths:
 *
 * <ol>
 *   <li><b>Org path</b> (seeded {@code analytics_metrics} rows): the SQL reads
 *       {@code kevCount} WITHOUT coalescing, so rows written before the key
 *       existed yield null and the mapper omits the KEV point -- a legacy day
 *       must not draw a false "0 KEV exposure" line, and the series starts
 *       where the data starts.</li>
 *   <li><b>Branch path</b> (computed from per-day latest release metrics):
 *       {@code kevCount} is already maintained on release metrics (stamped at
 *       compute, re-stamped by the KEV catalog sync), so the chart emits it
 *       for every point.</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class KevSeriesChartTest {

	@Autowired private AnalyticsMetricsService analyticsMetricsService;
	@Autowired private AnalyticsMetricsRepository analyticsMetricsRepository;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private OssAnalyticsMetricsService ossAnalyticsMetricsService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String KEV_SERIES = "KEV Vulnerabilities";

	private void saveOrgRow(UUID org, String dateKey, Map<String, Object> numericMetrics) {
		AnalyticsMetrics am = new AnalyticsMetrics();
		am.setOrg(org);
		am.setDateKey(dateKey);
		am.setNumericMetrics(numericMetrics);
		analyticsMetricsRepository.save(am);
	}

	private static Optional<VulnViolationsChartDto> kevPointOn(List<VulnViolationsChartDto> dtos, String dateKey) {
		return dtos.stream()
				.filter(d -> KEV_SERIES.equals(d.type()))
				.filter(d -> d.createdDate().toLocalDate().toString().equals(dateKey))
				.findFirst();
	}

	@Test
	public void orgChartOmitsKevForLegacyRowsAndEmitsForNewOnes() {
		Organization org = testInitializer.obtainOrganization();
		ZonedDateTime now = ZonedDateTime.now();
		String legacyKey = now.minusDays(3).toLocalDate().toString();
		String currentKey = now.minusDays(2).toLocalDate().toString();

		// Legacy row: seeded before the kevCount key existed.
		saveOrgRow(org.getUuid(), legacyKey, Map.of(
				"critical", 4, "high", 2, "medium", 1, "low", 0, "unassigned", 0,
				"policyViolationsLicenseTotal", 0, "policyViolationsOperationalTotal", 0,
				"policyViolationsSecurityTotal", 0));
		// Current row: carries the key.
		saveOrgRow(org.getUuid(), currentKey, Map.of(
				"critical", 4, "high", 2, "medium", 1, "low", 0, "unassigned", 0, "kevCount", 3,
				"policyViolationsLicenseTotal", 0, "policyViolationsOperationalTotal", 0,
				"policyViolationsSecurityTotal", 0));

		List<VulnViolationsChartDto> dtos = analyticsMetricsService.listChartDataByOrgDates(
				org.getUuid(), now.minusDays(4), now.minusDays(1));

		assertTrue(kevPointOn(dtos, legacyKey).isEmpty(),
				"legacy row without kevCount must omit the KEV point, not draw a false zero");
		Optional<VulnViolationsChartDto> current = kevPointOn(dtos, currentKey);
		assertTrue(current.isPresent(), "row with kevCount must emit the KEV point");
		assertEquals(3, current.get().num());

		// The other eight series stay intact on BOTH days.
		for (String key : List.of(legacyKey, currentKey)) {
			long seriesOnDay = dtos.stream()
					.filter(d -> d.createdDate().toLocalDate().toString().equals(key)).count();
			assertEquals(key.equals(currentKey) ? 9 : 8, seriesOnDay,
					"unexpected series count on " + key);
		}
	}

	@Test
	public void branchChartEmitsKevFromReleaseMetrics() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String slug = "it-kev-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid())
				.name(slug)
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid)
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.build(), WU).getUuid();

		// Stamp metrics the way the compute service does, with KEV present.
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setCritical(2);
		rmd.setHigh(1);
		rmd.setKevCount(1);
		sharedReleaseService.saveReleaseMetrics(r, rmd);

		ZonedDateTime now = ZonedDateTime.now();
		List<VulnViolationsChartDto> dtos = analyticsMetricsService.getVulnViolationByBranchChartData(
				branch.getUuid(), now.minusDays(1), now.plusDays(1));

		String todayKey = now.toLocalDate().toString();
		Optional<VulnViolationsChartDto> kev = kevPointOn(dtos, todayKey);
		assertTrue(kev.isPresent(), "branch chart must emit the KEV series");
		assertEquals(1, kev.get().num());
		assertEquals(9, dtos.size(), "compute path emits all nine series");
	}

	/**
	 * Goes through the PRODUCTION org-row writer -- the OssAnalyticsMetricsService
	 * save() that the midnight seed and the change-driven today-refresh use --
	 * rather than writing rows via the repository. The KEV series originally
	 * shipped with kevCount added only to AnalyticsMetricsService.save(), a
	 * duplicated whitelist the production path never calls, so the field was
	 * never persisted by a real compute; a repository-written fixture cannot
	 * catch that class of bug.
	 */
	@Test
	public void productionOrgComputePersistsKevCount() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String slug = "it-kevprod-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid())
				.name(slug)
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid)
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.build(), WU).getUuid();

		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(List.of(
				new ReleaseMetricsDto.VulnerabilityDto(
						"pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1", "CVE-2021-44228",
						ReleaseMetricsDto.VulnerabilitySeverity.CRITICAL,
						null, null, null, null, null, null, null, null, null, null, null,
						Boolean.TRUE),
				new ReleaseMetricsDto.VulnerabilityDto(
						"pkg:maven/org.springframework/spring-beans@5.3.17", "CVE-2022-22965",
						ReleaseMetricsDto.VulnerabilitySeverity.HIGH,
						null, null, null, null, null, null, null, null, null, null, null,
						Boolean.FALSE)));
		rmd.computeMetricsFromFacts();
		sharedReleaseService.saveReleaseMetrics(r, rmd);

		ZonedDateTime now = ZonedDateTime.now();
		String todayKey = now.toLocalDate().toString();
		ossAnalyticsMetricsService.computeAndRecordAnalyticsMetricsForOrgAndDate(org.getUuid(), todayKey, WU);

		List<VulnViolationsChartDto> dtos = analyticsMetricsService.listChartDataByOrgDates(
				org.getUuid(), now.minusDays(1), now.plusDays(1));
		Optional<VulnViolationsChartDto> kev = kevPointOn(dtos, todayKey);
		assertTrue(kev.isPresent(),
				"the production seed/refresh writer must persist kevCount so the chart emits the KEV point");
		assertEquals(1, kev.get().num());
		// and the rest of the row is intact
		assertTrue(dtos.stream().anyMatch(d -> "Critical Vulnerabilities".equals(d.type())
				&& d.createdDate().toLocalDate().toString().equals(todayKey) && d.num() == 1),
				"critical count must persist alongside kevCount");
	}
}

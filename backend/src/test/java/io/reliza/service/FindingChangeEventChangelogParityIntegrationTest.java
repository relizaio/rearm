/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.dto.ChangelogRecords.AggregatedOrganizationChangelog;
import io.reliza.dto.ChangelogRecords.MetricsRevisionFindingChange;
import io.reliza.dto.ChangelogRecords.OrganizationChangelog;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.service.FindingComparisonService.AuditChangeReleaseContext;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Parity gate for board task #38 phase 3 (re-source READS). The changelog now reads its
 * "Finding changes over time" list from the persisted {@code finding_change_events} diff table
 * ({@link FindingComparisonService#loadOverTimeFindingChanges}) instead of re-walking
 * {@code metrics_audit} ({@link FindingComparisonService#computeMetricsAuditChanges}). This test
 * re-scans a single release several times (no new release) and asserts the re-sourced changelog
 * output EQUALS the #252 audit-read output for the same org / component / date range -- proving the
 * source swap is behavior-preserving.
 *
 * <p>Phase-1 live emission writes the change rows during {@code saveReleaseMetrics}, so by the time
 * the changelog reads, the diff table is already seeded; the audit history is also present, so the
 * #252 audit-read path can be exercised in the same test as the reference oracle.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FindingChangeEventChangelogParityIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ChangeLogService changeLogService;
	@Autowired private FindingComparisonService findingComparisonService;
	@Autowired private TestInitializer testInitializer;

	private static final String CVE = "CVE-2026-8801";
	private static final String CVE2 = "CVE-2026-8802";
	private static final String PURL = "pkg:npm/example@1.0.0";

	private static VulnerabilityDto vuln(String id, VulnerabilitySeverity sev, Boolean kev) {
		return new VulnerabilityDto(PURL, id, sev, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, kev);
	}

	private static ReleaseMetricsDto metricsWith(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}

	private void saveMetrics(UUID releaseUuid, ReleaseMetricsDto metrics) {
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r, metrics);
	}

	/**
	 * Canonical comparable key for one change DTO, identity-agnostic of surrogate uuid -- captures
	 * changeKind + findingKey + changeDate + previousSeverity + the denormalized identity columns the
	 * UI renders, so the two sources must agree on WHAT changed, WHEN, and the displayed fields.
	 */
	private static String changeKey(MetricsRevisionFindingChange c) {
		String findingKind;
		String id;
		if (c.vulnerability() != null) {
			findingKind = "VULNERABILITY";
			id = c.vulnerability().vulnId() + "|" + nz(c.vulnerability().purl()) + "|" + nz(c.vulnerability().severity());
		} else if (c.violation() != null) {
			findingKind = "VIOLATION";
			id = c.violation().type() + "|" + nz(c.violation().purl());
		} else {
			findingKind = "WEAKNESS";
			id = nz(c.weakness().cweId()) + "|" + nz(c.weakness().ruleId()) + "|" + nz(c.weakness().location());
		}
		return c.changeKind() + "#" + findingKind + "#" + id + "#" + c.releaseUuid() + "#"
				+ c.componentUuid() + "#" + c.changeDate().toInstant() + "#" + nz(c.previousSeverity());
	}

	private static String nz(String s) { return s != null ? s : ""; }
	private static String nz(Object o) { return o != null ? o.toString() : ""; }

	@Test
	public void resourcedChangelog_equalsAuditReadOutput() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		ReleaseDto dto = ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.build();
		UUID releaseUuid = ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated()).getUuid();

		ZonedDateTime windowFrom = ZonedDateTime.now().minusMinutes(5);

		// Re-scan timeline (no new release): APPEARED, then SEVERITY_INCREASED + KEV_ADDED, then a 2nd
		// CVE APPEARED. rev0 (null live) writes no audit row -- the empty baseline.
		saveMetrics(releaseUuid, metricsWith());
		saveMetrics(releaseUuid, metricsWith(vuln(CVE, VulnerabilitySeverity.MEDIUM, false)));
		saveMetrics(releaseUuid, metricsWith(vuln(CVE, VulnerabilitySeverity.CRITICAL, true)));
		saveMetrics(releaseUuid, metricsWith(vuln(CVE, VulnerabilitySeverity.CRITICAL, true),
				vuln(CVE2, VulnerabilitySeverity.LOW, false)));

		ZonedDateTime windowTo = ZonedDateTime.now().plusMinutes(5);

		// --- Reference oracle: the #252 metrics_audit-read path over the same range. ---
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid).orElseThrow();
		String componentName = getComponentService.getComponentData(component.getUuid())
				.map(ComponentData::getName).orElse("");
		AuditChangeReleaseContext ctx = new AuditChangeReleaseContext(
				releaseUuid, rd.getVersion(), rd.getComponent(), componentName,
				rd.getLifecycle(), rd.getMetrics());
		List<MetricsRevisionFindingChange> auditRead = findingComparisonService
				.computeMetricsAuditChanges(List.of(ctx), windowFrom, windowTo);
		assertFalse(auditRead.isEmpty(), "fixture must produce some over-time changes");

		// --- Re-sourced changelog: now reads finding_change_events. ---
		OrganizationChangelog cl = changeLogService.getOrganizationChangelogByDate(
				org.getUuid(), null, windowFrom, windowTo, AggregationType.AGGREGATED, "UTC");
		AggregatedOrganizationChangelog agg = (AggregatedOrganizationChangelog) cl;
		List<MetricsRevisionFindingChange> resourced = agg.overTimeFindingChanges().stream()
				.filter(c -> c.releaseUuid() != null && releaseUuid.equals(c.releaseUuid()))
				.toList();

		// --- PARITY: the two sources must produce the identical set of changes. ---
		Set<String> auditKeys = auditRead.stream()
				.map(FindingChangeEventChangelogParityIntegrationTest::changeKey).collect(Collectors.toSet());
		Set<String> resourcedKeys = resourced.stream()
				.map(FindingChangeEventChangelogParityIntegrationTest::changeKey).collect(Collectors.toSet());
		assertEquals(auditKeys, resourcedKeys,
				"re-sourced changelog (finding_change_events) must EQUAL the #252 audit-read output");
		assertEquals(auditRead.size(), resourced.size(),
				"re-sourced changelog must have the same number of changes as the audit-read output");
	}
}

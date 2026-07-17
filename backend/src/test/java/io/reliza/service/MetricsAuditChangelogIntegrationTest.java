/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.dto.ChangelogRecords.AggregatedOrganizationChangelog;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.dto.ChangelogRecords.MetricsRevisionFindingChange;
import io.reliza.dto.ChangelogRecords.OrganizationChangelog;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * End-to-end integration test for the metrics_audit "Finding changes over time" surface
 * (board task #37): a single release is re-scanned twice (no new release), and the org changelog
 * must surface APPEARED then KEV_ADDED from the persisted metrics_audit history, while the legacy
 * release-anchored {@code findingChanges} remains unaffected for the window.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class MetricsAuditChangelogIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ChangeLogService changeLogService;
	@Autowired private TestInitializer testInitializer;

	private static final String CVE = "CVE-2026-9001";
	private static final String PURL = "pkg:npm/example@1.0.0";

	private static VulnerabilityDto vuln(VulnerabilitySeverity sev, Boolean kev) {
		return new VulnerabilityDto(PURL, CVE, sev, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, kev);
	}

	private static ReleaseMetricsDto metricsWith(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}

	/** Re-fetches the live Release entity and runs one metrics save (writes an audit row of the prior state). */
	private void saveMetrics(UUID releaseUuid, ReleaseMetricsDto metrics) {
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r, metrics);
	}

	@Test
	public void rescanDrivenChanges_surfacedOverTime_legacyChangesUnaffected() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();

		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		ZonedDateTime windowFrom = ZonedDateTime.now().minusMinutes(5);

		ReleaseDto releaseDto = ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.build();
		Release release = ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		UUID releaseUuid = release.getUuid();

		// rev0: initial empty scan. On the first save the live metrics column is null, so no audit
		// row is written -- this just establishes the empty baseline snapshot.
		saveMetrics(releaseUuid, metricsWith());
		// re-scan #1: CVE appears (HIGH, not KEV). Writes an audit row holding the empty state.
		saveMetrics(releaseUuid, metricsWith(vuln(VulnerabilitySeverity.HIGH, false)));
		// re-scan #2: same CVE now KEV-listed. Writes an audit row holding the CVE (non-KEV) state.
		saveMetrics(releaseUuid, metricsWith(vuln(VulnerabilitySeverity.HIGH, true)));

		ZonedDateTime windowTo = ZonedDateTime.now().plusMinutes(5);

		OrganizationChangelog cl = changeLogService.getOrganizationChangelogByDate(
				org.getUuid(), null, windowFrom, windowTo, AggregationType.AGGREGATED, "UTC");

		assertTrue(cl instanceof AggregatedOrganizationChangelog,
				"AGGREGATED mode must return AggregatedOrganizationChangelog");
		AggregatedOrganizationChangelog agg = (AggregatedOrganizationChangelog) cl;

		List<MetricsRevisionFindingChange> overTime = agg.overTimeFindingChanges();
		List<MetricsRevisionFindingChange> forCve = overTime.stream()
				.filter(c -> c.vulnerability() != null && CVE.equals(c.vulnerability().vulnId()))
				.toList();

		assertTrue(forCve.stream().anyMatch(c -> c.changeKind() == FindingChangeKind.APPEARED),
				"Re-scan that introduced the CVE must surface as APPEARED over time");
		assertTrue(forCve.stream().anyMatch(c -> c.changeKind() == FindingChangeKind.KEV_ADDED),
				"Re-scan that KEV-listed the CVE must surface as KEV_ADDED over time");

		// Date ordering: APPEARED must precede KEV_ADDED.
		MetricsRevisionFindingChange appeared = forCve.stream()
				.filter(c -> c.changeKind() == FindingChangeKind.APPEARED).findFirst().orElseThrow();
		MetricsRevisionFindingChange kevAdded = forCve.stream()
				.filter(c -> c.changeKind() == FindingChangeKind.KEV_ADDED).findFirst().orElseThrow();
		assertFalse(kevAdded.changeDate().isBefore(appeared.changeDate()),
				"KEV_ADDED must not predate APPEARED");
		assertEquals(releaseUuid, appeared.releaseUuid());
		assertEquals(component.getUuid(), appeared.componentUuid());

		// Legacy release-anchored aggregation is unaffected: there is only ONE release in the
		// window and no baseline predecessor, so the org-wide findingChanges reports no net
		// appeared/resolved findings from a release-to-release diff.
		assertEquals(0, agg.findingChanges().totalAppeared(),
				"Legacy release-anchored findingChanges must be unchanged (no new release in window)");
		assertEquals(0, agg.findingChanges().totalResolved(),
				"Legacy release-anchored findingChanges must be unchanged (no new release in window)");
	}
}

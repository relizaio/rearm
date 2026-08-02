/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.dto.ChangelogRecords.ComponentChangelog;
import io.reliza.dto.ChangelogRecords.NoneChangelog;
import io.reliza.dto.ChangelogRecords.NoneReleaseChanges;
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
 * Pins the date-window NONE changelog contract (changelog re-scan visibility, proposal 3):
 * every release in the window is SHOWN -- the branch's first release EVER renders as a
 * labeled baseline (baselineRelease=true, empty diffs) instead of being suppressed, and a
 * window that starts after some releases diffs its oldest shown release against the
 * out-of-window predecessor (baselineRelease=false).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ChangelogBaselineVisibilityIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ChangeLogService changeLogService;
	@Autowired private TestInitializer testInitializer;

	private static final String CVE = "CVE-2026-9002";
	private static final String PURL = "pkg:npm/baseline-demo@1.0.0";

	private static ReleaseMetricsDto metricsWith(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}

	private static VulnerabilityDto vuln() {
		return new VulnerabilityDto(PURL, CVE, VulnerabilitySeverity.HIGH, java.util.Set.of(),
				java.util.Set.of(), java.util.Set.of(), null, null, ZonedDateTime.now(),
				null, null, null, null, null, false);
	}

	private UUID createRelease(Component component, Branch branch, Organization org, String version,
			ReleaseMetricsDto metrics) throws RelizaException {
		ReleaseDto dto = ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version(version)
				.build();
		Release r = ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated());
		if (metrics != null) {
			Release live = sharedReleaseService.getRelease(r.getUuid()).orElseThrow();
			sharedReleaseService.saveReleaseMetrics(live, metrics);
		}
		return r.getUuid();
	}

	@Test
	public void dateWindowNone_firstEverReleaseShownAsBaseline_laterWindowUsesPredecessor()
			throws RelizaException, InterruptedException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		ZonedDateTime windowFrom = ZonedDateTime.now().minusMinutes(5);
		createRelease(component, branch, org, "1.0.0", metricsWith());
		Thread.sleep(50);
		ZonedDateTime betweenReleases = ZonedDateTime.now();
		Thread.sleep(50);
		createRelease(component, branch, org, "1.1.0", metricsWith(vuln()));
		ZonedDateTime windowTo = ZonedDateTime.now().plusMinutes(5);

		// Full window: BOTH releases shown; the first-ever one is the labeled baseline.
		ComponentChangelog cl = changeLogService.getComponentChangelogByDate(
				component.getUuid(), null, org.getUuid(), AggregationType.NONE, "UTC", windowFrom, windowTo);
		assertTrue(cl instanceof NoneChangelog, "NONE mode must return NoneChangelog");
		List<NoneReleaseChanges> releases = ((NoneChangelog) cl).branches().get(0).releases();
		assertEquals(2, releases.size(), "date-window NONE must show every release in the window");

		NoneReleaseChanges newest = releases.get(0);
		NoneReleaseChanges oldest = releases.get(1);
		assertTrue(oldest.version().startsWith("1.0.0"), "oldest card must be the first release");
		assertTrue(oldest.baselineRelease(), "first release EVER must carry baselineRelease=true");
		assertEquals(0, oldest.findingChanges().appearedCount(),
				"baseline card has no predecessor to diff against");
		assertFalse(newest.baselineRelease(), "a release with a predecessor is not the baseline");
		assertEquals(1, newest.findingChanges().appearedCount(),
				"pairwise diff vs the shown predecessor must be intact");

		// Narrow window (starts between the releases): only 1.1.0 in window, diffed against the
		// OUT-OF-WINDOW predecessor 1.0.0 -- shown, not a baseline, diff intact.
		ComponentChangelog clNarrow = changeLogService.getComponentChangelogByDate(
				component.getUuid(), null, org.getUuid(), AggregationType.NONE, "UTC", betweenReleases, windowTo);
		List<NoneReleaseChanges> narrowReleases = ((NoneChangelog) clNarrow).branches().get(0).releases();
		assertEquals(1, narrowReleases.size(), "only the in-window release is shown");
		NoneReleaseChanges narrowCard = narrowReleases.get(0);
		assertTrue(narrowCard.version().startsWith("1.1.0"));
		assertFalse(narrowCard.baselineRelease(),
				"a release with an out-of-window predecessor is NOT the baseline");
		assertEquals(1, narrowCard.findingChanges().appearedCount(),
				"oldest in-window release must diff against the out-of-window predecessor");
	}
}

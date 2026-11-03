/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AnalyticsDtos.MostVulnerableComponent;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.BranchRepository;
import io.reliza.repositories.ComponentRepository;
import io.reliza.service.oss.OssAnalyticsMetricsService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Findings analytics skip archived branches, branches of archived components and EXCLUDED
 * branches on every aggregate path ({@link AnalyticsMetricsService#isAnalyticsEligible}): the org
 * daily row, the component rows behind the
 * most-vulnerable-components widget and the component drill-down. The single-branch drill-down
 * still shows the branch asked for.
 *
 * <p>Each release carries one HIGH vulnerability with a release-specific id, so the merged
 * vulnerability ids name exactly the branches that were counted.
 */
@SpringBootTest(classes = {App.class})
public class FindingAnalyticsSkipArchivedTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String COUNTED = "CVE-FASA-ACTIVE";

	@Autowired private AnalyticsMetricsService analyticsMetricsService;
	@Autowired private OssAnalyticsMetricsService ossAnalyticsMetricsService;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private BranchRepository branchRepository;
	@Autowired private ComponentRepository componentRepository;
	@Autowired private TestInitializer testInitializer;

	/** The seeded org: uuids the assertions need. */
	private record Fixture(UUID org, UUID compActive, UUID compArchived, UUID archivedBranch,
			String dateKey) {}

	private Fixture seed() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();

		// active component: its base branch counts; an archived and an EXCLUDED branch do not
		UUID compActive = createComponent(org, "fasa-active", ComponentType.COMPONENT);
		release(org, compActive, branch(compActive, "main"), "1.0.0", COUNTED);
		UUID archivedBranch = branch(compActive, "feature/archived");
		release(org, compActive, archivedBranch, "2.0.0", "CVE-FASA-ARCHBR");
		patchBranch(archivedBranch, CommonVariables.STATUS_FIELD, StatusEnum.ARCHIVED.name());
		UUID excludedBranch = branch(compActive, "feature/excluded");
		release(org, compActive, excludedBranch, "3.0.0", "CVE-FASA-EXCL");
		patchBranch(excludedBranch, "findingAnalyticsParticipation",
				BranchData.FindingAnalyticsParticipation.EXCLUDED.name());

		// archived component whose branch was left active, as a legacy row would be
		UUID compArchived = createComponent(org, "fasa-archcomp", ComponentType.COMPONENT);
		UUID archivedCompMain = branch(compArchived, "main");
		release(org, compArchived, archivedCompMain, "1.0.0", "CVE-FASA-ARCHCOMP");

		patchComponentStatus(compArchived, StatusEnum.ARCHIVED);
		String dateKey = LocalDate.now(ZoneOffset.UTC).toString();
		return new Fixture(org, compActive, compArchived, archivedBranch, dateKey);
	}

	@Test
	public void orgRowCountsOnlyTheEligibleBranch() throws RelizaException {
		Fixture f = seed();
		ossAnalyticsMetricsService.computeAndRecordAnalyticsMetricsForOrgAndDate(f.org(), f.dateKey(), WU);

		assertEquals(Set.of(COUNTED), vulnIds(analyticsMetricsService
				.getFindingsPerDay(f.org(), f.dateKey()).orElseThrow()), "org daily row");
	}

	@Test
	public void mostVulnerableComponentsCountOnlyTheEligibleBranch() throws RelizaException {
		Fixture f = seed();
		// fresh org: no stored component rows yet, so the widget computes and records them once
		List<MostVulnerableComponent> mvc = analyticsMetricsService.mostVulnerableComponentsPerOrg(
				f.org(), ZonedDateTime.now(ZoneOffset.UTC), ComponentType.COMPONENT, 50, null);
		Map<UUID, Integer> highByComponent = mvc.stream()
				.collect(Collectors.toMap(MostVulnerableComponent::componentuuid, m -> m.metrics().getHigh()));
		assertEquals(Map.of(f.compActive(), 1), highByComponent,
				"only the active branch of the active component counts; the archived component is absent");
	}

	@Test
	public void componentDrillDownSkipsAndBranchDrillDownShowsTheBranchAskedFor() throws RelizaException {
		Fixture f = seed();
		assertEquals(Set.of(COUNTED), vulnIds(analyticsMetricsService
				.getFindingsPerDayForComponent(f.compActive(), f.dateKey()).orElseThrow()));
		assertTrue(analyticsMetricsService.getFindingsPerDayForComponent(f.compArchived(), f.dateKey()).isEmpty(),
				"an archived component counts nothing");
		assertEquals(Set.of("CVE-FASA-ARCHBR"), vulnIds(analyticsMetricsService
				.getFindingsPerDayForBranch(f.archivedBranch(), f.dateKey()).orElseThrow()),
				"the single-branch drill-down shows the archived branch asked for");
	}

	// ---- helpers ----

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static Set<String> vulnIds(ReleaseMetricsDto m) {
		return m.getVulnerabilityDetails() == null ? Set.of()
				: m.getVulnerabilityDetails().stream().map(ReleaseMetricsDto.VulnerabilityDto::vulnId)
						.collect(Collectors.toSet());
	}

	private UUID createComponent(UUID org, String prefix, ComponentType type) throws RelizaException {
		return componentService.createComponent(CreateComponentDto.builder()
				.organization(org)
				.name(prefix + "-" + suffix())
				.type(type)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
	}

	private UUID branch(UUID component, String name) throws RelizaException {
		return branchService.findBranchByName(component, name, true, WU).orElseThrow().getUuid();
	}

	/** An ASSEMBLED release with one HIGH vulnerability. */
	private void release(UUID org, UUID component, UUID branch, String version, String vulnId)
			throws RelizaException {
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(component)
				.branch(branch)
				.org(org)
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version(version)
				.build(), WU).getUuid();
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(List.of(new ReleaseMetricsDto.VulnerabilityDto(
				"pkg:npm/fasa-" + vulnId.toLowerCase() + "@1.0.0", vulnId,
				ReleaseMetricsDto.VulnerabilitySeverity.HIGH,
				null, null, null, null, null, null, null, null, null, null, null, Boolean.FALSE)));
		rmd.computeMetricsFromFacts();
		sharedReleaseService.saveReleaseMetrics(sharedReleaseService.getRelease(releaseUuid).orElseThrow(), rmd);
	}

	private void patchBranch(UUID branchUuid, String key, Object value) {
		Branch b = branchRepository.findById(branchUuid).orElseThrow();
		Map<String, Object> recordData = b.getRecordData();
		recordData.put(key, value);
		b.setRecordData(recordData);
		branchRepository.save(b);
	}

	private void patchComponentStatus(UUID componentUuid, StatusEnum status) {
		Component c = componentRepository.findById(componentUuid).orElseThrow();
		Map<String, Object> recordData = c.getRecordData();
		recordData.put(CommonVariables.STATUS_FIELD, status.name());
		c.setRecordData(recordData);
		componentRepository.save(c);
	}
}

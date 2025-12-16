/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.AnalyticsMetrics;
import io.reliza.model.AnalyticsMetricsData;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.dto.AnalyticsDtos.ReleasesPerBranch;
import io.reliza.model.dto.AnalyticsDtos.ReleasesPerComponent;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import io.reliza.model.dto.AnalyticsDtos;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.repositories.AnalyticsMetricsRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AnalyticsMetricsService {

	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	BranchService branchService;

	@Autowired
	SharedReleaseService sharedReleaseService;
	
	private final AnalyticsMetricsRepository repository;

	AnalyticsMetricsService(
		AnalyticsMetricsRepository repository
	) {
	    this.repository = repository;
	}
	
	public List<AnalyticsMetricsData> listAnalyticsMetricsByOrgDates(UUID org, ZonedDateTime dateFrom,
			ZonedDateTime dateTo) {
		var ams = repository.findAnalyticsMetricsByOrgDates(org.toString(), dateFrom, dateTo);
		return ams.stream().map(AnalyticsMetricsData::dataFromRecord).toList();
	}
	
	private Optional<AnalyticsMetrics> findAnalyticsMetricsByOrgDate(UUID org, ZonedDateTime date) {
		String dateKey = AnalyticsMetricsData.obtainAnalyticsDateKey(date);
		return repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
	}
	
	public List<VulnViolationsChartDto> getVulnViolationByOrgChartData(UUID org, ZonedDateTime dateFrom,
			ZonedDateTime dateTo) {
		ZonedDateTime today = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
		if (dateTo.isAfter(today)) dateTo = today;
		var amds = listAnalyticsMetricsByOrgDates(org, dateFrom, dateTo);
		var chartData = new LinkedList<>(amds
				.stream().map(amd -> amd.convertToChartDto()).flatMap(List::stream).toList());
		var todaysData = computeActualAnalyticsMetricsDataForOrg(org, ZonedDateTime.now());
		chartData.addAll(todaysData.convertToChartDto());
		return chartData;
	}
	
	protected void computeAndRecordAnalyticsMetricsForAllOrgs () {
		var orgs = organizationService.listAllOrganizationData();
		orgs.forEach(org -> {
			if (org.getStatus() != StatusEnum.ARCHIVED && 
					!CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(org.getUuid())) {
				computeAndRecordAnalyticsMetricsPerOrg(org.getUuid());
			}
		});
		
	}
	
	private void computeAndRecordAnalyticsMetricsPerOrg(UUID org) {
		AnalyticsMetrics am = new AnalyticsMetrics();
		Optional<AnalyticsMetrics> existingAm = findAnalyticsMetricsByOrgDate(org, am.getCreatedDate());
		if (existingAm.isEmpty()) {
			AnalyticsMetricsData amd = computeActualAnalyticsMetricsDataForOrg(org, am.getCreatedDate());
			save(am, Utils.dataToRecord(amd), WhoUpdated.getAutoWhoUpdated());
		}
	}
	
	private AnalyticsMetricsData computeActualAnalyticsMetricsDataForOrg (UUID org, ZonedDateTime createdDate) {
		var activeBranches = branchService.listBranchesOfOrg(org);
		List<ReleaseData> latestReleasesOfBranches;
		try (ForkJoinPool customPool = new ForkJoinPool(4)) {
			latestReleasesOfBranches = customPool.submit(() -> 
				activeBranches.parallelStream()
					.map(ab -> sharedReleaseService.getReleaseDataOfBranch(org, ab.getUuid(), ReleaseLifecycle.ASSEMBLED))
					.filter(Optional::isPresent)
					.map(Optional::get)
					.collect(Collectors.toList())
			).get();
		} catch (Exception e) {
			log.error("Error in parallel release fetch", e);
			latestReleasesOfBranches = new LinkedList<>();
		}
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		latestReleasesOfBranches.forEach(rd -> rmd.mergeWithByContent(rd.getMetrics()));
		return AnalyticsMetricsData.analyticsMetricsDataFactory(org, rmd, createdDate);
	}
	
	@Transactional
	private AnalyticsMetrics save (AnalyticsMetrics am, Map<String,Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty() || 
				null == recordData.get(CommonVariables.ORGANIZATION_FIELD)) {
			throw new IllegalStateException("Analytics metrics must have organization in record data");
		}
		
		am.setRecordData(recordData);
		am = (AnalyticsMetrics) WhoUpdated.injectWhoUpdatedData(am, wu);
		return repository.save(am);
	}
	
	public List<ReleasesPerComponent> analyticsComponentsWithMostRecentReleases (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization) {
		List<ReleasesPerComponent> res = new ArrayList<>();
		var objList = repository.analyticsComponentsWithMostReleases(cutOffDate, compType.name(),
				maxComponents, organization.toString());
		if (null != objList && !objList.isEmpty()) {
			res = objList.stream().map(AnalyticsDtos::mapDbOutputToReleasePerComponent).toList();
		}
		return res;
	}
	
	public List<ReleasesPerBranch> analyticsBranchesWithMostRecentReleases (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization) {
		List<ReleasesPerBranch> res = new ArrayList<>();
		var objList = repository.analyticsBranchesWithMostReleases(cutOffDate, compType.name(),
				maxComponents, organization.toString());
		if (null != objList && !objList.isEmpty()) {
			res = objList.stream().map(AnalyticsDtos::mapDbOutputToReleasePerBranch).toList();
		}
		return res;
	}
	
}

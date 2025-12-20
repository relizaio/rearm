/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalyticsMetrics;
import io.reliza.model.AnalyticsMetricsData;
import io.reliza.model.BranchData;
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
	private OrganizationService organizationService;
	
	@Autowired
	private BranchService branchService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private VulnAnalysisService vulnAnalysisService;
	
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
	
	public Optional<ReleaseMetricsDto> getFindingsPerDay(UUID org, String dateKey) {
		Optional<AnalyticsMetrics> existingAm = repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
		
		if (existingAm.isPresent()) {
			return existingAm.map(AnalyticsMetricsData::dataFromRecord)
					.map(AnalyticsMetricsData::getMetrics);
		}
		
		// If not found in DB, check if dateKey matches today's date
		java.time.LocalDate requestedDate = java.time.LocalDate.parse(dateKey);
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		
		if (requestedDate.equals(today)) {
			// Compute metrics for today without saving
			ZonedDateTime createdDate = requestedDate.atTime(23, 59, 59).atZone(java.time.ZoneOffset.UTC);
			AnalyticsMetricsData amd = computeActualAnalyticsMetricsDataForOrg(org, createdDate);
			var metrics = amd.getMetrics().clone();
			vulnAnalysisService.processReleaseMetricsDto(org, org, AnalysisScope.ORG, metrics);
			return Optional.of(metrics);
		}
		
		return Optional.empty();
	}
	
	public Optional<ReleaseMetricsDto> getFindingsPerDayForComponent(UUID componentUuid, String dateKey) {
		java.time.LocalDate requestedDate = java.time.LocalDate.parse(dateKey);
		ZonedDateTime upToDate = requestedDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC);
		
		// Get all active branches of the component
		List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, StatusEnum.ACTIVE);
		if (branches.isEmpty()) {
			return Optional.empty();
		}
		
		UUID org = branches.get(0).getOrg();
		
		// Get latest release of each branch up to the specified date
		List<ReleaseData> latestReleasesOfBranches = branches.stream()
				.map(b -> sharedReleaseService.getReleaseDataOfBranch(org, b.getUuid(), ReleaseLifecycle.ASSEMBLED, upToDate))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(Collectors.toList());
		
		if (latestReleasesOfBranches.isEmpty()) {
			return Optional.empty();
		}
		
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		latestReleasesOfBranches.forEach(rd -> {
			ReleaseMetricsDto rmd2 = rd.getMetrics();
			if (rmd2 != null) {
				rmd2.enrichSourcesWithRelease(rd.getUuid());
				rmd.mergeWithByContent(rmd2);
			}
		});
		vulnAnalysisService.processReleaseMetricsDto(org, componentUuid, AnalysisScope.COMPONENT, rmd);
		return Optional.of(rmd);
	}
	
	public Optional<ReleaseMetricsDto> getFindingsPerDayForBranch(UUID branchUuid, String dateKey) {
		java.time.LocalDate requestedDate = java.time.LocalDate.parse(dateKey);
		ZonedDateTime upToDate = requestedDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC);
		
		// Get branch to find org
		var branchOpt = branchService.getBranchData(branchUuid);
		if (branchOpt.isEmpty()) {
			return Optional.empty();
		}
		
		UUID org = branchOpt.get().getOrg();
		
		// Get latest release of the branch up to the specified date
		Optional<ReleaseData> releaseOpt = sharedReleaseService.getReleaseDataOfBranch(org, branchUuid, ReleaseLifecycle.ASSEMBLED, upToDate);
		
		if (releaseOpt.isEmpty()) {
			return Optional.empty();
		}
		
		ReleaseData rd = releaseOpt.get();
		ReleaseMetricsDto rmd = rd.getMetrics();
		if (rmd != null) {
			rmd.enrichSourcesWithRelease(rd.getUuid());
			vulnAnalysisService.processReleaseMetricsDto(org, branchUuid, AnalysisScope.BRANCH, rmd);
		}
		return Optional.ofNullable(rmd);
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
	
	public List<VulnViolationsChartDto> getVulnViolationByComponentChartData(UUID componentUuid, 
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		ZonedDateTime today = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
		if (dateTo.isAfter(today)) dateTo = today;
		
		// Get all active branches of the component
		List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, StatusEnum.ACTIVE);
		if (branches.isEmpty()) {
			return new LinkedList<>();
		}
		
		UUID org = branches.get(0).getOrg();
		
		// Collect all releases from all branches within the date range
		List<ReleaseData> allReleasesInRange = new LinkedList<>();
		for (BranchData branch : branches) {
			List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
					branch.getUuid(), dateFrom, dateTo, ReleaseLifecycle.ASSEMBLED);
			allReleasesInRange.addAll(branchReleases);
		}
		
		if (allReleasesInRange.isEmpty()) {
			return new LinkedList<>();
		}
		
		// Group releases by date and branch, pick the latest release per branch per day
		// Then merge all branch metrics for each day
		Map<LocalDate, Map<UUID, ReleaseData>> latestReleasePerBranchPerDay = new HashMap<>();
		for (ReleaseData rd : allReleasesInRange) {
			LocalDate releaseDate = rd.getCreatedDate().toLocalDate();
			UUID branchUuid = rd.getBranch();
			latestReleasePerBranchPerDay.computeIfAbsent(releaseDate, k -> new HashMap<>());
			Map<UUID, ReleaseData> branchMap = latestReleasePerBranchPerDay.get(releaseDate);
			ReleaseData existing = branchMap.get(branchUuid);
			if (existing == null || rd.getCreatedDate().isAfter(existing.getCreatedDate())) {
				branchMap.put(branchUuid, rd);
			}
		}
		
		// Convert each day's merged metrics to chart data
		List<VulnViolationsChartDto> chartData = new LinkedList<>();
		for (Map.Entry<LocalDate, Map<UUID, ReleaseData>> dayEntry : latestReleasePerBranchPerDay.entrySet()) {
			ReleaseMetricsDto mergedRmd = new ReleaseMetricsDto();
			ZonedDateTime dateForChart = null;
			for (ReleaseData rd : dayEntry.getValue().values()) {
				ReleaseMetricsDto rmd = rd.getMetrics();
				if (rmd != null) {
					rmd.enrichSourcesWithRelease(rd.getUuid());
					mergedRmd.mergeWithByContent(rmd);
				}
				if (dateForChart == null) {
					dateForChart = dayEntry.getKey().atStartOfDay(rd.getCreatedDate().getZone());
				}
			}
			if (dateForChart != null) {
				chartData.addAll(mergedRmd.convertToChartDto(dateForChart));
			}
		}
		
		return chartData;
	}
	
	public List<VulnViolationsChartDto> getVulnViolationByBranchChartData(UUID branchUuid, 
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		ZonedDateTime today = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
		if (dateTo.isAfter(today)) dateTo = today;
		
		// Get all releases of the branch within the date range (ASSEMBLED or higher)
		List<ReleaseData> releasesInRange = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
				branchUuid, dateFrom, dateTo, ReleaseLifecycle.ASSEMBLED);

		if (releasesInRange.isEmpty()) {
			return new LinkedList<>();
		}

		UUID org = releasesInRange.get(0).getOrg();
		
		// Group releases by date (LocalDate) and pick the latest release for each day
		Map<LocalDate, ReleaseData> latestReleasePerDay = new HashMap<>();
		for (ReleaseData rd : releasesInRange) {
			LocalDate releaseDate = rd.getCreatedDate().toLocalDate();
			ReleaseData existing = latestReleasePerDay.get(releaseDate);
			if (existing == null || rd.getCreatedDate().isAfter(existing.getCreatedDate())) {
				latestReleasePerDay.put(releaseDate, rd);
			}
		}
		
		// Convert each day's latest release to chart data
		List<VulnViolationsChartDto> chartData = new LinkedList<>();
		for (Map.Entry<LocalDate, ReleaseData> entry : latestReleasePerDay.entrySet()) {
			ReleaseData rd = entry.getValue();
			ReleaseMetricsDto rmd = rd.getMetrics();
			if (rmd != null) {
				rmd.enrichSourcesWithRelease(rd.getUuid());
				ZonedDateTime dateForChart = entry.getKey().atStartOfDay(rd.getCreatedDate().getZone());
				chartData.addAll(rmd.convertToChartDto(dateForChart));
			}
		}
		
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
	
	@Transactional
	public ReleaseMetricsDto computeAndRecordAnalyticsMetricsForOrgAndDate(UUID org, String dateKey, WhoUpdated wu) {
		ZonedDateTime createdDate = java.time.LocalDate.parse(dateKey).atStartOfDay(java.time.ZoneOffset.UTC);
		Optional<AnalyticsMetrics> existingAm = repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
		AnalyticsMetrics am = existingAm.orElse(new AnalyticsMetrics());
		AnalyticsMetricsData amd = computeActualAnalyticsMetricsDataForOrg(org, createdDate);
		save(am, Utils.dataToRecord(amd), wu);
		return amd.getMetrics();
	}
	
	private AnalyticsMetricsData computeActualAnalyticsMetricsDataForOrg (UUID org, ZonedDateTime createdDate) {
		ZonedDateTime upToDate = createdDate.toLocalDate().plusDays(1).atStartOfDay(createdDate.getZone());
		var activeBranches = branchService.listBranchesOfOrg(org);
		List<ReleaseData> latestReleasesOfBranches;
		try (ForkJoinPool customPool = new ForkJoinPool(4)) {
			latestReleasesOfBranches = customPool.submit(() -> 
				activeBranches.parallelStream()
					.map(ab -> sharedReleaseService.getReleaseDataOfBranch(org, ab.getUuid(), ReleaseLifecycle.ASSEMBLED, upToDate))
					.filter(Optional::isPresent)
					.map(Optional::get)
					.collect(Collectors.toList())
			).get();
		} catch (Exception e) {
			log.error("Error in parallel release fetch", e);
			latestReleasesOfBranches = new LinkedList<>();
		}
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		latestReleasesOfBranches.forEach(rd -> {
			ReleaseMetricsDto rmd2 = rd.getMetrics();
			rmd2.enrichSourcesWithRelease(rd.getUuid());
			rmd.mergeWithByContent(rmd2);
		});
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

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import io.reliza.model.ComponentAnalyticsMetrics;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.repositories.ComponentAnalyticsMetricsRepository;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.dto.AnalyticsDtos.ReleasesPerBranch;
import io.reliza.model.dto.AnalyticsDtos.ReleasesPerComponent;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import io.reliza.model.dto.AnalyticsDtos.MostVulnerableComponent;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.repositories.AnalyticsMetricsRepository;
import io.reliza.repositories.dao.ReleasesPerBranchDao;
import io.reliza.repositories.dao.ReleasesPerComponentDao;
import io.reliza.repositories.dao.VulnViolationsChartDao;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AnalyticsMetricsService {

	@Autowired
	private BranchService branchService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private VulnAnalysisService vulnAnalysisService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private ComponentAnalyticsMetricsRepository componentAnalyticsMetricsRepository;
	
	private final AnalyticsMetricsRepository repository;

	AnalyticsMetricsService(
		AnalyticsMetricsRepository repository
	) {
	    this.repository = repository;
	}
	
	public List<VulnViolationsChartDto> listChartDataByOrgDates(UUID org, ZonedDateTime dateFrom,
			ZonedDateTime dateTo) {
		String dateKeyFrom = AnalyticsMetricsData.obtainAnalyticsDateKey(dateFrom);
		String dateKeyTo = AnalyticsMetricsData.obtainAnalyticsDateKey(dateTo);
		var zone = dateFrom.getZone();
		var rows = repository.findAnalyticsChartDataByOrgDates(org.toString(), dateKeyFrom, dateKeyTo);
		return rows.stream().flatMap(r -> mapChartDaoToChartDtos(r, zone).stream()).toList();
	}
	
	public List<VulnViolationsChartDto> listChartDataByOrgPerspectiveDates(UUID org, UUID perspectiveUuid,
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		String dateKeyFrom = AnalyticsMetricsData.obtainAnalyticsDateKey(dateFrom);
		String dateKeyTo = AnalyticsMetricsData.obtainAnalyticsDateKey(dateTo);
		var zone = dateFrom.getZone();
		var rows = repository.findAnalyticsChartDataByOrgPerspectiveDates(
				org.toString(), perspectiveUuid.toString(), dateKeyFrom, dateKeyTo);
		return rows.stream().flatMap(r -> mapChartDaoToChartDtos(r, zone).stream()).toList();
	}
	
	private List<VulnViolationsChartDto> mapChartDaoToChartDtos(VulnViolationsChartDao row, java.time.ZoneId zone) {
		LocalDate localDate = LocalDate.parse(row.getDateKey());
		ZonedDateTime date = localDate.atStartOfDay(zone);
		return List.of(
			new VulnViolationsChartDto(date, row.getCritical(), "Critical Vulnerabilities"),
			new VulnViolationsChartDto(date, row.getHigh(), "High Vulnerabilities"),
			new VulnViolationsChartDto(date, row.getMedium(), "Medium Vulnerabilities"),
			new VulnViolationsChartDto(date, row.getLow(), "Low Vulnerabilities"),
			new VulnViolationsChartDto(date, row.getUnassigned(), "Unassigned Vulnerabilities"),
			new VulnViolationsChartDto(date, row.getLicenseViolations(), "License Violations"),
			new VulnViolationsChartDto(date, row.getOperationalViolations(), "Operational Violations"),
			new VulnViolationsChartDto(date, row.getSecurityViolations(), "Security Violations")
		);
	}
	
	public Optional<AnalyticsMetricsData> findAnalyticsMetricsByOrgPerspectiveDateKey(UUID org, UUID perspective, String dateKey) {
		Optional<AnalyticsMetrics> am = repository.findAnalyticsMetricsByOrgPerspectiveDateKey(
				org.toString(), perspective.toString(), dateKey);
		return am.map(AnalyticsMetricsData::dataFromRecord);
	}
	
	public Optional<ReleaseMetricsDto> getFindingsPerDay(UUID org, String dateKey) {
		Optional<AnalyticsMetrics> existingAm = repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
		if (existingAm.isEmpty()) {
			// DB-only: today's row is maintained by the change-driven refresh and
			// the midnight seed; there is no hot recompute fallback anymore.
			return Optional.empty();
		}
		Optional<ReleaseMetricsDto> metrics = existingAm.map(AnalyticsMetricsData::dataFromRecord)
				.map(AnalyticsMetricsData::getMetrics);
		// Preserve the pre-existing behavior split: today's drill-down applied the
		// org-scope vuln analysis overlay (the old hot path did this in-memory),
		// historical days are returned as stored. Same overlay, now applied to the
		// stored row instead of a fresh recompute.
		java.time.LocalDate requestedDate = java.time.LocalDate.parse(dateKey);
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		if (metrics.isPresent() && requestedDate.equals(today)) {
			var overlaid = metrics.get().clone();
			vulnAnalysisService.processReleaseMetricsDto(org, org, AnalysisScope.ORG, overlaid);
			return Optional.of(overlaid);
		}
		return metrics;
	}
	
	
	public Optional<ReleaseMetricsDto> getFindingsPerDayForComponent(UUID componentUuid, String dateKey) {
		java.time.LocalDate requestedDate = java.time.LocalDate.parse(dateKey);
		ZonedDateTime upToDate = requestedDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC);
		
		log.info("FINDINGS_COMPARISON: getFindingsPerDayForComponent - component: {}, dateKey: {}, upToDate: {}", 
			componentUuid, dateKey, upToDate);
		
		// Get all active branches of the component, excluding branches with EXCLUDED findingAnalyticsParticipation
		List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, StatusEnum.ACTIVE).stream()
				.filter(b -> b.getFindingAnalyticsParticipation() != BranchData.FindingAnalyticsParticipation.EXCLUDED)
				.collect(Collectors.toList());
		if (branches.isEmpty()) {
			log.warn("FINDINGS_COMPARISON: No active branches found for component {}", componentUuid);
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
			log.warn("FINDINGS_COMPARISON: No releases found up to date {} for component {}", upToDate, componentUuid);
			return Optional.empty();
		}
		
		log.info("FINDINGS_COMPARISON: Found {} releases for dateKey {}", latestReleasesOfBranches.size(), dateKey);
		latestReleasesOfBranches.forEach(rd -> 
			log.info("FINDINGS_COMPARISON:   - Release: version={}, uuid={}, createdDate={}", 
				rd.getVersion(), rd.getUuid(), rd.getCreatedDate()));
		
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		latestReleasesOfBranches.forEach(rd -> {
			ReleaseMetricsDto rmd2 = rd.getMetrics();
			if (rmd2 != null) {
				log.info("FINDINGS_COMPARISON:   - Merging metrics from release {}: vulnDetails size={}", 
					rd.getVersion(), rmd2.getVulnerabilityDetails() != null ? rmd2.getVulnerabilityDetails().size() : "null");
				rmd2.enrichSourcesWithRelease(rd.getUuid());
				rmd.mergeWithByContent(rmd2);
			}
		});
		vulnAnalysisService.processReleaseMetricsDto(org, componentUuid, AnalysisScope.COMPONENT, rmd);
		
		log.info("FINDINGS_COMPARISON: Final merged metrics for dateKey {}: vulnDetails size={}", 
			dateKey, rmd.getVulnerabilityDetails() != null ? rmd.getVulnerabilityDetails().size() : "null");
		
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
		ZonedDateTime today = ZonedDateTime.now();
		if (dateTo.isAfter(today)) dateTo = today;
		// DB-only, including today's point: the today row is maintained by the
		// change-driven refresh (OssAnalyticsMetricsService
		// .refreshTodayAnalyticsForChangedOrgs) and the midnight seed, so the
		// read path never recomputes metrics. Component/branch chart variants
		// below intentionally keep their compute path -- analytics_metrics has
		// no component/branch dimension and persisting that cardinality is not
		// worth it for non-home-page charts.
		return new LinkedList<>(listChartDataByOrgDates(org, dateFrom, dateTo));
	}
	
	
	public List<VulnViolationsChartDto> getVulnViolationByComponentChartData(UUID componentUuid, 
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		ZonedDateTime today = ZonedDateTime.now();
		if (dateTo.isAfter(today)) dateTo = today;
		
		// Get all active branches of the component
		List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, StatusEnum.ACTIVE);
		if (branches.isEmpty()) {
			return new LinkedList<>();
		}

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
			// Retired releases (END_OF_SUPPORT or later) leave the findings-over-time chart.
			if (ReleaseLifecycle.isSupportEnded(rd.getLifecycle())) continue;
			LocalDate releaseDate = rd.getCreatedDate().toLocalDate();
			UUID branchUuid = rd.getBranch();
			latestReleasePerBranchPerDay.computeIfAbsent(releaseDate, k -> new HashMap<>());
			Map<UUID, ReleaseData> branchMap = latestReleasePerBranchPerDay.get(releaseDate);
			ReleaseData existing = branchMap.get(branchUuid);
			if (existing == null ||
				(rd.getCreatedDate().isAfter(existing.getCreatedDate()) && 
					rd.getLifecycle() != ReleaseLifecycle.CANCELLED && 
					rd.getLifecycle() != ReleaseLifecycle.REJECTED &&
					rd.getLifecycle() != ReleaseLifecycle.PENDING
				))  {
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
		ZonedDateTime today = ZonedDateTime.now();
		if (dateTo.isAfter(today)) dateTo = today;
		
		// Get all releases of the branch within the date range (ASSEMBLED or higher)
		List<ReleaseData> releasesInRange = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
				branchUuid, dateFrom, dateTo, ReleaseLifecycle.ASSEMBLED);

		if (releasesInRange.isEmpty()) {
			return new LinkedList<>();
		}

		// Group releases by date (LocalDate) and pick the latest release for each day
		Map<LocalDate, ReleaseData> latestReleasePerDay = new HashMap<>();
		for (ReleaseData rd : releasesInRange) {
			// Retired releases (END_OF_SUPPORT or later) leave the findings-over-time chart.
			if (ReleaseLifecycle.isSupportEnded(rd.getLifecycle())) continue;
			LocalDate releaseDate = rd.getCreatedDate().toLocalDate();
			ReleaseData existing = latestReleasePerDay.get(releaseDate);
			if (existing == null || 
				(rd.getCreatedDate().isAfter(existing.getCreatedDate()) && 
					rd.getLifecycle() != ReleaseLifecycle.CANCELLED && 
					rd.getLifecycle() != ReleaseLifecycle.REJECTED &&
					rd.getLifecycle() != ReleaseLifecycle.PENDING
				)) {
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
	
	public void computeAndRecordAnalyticsMetricsPerOrg (UUID org, ZonedDateTime targetDate) {
		String dateKey = AnalyticsMetricsData.obtainAnalyticsDateKey(targetDate);
		
		// Compute for org-wide
		Optional<AnalyticsMetrics> existingAm = repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
		AnalyticsMetrics am = existingAm.orElse(new AnalyticsMetrics());
		AnalyticsMetricsData amd = computeActualAnalyticsMetricsDataForOrg(org, targetDate);
		save(am, Utils.dataToRecord(amd), WhoUpdated.getAutoWhoUpdated());
	}
	
	
	// No more per-replica today-cache here: writers (the change-driven refresh
	// and the midnight job) are the only callers now, each already gated by a
	// change signal, and the read path serves the shared analytics_metrics
	// rows -- which also removes the old replica-divergence on today's data.
	public AnalyticsMetricsData computeActualAnalyticsMetricsDataForOrg (UUID org, ZonedDateTime createdDate) {
		return computeAnalyticsMetricsDataInternal(org, createdDate);
	}

	private AnalyticsMetricsData computeAnalyticsMetricsDataInternal (UUID org, ZonedDateTime createdDate) {
		ZonedDateTime upToDate = createdDate.toLocalDate().plusDays(1).atStartOfDay(createdDate.getZone());
		var activeBranches = branchService.listBranchesOfOrg(org).stream()
				.map(BranchData::branchDataFromDbRecord)
				.filter(b -> b.getFindingAnalyticsParticipation() != BranchData.FindingAnalyticsParticipation.EXCLUDED)
				.collect(Collectors.toList());
		Set<UUID> componentUuids = activeBranches.stream().map(BranchData::getComponent).collect(Collectors.toSet());
		Map<UUID, ComponentData> componentByUuid = getComponentService.getListOfComponentData(componentUuids).stream()
				.collect(Collectors.toMap(ComponentData::getUuid, c -> c));
		List<ReleaseData> latestReleasesOfBranches;
		try (ForkJoinPool customPool = new ForkJoinPool(4)) {
			latestReleasesOfBranches = customPool.submit(() -> 
				activeBranches.parallelStream()
					.map(ab -> sharedReleaseService.getReleaseDataOfBranch(org, ab, componentByUuid.get(ab.getComponent()), ReleaseLifecycle.ASSEMBLED, upToDate))
					.filter(Optional::isPresent)
					.map(Optional::get)
					.collect(Collectors.toList())
			).get();
		} catch (Exception e) {
			log.error("Error in parallel release fetch", e);
			latestReleasesOfBranches = new LinkedList<>();
		}
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		// One walk feeds two aggregations: the org-wide rollup (dedup-by-content
		// across everything) and per-component/product rollups persisted to
		// component_analytics_metrics (counts only) for the most-vulnerable
		// widget and PRODUCT-perspective findings-over-time.
		Map<UUID, ReleaseMetricsDto> byComponent = new HashMap<>();
		latestReleasesOfBranches.forEach(rd -> {
			ReleaseMetricsDto rmd2 = rd.getMetrics();
			if (rmd2 == null) return; // scan-pending release: nothing to merge yet
			ReleaseMetricsDto enriched = rmd2.clone();
			enriched.enrichSourcesWithRelease(rd.getUuid());
			rmd.mergeWithByContent(enriched);
			byComponent.computeIfAbsent(rd.getComponent(), k -> new ReleaseMetricsDto())
					.mergeWithByContent(enriched);
		});
		recordComponentAnalytics(org, byComponent, componentByUuid,
				AnalyticsMetricsData.obtainAnalyticsDateKey(createdDate));
		return AnalyticsMetricsData.analyticsMetricsDataFactory(org, null, rmd, createdDate);
	}

	/**
	 * Upsert counts-only daily rows for the given per-component merged metrics.
	 * Detail arrays are never persisted here -- drill-downs recompute via
	 * {@link #getFindingsPerDayForComponent}.
	 */
	private void recordComponentAnalytics(UUID org, Map<UUID, ReleaseMetricsDto> byComponent,
			Map<UUID, ComponentData> componentByUuid, String dateKey) {
		for (Map.Entry<UUID, ReleaseMetricsDto> e : byComponent.entrySet()) {
			ComponentData cd = componentByUuid.get(e.getKey());
			if (cd == null || cd.getType() == null) continue;
			ReleaseMetricsDto metrics = e.getValue();
			metrics.computeMetricsFromFacts();
			try {
				componentAnalyticsMetricsRepository.upsertDay(org, cd.getUuid(), cd.getType().name(),
						dateKey, Utils.OM.writeValueAsString(numericMetricsMap(metrics)));
			} catch (Exception ex) {
				log.error("Failed to upsert component analytics for component {} date {}", cd.getUuid(), dateKey, ex);
			}
		}
	}

	private Map<String, Object> numericMetricsMap(ReleaseMetricsDto m) {
		Map<String, Object> nm = new java.util.LinkedHashMap<>();
		nm.put("critical", metricInt(m.getCritical()));
		nm.put("high", metricInt(m.getHigh()));
		nm.put("medium", metricInt(m.getMedium()));
		nm.put("low", metricInt(m.getLow()));
		nm.put("unassigned", metricInt(m.getUnassigned()));
		nm.put("weaknesses", metricInt(m.getWeaknesses()));
		nm.put("policyViolationsTotal", metricInt(m.getPolicyViolationsTotal()));
		nm.put("policyViolationsLicenseTotal", metricInt(m.getPolicyViolationsLicenseTotal()));
		nm.put("policyViolationsOperationalTotal", metricInt(m.getPolicyViolationsOperationalTotal()));
		nm.put("policyViolationsSecurityTotal", metricInt(m.getPolicyViolationsSecurityTotal()));
		return nm;
	}

	/**
	 * Compute + persist counts-only daily rows for the org's components and
	 * products as of {@code targetDate}, optionally restricted to
	 * {@code onlyComponents} (the product 60-day self-backfill path). Same walk
	 * shape as the org rollup, without the org-wide merge.
	 */
	public void computeAndRecordComponentAnalyticsForOrg(UUID org, ZonedDateTime targetDate, Set<UUID> onlyComponents) {
		ZonedDateTime upToDate = targetDate.toLocalDate().plusDays(1).atStartOfDay(targetDate.getZone());
		var activeBranches = branchService.listBranchesOfOrg(org).stream()
				.map(BranchData::branchDataFromDbRecord)
				.filter(b -> b.getFindingAnalyticsParticipation() != BranchData.FindingAnalyticsParticipation.EXCLUDED)
				.filter(b -> onlyComponents == null || onlyComponents.contains(b.getComponent()))
				.collect(Collectors.toList());
		if (activeBranches.isEmpty()) return;
		Set<UUID> componentUuids = activeBranches.stream().map(BranchData::getComponent).collect(Collectors.toSet());
		Map<UUID, ComponentData> componentByUuid = getComponentService.getListOfComponentData(componentUuids).stream()
				.collect(Collectors.toMap(ComponentData::getUuid, c -> c));
		List<ReleaseData> latestReleasesOfBranches;
		try (ForkJoinPool customPool = new ForkJoinPool(4)) {
			latestReleasesOfBranches = customPool.submit(() ->
				activeBranches.parallelStream()
					.map(ab -> sharedReleaseService.getReleaseDataOfBranch(org, ab, componentByUuid.get(ab.getComponent()), ReleaseLifecycle.ASSEMBLED, upToDate))
					.filter(Optional::isPresent)
					.map(Optional::get)
					.collect(Collectors.toList())
			).get();
		} catch (Exception e) {
			log.error("Error in parallel release fetch for component analytics", e);
			return;
		}
		Map<UUID, ReleaseMetricsDto> byComponent = new HashMap<>();
		for (ReleaseData rd : latestReleasesOfBranches) {
			if (rd.getMetrics() == null) continue;
			ReleaseMetricsDto enriched = rd.getMetrics().clone();
			enriched.enrichSourcesWithRelease(rd.getUuid());
			byComponent.computeIfAbsent(rd.getComponent(), k -> new ReleaseMetricsDto())
					.mergeWithByContent(enriched);
		}
		recordComponentAnalytics(org, byComponent, componentByUuid,
				AnalyticsMetricsData.obtainAnalyticsDateKey(targetDate));
	}

	/**
	 * Table-backed read of pre-computed daily counts (component_analytics_metrics).
	 * Reads today's rows, falling back to yesterday's (today's appear via the
	 * change-driven refresh / midnight seed); a fresh org with neither computes
	 * and records today's rows once (self-heal), after which reads are indexed.
	 * Response is totals-only by construction -- detail drill-downs go through
	 * findingsPerDayForComponent.
	 */
	public List<MostVulnerableComponent> mostVulnerableComponentsPerOrg(UUID org, ZonedDateTime createdDate,
			ComponentType componentType, Integer maxComponents, Set<UUID> perspectiveComponentUuids) {
		if (maxComponents == null || maxComponents <= 0) return List.of();
		String typeName = (componentType == null || componentType == ComponentType.ANY)
				? ComponentType.COMPONENT.name() : componentType.name();

		String todayKey = AnalyticsMetricsData.obtainAnalyticsDateKey(createdDate);
		List<ComponentAnalyticsMetrics> rows = componentAnalyticsMetricsRepository
				.findByOrgTypeAndDateOrdered(org, typeName, todayKey);
		if (rows.isEmpty()) {
			String yesterdayKey = AnalyticsMetricsData.obtainAnalyticsDateKey(createdDate.minusDays(1));
			rows = componentAnalyticsMetricsRepository.findByOrgTypeAndDateOrdered(org, typeName, yesterdayKey);
		}
		if (rows.isEmpty()) {
			// Fresh org / pre-backfill: compute once, record, then serve from the table.
			computeAndRecordComponentAnalyticsForOrg(org, createdDate, null);
			rows = componentAnalyticsMetricsRepository.findByOrgTypeAndDateOrdered(org, typeName, todayKey);
		}
		if (rows.isEmpty()) return List.of();

		List<ComponentAnalyticsMetrics> filtered = rows.stream()
				.filter(r -> perspectiveComponentUuids == null || perspectiveComponentUuids.contains(r.getComponent()))
				.limit(maxComponents)
				.collect(Collectors.toList());
		if (filtered.isEmpty()) return List.of();

		Map<UUID, ComponentData> componentByUuid = getComponentService
				.getListOfComponentData(filtered.stream().map(ComponentAnalyticsMetrics::getComponent).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(ComponentData::getUuid, c -> c));

		List<MostVulnerableComponent> result = new ArrayList<>();
		for (ComponentAnalyticsMetrics row : filtered) {
			ComponentData cd = componentByUuid.get(row.getComponent());
			if (cd == null) continue;
			result.add(new MostVulnerableComponent(cd.getUuid(), cd.getName(), cd.getType(),
					metricsFromNumericMap(row.getNumericMetrics())));
		}
		return result;
	}

	private ReleaseMetricsDto metricsFromNumericMap(Map<String, Object> nm) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(null);
		m.setViolationDetails(null);
		m.setWeaknessDetails(null);
		if (nm == null) return m;
		m.setCritical(numOrZero(nm.get("critical")));
		m.setHigh(numOrZero(nm.get("high")));
		m.setMedium(numOrZero(nm.get("medium")));
		m.setLow(numOrZero(nm.get("low")));
		m.setUnassigned(numOrZero(nm.get("unassigned")));
		m.setWeaknesses(numOrZero(nm.get("weaknesses")));
		m.setPolicyViolationsTotal(numOrZero(nm.get("policyViolationsTotal")));
		m.setPolicyViolationsLicenseTotal(numOrZero(nm.get("policyViolationsLicenseTotal")));
		m.setPolicyViolationsOperationalTotal(numOrZero(nm.get("policyViolationsOperationalTotal")));
		m.setPolicyViolationsSecurityTotal(numOrZero(nm.get("policyViolationsSecurityTotal")));
		return m;
	}

	private Integer numOrZero(Object v) {
		return v instanceof Number n ? n.intValue() : 0;
	}

	/**
	 * Self-backfill, run per org from the periodic analytics tick: any PRODUCT
	 * missing yesterday's counts row gets {@code productBackfillDays} recomputed
	 * (charts need history), any COMPONENT missing yesterday's row gets
	 * yesterday only (the widget needs at most one day). No-op once seeded --
	 * one IN-list existence query per type per tick -- and self-heals new
	 * components/products as they appear.
	 */
	public void seedComponentAnalyticsIfMissing(UUID org, int productBackfillDays) {
		var activeBranches = branchService.listBranchesOfOrg(org).stream()
				.map(BranchData::branchDataFromDbRecord)
				.filter(b -> b.getFindingAnalyticsParticipation() != BranchData.FindingAnalyticsParticipation.EXCLUDED)
				.collect(Collectors.toList());
		if (activeBranches.isEmpty()) return;
		Set<UUID> componentUuids = activeBranches.stream().map(BranchData::getComponent).collect(Collectors.toSet());
		Map<UUID, ComponentData> componentByUuid = getComponentService.getListOfComponentData(componentUuids).stream()
				.collect(Collectors.toMap(ComponentData::getUuid, c -> c));

		ZonedDateTime yesterday = ZonedDateTime.now().minusDays(1);
		String yesterdayKey = AnalyticsMetricsData.obtainAnalyticsDateKey(yesterday);

		Set<UUID> products = componentByUuid.values().stream()
				.filter(c -> c.getType() == ComponentType.PRODUCT)
				.map(ComponentData::getUuid).collect(Collectors.toSet());
		Set<UUID> components = componentByUuid.values().stream()
				.filter(c -> c.getType() == ComponentType.COMPONENT)
				.map(ComponentData::getUuid).collect(Collectors.toSet());

		Set<UUID> missingProducts = missingForDate(org, products, yesterdayKey);
		if (!missingProducts.isEmpty()) {
			log.info("component analytics seed: org {} backfilling {} product(s) {} day(s) back",
					org, missingProducts.size(), productBackfillDays);
			for (int i = productBackfillDays - 1; i >= 0; i--) {
				computeAndRecordComponentAnalyticsForOrg(org, yesterday.minusDays(i), missingProducts);
			}
		}
		Set<UUID> missingComponents = missingForDate(org, components, yesterdayKey);
		if (!missingComponents.isEmpty()) {
			log.info("component analytics seed: org {} seeding yesterday for {} component(s)",
					org, missingComponents.size());
			computeAndRecordComponentAnalyticsForOrg(org, yesterday, missingComponents);
		}
	}

	private Set<UUID> missingForDate(UUID org, Set<UUID> candidates, String dateKey) {
		if (candidates.isEmpty()) return Set.of();
		Set<UUID> present = componentAnalyticsMetricsRepository
				.findByOrgAndComponentInAndDateKey(org, candidates, dateKey).stream()
				.map(ComponentAnalyticsMetrics::getComponent).collect(Collectors.toSet());
		Set<UUID> missing = new java.util.HashSet<>(candidates);
		missing.removeAll(present);
		return missing;
	}

	/**
	 * Table-backed findings-over-time for one component/product, used by the
	 * component/product page charts and PRODUCT-perspective charts.
	 *
	 * History ([from .. yesterday]) is served from component_analytics_metrics;
	 * a component with no stored rows in the window is lazily backfilled on
	 * first open (scoped to this component, one release-list query per branch
	 * with carry-forward across days -- NOT one as-of query per day).
	 *
	 * TODAY's tick is ALWAYS computed hot from the component's latest branch
	 * releases (cheap: one component's branches) and written through to the
	 * table, so the chart's newest point reflects the latest release state
	 * regardless of the analytics refresh cadence.
	 */
	public List<VulnViolationsChartDto> getVulnViolationByComponentChartDataStored(UUID componentUuid,
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		ZonedDateTime now = ZonedDateTime.now();
		if (dateTo.isAfter(now)) dateTo = now;
		var branches = branchService.listBranchDataOfComponent(componentUuid, StatusEnum.ACTIVE).stream()
				.filter(b -> b.getFindingAnalyticsParticipation() != BranchData.FindingAnalyticsParticipation.EXCLUDED)
				.collect(Collectors.toList());
		if (branches.isEmpty()) return new LinkedList<>();
		UUID org = branches.get(0).getOrg();

		String fromKey = AnalyticsMetricsData.obtainAnalyticsDateKey(dateFrom);
		String todayKey = AnalyticsMetricsData.obtainAnalyticsDateKey(now);
		String yesterdayKey = AnalyticsMetricsData.obtainAnalyticsDateKey(now.minusDays(1));
		String histToKey = AnalyticsMetricsData.obtainAnalyticsDateKey(dateTo).compareTo(yesterdayKey) < 0
				? AnalyticsMetricsData.obtainAnalyticsDateKey(dateTo) : yesterdayKey;

		List<ComponentAnalyticsMetrics> rows = componentAnalyticsMetricsRepository
				.findByComponentAndDateRange(componentUuid, fromKey, histToKey);
		// Lazy history backfill trigger: the startup seed writes only
		// yesterday's row for components, so "zero rows" is the wrong signal --
		// detect a LEADING GAP instead (earliest stored row later than the
		// window start), guarded by the component's first release date so the
		// trigger can't re-fire for windows predating the component entirely.
		String earliestStoredKey = rows.isEmpty() ? null : rows.get(0).getDateKey();
		if (fromKey.compareTo(histToKey) <= 0
				&& (earliestStoredKey == null || earliestStoredKey.compareTo(fromKey) > 0)) {
			var firstRelease = sharedReleaseService.findEarliestReleaseDateOfComponent(componentUuid);
			String gapEndKey = earliestStoredKey == null ? histToKey
					: LocalDate.parse(earliestStoredKey).minusDays(1).format(java.time.format.DateTimeFormatter.ISO_DATE);
			if (firstRelease != null
					&& AnalyticsMetricsData.obtainAnalyticsDateKey(firstRelease).compareTo(gapEndKey) <= 0) {
				ZonedDateTime gapFrom = firstRelease.isAfter(dateFrom) ? firstRelease : dateFrom;
				backfillComponentAnalyticsRange(org, componentUuid, branches, gapFrom,
						LocalDate.parse(gapEndKey).atStartOfDay(dateFrom.getZone()));
				rows = componentAnalyticsMetricsRepository.findByComponentAndDateRange(componentUuid, fromKey, histToKey);
			}
		}

		// today's tick: hot from latest releases, written through
		if (AnalyticsMetricsData.obtainAnalyticsDateKey(dateTo).compareTo(todayKey) >= 0) {
			computeAndRecordComponentAnalyticsForOrg(org, now, Set.of(componentUuid));
			rows = new ArrayList<>(rows);
			rows.addAll(componentAnalyticsMetricsRepository.findByComponentAndDateRange(componentUuid, todayKey, todayKey));
		}

		var zone = dateFrom.getZone();
		List<VulnViolationsChartDto> out = new LinkedList<>();
		for (ComponentAnalyticsMetrics row : rows) {
			ZonedDateTime date = LocalDate.parse(row.getDateKey()).atStartOfDay(zone);
			out.addAll(metricsFromNumericMap(row.getNumericMetrics()).convertToChartDto(date));
		}
		return out;
	}

	/**
	 * Range backfill for ONE component: per branch, one query for the releases
	 * inside the window plus one as-of anchor before it, then an in-memory
	 * carry-forward walk producing the latest release per branch per day.
	 * Days before the component's first release produce no row (matching the
	 * as-of walk semantics); days after it always do, so the lazy trigger
	 * (zero rows in window) fires at most once per component per window.
	 */
	private void backfillComponentAnalyticsRange(UUID org, UUID componentUuid, List<BranchData> branches,
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		if (dateTo.isBefore(dateFrom)) return;
		ComponentData cd = getComponentService.getComponentData(componentUuid).orElse(null);
		if (cd == null || cd.getType() == null) return;
		LocalDate fromDay = dateFrom.toLocalDate();
		LocalDate toDay = dateTo.toLocalDate();

		// per branch: anchor (latest as of window start EOD) + in-window releases
		Map<UUID, java.util.NavigableMap<LocalDate, ReleaseData>> perBranchByDay = new HashMap<>();
		for (BranchData b : branches) {
			java.util.TreeMap<LocalDate, ReleaseData> byDay = new java.util.TreeMap<>();
			sharedReleaseService.getReleaseDataOfBranch(org, b.getUuid(), ReleaseLifecycle.ASSEMBLED,
					fromDay.plusDays(1).atStartOfDay(dateFrom.getZone()))
					.ifPresent(rd -> byDay.put(fromDay, rd));
			for (ReleaseData rd : sharedReleaseService.listReleaseDataOfBranchBetweenDates(
					b.getUuid(), dateFrom, dateTo.toLocalDate().plusDays(1).atStartOfDay(dateTo.getZone()),
					ReleaseLifecycle.ASSEMBLED)) {
				LocalDate day = rd.getCreatedDate().toLocalDate();
				ReleaseData existing = byDay.get(day);
				if (existing == null || rd.getCreatedDate().isAfter(existing.getCreatedDate())) {
					byDay.put(day, rd);
				}
			}
			if (!byDay.isEmpty()) perBranchByDay.put(b.getUuid(), byDay);
		}
		if (perBranchByDay.isEmpty()) return;

		String dateKeyPrefixLog = null;
		for (LocalDate day = fromDay; !day.isAfter(toDay); day = day.plusDays(1)) {
			ReleaseMetricsDto merged = new ReleaseMetricsDto();
			boolean any = false;
			for (var byDay : perBranchByDay.values()) {
				var entry = byDay.floorEntry(day); // latest release on-or-before this day (carry-forward)
				if (entry == null) continue;
				ReleaseMetricsDto m = entry.getValue().getMetrics();
				if (m == null) continue;
				ReleaseMetricsDto enriched = m.clone();
				enriched.enrichSourcesWithRelease(entry.getValue().getUuid());
				merged.mergeWithByContent(enriched);
				any = true;
			}
			if (!any) continue;
			merged.computeMetricsFromFacts();
			String dateKey = day.format(java.time.format.DateTimeFormatter.ISO_DATE);
			if (dateKeyPrefixLog == null) {
				dateKeyPrefixLog = dateKey;
				log.info("component analytics lazy backfill: component {} window {}..{}", componentUuid, dateKey, toDay);
			}
			try {
				componentAnalyticsMetricsRepository.upsertDay(org, componentUuid, cd.getType().name(),
						dateKey, Utils.OM.writeValueAsString(numericMetricsMap(merged)));
			} catch (Exception ex) {
				log.error("lazy backfill upsert failed for component {} date {}", componentUuid, dateKey, ex);
			}
		}
	}

	private int metricInt(Integer value) {
		return value == null ? 0 : value;
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

	private static ReleasesPerComponent mapDaoToReleasesPerComponent(ReleasesPerComponentDao dao) {
		return new ReleasesPerComponent(dao.getComponentuuid(), dao.getComponentname(),
				ComponentType.valueOf(dao.getComponenttype()), dao.getRlzcount());
	}
	
	private static ReleasesPerBranch mapDaoToReleasesPerBranch(ReleasesPerBranchDao dao) {
		return new ReleasesPerBranch(dao.getComponentuuid(), dao.getComponentname(),
				dao.getBranchuuid(), dao.getBranchname(),
				ComponentType.valueOf(dao.getComponenttype()), dao.getRlzcount());
	}
	
	public List<ReleasesPerComponent> analyticsComponentsWithMostRecentReleases (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization) {
		var rows = repository.analyticsComponentsWithMostReleases(cutOffDate, compType.name(),
				maxComponents, organization.toString());
		return rows.stream().map(AnalyticsMetricsService::mapDaoToReleasesPerComponent).toList();
	}
	
	public List<ReleasesPerBranch> analyticsBranchesWithMostRecentReleases (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization) {
		var rows = repository.analyticsBranchesWithMostReleases(cutOffDate, compType.name(),
				maxComponents, organization.toString());
		return rows.stream().map(AnalyticsMetricsService::mapDaoToReleasesPerBranch).toList();
	}
	
	public List<ReleasesPerComponent> analyticsComponentsWithMostRecentReleasesByPerspective (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization, UUID perspectiveUuid) {
		var rows = repository.analyticsComponentsWithMostReleasesByPerspective(cutOffDate, compType.name(),
				maxComponents, organization.toString(), perspectiveUuid.toString());
		return rows.stream().map(AnalyticsMetricsService::mapDaoToReleasesPerComponent).toList();
	}
	
	public List<ReleasesPerBranch> analyticsBranchesWithMostRecentReleasesByPerspective (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization, UUID perspectiveUuid) {
		var rows = repository.analyticsBranchesWithMostReleasesByPerspective(cutOffDate, compType.name(),
				maxComponents, organization.toString(), perspectiveUuid.toString());
		return rows.stream().map(AnalyticsMetricsService::mapDaoToReleasesPerBranch).toList();
	}
	
	public List<ReleasesPerComponent> analyticsComponentsWithMostRecentReleasesByProduct (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxComponents, UUID organization, UUID productUuid) {
		// Get all component UUIDs that belong to this product (including nested components)
		Set<UUID> allComponentUuids = new java.util.LinkedHashSet<>();
		allComponentUuids.add(productUuid);
		Set<UUID> productComponents = sharedReleaseService.obtainComponentsOfProductOrComponent(productUuid, allComponentUuids);
		allComponentUuids.addAll(productComponents);
		
		var rows = repository.analyticsComponentsWithMostReleasesByProduct(cutOffDate, compType.name(),
				maxComponents, organization.toString(), new ArrayList<>(allComponentUuids));
		return rows.stream().map(AnalyticsMetricsService::mapDaoToReleasesPerComponent).toList();
	}
	
	public List<ReleasesPerBranch> analyticsBranchesWithMostRecentReleasesByProduct (ZonedDateTime cutOffDate,
			ComponentType compType, Integer maxBranches, UUID organization, UUID productUuid) {
		// Get all component UUIDs that belong to this product (including nested components)
		Set<UUID> allComponentUuids = new java.util.LinkedHashSet<>();
		allComponentUuids.add(productUuid);
		Set<UUID> productComponents = sharedReleaseService.obtainComponentsOfProductOrComponent(productUuid, allComponentUuids);
		allComponentUuids.addAll(productComponents);
		
		var rows = repository.analyticsBranchesWithMostReleasesByProduct(cutOffDate, compType.name(),
				maxBranches, organization.toString(), new ArrayList<>(allComponentUuids));
		return rows.stream().map(AnalyticsMetricsService::mapDaoToReleasesPerBranch).toList();
	}
	
	
}

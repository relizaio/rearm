/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.AnalyticsMetrics;
import io.reliza.repositories.dao.ReleasesPerBranchDao;
import io.reliza.repositories.dao.ReleasesPerComponentDao;
import io.reliza.repositories.dao.VulnViolationsChartDao;

public interface AnalyticsMetricsRepository extends CrudRepository<AnalyticsMetrics, UUID> {
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_CHART_DATA_BY_ORG_DATES,
			nativeQuery = true)
	List<VulnViolationsChartDao> findAnalyticsChartDataByOrgDates(String orgUuidAsString,
			String dateKeyFrom, String dateKeyTo);
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_METRICS_BY_ORG_DATE_KEY,
			nativeQuery = true)
	Optional<AnalyticsMetrics> findAnalyticsMetricsByOrgDateKey(String orgUuidAsString,
			String dateKey);
	
	@Query(
			value = VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES,
			nativeQuery = true)
	List<ReleasesPerComponentDao> analyticsComponentsWithMostReleases(ZonedDateTime cutOffDate, String compType,
			Integer maxComponents, String organization);
	
	@Query(
			value = VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES,
			nativeQuery = true)
	List<ReleasesPerBranchDao> analyticsBranchesWithMostReleases(ZonedDateTime cutOffDate, String compType,
			Integer maxBranches, String organization);
	
	@Query(
			value = VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PERSPECTIVE,
			nativeQuery = true)
	List<ReleasesPerComponentDao> analyticsComponentsWithMostReleasesByPerspective(ZonedDateTime cutOffDate, String compType,
			Integer maxComponents, String organization, String perspectiveUuidAsString);
	
	@Query(
			value = VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PERSPECTIVE,
			nativeQuery = true)
	List<ReleasesPerBranchDao> analyticsBranchesWithMostReleasesByPerspective(ZonedDateTime cutOffDate, String compType,
			Integer maxBranches, String organization, String perspectiveUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_METRICS_BY_ORG_PERSPECTIVE_DATE_KEY,
			nativeQuery = true)
	Optional<AnalyticsMetrics> findAnalyticsMetricsByOrgPerspectiveDateKey(String orgUuidAsString,
			String perspectiveUuidAsString, String dateKey);
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_CHART_DATA_BY_ORG_PERSPECTIVE_DATES,
			nativeQuery = true)
	List<VulnViolationsChartDao> findAnalyticsChartDataByOrgPerspectiveDates(String orgUuidAsString,
			String perspectiveUuidAsString, String dateKeyFrom, String dateKeyTo);
	
	@Query(
			value = VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PRODUCT,
			nativeQuery = true)
	List<ReleasesPerComponentDao> analyticsComponentsWithMostReleasesByProduct(ZonedDateTime cutOffDate, String compType,
			Integer maxComponents, String organization, List<UUID> componentUuids);
	
	@Query(
			value = VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PRODUCT,
			nativeQuery = true)
	List<ReleasesPerBranchDao> analyticsBranchesWithMostReleasesByProduct(ZonedDateTime cutOffDate, String compType,
			Integer maxBranches, String organization, List<UUID> componentUuids);
	
}

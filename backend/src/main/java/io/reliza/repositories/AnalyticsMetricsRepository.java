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

public interface AnalyticsMetricsRepository extends CrudRepository<AnalyticsMetrics, UUID> {
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_METRICS_BY_ORG_DATES,
			nativeQuery = true)
	List<AnalyticsMetrics> findAnalyticsMetricsByOrgDates(String orgUuidAsString,
			String dateKeyFrom, String dateKeyTo);
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_METRICS_BY_ORG_DATE_KEY,
			nativeQuery = true)
	Optional<AnalyticsMetrics> findAnalyticsMetricsByOrgDateKey(String orgUuidAsString,
			String dateKey);
	
	@Query(
			value = VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES,
			nativeQuery = true)
	List<Object[]> analyticsComponentsWithMostReleases(ZonedDateTime cutOffDate, String compType,
			Integer maxComponents, String organization);
	
	@Query(
			value = VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES,
			nativeQuery = true)
	List<Object[]> analyticsBranchesWithMostReleases(ZonedDateTime cutOffDate, String compType,
			Integer maxBranches, String organization);
	
	@Query(
			value = VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PERSPECTIVE,
			nativeQuery = true)
	List<Object[]> analyticsComponentsWithMostReleasesByPerspective(ZonedDateTime cutOffDate, String compType,
			Integer maxComponents, String organization, String perspectiveUuidAsString);
	
	@Query(
			value = VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PERSPECTIVE,
			nativeQuery = true)
	List<Object[]> analyticsBranchesWithMostReleasesByPerspective(ZonedDateTime cutOffDate, String compType,
			Integer maxBranches, String organization, String perspectiveUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_METRICS_BY_ORG_PERSPECTIVE_DATE_KEY,
			nativeQuery = true)
	Optional<AnalyticsMetrics> findAnalyticsMetricsByOrgPerspectiveDateKey(String orgUuidAsString,
			String perspectiveUuidAsString, String dateKey);
	
	@Query(
			value = VariableQueries.FIND_ANALYTICS_METRICS_BY_ORG_PERSPECTIVE_DATES,
			nativeQuery = true)
	List<AnalyticsMetrics> findAnalyticsMetricsByOrgPerspectiveDates(String orgUuidAsString,
			String perspectiveUuidAsString, String dateKeyFrom, String dateKeyTo);
	
	@Query(
			value = VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PRODUCT,
			nativeQuery = true)
	List<Object[]> analyticsComponentsWithMostReleasesByProduct(ZonedDateTime cutOffDate, String compType,
			Integer maxComponents, String organization, List<UUID> componentUuids);
	
	@Query(
			value = VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PRODUCT,
			nativeQuery = true)
	List<Object[]> analyticsBranchesWithMostReleasesByProduct(ZonedDateTime cutOffDate, String compType,
			Integer maxBranches, String organization, List<UUID> componentUuids);
	
}

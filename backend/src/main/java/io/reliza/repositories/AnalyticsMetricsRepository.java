/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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
			ZonedDateTime dateFrom, ZonedDateTime dateTo);
	
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
	
}

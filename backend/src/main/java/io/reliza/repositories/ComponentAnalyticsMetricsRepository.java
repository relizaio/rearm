/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ComponentAnalyticsMetrics;

public interface ComponentAnalyticsMetricsRepository extends CrudRepository<ComponentAnalyticsMetrics, UUID> {

	/**
	 * Upsert one day's counts for a component/product. ON CONFLICT keyed by the
	 * unique (org, component, date_key) index — the today-refresh rewrites the
	 * same row all day.
	 */
	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO rearm.component_analytics_metrics
				(org, component, component_type, date_key, numeric_metrics)
			VALUES (:org, :component, :componentType, :dateKey, CAST(:numericMetrics AS jsonb))
			ON CONFLICT (org, component, date_key)
			DO UPDATE SET numeric_metrics = CAST(:numericMetrics AS jsonb),
			              component_type = :componentType,
			              last_updated_date = now()
			""", nativeQuery = true)
	void upsertDay(@Param("org") UUID org, @Param("component") UUID component,
			@Param("componentType") String componentType, @Param("dateKey") String dateKey,
			@Param("numericMetrics") String numericMetrics);

	/**
	 * Widget read: rows for one org/type/date ordered by the same severity
	 * cascade the in-memory sort used (critical, high, medium, low, unassigned,
	 * weaknesses, total violations). Perspective filtering happens in the
	 * caller (the perspective component set is already in memory there).
	 */
	@Query(value = """
			SELECT * FROM rearm.component_analytics_metrics
			WHERE org = :org AND component_type = :componentType AND date_key = :dateKey
			ORDER BY COALESCE((numeric_metrics->>'critical')::int, 0) DESC,
			         COALESCE((numeric_metrics->>'high')::int, 0) DESC,
			         COALESCE((numeric_metrics->>'medium')::int, 0) DESC,
			         COALESCE((numeric_metrics->>'low')::int, 0) DESC,
			         COALESCE((numeric_metrics->>'unassigned')::int, 0) DESC,
			         COALESCE((numeric_metrics->>'weaknesses')::int, 0) DESC,
			         COALESCE((numeric_metrics->>'policyViolationsTotal')::int, 0) DESC
			""", nativeQuery = true)
	List<ComponentAnalyticsMetrics> findByOrgTypeAndDateOrdered(@Param("org") UUID org,
			@Param("componentType") String componentType, @Param("dateKey") String dateKey);

	/** Chart read: one component/product across a date-key range, oldest first. */
	@Query(value = """
			SELECT * FROM rearm.component_analytics_metrics
			WHERE component = :component AND date_key >= :dateKeyFrom AND date_key <= :dateKeyTo
			ORDER BY date_key
			""", nativeQuery = true)
	List<ComponentAnalyticsMetrics> findByComponentAndDateRange(@Param("component") UUID component,
			@Param("dateKeyFrom") String dateKeyFrom, @Param("dateKeyTo") String dateKeyTo);

	List<ComponentAnalyticsMetrics> findByOrgAndComponentInAndDateKey(UUID org, Collection<UUID> component, String dateKey);
}

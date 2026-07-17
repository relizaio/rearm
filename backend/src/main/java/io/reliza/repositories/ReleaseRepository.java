/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Release;
import io.reliza.repositories.dao.DateCountDao;
import jakarta.persistence.LockModeType;

public interface ReleaseRepository extends CrudRepository<Release, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT i FROM Release i where uuid = :uuid")
	public Optional<Release> findByIdWriteLocked(UUID uuid);
	
	@Query(
			value = VariableQueries.FIND_RELEASE_BY_ID_AND_ORG,
			nativeQuery = true)
	public Optional<Release> findReleaseByIdAndOrg(UUID releaseUuid, String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH,
			nativeQuery = true)
	List<Release> findReleasesOfBranch(String branchUuidAsString, String limitAsStr, String offsetAsStr);

	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_UP_TO_DATE,
			nativeQuery = true)
	List<Release> findReleasesOfBranchUpToDate(String branchUuidAsString, ZonedDateTime upToDate, String limitAsStr, String offsetAsStr);

	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_WHERE_IN_SCE,
			nativeQuery = true)
	List<Release> findReleasesOfBranchWhereInSce(String branchUuidAsString, List<String> sces, String limitAsStr, String offsetAsStr);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG,
			nativeQuery = true)
	List<Release> findReleasesOfOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ALL_PRODUCT_RELEASES_OF_ORG,
			nativeQuery = true)
	List<Release> findProductReleasesOfOrg(String orgUuidAsString, String limitAsStr, String offsetAsStr);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_COMPONENT,
			nativeQuery = true)
	List<Release> findReleasesOfComponent(String componentUuidAsString, String limitAsStr, String offsetAsStr);

	/**
	 * Batched (component_uuid, lifecycle) projection across many components in a single
	 * round-trip. Backs the synthetic {@code Component.effectiveLifecycle} GraphQL field
	 * so the components-list resolver doesn't need to N+1 per-component release lookups.
	 * Each row is a {@code String[2]} of [componentUuidAsString, lifecycle].
	 */
	@Query(
			value = VariableQueries.FIND_RELEASE_LIFECYCLES_BY_COMPONENTS,
			nativeQuery = true)
	List<Object[]> findReleaseLifecyclesByComponents(@Param("componentUuidsAsStrings") java.util.Collection<String> componentUuidsAsStrings);

	@Query(
			value = VariableQueries.COUNT_RELEASES_OF_ORG_BY_DATE,
			nativeQuery = true)
	List<DateCountDao> countReleasesOfOrgByDate(String orgUuidAsString, ZonedDateTime cutOffDate, String tz);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfOrgBetweenDates(String orgUuidAsString, ZonedDateTime startDate, ZonedDateTime endDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_COMPONENT_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfComponentBetweenDates(String componentUuidAsString, ZonedDateTime startDate, ZonedDateTime endDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfBranchBetweenDates(String branchUuidAsString, ZonedDateTime startDate, ZonedDateTime endDate);
	
	@Query(
			value = VariableQueries.COUNT_RELEASES_OF_COMPONENT_BY_DATE,
			nativeQuery = true)
	List<DateCountDao> countReleasesOfComponentByDate(String componentUuidAsString, ZonedDateTime cutOffDate, String tz);
	
	@Query(
			value = VariableQueries.COUNT_RELEASES_OF_BRANCH_BY_DATE,
			nativeQuery = true)
	List<DateCountDao> countReleasesOfBranchByDate(String branchUuidAsString, ZonedDateTime cutOffDate, String tz);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_DELIVERABLE_AND_ORG,
			nativeQuery = true)
	List<Release> findReleasesByDeliverable(String deliverableUuidAsString, String orgUuidAsString);
	
	/**
	 * This only locates releases by artifacts directly attached to release and not to its deliverables
	 * @param artifactUuidAsString
	 * @param orgUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_ARTIFACT_AND_ORG,
			nativeQuery = true)
	List<Release> findReleasesByReleaseArtifact(String artifactUuidAsString, String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_PENDING_RELEASES_AFTER_CUTOFF,
			nativeQuery = true)
	List<Release> findPendingReleasesAfterCutoff(String lifecycle, String cutOffDate, int limit);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG_BY_VERSION,
			nativeQuery = true)
	List<Release> findReleasesOfOrgByVersion(String orgUuidAsString, String version);

	@Query(
			value = VariableQueries.FIND_RELEASES_BY_SCE_AND_ORG,
			nativeQuery = true)
	List<Release> findReleaseBySce(String sceUuidAsString, String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_LATEST_RELEASE_BY_SCE_AND_ORG,
			nativeQuery = true)
	Optional<Release> findLatestReleaseBySce(String sceUuidAsString, String orgUuidAsString);

	/**
	 * Find every release whose primary sourceCodeEntry is one of the given
	 * SCE UUIDs in a single round-trip. Hits the
	 * {@code releases_source_code_entry} expression index added in V30.
	 * Used by the PR aggregator to fan-in attributed releases for a PR's
	 * commits list.
	 */
	@Query(value = "SELECT * FROM rearm.releases r "
			+ "WHERE r.record_data->>'sourceCodeEntry' IN (:scesAsStrings)",
			nativeQuery = true)
	List<Release> findReleasesBySces(@Param("scesAsStrings") Collection<String> scesAsStrings);

	@Query(
			value = VariableQueries.FIND_RELEASE_BY_COMPONENT_AND_VERSION,
			nativeQuery = true)
	Optional<Release> findByComponentAndVersion(String compUuidAsString, String version);
	
	@Query(
			value = VariableQueries.FIND_PRODUCTS_THAT_HAVE_THIS_RELEASE,
			nativeQuery = true)
	List<Release> findProductsByRelease(String orgUuidAsString, String releaseUuidAsString);	

	@Query(
			value = VariableQueries.FIND_PRODUCTS_THAT_HAVE_THESE_RELEASES,
			nativeQuery = true)
	List<Release> findProductsByReleases(String orgUuidAsString, String releaseArrString);	
	
	@Query(
		value = VariableQueries.LIST_RELEASES_BY_COMPONENT,
		nativeQuery = true
	)
	List<Release> listReleasesByComponent(String compUuidAsString);
	
	@Query(
		value = VariableQueries.LIST_RELEASES_BY_COMPONENTS,
		nativeQuery = true
	)
	List<Release> listReleasesByComponents(Collection<UUID> componentUuids);

	@Query(
			value = VariableQueries.FIND_RELEASES_OF_BRANCH_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfBranchBetweenDates(String branchUuidAsString, String fromDate, String toDate);
	
	@Query(
			value = VariableQueries.FIND_PREVIOUS_RELEASES_OF_BRANCH_FOR_RELEASE,
			nativeQuery = true)
	UUID findPreviousReleasesOfBranchForRelease(String branch, UUID release);
	
	@Query(
			value = VariableQueries.FIND_NEXT_RELEASES_OF_BRANCH_FOR_RELEASE,
			nativeQuery = true)
	UUID findNextReleasesOfBranchForRelease(String branch, UUID release);
	
	@Query(
			value = VariableQueries.FIND_LATEST_RELEASE_BEFORE_TIMESTAMP,
			nativeQuery = true)
	UUID findLatestReleaseBeforeTimestamp(String branchUuidAsString, String timestamp);

	@Query(
			value = VariableQueries.FIND_LATEST_RELEASE_AT_OR_BEFORE_TIMESTAMP,
			nativeQuery = true)
	UUID findLatestReleaseAtOrBeforeTimestamp(@Param("branchUuidAsString") String branchUuidAsString, @Param("timestamp") String timestamp);

	@Query(
			value = VariableQueries.FIND_LATEST_RELEASES_AT_OR_BEFORE_TIMESTAMP_BATCH,
			nativeQuery = true)
	List<Release> findLatestReleasesAtOrBeforeTimestampBatch(@Param("branchUuidStrings") String[] branchUuidStrings, @Param("timestamp") String timestamp);
	
	@Query(
			value = VariableQueries.FIND_DISTINCT_RELEASE_TAG_KEYS_OF_ORG,
			nativeQuery = true)
	List<String> findDistrinctReleaseKeysOfOrg(String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_RELEASES_BY_TAG_KEY,
			nativeQuery = true)
	List<Release> findReleasesByTagKey(String orgUuidAsString, String tagKey);

	@Query(
			value = VariableQueries.FIND_BRANCH_RELEASES_BY_TAG_KEY,
			nativeQuery = true)
	List<Release> findBranchReleasesByTagKey(String orgUuidAsString, String branchUuidAsString, String tagKey);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_TAG_KEY_VALUE,
			nativeQuery = true)
	List<Release> findReleasesByTagKeyAndValue(String orgUuidAsString, String tagKey, String tagValue);
	
	@Query(
			value = VariableQueries.FIND_BRANCH_RELEASES_BY_TAG_KEY_VALUE,
			nativeQuery = true)
	List<Release> findBranchReleasesByTagKeyAndValue(String orgUuidAsString, String branchUuidAsString, String tagKey, String tagValue);

	/**
	 * Per-org (org, SUM(metrics_revision), assembled_count) rows backing the
	 * today-analytics refresh change signal -- see the SQL comment in
	 * {@link VariableQueries#ORG_METRICS_SIGNALS}. Each row is
	 * [String orgUuid, Number revSum, Number assembledCount, Number maxUpdatedEpoch].
	 */
	@Query(
			value = VariableQueries.ORG_METRICS_SIGNALS,
			nativeQuery = true)
	List<Object[]> findOrgMetricsSignals();
	
	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_ARTIFACT_DIRECT,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeByArtifactDirect(int limit);

	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_SCE,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeBySce(int limit);

	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_OUTBOUND_DELIVERABLES,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeByOutboundDeliverables(int limit);

	@Query(
			value = VariableQueries.FIND_PRODUCT_RELEASES_FOR_METRICS_COMPUTE,
			nativeQuery = true)
	List<Release> findProductReleasesForMetricsCompute(int limit);

	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeByUpdate(int limit);
	
	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_SCE_ARTIFACT,
		nativeQuery = true)
		List<Release> findReleasesSharingSceArtifact(String artUuidAsString);

	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_DELIVRABLE_ARTIFACT,
		nativeQuery = true)
		List<Release> findReleasesSharingDeliverableArtifact(String artUuidAsString);

	@Query(
		value = VariableQueries.FIND_RELEASES_BY_ARTIFACTS_AND_ORG,
		nativeQuery = true)
	List<Release> findReleasesByReleaseArtifacts(Collection<String> artifactUuidsAsStrings, String orgUuidAsString);

	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_SCE_ARTIFACTS,
		nativeQuery = true)
	List<Release> findReleasesSharingSceArtifacts(Collection<String> artifactUuidsAsStrings);

	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_DELIVERABLE_ARTIFACTS,
		nativeQuery = true)
	List<Release> findReleasesSharingDeliverableArtifacts(Collection<String> artifactUuidsAsStrings);

	@Query(
		value = VariableQueries.FIND_RELEASES_BY_ORG_AND_IDENTIFIER,
		nativeQuery = true)
		List<Release> findReleasesByOrgAndIdentifier(String orgUuidAsString, String idType, String idValue);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY,
			nativeQuery = true)
	List<UUID> findReleasesWithVulnerability(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY_ANY_LOCATION,
			nativeQuery = true)
	List<UUID> findReleasesWithVulnerabilityAnyLocation(String orgUuidAsString, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VIOLATION,
			nativeQuery = true)
	List<UUID> findReleasesWithViolation(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_WEAKNESS,
			nativeQuery = true)
	List<UUID> findReleasesWithWeakness(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY_IN_BRANCH,
			nativeQuery = true)
	List<UUID> findReleasesWithVulnerabilityInBranch(String orgUuidAsString, String branchUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VIOLATION_IN_BRANCH,
			nativeQuery = true)
	List<UUID> findReleasesWithViolationInBranch(String orgUuidAsString, String branchUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_WEAKNESS_IN_BRANCH,
			nativeQuery = true)
	List<UUID> findReleasesWithWeaknessInBranch(String orgUuidAsString, String branchUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY_IN_COMPONENT,
			nativeQuery = true)
	List<UUID> findReleasesWithVulnerabilityInComponent(String orgUuidAsString, String componentUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VIOLATION_IN_COMPONENT,
			nativeQuery = true)
	List<UUID> findReleasesWithViolationInComponent(String orgUuidAsString, String componentUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_WEAKNESS_IN_COMPONENT,
			nativeQuery = true)
	List<UUID> findReleasesWithWeaknessInComponent(String orgUuidAsString, String componentUuidAsString, String location, String findingId);
	
	/**
	 * Earliest created_date among a component's releases (any lifecycle) --
	 * bound for the component-analytics lazy history backfill: no point
	 * walking chart days before the component's first release, and the
	 * guard keeps the leading-gap trigger from re-firing forever.
	 */
	@Query(value = """
			SELECT min(created_date) FROM rearm.releases
			WHERE record_data->>'component' = :componentUuidAsString
			""", nativeQuery = true)
	java.time.Instant findEarliestReleaseDateOfComponent(@Param("componentUuidAsString") String componentUuidAsString);

	/** Rows are (uuid, total_matches) — capped uuid page + window count. */
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_CVE_ID,
			nativeQuery = true)
	List<Object[]> findReleasesByCveId(String orgUuidAsString, String cveId, int limit);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases SET metrics = CAST(:metrics AS jsonb), metrics_revision = metrics_revision + 1 WHERE uuid = :uuid", nativeQuery = true)
	void updateMetrics(@Param("uuid") UUID uuid, @Param("metrics") String metrics);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases SET metrics_revision = metrics_revision + 1 WHERE uuid = :uuid", nativeQuery = true)
	void bumpMetricsRevision(@Param("uuid") UUID uuid);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases SET metrics = jsonb_set(coalesce(metrics, '{}'), '{lastScanned}', to_jsonb(extract(epoch from now()))), last_updated_date = now() WHERE uuid = :uuid", nativeQuery = true)
	void touchLastScanned(@Param("uuid") UUID uuid);
	@Query(
			value = VariableQueries.LIST_RELEASE_UUIDS_BY_COMPONENTS,
			nativeQuery = true)
	List<String> listReleaseUuidsByComponents(Collection<String> componentUuids);

	/**
	 * Mark the release as needing SBOM-component reconciliation. First-write
	 * wins on the timestamp so FIFO drain order is preserved when several
	 * triggers fire close together; subsequent calls are no-ops until the
	 * scheduler clears the sbomReconcile* keys on flow_control.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set(coalesce(flow_control, '{}'::jsonb), '{sbomReconcileRequestedAt}', to_jsonb(now()), true) "
			+ "WHERE uuid = :uuid AND (flow_control->>'sbomReconcileRequestedAt') IS NULL", nativeQuery = true)
	void markSbomReconcileRequested(@Param("uuid") UUID uuid);

	/**
	 * Picked up by the every-minute dependency-track scheduler. Excludes rows
	 * still in failure-backoff. Oldest-first to drain backed-up work fairly.
	 *
	 * <p>Returns just UUIDs so the caller can iterate with a heap-pressure
	 * guard between {@code findById} calls -- at most one Release entity
	 * (and therefore at most one row's worth of JSONB snapshots) is
	 * resident in the persistence context at any moment. Loading full
	 * {@link Release} rows up front would trigger Hibernate dirty-checking
	 * snapshots that deep-copy every JSONB column via
	 * serialize→bytes→deserialize ({@code hypersistence-utils} {@code JsonType}),
	 * which was large enough to OOM the scheduler thread before the
	 * per-iteration heap guard fired.
	 */
	@Query(value = "SELECT uuid FROM rearm.releases r "
			+ "WHERE r.flow_control->>'sbomReconcileRequestedAt' IS NOT NULL "
			+ "AND (r.flow_control->>'sbomReconcileSkipUntil' IS NULL "
			+ "     OR (r.flow_control->>'sbomReconcileSkipUntil')::timestamptz < now()) "
			+ "ORDER BY (r.flow_control->>'sbomReconcileRequestedAt')::timestamptz ASC "
			+ "LIMIT :batchLimit", nativeQuery = true)
	List<UUID> findUuidsOfReleasesPendingSbomReconcile(@Param("batchLimit") int batchLimit);

	/**
	 * Clear the queue marker after a successful reconcile. Strips just the
	 * sbomReconcile* keys (preserves any future flow keys that may be
	 * sharing the column) and collapses an emptied object back to NULL.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = NULLIF("
			+ "    flow_control - 'sbomReconcileRequestedAt' - 'sbomReconcileSkipUntil' - 'sbomReconcileFailureCount', "
			+ "    '{}'::jsonb) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	void clearSbomReconcileRequested(@Param("uuid") UUID uuid);

	/**
	 * Record a reconcile failure: bump the counter and push the next attempt
	 * out by {@code skipSeconds}. Leaves the requested-at timestamp in place
	 * so the row stays queued and retains FIFO position.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set("
			+ "    jsonb_set(coalesce(flow_control, '{}'::jsonb), "
			+ "              '{sbomReconcileFailureCount}', "
			+ "              to_jsonb(coalesce((flow_control->>'sbomReconcileFailureCount')::int, 0) + 1), "
			+ "              true), "
			+ "    '{sbomReconcileSkipUntil}', "
			+ "    to_jsonb((now() + (:skipSeconds || ' seconds')::interval)::text), "
			+ "    true) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	void recordSbomReconcileFailure(@Param("uuid") UUID uuid, @Param("skipSeconds") int skipSeconds);

	/**
	 * Record an incomplete (or throwing) metrics compute: bump the counter and
	 * fence the release out of the metrics finders for {@code skipSeconds}.
	 * The release stays finder-eligible (an incomplete compute stamps no
	 * lastScanned) — the fence only spaces retries, so a release waiting on an
	 * unscanned BOM / child doesn't occupy one of the per-tick finder slots
	 * every minute and starve younger rows behind it in the ORDER BY.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set("
			+ "    jsonb_set(coalesce(flow_control, '{}'::jsonb), "
			+ "              '{metricsComputeFailureCount}', "
			+ "              to_jsonb(coalesce((flow_control->>'metricsComputeFailureCount')::int, 0) + 1), "
			+ "              true), "
			+ "    '{metricsComputeSkipUntil}', "
			+ "    to_jsonb((now() + (:skipSeconds || ' seconds')::interval)::text), "
			+ "    true) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	void recordMetricsComputeIncomplete(@Param("uuid") UUID uuid, @Param("skipSeconds") int skipSeconds);

	/**
	 * Drop the metrics-compute backoff fence. Called when a compute finishes
	 * complete (fresh start for any future wait) and eagerly on containing
	 * product releases when a child's firstScanned lands, so the parent is
	 * re-derived on the next tick instead of waiting out its backoff. The
	 * WHERE guard makes the common no-fence case a no-op.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = NULLIF("
			+ "    flow_control - 'metricsComputeSkipUntil' - 'metricsComputeFailureCount', "
			+ "    '{}'::jsonb) "
			+ "WHERE uuid = :uuid "
			+ "AND flow_control->>'metricsComputeSkipUntil' IS NOT NULL", nativeQuery = true)
	void clearMetricsComputeBackoff(@Param("uuid") UUID uuid);

	// ---------------------------------------------------------------------
	// Auto-integrate queue -- same flow_control marker pattern as the SBOM
	// reconcile queue above. Lets release-create mark a release as needing
	// product feature-set auto-integration and have it run AFTER COMMIT on a
	// bounded executor (off the request connection), with durable retry so a
	// transient failure (e.g. DB pool pressure) self-heals instead of being
	// silently dropped. No migration: flow_control is JSONB.
	// ---------------------------------------------------------------------

	/** First-write-wins marker; idempotent re-marks are no-ops while queued. */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set(coalesce(flow_control, '{}'::jsonb), '{autoIntegrateRequestedAt}', to_jsonb(now()), true) "
			+ "WHERE uuid = :uuid AND (flow_control->>'autoIntegrateRequestedAt') IS NULL", nativeQuery = true)
	void markAutoIntegrateRequested(@Param("uuid") UUID uuid);

	/**
	 * Atomic claim of a queued auto-integrate. The immediate after-commit run
	 * and the per-minute scheduler drain can otherwise pick up the SAME queued
	 * release concurrently (the marker is only cleared at the end of a run) and
	 * double-integrate it -- observed as duplicate same-second product releases.
	 * Sets the skip-until lease iff the release is queued and not already
	 * leased; returns 0 when the claim is lost (or nothing is queued) so the
	 * caller skips. A successful run clears all markers; a failed run replaces
	 * the lease with the retry backoff; a crashed run leaves the lease to
	 * expire, after which the scheduler retries.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set(coalesce(flow_control, '{}'::jsonb), '{autoIntegrateSkipUntil}', "
			+ "    to_jsonb((now() + (:leaseSeconds || ' seconds')::interval)::text), true) "
			+ "WHERE uuid = :uuid AND (flow_control->>'autoIntegrateRequestedAt') IS NOT NULL "
			+ "AND ((flow_control->>'autoIntegrateSkipUntil') IS NULL "
			+ "     OR (flow_control->>'autoIntegrateSkipUntil')::timestamptz < now())", nativeQuery = true)
	int claimAutoIntegrate(@Param("uuid") UUID uuid, @Param("leaseSeconds") int leaseSeconds);

	/** Pending releases not in backoff, oldest-first. UUIDs only (heap guard). */
	@Query(value = "SELECT uuid FROM rearm.releases r "
			+ "WHERE r.flow_control->>'autoIntegrateRequestedAt' IS NOT NULL "
			+ "AND (r.flow_control->>'autoIntegrateSkipUntil' IS NULL "
			+ "     OR (r.flow_control->>'autoIntegrateSkipUntil')::timestamptz < now()) "
			+ "ORDER BY (r.flow_control->>'autoIntegrateRequestedAt')::timestamptz ASC "
			+ "LIMIT :batchLimit", nativeQuery = true)
	List<UUID> findUuidsOfReleasesPendingAutoIntegrate(@Param("batchLimit") int batchLimit);

	/** Clear the marker after every feature set integrated (or no-op). */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = NULLIF("
			+ "    flow_control - 'autoIntegrateRequestedAt' - 'autoIntegrateSkipUntil' - 'autoIntegrateFailureCount', "
			+ "    '{}'::jsonb) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	void clearAutoIntegrateRequested(@Param("uuid") UUID uuid);

	/** Bump failure count + push next attempt out by skipSeconds; stays queued. */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set("
			+ "    jsonb_set(coalesce(flow_control, '{}'::jsonb), "
			+ "              '{autoIntegrateFailureCount}', "
			+ "              to_jsonb(coalesce((flow_control->>'autoIntegrateFailureCount')::int, 0) + 1), "
			+ "              true), "
			+ "    '{autoIntegrateSkipUntil}', "
			+ "    to_jsonb((now() + (:skipSeconds || ' seconds')::interval)::text), "
			+ "    true) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	void recordAutoIntegrateFailure(@Param("uuid") UUID uuid, @Param("skipSeconds") int skipSeconds);

	/**
	 * Stamp {@code sbom_schema_version} after a successful reconcile so the
	 * catch-up scheduler can later identify releases still on an older
	 * aggregation layout via the partial index added in V37. Updated
	 * separately from the flow_control clear so future migrations of this
	 * area can simply bump the constant in code -- rows below it surface as
	 * eligible-for-reconcile without any Flyway re-enqueue UPDATE.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET sbom_schema_version = :version "
			+ "WHERE uuid = :uuid AND sbom_schema_version < :version", nativeQuery = true)
	void recordSbomReconciledAtVersion(@Param("uuid") UUID uuid, @Param("version") int version);

	/**
	 * Atomically claim the once-per-release BOM-diff notification. Stamps
	 * {@code flow_control.bomDiffNotifiedAt} only if it isn't set yet and
	 * returns the affected-row count: 1 means this caller won the claim and
	 * should evaluate + fire the alert, 0 means a prior reconcile already
	 * notified. The single-threaded scheduler drain makes a race unlikely,
	 * but the conditional UPDATE keeps the one-shot guarantee regardless.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.releases "
			+ "SET flow_control = jsonb_set(coalesce(flow_control, '{}'::jsonb), '{bomDiffNotifiedAt}', to_jsonb(now()), true) "
			+ "WHERE uuid = :uuid AND (flow_control->>'bomDiffNotifiedAt') IS NULL", nativeQuery = true)
	int claimBomDiffNotification(@Param("uuid") UUID uuid);

}

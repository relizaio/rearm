/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.PullRequest;
import jakarta.persistence.LockModeType;

public interface PullRequestRepository extends CrudRepository<PullRequest, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT pr FROM PullRequest pr WHERE uuid = :uuid")
	Optional<PullRequest> findByIdWriteLocked(UUID uuid);

	/**
	 * Upsert lookup. (targetVcsRepository, identity) is the unique key —
	 * matches the partial unique index from V30. Used by the
	 * addReleaseProgrammatic --pr-* path to find-or-create.
	 */
	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'targetVcsRepository' = :targetRepoUuidAsString "
			+ "AND pr.record_data->>'identity' = :identity",
			nativeQuery = true)
	Optional<PullRequest> findByTargetRepoAndIdentity(@Param("targetRepoUuidAsString") String targetRepoUuidAsString,
			@Param("identity") String identity);

	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY pr.created_date DESC",
			nativeQuery = true)
	List<PullRequest> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Same as {@link #findByOrg} but narrowed to the supplied state names.
	 * Caller filters via the GraphQL {@code states} arg — the default UI
	 * load passes {@code [OPEN]} so terminal-state PRs aren't fetched
	 * until the user opts in.
	 */
	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'org' = :orgUuidAsString "
			+ "AND pr.record_data->>'state' IN (:states) "
			+ "ORDER BY pr.created_date DESC",
			nativeQuery = true)
	List<PullRequest> findByOrgAndStates(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("states") List<String> states);

	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'targetVcsRepository' = :targetRepoUuidAsString "
			+ "ORDER BY pr.created_date DESC",
			nativeQuery = true)
	List<PullRequest> findByTargetRepository(@Param("targetRepoUuidAsString") String targetRepoUuidAsString);

	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'targetVcsRepository' = :targetRepoUuidAsString "
			+ "AND pr.record_data->>'state' IN (:states) "
			+ "ORDER BY pr.created_date DESC",
			nativeQuery = true)
	List<PullRequest> findByTargetRepositoryAndStates(@Param("targetRepoUuidAsString") String targetRepoUuidAsString,
			@Param("states") List<String> states);

	/**
	 * All currently-OPEN PRs against the given VCS. Used by the
	 * synchronizeLiveBranches close-stale-PRs path to find candidates
	 * whose source branch is no longer live at the SCM.
	 */
	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'targetVcsRepository' = :targetRepoUuidAsString "
			+ "AND pr.record_data->>'state' = 'OPEN'",
			nativeQuery = true)
	List<PullRequest> findOpenByTargetRepository(@Param("targetRepoUuidAsString") String targetRepoUuidAsString);

	/**
	 * Find open PRs in a target repo whose commits list contains the given
	 * SCE. The aggregator calls this whenever a release is added/updated
	 * to determine which PRs should be re-aggregated.
	 *
	 * jsonb_contains on `commits` matches the pattern used in
	 * FIND_RELEASES_BY_SCE_AND_ORG; PostgreSQL can use a GIN index on
	 * record_data if one is added later for hot loads.
	 */
	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'targetVcsRepository' = :targetRepoUuidAsString "
			+ "AND pr.record_data->>'state' = 'OPEN' "
			+ "AND jsonb_contains(pr.record_data->'commits', jsonb_build_array(:sceUuidAsString))",
			nativeQuery = true)
	List<PullRequest> findOpenByTargetRepoAndCommit(@Param("targetRepoUuidAsString") String targetRepoUuidAsString,
			@Param("sceUuidAsString") String sceUuidAsString);

	/**
	 * PRs in an org whose commits[] list contains any of the supplied SCE
	 * uuids. Used by {@code Session.pullRequests} to walk
	 * session.commits → PRs touched. EXISTS + jsonb_array_elements_text
	 * gives us the "any-of" semantics without the ?| operator (which
	 * Hibernate's native-query parser mistakes for a JDBC positional
	 * parameter).
	 */
	@Query(value = "SELECT * FROM rearm.pull_requests pr "
			+ "WHERE pr.record_data->>'org' = :orgUuidAsString "
			+ "AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(pr.record_data->'commits') AS c(v) "
			+ "            WHERE c.v = ANY(CAST(:sceUuids AS text[]))) "
			+ "ORDER BY pr.created_date DESC",
			nativeQuery = true)
	List<PullRequest> findByOrgAndAnyCommit(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("sceUuids") String[] sceUuids);

	/**
	 * Append a single validation event to the appropriate jsonb column.
	 * Implemented as native SQL to avoid the @DynamicUpdate aliasing
	 * documented on Release.java — concurrent appends from different
	 * transactions stay correct because the read-modify-write happens
	 * inside Postgres on the row.
	 *
	 * {@code clearAutomatically = true} flushes Hibernate's L1 cache
	 * after the update so a subsequent {@code findById} in the same
	 * transaction reloads the row and sees the freshly appended event
	 * (the native SQL bypasses the entity proxy and would otherwise
	 * leave a stale cached PullRequest in place).
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE rearm.pull_requests "
			+ "SET pr_validation_events = pr_validation_events || CAST(:event AS jsonb), "
			+ "    last_updated_date = now() "
			+ "WHERE uuid = :uuid",
			nativeQuery = true)
	void appendPrValidationEvent(@Param("uuid") UUID uuid, @Param("event") String eventJson);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE rearm.pull_requests "
			+ "SET release_validation_events = release_validation_events || CAST(:event AS jsonb), "
			+ "    last_updated_date = now() "
			+ "WHERE uuid = :uuid",
			nativeQuery = true)
	void appendReleaseValidationEvent(@Param("uuid") UUID uuid, @Param("event") String eventJson);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE rearm.pull_requests "
			+ "SET update_events = update_events || CAST(:event AS jsonb), "
			+ "    last_updated_date = now() "
			+ "WHERE uuid = :uuid",
			nativeQuery = true)
	void appendUpdateEvent(@Param("uuid") UUID uuid, @Param("event") String eventJson);
}

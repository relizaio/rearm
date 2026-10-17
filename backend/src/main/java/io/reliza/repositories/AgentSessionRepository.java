/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.AgentSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;

public interface AgentSessionRepository extends CrudRepository<AgentSession, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT s FROM AgentSession s WHERE uuid = :uuid")
	Optional<AgentSession> findByIdWriteLocked(UUID uuid);

	/**
	 * Natural-key lookup (org, root-agent, clientSessionId). The
	 * {@code agent} field on the session row is always the ROOT — the
	 * commit trailer parser (PR 2) resolves leaf agent → root before
	 * calling this.
	 */
	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'org' = :orgUuidAsString "
			+ "AND s.record_data->>'agent' = :agentUuidAsString "
			+ "AND s.record_data->>'clientSessionId' = :clientSessionId",
			nativeQuery = true)
	Optional<AgentSession> findByOrgAgentAndClientSessionId(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("agentUuidAsString") String agentUuidAsString,
			@Param("clientSessionId") String clientSessionId);

	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY s.last_updated_date DESC",
			nativeQuery = true)
	List<AgentSession> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Sessions in an org that recorded the given provider session id, as either the local or the
	 * remote id. Scoped by org first, so the element scan runs over one org's sessions.
	 */
	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'org' = :orgUuidAsString "
			+ "AND EXISTS (SELECT 1 FROM jsonb_array_elements("
			+ "COALESCE(s.record_data->'providerSessions', '[]'::jsonb)) ps "
			+ "WHERE ps->>'id' = :providerSessionId OR ps->>'remoteId' = :providerSessionId) "
			+ "ORDER BY s.last_updated_date DESC",
			nativeQuery = true)
	List<AgentSession> findByOrgAndProviderSessionId(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("providerSessionId") String providerSessionId);

	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'org' = :orgUuidAsString "
			+ "AND s.record_data->>'status' IN (:statuses) "
			+ "ORDER BY s.last_updated_date DESC",
			nativeQuery = true)
	List<AgentSession> findByOrgAndStatuses(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("statuses") List<String> statuses);

	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'agent' = :agentUuidAsString "
			+ "ORDER BY s.last_updated_date DESC",
			nativeQuery = true)
	List<AgentSession> findByAgent(@Param("agentUuidAsString") String agentUuidAsString);

	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'agent' = :agentUuidAsString "
			+ "AND s.record_data->>'status' IN (:statuses) "
			+ "ORDER BY s.last_updated_date DESC",
			nativeQuery = true)
	List<AgentSession> findByAgentAndStatuses(@Param("agentUuidAsString") String agentUuidAsString,
			@Param("statuses") List<String> statuses);

	/**
	 * Idle-session lookup for the autoclose scheduler. Returns OPEN
	 * sessions whose {@code lastActivityAt} is older than the supplied
	 * cutoff. Jackson writes {@code ZonedDateTime} to JSONB as an
	 * epoch-seconds-with-nanos number (e.g. {@code 1779239058.361082105})
	 * — NOT an ISO-8601 string — so the comparison is numeric on both
	 * sides. The {@code ->>} extraction yields text; the explicit
	 * {@code ::numeric} cast lifts both stored value and cutoff into
	 * the same numeric domain. BLOCKED sessions are intentionally
	 * excluded — they need operator attention, not an automatic close.
	 */
	@Query(value = "SELECT * FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'status' = 'OPEN' "
			+ "AND s.record_data->>'lastActivityAt' IS NOT NULL "
			+ "AND (s.record_data->>'lastActivityAt')::numeric < :cutoffEpochSeconds "
			+ "ORDER BY (s.record_data->>'lastActivityAt')::numeric ASC",
			nativeQuery = true)
	List<AgentSession> findOpenSessionsIdleBefore(@Param("cutoffEpochSeconds") double cutoffEpochSeconds);

	/**
	 * Per-status session counts for one ROOT agent, aggregated in SQL
	 * so dashboard badge resolvers don't deserialize every session row
	 * just to count them. Each result row is a (status, count) pair.
	 */
	@Query(value = "SELECT s.record_data->>'status' AS status, count(*) AS cnt "
			+ "FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'agent' = :agentUuidAsString "
			+ "GROUP BY s.record_data->>'status'",
			nativeQuery = true)
	List<Object[]> countByAgentGroupedByStatus(@Param("agentUuidAsString") String agentUuidAsString);

	/**
	 * Most recent {@code lastActivityAt} across one ROOT agent's
	 * sessions, as the raw epoch-seconds-with-nanos numeric Jackson
	 * writes to JSONB (format note on {@link #findOpenSessionsIdleBefore}).
	 * Null when the agent has no sessions with recorded activity.
	 */
	@Query(value = "SELECT max((s.record_data->>'lastActivityAt')::numeric) "
			+ "FROM rearm.agent_sessions s "
			+ "WHERE s.record_data->>'agent' = :agentUuidAsString",
			nativeQuery = true)
	BigDecimal maxLastActivityAtForAgent(@Param("agentUuidAsString") String agentUuidAsString);

	/** Re-point sessions at a surviving catalogue row; see AgentRepository.repointModel. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_sessions SET record_data = jsonb_set(record_data, '{model}', to_jsonb(cast(:into as text))) "
			+ "WHERE record_data->>'model' = :from", nativeQuery = true)
	int repointModel(@Param("from") String from, @Param("into") String into);

	/**
	 * Stamp an OPEN session's activity and clear a pending idle warning, and nothing else (task RD3-1):
	 * no revision, no lastUpdatedDate, no who-updated fields and no audit copy, since a keep-alive is
	 * not a change of record. {@code epochSeconds} is in the numeric form Jackson writes (see
	 * {@link #findOpenSessionsIdleBefore}). Monotonic: an older stamp never overwrites a newer one.
	 *
	 * @return 1 when the stamp was written, 0 when the session is not OPEN or already has a later one
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_sessions SET record_data = "
			+ "jsonb_set(record_data, '{lastActivityAt}', to_jsonb(cast(:epochSeconds as numeric))) - 'idleWarnedAt' "
			+ "WHERE uuid = :uuid AND record_data->>'status' = 'OPEN' "
			+ "AND coalesce((record_data->>'lastActivityAt')::numeric, 0) < cast(:epochSeconds as numeric)",
			nativeQuery = true)
	int stampActivity(@Param("uuid") UUID uuid, @Param("epochSeconds") BigDecimal epochSeconds);

	/**
	 * Record the idle warning on an OPEN session that has none, the same way as
	 * {@link #stampActivity}: activity bookkeeping, not a revision.
	 *
	 * @return 1 when written, 0 when the session is not OPEN or is already warned
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_sessions SET record_data = "
			+ "jsonb_set(record_data, '{idleWarnedAt}', to_jsonb(cast(:epochSeconds as numeric))) "
			+ "WHERE uuid = :uuid AND record_data->>'status' = 'OPEN' "
			+ "AND coalesce(record_data->'idleWarnedAt', 'null'::jsonb) = 'null'::jsonb",
			nativeQuery = true)
	int stampIdleWarning(@Param("uuid") UUID uuid, @Param("epochSeconds") BigDecimal epochSeconds);

	/**
	 * Record that an OPEN session polled a board for work (task RD3-4): the poll time and the roles it
	 * declared, under boardActivity.&lt;board&gt;, as activity bookkeeping rather than a revision (the RD3-1
	 * pattern). Throttled in the statement: nothing is written while the last poll is newer than
	 * {@code throttleBefore}.
	 *
	 * @return 1 when written, 0 when throttled or the session is not OPEN
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_sessions SET record_data = jsonb_set(record_data, '{boardActivity}', "
			+ "CASE WHEN jsonb_typeof(record_data->'boardActivity') = 'object' THEN record_data->'boardActivity' ELSE '{}'::jsonb END || jsonb_build_object(cast(:board as text), "
			+ "CASE WHEN jsonb_typeof(record_data->'boardActivity'->:board) = 'object' THEN record_data->'boardActivity'->:board ELSE '{}'::jsonb END || jsonb_build_object('lastPollAt', cast(:epochSeconds as numeric), 'roles', cast(:roles as jsonb)))) "
			+ "WHERE uuid = :uuid AND record_data->>'status' = 'OPEN' "
			+ "AND coalesce((record_data->'boardActivity'->:board->>'lastPollAt')::numeric, 0) < cast(:throttleBefore as numeric)",
			nativeQuery = true)
	int stampBoardPoll(@Param("uuid") UUID uuid, @Param("board") String board, @Param("epochSeconds") BigDecimal epochSeconds,
			@Param("roles") String roles, @Param("throttleBefore") BigDecimal throttleBefore);

	/** As {@link #stampBoardPoll}, when the poll offered a task: the offer time and the poll time, never throttled. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_sessions SET record_data = jsonb_set(record_data, '{boardActivity}', "
			+ "CASE WHEN jsonb_typeof(record_data->'boardActivity') = 'object' THEN record_data->'boardActivity' ELSE '{}'::jsonb END || jsonb_build_object(cast(:board as text), "
			+ "CASE WHEN jsonb_typeof(record_data->'boardActivity'->:board) = 'object' THEN record_data->'boardActivity'->:board ELSE '{}'::jsonb END || jsonb_build_object('lastPollAt', cast(:epochSeconds as numeric), "
			+ "'lastOfferAt', cast(:epochSeconds as numeric), 'roles', cast(:roles as jsonb)))) "
			+ "WHERE uuid = :uuid AND record_data->>'status' = 'OPEN'",
			nativeQuery = true)
	int stampBoardOffer(@Param("uuid") UUID uuid, @Param("board") String board, @Param("epochSeconds") BigDecimal epochSeconds,
			@Param("roles") String roles);

	/**
	 * The roles each OPEN session declared when it last polled the board at or after {@code since}, one
	 * JSON array per session; an empty array is a session that takes any role (task RD3-4).
	 */
	@Query(value = "SELECT coalesce(record_data->'boardActivity'->:board->'roles', '[]'::jsonb)::text "
			+ "FROM rearm.agent_sessions WHERE record_data->>'status' = 'OPEN' "
			+ "AND jsonb_typeof(record_data->'boardActivity'->:board->'lastPollAt') = 'number' "
			+ "AND (record_data->'boardActivity'->:board->>'lastPollAt')::numeric >= cast(:since as numeric)",
			nativeQuery = true)
	List<String> rolesPolledSince(@Param("board") String board, @Param("since") BigDecimal since);

	/** Every session that has polled the board, open or closed (task RD3-5): its boardActivity names the board. */
	@Query(value = "SELECT * FROM rearm.agent_sessions WHERE jsonb_typeof(record_data->'boardActivity'->:board) = 'object'",
			nativeQuery = true)
	List<AgentSession> findWithBoardActivity(@Param("board") String board);
}

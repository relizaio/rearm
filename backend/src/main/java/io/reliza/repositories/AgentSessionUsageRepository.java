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
import org.springframework.data.repository.query.Param;

import io.reliza.model.AgentSessionUsage;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usage rows are append-only and are read three ways: by session (rollup rebuild), by task (hop
 * and task totals) and by period for a board or an org. Each has its index; none of these reads
 * touches {@code record_data}.
 */
public interface AgentSessionUsageRepository extends CrudRepository<AgentSessionUsage, UUID> {

	List<AgentSessionUsage> findBySessionOrderByReportedAtAsc(UUID session);

	List<AgentSessionUsage> findByTask(UUID task);

	/**
	 * Every row charged to a board: its tasks' rows and its coordinator seat's. What the budget
	 * projection reads, once per board per poll.
	 */
	List<AgentSessionUsage> findByBoard(UUID board);

	/**
	 * Idempotency probe for one report line. The unique index carries model, hosting and band
	 * because one delta legitimately produces several rows under one sequence.
	 */
	Optional<AgentSessionUsage> findBySessionAndClientSeqAndModelAndHostingAndContextBand(
			UUID session, long clientSeq, UUID model, io.reliza.model.SessionUsageHosting hosting,
			long contextBand);

	/**
	 * Highest sequence accepted for a session. A report at or above this is new or a retry; below
	 * it is refused, which is what stops an out-of-order backfill from re-inserting old windows.
	 */
	@Query(value = "SELECT max(u.client_seq) FROM rearm.agent_session_usages u "
			+ "WHERE u.session = :session", nativeQuery = true)
	Long findMaxClientSeq(@Param("session") UUID session);

	/**
	 * Rows attributed to a task within one assignment window: the hop snapshot.
	 *
	 * <p>{@code :to} is cast explicitly because it is nullable and appears in {@code IS NULL}:
	 * Postgres cannot infer a bare parameter's type from that position alone and refuses the whole
	 * statement with "could not determine data type", whatever value is bound. The open end of the
	 * window is the live hop, so without the cast every sign-off and return would have failed.
	 */
	@Query(value = "SELECT * FROM rearm.agent_session_usages u "
			+ "WHERE u.task = :task AND u.session = :session "
			+ "AND u.reported_at >= :from "
			+ "AND (cast(:to as timestamptz) IS NULL OR u.reported_at < cast(:to as timestamptz))",
			nativeQuery = true)
	List<AgentSessionUsage> findForHop(@Param("task") UUID task, @Param("session") UUID session,
			@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);

	@Query(value = "SELECT * FROM rearm.agent_session_usages u "
			+ "WHERE u.board = :board AND u.reported_at >= :from AND u.reported_at < :to",
			nativeQuery = true)
	List<AgentSessionUsage> findForBoardPeriod(@Param("board") UUID board,
			@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);

	@Query(value = "SELECT * FROM rearm.agent_session_usages u "
			+ "WHERE u.org = :org AND u.reported_at >= :from AND u.reported_at < :to",
			nativeQuery = true)
	List<AgentSessionUsage> findForOrgPeriod(@Param("org") UUID org,
			@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);

	/** Re-point usage rows at a surviving catalogue row; see AgentRepository.repointModel. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_session_usages SET model = :into WHERE model = :from",
			nativeQuery = true)
	int repointModel(@Param("from") UUID from, @Param("into") UUID into);

	/**
	 * Re-point the usage rows of a folded model that have no twin on the survivor (task RD2-26): the
	 * rows that would collide on (session, client_seq, model, hosting, context_band) stay behind for
	 * the caller to sum into their twin.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.agent_session_usages u SET model = :into WHERE u.model = :from AND NOT EXISTS ("
			+ "SELECT 1 FROM rearm.agent_session_usages t WHERE t.model = :into AND t.session = u.session"
			+ " AND t.client_seq = u.client_seq AND t.hosting = u.hosting AND t.context_band = u.context_band)",
			nativeQuery = true)
	int repointModelWithoutCollision(@Param("from") UUID from, @Param("into") UUID into);

	List<AgentSessionUsage> findByModel(UUID model);

	/** Sessions and usage lines on a model since a moment (task RD2-27): one row, {sessions, lines}. */
	@Query(value = "SELECT count(DISTINCT u.session), count(*) FROM rearm.agent_session_usages u "
			+ "WHERE u.model = :model AND u.reported_at >= :since", nativeQuery = true)
	List<Object[]> countSessionsAndLinesSince(@Param("model") UUID model, @Param("since") ZonedDateTime since);
}

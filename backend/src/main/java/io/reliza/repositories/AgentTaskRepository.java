/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.AgentTask;
import jakarta.persistence.LockModeType;

public interface AgentTaskRepository extends CrudRepository<AgentTask, UUID> {

	/**
	 * Assignment and coordinator mutations lock the row so racing
	 * agents serialize — the loser re-reads a row that already carries
	 * the winner's state.
	 */
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT t FROM AgentTask t WHERE uuid = :uuid")
	Optional<AgentTask> findByIdWriteLocked(UUID uuid);

	/** Idempotent-registration lookup on the (org, externalRef) natural key. */
	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->>'org' = :orgUuidAsString "
			+ "AND t.record_data->>'externalRef' = :externalRef",
			nativeQuery = true)
	Optional<AgentTask> findByOrgAndExternalRef(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("externalRef") String externalRef);

	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->>'board' = :boardUuidAsString "
			+ "ORDER BY (t.record_data->>'orderIndex')::int ASC, t.created_date ASC",
			nativeQuery = true)
	List<AgentTask> findByBoard(@Param("boardUuidAsString") String boardUuidAsString);

	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->>'board' = :boardUuidAsString "
			+ "AND t.record_data->>'status' = :status "
			+ "ORDER BY (t.record_data->>'orderIndex')::int ASC, t.created_date ASC",
			nativeQuery = true)
	List<AgentTask> findByBoardAndStatus(@Param("boardUuidAsString") String boardUuidAsString,
			@Param("status") String status);

	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY t.created_date ASC",
			nativeQuery = true)
	List<AgentTask> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/** Every task in one status, across organizations: the delivery sweep's DELIVERING tasks. */
	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->>'status' = :status "
			+ "ORDER BY t.created_date ASC",
			nativeQuery = true)
	List<AgentTask> findByStatus(@Param("status") String status);

	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->>'org' = :orgUuidAsString "
			+ "AND t.record_data->>'status' = :status "
			+ "ORDER BY t.created_date ASC",
			nativeQuery = true)
	List<AgentTask> findByOrgAndStatus(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("status") String status);

	/** ASSIGNED tasks held by the given session (session-close release path). */
	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->'assignment'->>'session' = :sessionUuidAsString",
			nativeQuery = true)
	List<AgentTask> findByAssignmentSession(@Param("sessionUuidAsString") String sessionUuidAsString);

	/**
	 * Tasks currently assigned to a session. Implicit attribution needs exactly one of these to
	 * exist for a report window; two mean the server cannot tell which task spent the tokens, and
	 * the row stays unattributed rather than guessing.
	 */
	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->'assignment'->>'session' = :sessionUuidAsString",
			nativeQuery = true)
	List<AgentTask> findByAssignedSession(@Param("sessionUuidAsString") String sessionUuidAsString);

	/**
	 * Tasks this session has ever touched: the current assignment, or a sign-off or return it
	 * wrote. Explicit attribution is accepted only against this set -- a client naming a task it
	 * never worked is a bug worth surfacing rather than a row to file quietly.
	 */
	@Query(value = "SELECT * FROM rearm.agent_tasks t "
			+ "WHERE t.record_data->'assignment'->>'session' = :sessionUuidAsString "
			// cast(... as jsonb) rather than ::jsonb: the shorthand's colons are read as the start
			// of another named parameter, and the binding fails at runtime rather than at build.
			+ "OR t.record_data->'signOffs' @> cast(:sessionRef as jsonb) "
			+ "OR t.record_data->'returns' @> cast(:sessionRef as jsonb)",
			nativeQuery = true)
	List<AgentTask> findTouchedBySession(@Param("sessionUuidAsString") String sessionUuidAsString,
			@Param("sessionRef") String sessionRef);
}

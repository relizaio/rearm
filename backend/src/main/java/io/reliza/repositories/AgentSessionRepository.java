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

import io.reliza.model.AgentSession;
import jakarta.persistence.LockModeType;

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
}

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

import io.reliza.model.Agent;
import jakarta.persistence.LockModeType;

public interface AgentRepository extends CrudRepository<Agent, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT a FROM Agent a WHERE uuid = :uuid")
	Optional<Agent> findByIdWriteLocked(UUID uuid);

	/**
	 * Lookup by (org, agentIdentity, lower(name)) — the natural-identity
	 * boundary the V40 unique index enforces. Used by the
	 * auto-registration path to find-or-create an agent row on the first
	 * session under a given identity, and by sub-agent spawning when the
	 * parent registers a child by name (the parent's identity is used).
	 */
	@Query(value = "SELECT * FROM rearm.agents a "
			+ "WHERE a.record_data->>'org' = :orgUuidAsString "
			+ "AND a.record_data->>'agentIdentity' = :agentIdentityAsString "
			+ "AND lower(a.record_data->>'name') = lower(:name)",
			nativeQuery = true)
	Optional<Agent> findByOrgIdentityAndName(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("agentIdentityAsString") String agentIdentityAsString,
			@Param("name") String name);

	@Query(value = "SELECT * FROM rearm.agents a "
			+ "WHERE a.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY a.created_date DESC",
			nativeQuery = true)
	List<Agent> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Direct children of a given root agent (one level of the tree).
	 * Backed by the {@code agents_root_agent} index in V37.
	 */
	@Query(value = "SELECT * FROM rearm.agents a "
			+ "WHERE a.record_data->>'rootAgent' = :rootUuidAsString "
			+ "ORDER BY a.created_date ASC",
			nativeQuery = true)
	List<Agent> findByRootAgent(@Param("rootUuidAsString") String rootUuidAsString);
}

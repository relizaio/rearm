/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.AgentTaskRoleConfig;

public interface AgentTaskRoleConfigRepository extends CrudRepository<AgentTaskRoleConfig, UUID> {

	@Query(value = "SELECT * FROM rearm.agent_task_role_configs rc "
			+ "WHERE rc.record_data->>'board' = :boardUuidAsString "
			+ "AND lower(rc.record_data->>'name') = lower(:name)",
			nativeQuery = true)
	Optional<AgentTaskRoleConfig> findByBoardAndName(@Param("boardUuidAsString") String boardUuidAsString,
			@Param("name") String name);

	@Query(value = "SELECT * FROM rearm.agent_task_role_configs rc "
			+ "WHERE rc.record_data->>'board' = :boardUuidAsString "
			+ "ORDER BY (rc.record_data->>'orderIndex')::int ASC",
			nativeQuery = true)
	List<AgentTaskRoleConfig> findByBoard(@Param("boardUuidAsString") String boardUuidAsString);

	/** Org-level presets: role-config rows with no board. */
	@Query(value = "SELECT * FROM rearm.agent_task_role_configs rc "
			+ "WHERE rc.record_data->>'org' = :orgUuidAsString "
			+ "AND rc.record_data->>'board' IS NULL "
			+ "ORDER BY (rc.record_data->>'orderIndex')::int ASC",
			nativeQuery = true)
	List<AgentTaskRoleConfig> findPresetsByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	@Query(value = "SELECT * FROM rearm.agent_task_role_configs rc "
			+ "WHERE rc.record_data->>'org' = :orgUuidAsString "
			+ "AND rc.record_data->>'board' IS NULL "
			+ "AND lower(rc.record_data->>'name') = lower(:name)",
			nativeQuery = true)
	Optional<AgentTaskRoleConfig> findPresetByOrgAndName(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("name") String name);

	/**
	 * Roles and presets holding a strength override for a model, for re-pointing them when the
	 * model is merged. Board roles and org presets share this table, so one query covers both.
	 */
	@Query(value = "SELECT * FROM rearm.agent_task_role_configs rc "
			+ "WHERE rc.record_data->'modelStrengths' @> "
			+ "jsonb_build_array(jsonb_build_object('model', :modelUuidAsString))",
			nativeQuery = true)
	List<AgentTaskRoleConfig> findByModelStrengthModel(@Param("modelUuidAsString") String modelUuidAsString);
}

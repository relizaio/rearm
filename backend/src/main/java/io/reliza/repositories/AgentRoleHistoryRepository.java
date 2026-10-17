/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import io.reliza.model.AgentRoleHistory;

@Repository
public interface AgentRoleHistoryRepository extends CrudRepository<AgentRoleHistory, AgentRoleHistory.Key> {

	/**
	 * Count this sign-off, creating the row the first time.
	 *
	 * <p>An upsert rather than read-modify-write: two sessions signing off on one board at the
	 * same moment would otherwise race and lose a count. The count is advisory, but a lost write
	 * that takes a deadlock with it is not.
	 */
	@Modifying
	@Query(value = "INSERT INTO rearm.agent_role_history (board, agent, role, last_signed_off_at, sign_offs)"
			+ " VALUES (:board, :agent, :role, now(), 1)"
			+ " ON CONFLICT (board, agent, role) DO UPDATE"
			+ " SET sign_offs = rearm.agent_role_history.sign_offs + 1, last_signed_off_at = now()",
			nativeQuery = true)
	void record(@Param("board") UUID board, @Param("agent") UUID agent, @Param("role") UUID role);

	/** Roles this agent has signed off on this board, for poll ordering. */
	@Query(value = "SELECT role FROM rearm.agent_role_history WHERE board = :board AND agent = :agent",
			nativeQuery = true)
	List<UUID> rolesForAgent(@Param("board") UUID board, @Param("agent") UUID agent);

	/** Dropped with the board it belongs to. */
	@Modifying
	@Query(value = "DELETE FROM rearm.agent_role_history WHERE board = :board", nativeQuery = true)
	void deleteForBoard(@Param("board") UUID board);
}

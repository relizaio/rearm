/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.repositories.AgentRoleHistoryRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Which roles an agent has done on a board, for poll ordering.
 *
 * <p>Affinity, never eligibility: an agent that has reviewed on this board before is offered a
 * review first, and nothing is ever held for it. So every read here is allowed to be stale and
 * every write is allowed to fail -- the cost is a poll that offers a slightly worse task, which is
 * the correct price for a table on the sign-off path.
 */
@Slf4j
@Service
public class AgentRoleHistoryService {

	@Autowired private AgentRoleHistoryRepository repository;

	/**
	 * Count one sign-off. Called after the task transaction commits, never inside it -- from the
	 * effects applier's own transaction, which rolls back on any exception. Nothing here refuses;
	 * the rule is declared so the guard holds for every transaction in the agent services.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public void record(BoardEffects.RoleHistoryEntry entry) {
		if (null == entry || null == entry.agent() || null == entry.role()) return;
		repository.record(entry.board(), entry.agent(), entry.role());
	}

	/** Roles this agent has signed off on this board. Empty when it is new here. */
	public Set<UUID> rolesFor(UUID board, UUID agent) {
		if (null == board || null == agent) return Set.of();
		try {
			return Set.copyOf(repository.rolesForAgent(board, agent));
		} catch (RuntimeException e) {
			// Ordering data. A poll that cannot read it returns tasks in the next-best order
			// rather than failing, which is what "affinity is never eligibility" has to mean in
			// the code and not only in the design.
			log.error("Could not read role history for agent {} on board {}", agent, board, e);
			return Set.of();
		}
	}

	@Transactional(rollbackFor = RelizaException.class)
	public void forgetBoard(UUID board) {
		repository.deleteForBoard(board);
	}
}

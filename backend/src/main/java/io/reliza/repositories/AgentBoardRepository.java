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

import io.reliza.model.AgentBoard;
import jakarta.persistence.LockModeType;

public interface AgentBoardRepository extends CrudRepository<AgentBoard, UUID> {

	/**
	 * Seat claims and lock transitions serialize on the board row so
	 * two sessions racing for the coordinator seat resolve safely.
	 */
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT b FROM AgentBoard b WHERE uuid = :uuid")
	Optional<AgentBoard> findByIdWriteLocked(UUID uuid);

	@Query(value = "SELECT * FROM rearm.agent_boards b "
			+ "WHERE b.record_data->>'org' = :orgUuidAsString "
			+ "AND lower(b.record_data->>'name') = lower(:name)",
			nativeQuery = true)
	Optional<AgentBoard> findByOrgAndName(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("name") String name);

	@Query(value = "SELECT * FROM rearm.agent_boards b "
			+ "WHERE b.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY b.created_date ASC",
			nativeQuery = true)
	List<AgentBoard> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/** Boards that watch how long a task waits on a person (humanQueueAgeMinutes above 0). */
	@Query(value = "SELECT * FROM rearm.agent_boards b "
			+ "WHERE jsonb_typeof(b.record_data->'humanQueueAgeMinutes') = 'number' "
			+ "AND (b.record_data->>'humanQueueAgeMinutes')::int > 0",
			nativeQuery = true)
	List<AgentBoard> findWatchingHumanQueueAge();

	/** Boards whose coordinator seat is held by the given session (release-on-close path). */
	@Query(value = "SELECT * FROM rearm.agent_boards b "
			+ "WHERE b.record_data->'coordinatorSeat'->>'session' = :sessionUuidAsString",
			nativeQuery = true)
	List<AgentBoard> findBySeatSession(@Param("sessionUuidAsString") String sessionUuidAsString);


	/**
	 * Draw a board's next task number (task RD3-2): increment {@code nextTaskNumber} in place and
	 * return it, writing that one field and nothing else -- no revision, no lastUpdatedDate, no audit
	 * copy. A plain native query rather than {@code @Modifying}, which cannot return the RETURNING
	 * value. The caller holds the board lock (it serialises with numbering and a prefix change); the
	 * counter only grows, so a number is never reused, even after the last task is deleted.
	 */
	@Transactional
	@Query(value = "UPDATE rearm.agent_boards SET record_data = jsonb_set(record_data, '{nextTaskNumber}', "
			+ "to_jsonb(coalesce((record_data->>'nextTaskNumber')::int, 0) + 1)) "
			+ "WHERE uuid = :uuid RETURNING (record_data->>'nextTaskNumber')::int",
			nativeQuery = true)
	Integer nextTaskNumber(@Param("uuid") UUID uuid);

	/** Boards that set a staleness block (task RD3-4). */
	@Query(value = "SELECT * FROM rearm.agent_boards b WHERE jsonb_typeof(b.record_data->'staleness') = 'object'",
			nativeQuery = true)
	List<AgentBoard> findWatchingStaleness();

	/**
	 * The staleness sweep's last-alerted times (task RD3-4), replaced whole by a targeted update: the
	 * sweep's bookkeeping, not a revision of the board.
	 */
	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@org.springframework.transaction.annotation.Transactional
	@Query(value = "UPDATE rearm.agent_boards SET record_data = jsonb_set(record_data, '{stalenessAlerted}', cast(:alerted as jsonb)) "
			+ "WHERE uuid = :uuid",
			nativeQuery = true)
	int stampStalenessAlerted(@Param("uuid") UUID uuid, @Param("alerted") String alerted);
}

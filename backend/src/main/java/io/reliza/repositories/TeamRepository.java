/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Team;
import jakarta.persistence.LockModeType;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT t FROM Team t where t.uuid = :uuid")
	public Optional<Team> findByIdWriteLocked(UUID uuid);

	@Query(
			value = VariableQueries.FIND_ALL_TEAMS_BY_ORGANIZATION,
			nativeQuery = true)
	List<Team> findAllByOrganization(@Param("orgUuidAsString") String orgUuidAsString);
}

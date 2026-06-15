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
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.HbomComponent;

public interface HbomComponentRepository extends CrudRepository<HbomComponent, UUID> {

	@Query(value = "SELECT * FROM rearm.hbom_components h "
			+ "WHERE h.record_data->>'release' = :releaseUuidAsString "
			+ "ORDER BY (h.record_data->>'isRoot')::boolean DESC NULLS LAST, h.record_data->>'name' ASC",
			nativeQuery = true)
	List<HbomComponent> findByRelease(@Param("releaseUuidAsString") String releaseUuidAsString);

	@Transactional
	@Modifying
	@Query(value = "DELETE FROM rearm.hbom_components h WHERE h.record_data->>'release' = :releaseUuidAsString",
			nativeQuery = true)
	void deleteByRelease(@Param("releaseUuidAsString") String releaseUuidAsString);
}

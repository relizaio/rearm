/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Acollection;
import io.reliza.model.Deliverable;

public interface AcollectionRepository extends CrudRepository<Acollection, UUID> {
	@Query(
			value = VariableQueries.FIND_ACOLLECTIONS_BY_RELEASE,
			nativeQuery = true)
	List<Acollection> findAcollectionsByRelease(String releaseUuidAsString);

}

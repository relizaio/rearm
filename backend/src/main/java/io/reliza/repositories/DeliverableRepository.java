/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Deliverable;

public interface DeliverableRepository extends CrudRepository<Deliverable, UUID> {
	@Query(
			value = VariableQueries.FIND_DELIVERABLE_BY_DIGEST,
			nativeQuery = true)
	List<Deliverable> findDeliverableByDigest(String digest, String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_DELIVERABLE_BY_DIGEST_AND_COMPONENT,
			nativeQuery = true)
	Optional<Deliverable> findDeliverableByDigestAndComponent(String digest, String compUuidAsStr);
	
	@Query(
			value = VariableQueries.LIST_DELIVERABLES_BY_COMPONENT,
			nativeQuery = true)
	List<Deliverable> listDeliverablesByComponent(String compUuidAsStr);

	@Query(
			value = VariableQueries.LIST_DELIVERABLES_BY_ORG,
			nativeQuery = true)
	List<Deliverable> listDeliverablesByOrg(String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_DELIVERABLE_BY_BUILD_ID,
			nativeQuery = true)
	List<Deliverable> findDeliverableByBuildId(String query, String orgUuidAsString);
}

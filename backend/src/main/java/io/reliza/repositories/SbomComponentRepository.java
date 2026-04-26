/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.SbomComponent;

public interface SbomComponentRepository extends CrudRepository<SbomComponent, UUID> {

	Optional<SbomComponent> findByCanonicalPurl(String canonicalPurl);

	@Query(
		value = "SELECT * FROM rearm.sbom_components WHERE canonical_purl IN (:canonicalPurls)",
		nativeQuery = true)
	List<SbomComponent> findByCanonicalPurlIn(Collection<String> canonicalPurls);
}

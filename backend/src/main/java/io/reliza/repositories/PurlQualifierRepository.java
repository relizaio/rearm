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
import org.springframework.data.repository.query.Param;

import io.reliza.model.PurlQualifier;

public interface PurlQualifierRepository extends CrudRepository<PurlQualifier, UUID> {

	Optional<PurlQualifier> findByOrgAndFullPurl(UUID org, String fullPurl);

	@Query(
		value = "SELECT * FROM rearm.purl_qualifiers WHERE org = CAST(:orgUuidAsString AS uuid) AND full_purl IN (:fullPurls)",
		nativeQuery = true)
	List<PurlQualifier> findByOrgAndFullPurlIn(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("fullPurls") Collection<String> fullPurls);
}

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

import io.reliza.model.SbomComponent;

public interface SbomComponentRepository extends CrudRepository<SbomComponent, UUID> {

	Optional<SbomComponent> findByOrgAndCanonicalPurl(UUID org, String canonicalPurl);

	@Query(
		value = "SELECT * FROM rearm.sbom_components WHERE org = CAST(:orgUuidAsString AS uuid) AND canonical_purl IN (:canonicalPurls)",
		nativeQuery = true)
	List<SbomComponent> findByOrgAndCanonicalPurlIn(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("canonicalPurls") Collection<String> canonicalPurls);

	/**
	 * Search canonical sbom_components scoped to an org. Hits the
	 * (org, name) composite b-tree index — no JSONB scan. Version filter is
	 * optional; pass null to match any version.
	 */
	@Query("SELECT sc FROM SbomComponent sc "
			+ "WHERE sc.org = :org AND sc.name = :name "
			+ "AND (:version IS NULL OR sc.version = :version)")
	List<SbomComponent> searchByOrgAndNameAndOptionalVersion(
			@Param("org") UUID org,
			@Param("name") String name,
			@Param("version") String version);
}

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

	Optional<SbomComponent> findByCanonicalPurl(String canonicalPurl);

	@Query(
		value = "SELECT * FROM rearm.sbom_components WHERE canonical_purl IN (:canonicalPurls)",
		nativeQuery = true)
	List<SbomComponent> findByCanonicalPurlIn(Collection<String> canonicalPurls);

	/**
	 * Search canonical sbom_components scoped to an org. sbom_components is
	 * org-agnostic, so we narrow via release_sbom_components → releases.org.
	 * Version filter is optional; pass null to match any version.
	 */
	@Query(
		value = """
			SELECT DISTINCT sc.*
			FROM rearm.sbom_components sc
			JOIN rearm.release_sbom_components rsc ON rsc.sbom_component_uuid = sc.uuid
			JOIN rearm.releases r ON r.uuid = rsc.release_uuid
			WHERE r.record_data->>'org' = :orgUuidAsString
			AND sc.record_data->>'name' = :name
			AND (CAST(:version AS text) IS NULL OR sc.record_data->>'version' = :version)
		""",
		nativeQuery = true)
	List<SbomComponent> searchByOrgAndNameAndOptionalVersion(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("name") String name,
			@Param("version") String version);
}

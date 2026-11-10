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

import io.reliza.model.SbomComponentOverlay;

/** The current overlays of component keys (FDAW-15 3.1, 3.2). */
public interface SbomComponentOverlayRepository extends CrudRepository<SbomComponentOverlay, UUID> {

	Optional<SbomComponentOverlay> findByOrgAndCanonicalPurl(UUID org, String canonicalPurl);

	List<SbomComponentOverlay> findByOrgAndCanonicalPurlIn(UUID org, Collection<String> canonicalPurls);

	/** The organization's vendored descriptions of a stored name, newest first, at most 20 (FDAW-15 3.7). */
	@Query(value = "SELECT * FROM rearm.sbom_component_overlays WHERE org = :org AND kind = 'VENDORED'"
			+ " AND record_data->>'name' = :name ORDER BY last_updated_date DESC LIMIT 20", nativeQuery = true)
	List<SbomComponentOverlay> findVendoredByOrgAndName(@Param("org") UUID org, @Param("name") String name);

	/**
	 * Take the transaction lock that serializes the writers of one key; released when the transaction ends.
	 * Two-key form, {@code lockNamespace} = {@code AdvisoryLockKey.SBOM_COMPONENT_KEY_WRITE}, {@code orgAndKey}
	 * = {@code org|key}.
	 */
	@Query(
		value = """
			SELECT 1 FROM (
			  SELECT pg_advisory_xact_lock(:lockNamespace, hashtext(:orgAndKey))
			) AS locked
			""",
		nativeQuery = true)
	Integer lockKey(@Param("lockNamespace") int lockNamespace, @Param("orgAndKey") String orgAndKey);
}

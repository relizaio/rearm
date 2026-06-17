/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.KevAssertion;
import io.reliza.model.KevSource;

public interface KevAssertionRepository extends CrudRepository<KevAssertion, UUID> {

	/** Every assertion for a CVE, across all sources (active and revoked) —
	 *  drives the per-source detail surface. */
	List<KevAssertion> findByCveIdOrderBySourceAsc(String cveId);

	/** This source's current assertions, for the reconcile diff. */
	List<KevAssertion> findBySource(KevSource source);

	/**
	 * Bulk membership probe for the {@code knownExploited} data loader — one
	 * round trip for all CVE ids on a findings page. Distinct cve_id, and
	 * deliberately NOT filtered on {@code revoked_date}: a CVE that was ever
	 * asserted stays KEV-listed, so a withdrawn/poisoned feed can't flip the
	 * answer to false.
	 */
	@Query(
		value = "SELECT DISTINCT cve_id FROM rearm.kev_assertions WHERE cve_id IN (:cveIds)",
		nativeQuery = true)
	List<String> findExistingCveIds(@Param("cveIds") Collection<String> cveIds);

	/**
	 * Every CVE asserted by any source (active or revoked). Read once per
	 * sync to tell a genuinely new KEV (no prior assertion anywhere → emit a
	 * KEV_ADDED event) from one a different source already listed.
	 */
	@Query(
		value = "SELECT DISTINCT cve_id FROM rearm.kev_assertions",
		nativeQuery = true)
	List<String> findAllDistinctCveIds();
}

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

	/** Every assertion for a CVE within one org, across all sources (active
	 *  and revoked) — drives the per-source detail surface. */
	List<KevAssertion> findByOrgIdAndCveIdOrderBySourceAsc(UUID orgId, String cveId);

	/** This org+source's current assertions, for the reconcile diff. */
	List<KevAssertion> findByOrgIdAndSource(UUID orgId, KevSource source);

	/**
	 * Bulk membership probe for the {@code knownExploited} data loader — one
	 * round trip for all CVE ids on a findings page, scoped to one org.
	 * Distinct cve_id, and deliberately NOT filtered on {@code revoked_date}:
	 * a CVE that was ever asserted (in this org) stays KEV-listed, so a
	 * withdrawn/poisoned feed can't flip the answer to false.
	 */
	@Query(
		value = "SELECT DISTINCT cve_id FROM rearm.kev_assertions WHERE org_id = :orgId AND cve_id IN (:cveIds)",
		nativeQuery = true)
	List<String> findExistingCveIdsForOrg(@Param("orgId") UUID orgId, @Param("cveIds") Collection<String> cveIds);

	/**
	 * Every CVE asserted by any source within one org (active or revoked).
	 * Read once per per-org sync to tell a genuinely new KEV (no prior
	 * assertion in this org → emit a KEV_ADDED event) from one a different
	 * source in the same org already listed.
	 */
	@Query(
		value = "SELECT DISTINCT cve_id FROM rearm.kev_assertions WHERE org_id = :orgId",
		nativeQuery = true)
	List<String> findAllDistinctCveIdsForOrg(@Param("orgId") UUID orgId);

	/**
	 * Count of assertion rows in one org. Used by the bootstrap-silent check
	 * in KevCatalogSyncService — first sync for an org skips the KEV_ADDED
	 * event pass to avoid storming over years of old listings.
	 */
	long countByOrgId(UUID orgId);
}

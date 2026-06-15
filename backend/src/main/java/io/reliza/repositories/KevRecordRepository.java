/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.saas.KevRecord;

public interface KevRecordRepository extends CrudRepository<KevRecord, UUID> {

	Optional<KevRecord> findByCveId(String cveId);

	/**
	 * Bulk membership probe for the {@code knownExploited} GraphQL data
	 * loader — one round trip for all CVE ids on a findings page. Returns
	 * only the ids that are KEV-listed; resolution to per-row booleans
	 * happens in the service.
	 */
	@Query(
		value = "SELECT cve_id FROM rearm.kev_records WHERE cve_id IN (:cveIds)",
		nativeQuery = true)
	List<String> findExistingCveIds(@Param("cveIds") Collection<String> cveIds);

	/**
	 * Catalog removals (rare — CISA occasionally withdraws entries). The
	 * daily sync reconciles the full catalog, so rows whose CVE id is no
	 * longer published get dropped here.
	 */
	@Modifying
	@Transactional
	@Query(
		value = "DELETE FROM rearm.kev_records WHERE cve_id IN (:cveIds)",
		nativeQuery = true)
	int deleteByCveIdIn(@Param("cveIds") Collection<String> cveIds);
}

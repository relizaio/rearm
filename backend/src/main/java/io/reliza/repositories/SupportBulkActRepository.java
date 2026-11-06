/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SupportBulkAct;

/**
 * The bulk act receipts (FDAW-14 3.1 item 3). A plain {@link Repository}: one insert and reads, no update
 * and no delete, and the V109 trigger refuses both from any other SQL path.
 */
public interface SupportBulkActRepository extends Repository<SupportBulkAct, UUID> {

	/** The one write; every receipt has a fresh server-minted uuid. */
	SupportBulkAct save(SupportBulkAct act);

	Optional<SupportBulkAct> findById(UUID uuid);

	/** The newest receipts of an org, served by {@code support_bulk_acts_org (org, asserted_date)}. */
	@Query(value = "SELECT * FROM rearm.support_bulk_acts WHERE org = CAST(:orgUuidAsString AS uuid)"
			+ " ORDER BY asserted_date DESC LIMIT :lim", nativeQuery = true)
	List<SupportBulkAct> listNewestByOrg(@Param("orgUuidAsString") String orgUuidAsString, @Param("lim") int lim);
}

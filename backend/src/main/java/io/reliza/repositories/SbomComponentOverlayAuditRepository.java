/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SbomComponentOverlayAudit;

/**
 * The insert-only history of overlay writes (FDAW-15 3.1). A plain {@link Repository}: the insert and reads
 * only, no update and no delete; the V116 trigger refuses both from any other SQL path.
 */
public interface SbomComponentOverlayAuditRepository extends Repository<SbomComponentOverlayAudit, UUID> {

	SbomComponentOverlayAudit save(SbomComponentOverlayAudit audit);

	List<SbomComponentOverlayAudit> findByOrgAndCanonicalPurlOrderByRevisionAsc(UUID org, String canonicalPurl);

	/** The count term of A3's changesSinceBuild (FDAW-15 3.8): writes recorded after {@code builtAt}. */
	long countByOrgAndCanonicalPurlInAndAssertedDateAfter(UUID org, Collection<String> canonicalPurls,
			ZonedDateTime builtAt);

	/** The largest revision the audit holds for a key, 0 when none: where a created row starts (F-3). */
	@Query("SELECT COALESCE(MAX(a.revision), 0) FROM SbomComponentOverlayAudit a"
			+ " WHERE a.org = :org AND a.canonicalPurl = :key")
	int maxRevision(@Param("org") UUID org, @Param("key") String key);
}

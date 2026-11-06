/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import io.reliza.model.ReleaseSbomJustificationAudit;

/**
 * The insert-only history of hash justification writes (FDAW-14 3.1 item 2). A plain {@link Repository}:
 * the insert and reads only, no update and no delete; the V109 trigger refuses both from any other SQL
 * path.
 */
public interface ReleaseSbomJustificationAuditRepository extends Repository<ReleaseSbomJustificationAudit, UUID> {

	ReleaseSbomJustificationAudit save(ReleaseSbomJustificationAudit audit);

	List<ReleaseSbomJustificationAudit> findByReleaseSbomOrderByAssertedDateAsc(UUID releaseSbom);

	/** The count term of A3's changesSinceBuild (FDAW-14 3.6): writes recorded after {@code builtAt}. */
	long countByReleaseSbomAndAssertedDateAfter(UUID releaseSbom, ZonedDateTime builtAt);
}

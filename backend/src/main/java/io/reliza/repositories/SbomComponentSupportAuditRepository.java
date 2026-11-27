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

import io.reliza.model.SbomComponentSupportAudit;

/**
 * Append-only. A plain {@link Repository} offering the insert and the three reads: there is
 * deliberately no delete or update method here, and none should be added: a support attestation
 * is corrected by superseding it, never by erasing it. The V106 trigger refuses UPDATE and DELETE
 * from every other SQL path too (FDAW-1 F-8). See {@link io.reliza.model.SbomComponentSupportAudit}.
 */
public interface SbomComponentSupportAuditRepository extends Repository<SbomComponentSupportAudit, UUID> {

	/** The one write: every audit row has a fresh uuid, so the save inserts. */
	SbomComponentSupportAudit save(SbomComponentSupportAudit audit);

	/** Full attestation history for a component, newest first. */
	List<SbomComponentSupportAudit> findBySbomComponentUuidOrderByAssertedDateDesc(UUID sbomComponentUuid);

	/**
	 * Every row written by one bulk attestation, oldest first.
	 *
	 * <p>The question this column exists to answer. Without it an auditor can only INFER a
	 * sweep from identical justification text plus one user plus clustered timestamps -- an
	 * inference that fails exactly where it matters, because two sweeps run minutes apart
	 * with the same justification are indistinguishable from one.
	 *
	 * <p>Org-scoped, even though a batch id is a random UUID and guessing one is not a
	 * realistic attack: an id is client-echoable on batches 2..n, so a caller can put their
	 * own org's rows under an id they saw. Scoping the read means that can never widen into
	 * reading somebody else's.
	 */
	List<SbomComponentSupportAudit> findByBatchIdAndOrgOrderByAssertedDateAsc(UUID batchId, UUID org);

	/**
	 * The organization's attestation rows of the given components written after {@code after}: the
	 * support term of a draft build's change count (FDAW-30 3.6, F-10). Keyed by component, because the
	 * table has no support-row column; the caller passes no empty collection.
	 */
	@Query("select count(a) from SbomComponentSupportAudit a where a.org = :org and a.sbomComponentUuid in :components"
			+ " and a.assertedDate > :after")
	long countSince(@Param("org") UUID org, @Param("components") Collection<UUID> components,
			@Param("after") ZonedDateTime after);
}

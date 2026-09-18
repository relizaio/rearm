/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.SbomComponentSupportAudit;

/**
 * Append-only. There is deliberately no delete method here, and none should be
 * added: a support attestation is corrected by superseding it, never by erasing
 * it. See {@link io.reliza.model.SbomComponentSupportAudit}.
 */
public interface SbomComponentSupportAuditRepository extends CrudRepository<SbomComponentSupportAudit, UUID> {

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
}

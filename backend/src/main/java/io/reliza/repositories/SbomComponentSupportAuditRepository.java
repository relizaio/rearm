/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.SbomComponentSupportAudit;

public interface SbomComponentSupportAuditRepository extends CrudRepository<SbomComponentSupportAudit, UUID> {

	/** Full attestation history for a component, newest first. */
	List<SbomComponentSupportAudit> findBySbomComponentUuidOrderByAssertedDateDesc(UUID sbomComponentUuid);
}

/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.MetricsAudit;

public interface MetricsAuditRepository extends CrudRepository<MetricsAudit, UUID> {
	@Query(value = "SELECT COALESCE(MAX(metrics_revision), -1) FROM rearm.metrics_audit WHERE entity_type = :entityType AND entity_uuid = :entityUuid", nativeQuery = true)
	int findMaxRevision(@Param("entityType") String entityType, @Param("entityUuid") UUID entityUuid);
}

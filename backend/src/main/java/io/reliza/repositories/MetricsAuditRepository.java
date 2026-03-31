/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.MetricsAudit;

public interface MetricsAuditRepository extends CrudRepository<MetricsAudit, UUID> {
}

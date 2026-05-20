/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.AgentIdentity;

public interface AgentIdentityRepository extends CrudRepository<AgentIdentity, UUID> {
}

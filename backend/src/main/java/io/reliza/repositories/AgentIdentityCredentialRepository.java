/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.AgentIdentityCredential;

public interface AgentIdentityCredentialRepository extends CrudRepository<AgentIdentityCredential, UUID> {

	/**
	 * Auth-time reverse lookup: given an identity (type, value) pair —
	 * e.g. {@code (REARM_API_KEY, <key-uuid>)} — return the credential
	 * row pointing at its owning AgentIdentity. Backed by the
	 * {@code UNIQUE(identity_type, identity_value)} constraint.
	 */
	@Query(value = "SELECT * FROM rearm.agent_identity_credentials c "
			+ "WHERE c.identity_type = :type AND c.identity_value = :value LIMIT 1",
			nativeQuery = true)
	Optional<AgentIdentityCredential> findByTypeAndValue(
			@Param("type") String identityType,
			@Param("value") String identityValue);

	@Query(value = "SELECT * FROM rearm.agent_identity_credentials c "
			+ "WHERE c.agent_identity_uuid = :identityUuid "
			+ "ORDER BY c.created_date ASC",
			nativeQuery = true)
	List<AgentIdentityCredential> findByAgentIdentityUuid(
			@Param("identityUuid") UUID identityUuid);
}

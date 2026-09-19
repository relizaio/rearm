/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.FederatedTrustRule;

public interface FederatedTrustRuleRepository extends CrudRepository<FederatedTrustRule, UUID> {

	@Query(value = "SELECT * FROM rearm.federated_trust_rules WHERE org = :org ORDER BY created_date", nativeQuery = true)
	List<FederatedTrustRule> findByOrg(@Param("org") UUID org);

	@Query(value = "SELECT * FROM rearm.federated_trust_rules WHERE issuer = :issuer AND status = :status", nativeQuery = true)
	List<FederatedTrustRule> findActiveByIssuer(@Param("issuer") String issuer, @Param("status") String status);

	@Query(value = "SELECT * FROM rearm.federated_trust_rules WHERE org = :org AND grant_spec->>'keyUuid' = :keyUuid", nativeQuery = true)
	List<FederatedTrustRule> findByOrgAndBoundKey(@Param("org") UUID org, @Param("keyUuid") String keyUuid);

	/** Usage bookkeeping, at most once a minute per rule. */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.federated_trust_rules SET last_used_date = now() WHERE uuid = :uuid "
			+ "AND (last_used_date IS NULL OR last_used_date < now() - interval '1 minute')", nativeQuery = true)
	int touchLastUsed(@Param("uuid") UUID uuid);
}

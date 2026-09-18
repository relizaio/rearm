/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.repositories;

import java.time.ZonedDateTime;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.FederatedTrustRule;

/** Single-use bookkeeping of exchanged assertions; native statements only, no entity round trip. */
public interface FederatedAssertionIdRepository extends Repository<FederatedTrustRule, java.util.UUID> {

	/** 1 when the id was free and is now reserved, 0 when it was already used. Joins the caller's transaction. */
	@Modifying
	@Query(value = "INSERT INTO rearm.federated_assertion_ids (issuer, jti, expires_date) VALUES (:issuer, :jti, :expires) ON CONFLICT DO NOTHING", nativeQuery = true)
	int reserve(@Param("issuer") String issuer, @Param("jti") String jti, @Param("expires") ZonedDateTime expires);

	@Modifying
	@Query(value = "DELETE FROM rearm.federated_assertion_ids WHERE expires_date < now() - interval '1 hour'", nativeQuery = true)
	int purgeExpired();
}

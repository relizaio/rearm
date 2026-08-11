/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.FindingDim;

public interface FindingDimRepository extends CrudRepository<FindingDim, UUID> {

	/**
	 * Idempotent dimension insert: {@code ON CONFLICT (org, dim_hash) DO NOTHING} on the
	 * {@code finding_dim_org_hash_idx} natural key. The write path inserts a batch's distinct
	 * dimensions (sorted by {@code dim_hash} for deterministic lock order), then resolves ids via
	 * {@link #findByOrgAndHashes}. INSERT-ONLY: a dimension row is NEVER updated -- the first mint of a
	 * given {@code (org, dim_hash)} wins, and its non-key payload (purl/.../aliases, the representative
	 * variant) is fixed thereafter. Since the key is {@code (findingKind, findingKey)}, a change to a
	 * KEY field is by definition a different finding (different hash, new row); a change to a PAYLOAD
	 * field (e.g. alias drift) is a no-op. This convergence holds under READ COMMITTED without row-lock
	 * contention on the conflict path.
	 *
	 * @param aliases pre-serialized JSON string (nullable), cast to jsonb -- the representative
	 *        (non-key) alias variant for this finding
	 * @return rows actually inserted (0 on conflict)
	 */
	@Modifying
	@Query(value = "INSERT INTO rearm.finding_dim ("
			+ "uuid, org, dim_hash, key_version, finding_kind, finding_key, purl, vuln_id, cwe_id, "
			+ "rule_id, location, violation_type, aliases) VALUES ("
			+ ":uuid, :org, :dimHash, :keyVersion, :findingKind, :findingKey, :purl, :vulnId, :cweId, "
			+ ":ruleId, :location, :violationType, CAST(:aliases AS jsonb)) "
			+ "ON CONFLICT (org, dim_hash) DO NOTHING", nativeQuery = true)
	int insertIgnoreConflict(
			@Param("uuid") UUID uuid,
			@Param("org") UUID org,
			@Param("dimHash") byte[] dimHash,
			@Param("keyVersion") short keyVersion,
			@Param("findingKind") String findingKind,
			@Param("findingKey") String findingKey,
			@Param("purl") String purl,
			@Param("vulnId") String vulnId,
			@Param("cweId") String cweId,
			@Param("ruleId") String ruleId,
			@Param("location") String location,
			@Param("violationType") String violationType,
			@Param("aliases") String aliases);

	/**
	 * Resolve the dimension rows for a batch of hashes within one org (the second half of the
	 * write-path upsert-then-select). {@code (org, dim_hash)} is the unique natural key so each hash
	 * resolves to exactly one row.
	 */
	@Query("SELECT d FROM FindingDim d WHERE d.org = :org AND d.dimHash IN (:hashes)")
	List<FindingDim> findByOrgAndHashes(@Param("org") UUID org, @Param("hashes") Collection<byte[]> hashes);
}

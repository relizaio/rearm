/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.reliza.model.FindingChangeV3BranchSeed;
import io.reliza.model.FindingChangeV3BranchSeed.SeedId;

/**
 * Repository for the durable per-branch completion marker of the RESUMABLE v3 (events-lite) backfill DRAIN
 * (board task #38 follow-on; see {@code V65__finding_change_v3_branch_seed.sql}). The drain queries the
 * marks at the CURRENT {@link io.reliza.service.FindingDimKey#KEY_VERSION} to know which branches to skip and
 * marks each branch after it drains cleanly.
 */
public interface FindingChangeV3BranchSeedRepository extends JpaRepository<FindingChangeV3BranchSeed, SeedId> {

	/**
	 * Idempotent upsert of a branch's completion marker at {@code keyVersion}. Re-running a branch after a
	 * crash-between-drain-and-mark simply refreshes {@code completed_at}. Native so the {@code ON CONFLICT}
	 * upsert stays a single round-trip.
	 *
	 * @return rows affected (1: inserted or updated)
	 */
	@Modifying
	@Query(value = "INSERT INTO rearm.finding_change_v3_branch_seed "
			+ "(branch_uuid, org_uuid, key_version, completed_at) "
			+ "VALUES (:branchUuid, :orgUuid, :keyVersion, :completedAt) "
			+ "ON CONFLICT (branch_uuid, key_version) DO UPDATE SET "
			+ "completed_at = EXCLUDED.completed_at, org_uuid = EXCLUDED.org_uuid",
			nativeQuery = true)
	int markSeeded(
			@Param("branchUuid") UUID branchUuid,
			@Param("orgUuid") UUID orgUuid,
			@Param("keyVersion") int keyVersion,
			@Param("completedAt") String completedAt);

	/**
	 * The branch uuids already marked for {@code orgUuid} at {@code keyVersion} -- the drain's "skip set"
	 * (the caller wraps the returned list in a {@code Set}). Served by
	 * {@code finding_change_v3_branch_seed_org_ver_idx (org_uuid, key_version)}.
	 */
	@Query(value = "SELECT branch_uuid FROM rearm.finding_change_v3_branch_seed "
			+ "WHERE org_uuid = :orgUuid AND key_version = :keyVersion",
			nativeQuery = true)
	List<UUID> findSeededBranchUuids(
			@Param("orgUuid") UUID orgUuid,
			@Param("keyVersion") int keyVersion);

	/** Count of marked branches for {@code orgUuid} at {@code keyVersion} (progress meter). */
	@Query(value = "SELECT COUNT(*) FROM rearm.finding_change_v3_branch_seed "
			+ "WHERE org_uuid = :orgUuid AND key_version = :keyVersion",
			nativeQuery = true)
	long countSeeded(
			@Param("orgUuid") UUID orgUuid,
			@Param("keyVersion") int keyVersion);
}

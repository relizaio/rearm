/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Durable per-branch completion marker for the RESUMABLE v3 (events-lite) backfill DRAIN (board task #38
 * follow-on; see {@code V65__finding_change_v3_branch_seed.sql}). One row per branch that has been drained
 * CLEANLY (zero per-release failures) at a given {@code keyVersion}. The drain marks a branch here after
 * {@code FindingChangeEventBackfillService.backfillBranchV3} returns with no failures, so a subsequent tick
 * skips it instead of re-walking it -- the fix for the former one-shot boot backfill that re-walked every
 * uncertified org from zero on each boot.
 *
 * <p><b>Composite key.</b> {@code (branchUuid, keyVersion)} via {@link IdClass} (no existing entity in this
 * repo uses {@code @EmbeddedId}; {@code @IdClass} keeps the accessor surface flat, matching the plain
 * {@code @Column}/{@code @Id} style of {@link MetricsAudit}). {@code keyVersion} is part of the key so a
 * vocabulary bump ({@link io.reliza.service.FindingDimKey#KEY_VERSION}) coexists old and new markers and the
 * drain, querying at the current version, re-processes every branch resumably.
 *
 * <p><b>FK-free</b> (per coding_principles.md): {@code branchUuid}/{@code orgUuid} are bare uuids; a marker
 * whose branch was later deleted is an orphan that is simply never queried.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "finding_change_v3_branch_seed")
@IdClass(FindingChangeV3BranchSeed.SeedId.class)
public class FindingChangeV3BranchSeed implements Serializable {
	private static final long serialVersionUID = 650001;

	@Id
	@Column(name = "branch_uuid", nullable = false)
	private UUID branchUuid;

	@Id
	@Column(name = "key_version", nullable = false)
	private int keyVersion;

	@Column(name = "org_uuid", nullable = false)
	private UUID orgUuid;

	/** RFC-3339 UTC instant string (matches the org watermark's stored form). */
	@Column(name = "completed_at", nullable = false)
	private String completedAt;

	public UUID getBranchUuid() {
		return branchUuid;
	}

	public void setBranchUuid(UUID branchUuid) {
		this.branchUuid = branchUuid;
	}

	public int getKeyVersion() {
		return keyVersion;
	}

	public void setKeyVersion(int keyVersion) {
		this.keyVersion = keyVersion;
	}

	public UUID getOrgUuid() {
		return orgUuid;
	}

	public void setOrgUuid(UUID orgUuid) {
		this.orgUuid = orgUuid;
	}

	public String getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(String completedAt) {
		this.completedAt = completedAt;
	}

	/** Composite primary key {@code (branchUuid, keyVersion)} for {@link IdClass}. */
	public static class SeedId implements Serializable {
		private static final long serialVersionUID = 650002;

		private UUID branchUuid;
		private int keyVersion;

		public SeedId() {
		}

		public SeedId(UUID branchUuid, int keyVersion) {
			this.branchUuid = branchUuid;
			this.keyVersion = keyVersion;
		}

		public UUID getBranchUuid() {
			return branchUuid;
		}

		public void setBranchUuid(UUID branchUuid) {
			this.branchUuid = branchUuid;
		}

		public int getKeyVersion() {
			return keyVersion;
		}

		public void setKeyVersion(int keyVersion) {
			this.keyVersion = keyVersion;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SeedId)) {
				return false;
			}
			SeedId other = (SeedId) o;
			return keyVersion == other.keyVersion && Objects.equals(branchUuid, other.branchUuid);
		}

		@Override
		public int hashCode() {
			return Objects.hash(branchUuid, keyVersion);
		}
	}
}

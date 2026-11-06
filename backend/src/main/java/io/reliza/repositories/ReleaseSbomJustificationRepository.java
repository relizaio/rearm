/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.ReleaseSbomJustification;
import jakarta.persistence.LockModeType;

/**
 * The current hash justifications per release SBOM head (FDAW-14 3.3). No delete: a head's row is
 * emptied by clearing its keys, never removed, so its {@code revision} only moves forward.
 */
public interface ReleaseSbomJustificationRepository extends Repository<ReleaseSbomJustification, UUID> {

	ReleaseSbomJustification save(ReleaseSbomJustification row);

	Optional<ReleaseSbomJustification> findById(UUID releaseSbom);

	/**
	 * Creates the head's empty row when absent, so the lock below always has a row to hold: two
	 * concurrent first writes on one head then serialise on it instead of racing two inserts.
	 */
	@Modifying
	@Query(value = "INSERT INTO rearm.release_sbom_justifications (release_sbom, org, release)"
			+ " VALUES (:releaseSbom, :org, :release) ON CONFLICT (release_sbom) DO NOTHING", nativeQuery = true)
	int insertIfAbsent(@Param("releaseSbom") UUID releaseSbom, @Param("org") UUID org,
			@Param("release") UUID release);

	/** The head's row under {@code SELECT ... FOR UPDATE}: one writer at a time per head. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from ReleaseSbomJustification j where j.releaseSbom = :releaseSbom")
	Optional<ReleaseSbomJustification> lockById(@Param("releaseSbom") UUID releaseSbom);
}

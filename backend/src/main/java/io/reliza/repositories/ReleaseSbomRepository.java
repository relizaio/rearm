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

import io.reliza.model.ReleaseSbom;

/**
 * The release SBOM heads (FDAW-1 D1), one per (release, profile name). A new head is an INSERT by the
 * {@code Persistable} rule; a bump saves the head it loaded.
 */
public interface ReleaseSbomRepository extends CrudRepository<ReleaseSbom, UUID> {

	@Query("select h from ReleaseSbom h where h.release = :release and h.profileName = :profileName")
	Optional<ReleaseSbom> findByReleaseAndProfileName(@Param("release") UUID release,
			@Param("profileName") String profileName);

	@Query("select h from ReleaseSbom h where h.release = :release order by h.profileName")
	List<ReleaseSbom> listByRelease(@Param("release") UUID release);
}

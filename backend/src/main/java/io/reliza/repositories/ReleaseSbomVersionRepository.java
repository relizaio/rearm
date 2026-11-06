/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.ReleaseSbomVersion;
import io.reliza.model.ReleaseSbomVersionId;

/**
 * The immutable release SBOM versions (FDAW-1 D1). A plain {@link Repository}, so it offers an insert
 * and reads only: no removal and no bulk update exist on it, and the V108 trigger refuses both from any
 * other SQL path.
 */
public interface ReleaseSbomVersionRepository extends Repository<ReleaseSbomVersion, ReleaseSbomVersionId> {

	/** An INSERT by the {@code Persistable} rule; a second write of one key fails on the primary key. */
	ReleaseSbomVersion save(ReleaseSbomVersion v);

	Optional<ReleaseSbomVersion> findById(ReleaseSbomVersionId id);

	@Query("select v from ReleaseSbomVersion v where v.uuid = :uuid and v.version = :version")
	Optional<ReleaseSbomVersion> findVersion(@Param("uuid") UUID uuid, @Param("version") int version);

	/** The newest version of a serial, or empty. */
	@Query("select v from ReleaseSbomVersion v where v.uuid = :uuid and v.version = "
			+ "(select max(w.version) from ReleaseSbomVersion w where w.uuid = :uuid)")
	Optional<ReleaseSbomVersion> latest(@Param("uuid") UUID uuid);

	/** The highest stored version of a serial, 0 for none. */
	@Query("select coalesce(max(v.version), 0) from ReleaseSbomVersion v where v.uuid = :uuid")
	int maxVersion(@Param("uuid") UUID uuid);
}

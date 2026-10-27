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

import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionId;

/**
 * Immutable TEA collection versions (task TEA-5). The service only inserts and reads: there is no
 * update path, and {@link TeaPublishedCollection#isNew()} makes a save of an existing key fail.
 */
public interface TeaPublishedCollectionRepository extends CrudRepository<TeaPublishedCollection, TeaPublishedCollectionId> {

	@Query("select c from TeaPublishedCollection c where c.uuid = :uuid order by c.version")
	List<TeaPublishedCollection> listByPublication(@Param("uuid") UUID uuid);

	@Query("select c from TeaPublishedCollection c where c.uuid = :uuid and c.version = :version")
	Optional<TeaPublishedCollection> findVersion(@Param("uuid") UUID uuid, @Param("version") int version);

	/** The highest stored version of a publication, 0 when none: what the next version follows (3.5 step 10). */
	@Query("select coalesce(max(c.version), 0) from TeaPublishedCollection c where c.uuid = :uuid")
	int maxVersion(@Param("uuid") UUID uuid);

	/** Every collection version of the organization whose entries contain {@code needle} (task TEA-10), oldest first. */
	@Query(value = VariableQueries.LIST_TEA_COLLECTIONS_NAMING_ARTIFACT, nativeQuery = true)
	List<TeaPublishedCollection> listNamingArtifact(@Param("org") UUID org, @Param("needle") String needle);

	/**
	 * The oldest version of publication {@code uuid} whose entries contain {@code needle} (task TEA-28,
	 * D13): where a frozen entry of a TEA id and revision is carried from.
	 */
	@Query(value = VariableQueries.FIND_OLDEST_TEA_COLLECTION_NAMING, nativeQuery = true)
	Optional<TeaPublishedCollection> findOldestNaming(@Param("uuid") UUID uuid, @Param("needle") String needle);

	/**
	 * How many versions are stored under {@code uuid} for another organization or another release (task
	 * TEA-28, D16): a publication uuid the engine did not read from this release's head is used only when
	 * this is zero.
	 */
	@Query("select count(c) from TeaPublishedCollection c where c.uuid = :uuid and (c.org <> :org or c.release <> :release)")
	long countForeign(@Param("uuid") UUID uuid, @Param("org") UUID org, @Param("release") UUID release);
}

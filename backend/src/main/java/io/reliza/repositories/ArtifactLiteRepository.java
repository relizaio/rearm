/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ArtifactLite;

/**
 * Read-only repository over {@link ArtifactLite} — the light view of artifacts
 * that excludes the heavy metrics detail arrays. Use these methods on read
 * paths that only need artifact fields + totals. {@code findById} /
 * {@code findAllById} are inherited from {@link CrudRepository}.
 */
public interface ArtifactLiteRepository extends CrudRepository<ArtifactLite, UUID> {

	List<ArtifactLite> findByUuidIn(Collection<UUID> uuids);
}

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

import io.reliza.model.TeaPublication;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/** TEA publication heads (task TEA-5): one row per published release. */
public interface TeaPublicationRepository extends CrudRepository<TeaPublication, UUID> {

	/** The light projection the count seam reads: which component governs a head. */
	interface TeaPublicationHeadView {
		UUID getUuid();
		UUID getComponent();
		UUID getCascadeOf();
	}

	@Query("select p from TeaPublication p where p.release = :release")
	Optional<TeaPublication> findByRelease(@Param("release") UUID release);

	@Query("select p from TeaPublication p where p.org = :org and p.component = :component order by p.createdDate")
	List<TeaPublication> listByComponent(@Param("org") UUID org, @Param("component") UUID component);

	@Query("select p from TeaPublication p where p.org = :org and p.state = :state order by p.createdDate")
	List<TeaPublication> listByOrgAndState(@Param("org") UUID org, @Param("state") TeaPublicationState state);

	@Query("select p.uuid as uuid, p.component as component, p.cascadeOf as cascadeOf from TeaPublication p "
			+ "where p.org = :org and p.state = :state")
	List<TeaPublicationHeadView> listHeadsOfOrg(@Param("org") UUID org, @Param("state") TeaPublicationState state);
}

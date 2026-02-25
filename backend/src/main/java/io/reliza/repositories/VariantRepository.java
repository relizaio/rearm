/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Variant;

public interface VariantRepository extends CrudRepository<Variant, UUID> {

	@Query(
			value = VariableQueries.FIND_ALL_VARIANTS_OF_RELEASE,
			nativeQuery = true)
	List<Variant> findVariantsOfRelease(String releaseUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_BASE_VARIANT_OF_RELEASE,
			nativeQuery = true)
	Optional<Variant> findBaseVariantOfRelease(String releaseUuidAsString);

}

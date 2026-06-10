/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.SyntheticDtrackBucket;

public interface SyntheticDtrackBucketRepository extends CrudRepository<SyntheticDtrackBucket, UUID> {

	Optional<SyntheticDtrackBucket> findByOrgAndBucketIndex(UUID org, int bucketIndex);

	List<SyntheticDtrackBucket> findByOrg(UUID org);
}

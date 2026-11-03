/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.SyntheticDtrackBucket;

public interface SyntheticDtrackBucketRepository extends CrudRepository<SyntheticDtrackBucket, UUID> {

	Optional<SyntheticDtrackBucket> findByOrgAndBucketIndex(UUID org, int bucketIndex);

	List<SyntheticDtrackBucket> findByOrg(UUID org);

	/** Idle-skip probe: true when the org has a bucket needing submit-retry. */
	boolean existsByOrgAndIngestState(UUID org, SyntheticDtrackBucket.IngestState ingestState);

	/**
	 * The fan-out catch-up's write (TPF-137): sets one bucket's flow_control to the given jsonb
	 * text. Bookkeeping write in place: no revision, no audit row, last_updated_date untouched
	 * (the catch-up records a bucket at the date it already has). Flushes first and clears after,
	 * as the system_info stamps do.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(value = """
			UPDATE rearm.synthetic_dtrack_bucket SET flow_control = cast(:flowControl as jsonb)
			WHERE uuid = :uuid
			""", nativeQuery = true)
	int stampFlowControl(@Param("uuid") UUID uuid, @Param("flowControl") String flowControl);

	/**
	 * The completion clear of a change-driven fan-out drain (TPF-137): removes, from every bucket
	 * row of the org whatever its state, each flow_control.fanOutChanged entry dated at or before
	 * {@code cutoff}, keeping newer entries and recordedAt. Bookkeeping write in place: no
	 * revision, no audit row, last_updated_date untouched. A row with nothing to remove is not
	 * rewritten (the EXISTS guard), nor is a row without the map (the jsonb_typeof guard).
	 *
	 * @return rows rewritten
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.synthetic_dtrack_bucket b
			SET flow_control = jsonb_set(b.flow_control, '{fanOutChanged}',
			      coalesce((SELECT jsonb_object_agg(e.key, e.value)
			                FROM jsonb_each_text(b.flow_control->'fanOutChanged') e
			                WHERE e.value::timestamptz > :cutoff), '{}'::jsonb))
			WHERE b.org = :org AND jsonb_typeof(b.flow_control->'fanOutChanged') = 'object'
			  AND EXISTS (SELECT 1 FROM jsonb_each_text(b.flow_control->'fanOutChanged') e
			              WHERE e.value::timestamptz <= :cutoff)
			""", nativeQuery = true)
	int clearFanOutChangedThrough(@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff);
}

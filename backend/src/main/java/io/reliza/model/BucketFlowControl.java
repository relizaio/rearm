/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-bucket fan-out bookkeeping (TPF-137), persisted in
 * {@code synthetic_dtrack_bucket.flow_control}. The bucket-level counterpart of
 * {@link CanonicalMapFlowControl}: a jsonb object so later per-bucket state can be added as
 * fields without a migration.
 *
 * <p>{@code fanOutChanged} is the bucket's pending change map: component uuid (text) to the
 * bucket date (ISO-8601 UTC instant, microseconds) at which that component's findings or coverage
 * last changed at a save of this bucket and that no change-driven drain has covered yet. Absent
 * map or absent key = nothing pending. Cleared in place by
 * {@code SyntheticDtrackBucketRepository.clearFanOutChangedThrough} when a drain completes.
 *
 * <p>{@code recordedAt} is the bucket's {@code lastUpdatedDate} (same format) of the save that
 * last maintained the map. A bucket whose loaded date differs from it was moved by a writer that
 * recorded nothing and is caught up by the next fan-out pass (SyntheticSbomService.isUnrecorded).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BucketFlowControl(
		Map<String, String> fanOutChanged,
		String recordedAt) implements Serializable {
}

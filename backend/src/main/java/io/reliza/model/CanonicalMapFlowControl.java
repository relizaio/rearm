/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-canonical-artifact flow control, persisted in
 * {@code artifact_canonical_map.flow_control}. The artifact-level counterpart
 * to {@link FlowControl} on releases, and deliberately the same shape: a jsonb
 * object so later per-artifact queue state can be added as fields without a
 * migration.
 *
 * <p>{@code canonicalFormVersion} records the canonical-purl form this
 * artifact's component mappings have been verified against. Absent (null) means
 * never verified and is treated as 0, so every pre-existing row is picked up by
 * the sweep exactly once. See
 * {@code SbomComponentService.sweepStaleCanonicalQualifiers}.
 *
 * <p>Unlike the release-side fence keys there is no {@code skipUntil} /
 * {@code failureCount} pair here: the sweep calls nothing external (it derives
 * the corrected canonical locally via {@link io.reliza.common.Utils#canonicalizePurl}),
 * so there is no transient upstream failure to space retries against. Add them
 * if that ever stops being true.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalMapFlowControl(
		Integer canonicalFormVersion) implements Serializable {
}

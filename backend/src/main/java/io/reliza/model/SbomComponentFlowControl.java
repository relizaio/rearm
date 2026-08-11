/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-component flow control ({@code sbom_components.flow_control}); the
 * component-level sibling of {@link FlowControl} (releases) and
 * {@link CanonicalMapFlowControl} (canonical map). Jsonb so later per-component
 * queue state can be added without DDL.
 *
 * <p>{@code enrichmentTerminalAt}: when set, no mechanism can ever enrich this
 * component (own BOM pulled and unmatched -- era-drifted coordinates, re-parse
 * no longer contains it, internal self-reference). Terminal rows keep
 * {@code enriched_at} NULL and leave the matchable universe entirely: candidate
 * window, bucket membership, fan-out coverage gate (mirroring isRoot), stall
 * counters. See V75.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SbomComponentFlowControl(
		String enrichmentTerminalAt,
		String enrichmentTerminalReason) implements Serializable {
}

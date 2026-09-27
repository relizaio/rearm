/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.UUID;

/**
 * GraphQL local context of a {@code DependencyTrackMetrics} subtree: the org
 * that owns the metrics, which {@code ReleaseMetricsDto} does not carry.
 * Attached by the {@code Release.metrics} and {@code Artifact.metrics}
 * resolvers so that {@link VulnerabilityScoreDataFetcher} can read the org's
 * vulnerability records. Other carriers of the type attach none, and the
 * score resolvers then return empty.
 */
public record MetricsContext(UUID org) {}

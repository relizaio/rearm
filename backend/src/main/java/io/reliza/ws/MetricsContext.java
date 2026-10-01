/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.UUID;

import io.reliza.dto.FindingSbomMatch;

/**
 * GraphQL local context of a {@code DependencyTrackMetrics} subtree: the org
 * that owns the metrics, which {@code ReleaseMetricsDto} does not carry.
 * Attached by the {@code Release.metrics} and {@code Artifact.metrics}
 * resolvers so that {@link VulnerabilityScoreDataFetcher} can read the org's
 * vulnerability records. Other carriers of the type attach none, and the
 * score resolvers then return empty.
 *
 * @param org            the org that owns the metrics
 * @param release        the release whose findings these are, for matching them to
 *                       its SBOM components ({@code Vulnerability.sbomMatch}); null
 *                       under an artifact, whose findings belong to no one release
 * @param componentMatch set under {@code ReleaseSbomComponent.findings}: the
 *                       component every finding below was matched to, so their
 *                       {@code sbomMatch} needs no second read of the release's SBOM
 */
public record MetricsContext(UUID org, UUID release, FindingSbomMatch componentMatch) {

	public static MetricsContext ofRelease(UUID org, UUID release) {
		return new MetricsContext(org, release, null);
	}

	public static MetricsContext ofArtifact(UUID org) {
		return new MetricsContext(org, null, null);
	}

	/** Under {@code ReleaseSbomComponent.findings}: the findings of {@code release} that {@code match} names. */
	public static MetricsContext ofComponent(UUID org, UUID release, FindingSbomMatch match) {
		return new MetricsContext(org, release, match);
	}
}

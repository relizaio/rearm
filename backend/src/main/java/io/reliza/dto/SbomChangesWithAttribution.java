package io.reliza.dto;

import java.util.List;

/**
 * SBOM changes with release-level attribution.
 * Shows which artifacts were added/removed and which releases contributed.
 */
public record SbomChangesWithAttribution(
    List<ArtifactWithAttribution> artifacts,
    int totalAdded,
    int totalRemoved
) {}

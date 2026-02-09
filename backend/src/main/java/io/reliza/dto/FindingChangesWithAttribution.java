package io.reliza.dto;

import java.util.List;

/**
 * Finding changes with release-level attribution.
 * Replaces the old FindingChangesRecord with attributed versions.
 */
public record FindingChangesWithAttribution(
    List<VulnerabilityWithAttribution> vulnerabilities,
    List<ViolationWithAttribution> violations,
    List<WeaknessWithAttribution> weaknesses,
    Integer totalAppeared,
    Integer totalResolved
) {}

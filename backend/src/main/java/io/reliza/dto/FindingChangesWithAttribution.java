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
    int totalAppeared,
    int totalResolved
) {
    public static final FindingChangesWithAttribution EMPTY = new FindingChangesWithAttribution(
        List.of(), List.of(), List.of(), 0, 0);
}

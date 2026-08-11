package io.reliza.dto;

import java.util.List;

/**
 * Enhanced artifact (SBOM component/dependency) with component attribution.
 * Shows which components/releases added or removed this artifact.
 */
public record ArtifactWithAttribution(
    // Core artifact data
    String purl,
    String name,
    String version,
    
    // Attribution PREVIEW lists - capped at FindingComparisonService.ATTRIBUTION_PREVIEW_CAP.
    // The *Count fields carry the true totals (UI: preview inline + "+N more" -> drill-down).
    List<ComponentAttribution> addedIn,    // Releases that added this artifact (preview)
    List<ComponentAttribution> removedIn,  // Releases that removed this artifact (preview)

    // True totals (may exceed the preview list sizes above)
    int addedInCount,
    int removedInCount,

    // Org-wide state flags
    boolean isNetAdded,      // True if newly added to org (not removed anywhere)
    boolean isNetRemoved     // True if removed from all components
) {}

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
    
    // Attribution lists - which releases contributed to this artifact's lifecycle
    List<ComponentAttribution> addedIn,    // Releases that added this artifact
    List<ComponentAttribution> removedIn,  // Releases that removed this artifact
    
    // Org-wide state flags
    boolean isNetAdded,      // True if newly added to org (not removed anywhere)
    boolean isNetRemoved     // True if removed from all components
) {}

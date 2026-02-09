package io.reliza.dto;

import java.util.List;

/**
 * Organization-level context for findings in multi-component changelogs.
 * Provides semantic information about how a finding relates to the organization as a whole.
 */
public record OrgLevelContext(
    // Semantic flags for org-level understanding
    boolean isNewToOrganization,        // First occurrence across all components in the org
    boolean wasPreviouslyReported,      // Existed in other components when it appeared in new ones
    boolean isPartiallyResolved,        // Resolved in some components, still present in others
    boolean isFullyResolved,            // Resolved everywhere, not in any latest release
    boolean isInheritedInAllComponents, // Present in first AND last release of ALL components (technical debt)
    
    // Cross-component metadata
    int componentCount,                 // Number of components currently affected
    List<String> affectedComponentNames // Names of components currently affected
) {}

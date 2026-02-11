package io.reliza.dto;

import java.util.List;

/**
 * Enhanced violation detail with component attribution.
 * Shows which components/releases resolved, introduced, or still have this violation.
 */
public record ViolationWithAttribution(
    // Core violation data (field name must match GraphQL schema: "type")
    String type,
    String purl,
    
    // Attribution lists - which releases contributed to this violation's lifecycle
    List<ComponentAttribution> resolvedIn,      // Releases that resolved this violation
    List<ComponentAttribution> appearedIn,      // Releases where this violation appeared
    List<ComponentAttribution> presentIn,       // Latest releases that currently have this violation
    
    // Org-wide state flags
    boolean isNetResolved,   // True if resolved in all components (no longer in org)
    boolean isNetAppeared,   // True if newly appeared in org (wasn't anywhere before)
    boolean isStillPresent,  // True if exists in at least one component's latest release
    
    // Optional org-level context (only populated for multi-component org-level queries)
    OrgLevelContext orgContext
) {}

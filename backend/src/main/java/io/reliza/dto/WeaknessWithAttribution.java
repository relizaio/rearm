package io.reliza.dto;

import java.util.List;

/**
 * Enhanced weakness detail with component attribution.
 * Shows which components/releases resolved, introduced, or still have this weakness.
 */
public record WeaknessWithAttribution(
    // Core weakness data
    String cweId,
    String severity,
    String ruleId,
    String location,
    
    // Attribution lists - which releases contributed to this weakness's lifecycle
    List<ComponentAttribution> resolvedIn,      // Releases that resolved this weakness
    List<ComponentAttribution> appearedIn,      // Releases where this weakness appeared
    List<ComponentAttribution> presentIn,       // Latest releases that currently have this weakness
    
    // Org-wide state flags
    boolean isNetResolved,   // True if resolved in all components (no longer in org)
    boolean isNetAppeared,   // True if newly appeared in org (wasn't anywhere before)
    boolean isStillPresent,  // True if exists in at least one component's latest release
    
    // Optional org-level context (only populated for multi-component org-level queries)
    OrgLevelContext orgContext
) {}

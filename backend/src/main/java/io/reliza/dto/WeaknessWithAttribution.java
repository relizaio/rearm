package io.reliza.dto;

import java.util.List;

import io.reliza.model.AnalysisState;

/**
 * Enhanced weakness detail with component attribution.
 * Shows which components/releases resolved, introduced, or still have this weakness.
 */
public record WeaknessWithAttribution(
    // Opaque internal finding key ((cweId|ruleId)|location) -- handle for findingAttributionByDate drill-down.
    String findingKey,
    // Core weakness data
    String cweId,
    String severity,
    String ruleId,
    String location,
    
    // Attribution PREVIEW lists - capped at FindingComparisonService.ATTRIBUTION_PREVIEW_CAP.
    // The *Count fields carry the true totals (UI: preview inline + "+N more" -> drill-down).
    List<ComponentAttribution> resolvedIn,      // Releases that resolved this weakness (preview)
    List<ComponentAttribution> appearedIn,      // Releases where this weakness appeared (preview)
    List<ComponentAttribution> presentIn,       // Latest releases that currently have this weakness (preview)

    // True totals (may exceed the preview list sizes above)
    int resolvedInCount,
    int appearedInCount,
    int presentInCount,

    // Org-wide state flags
    boolean isNetResolved,   // True if resolved in all components (no longer in org)
    boolean isNetAppeared,   // True if newly appeared in org (wasn't anywhere before)
    boolean isStillPresent,  // True if exists in at least one component's latest release
    
    // Optional org-level context (only populated for multi-component org-level queries)
    OrgLevelContext orgContext,
    
    // Analysis state (FALSE_POSITIVE, NOT_AFFECTED, etc.)
    AnalysisState analysisState
) {}

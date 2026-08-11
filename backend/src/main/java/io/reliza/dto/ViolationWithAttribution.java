package io.reliza.dto;

import java.util.List;

import io.reliza.model.AnalysisState;

/**
 * Enhanced violation detail with component attribution.
 * Shows which components/releases resolved, introduced, or still have this violation.
 */
public record ViolationWithAttribution(
    // Opaque internal finding key (type|purl) -- handle for findingAttributionByDate drill-down.
    String findingKey,
    // Core violation data (field name must match GraphQL schema: "type")
    String type,
    String purl,
    
    // Attribution PREVIEW lists - capped at FindingComparisonService.ATTRIBUTION_PREVIEW_CAP.
    // The *Count fields carry the true totals (UI: preview inline + "+N more" -> drill-down).
    List<ComponentAttribution> resolvedIn,      // Releases that resolved this violation (preview)
    List<ComponentAttribution> appearedIn,      // Releases where this violation appeared (preview)
    List<ComponentAttribution> presentIn,       // Latest releases that currently have this violation (preview)

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
    AnalysisState analysisState,

    // ADDITIVE (changelog re-scan visibility): when this finding ARRIVED inside the window per
    // finding_change_events (APPEARED), the earliest in-window event date. Lets the UI say WHEN a
    // finding was first detected instead of implying the endpoint-anchor release introduced it.
    java.time.ZonedDateTime scanArrivalDate,
    // Earliest / latest in-window releases (by creation) whose CURRENT metrics carry this finding --
    // the honest carrier RANGE for an arrival, replacing the misleading single endpoint anchor.
    ComponentAttribution earliestCarrier,
    ComponentAttribution latestCarrier
) {
	/** Copy with the scan-arrival decoration set; every other field is preserved verbatim. */
	public ViolationWithAttribution withScanArrival(java.time.ZonedDateTime arrivalDate,
			ComponentAttribution earliest, ComponentAttribution latest) {
		return new ViolationWithAttribution(findingKey(), type(), purl(), resolvedIn(), appearedIn(), presentIn(), resolvedInCount(), appearedInCount(), presentInCount(), isNetResolved(), isNetAppeared(), isStillPresent(), orgContext(), analysisState(), arrivalDate, earliest, latest);
	}
}

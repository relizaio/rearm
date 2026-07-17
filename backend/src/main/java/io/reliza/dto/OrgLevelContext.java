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
    List<String> affectedComponentNames, // Names of components currently affected

    // "Worsened" annotations (board task #38, phase 3 posture-diff rollup). ADDITIVE — populated only
    // on the posture-diff path from in-window finding_change_events; null/false on the legacy
    // pairwise-diff path. A finding that is net-New but also KEV-added is badged (isNewlyKev=true) and
    // NOT double-counted in Net.
    Boolean isNewlyKev,                 // KEV_ADDED event in-window AND finding present at window end
    Boolean isSeverityIncreased,        // SEVERITY_INCREASED event in-window, net-higher, present at end
    String previousSeverity             // pre-escalation severity for a severity-increased finding
) {
    /**
     * Back-compat convenience constructor for the legacy pairwise-diff path
     * ({@code compareMetricsAcrossComponents}), which does not compute the "Worsened" annotations.
     * Leaves {@code isNewlyKev} / {@code isSeverityIncreased} / {@code previousSeverity} unset
     * ({@code null}) so the UI can distinguish "not computed" (legacy path) from "computed false"
     * (posture-diff path).
     */
    public OrgLevelContext(
            boolean isNewToOrganization,
            boolean wasPreviouslyReported,
            boolean isPartiallyResolved,
            boolean isFullyResolved,
            boolean isInheritedInAllComponents,
            int componentCount,
            List<String> affectedComponentNames) {
        this(isNewToOrganization, wasPreviouslyReported, isPartiallyResolved, isFullyResolved,
                isInheritedInAllComponents, componentCount, affectedComponentNames,
                null, null, null);
    }
}

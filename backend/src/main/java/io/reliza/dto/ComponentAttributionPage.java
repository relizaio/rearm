package io.reliza.dto;

import java.util.List;

/**
 * One page of a single finding's attribution for a given bucket (appeared/present/resolved), served by the
 * {@code findingAttributionByDate} drill-down behind the preview-capped inline lists. {@code total} is the
 * FULL bucket size (equal to the {@code *InCount} the changelog shows inline for that finding), so the UI can
 * render pagination and the "+N more" affordance. See ai-agents/changelog-read-contract-redesign.md.
 */
public record ComponentAttributionPage(
    List<ComponentAttribution> items,
    int total,
    int page,
    int pageSize
) {
    public static final ComponentAttributionPage EMPTY = new ComponentAttributionPage(List.of(), 0, 0, 0);
}

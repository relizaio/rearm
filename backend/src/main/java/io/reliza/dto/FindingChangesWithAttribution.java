package io.reliza.dto;

import java.time.ZonedDateTime;
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
    int totalResolved,
    // "Worsened" rollup counts (board task #38, phase 3 posture-diff). ADDITIVE -- 0 on the legacy
    // pairwise-diff path; populated only by the posture-diff rollup from in-window
    // finding_change_events. Never summed into totalAppeared / totalResolved (no double-count).
    int totalNewlyKev,
    int totalSeverityIncreased,
    // DISCLOSURE (board task #38): non-null when the window {@code from} predates the org's
    // finding-change-event retention horizon, so the from-baseline could NOT be fully reconstructed and
    // the buckets degrade to comparing against current metrics. It is that horizon instant -- the earliest
    // point from which the posture is reliably reconstructable. Null when the whole window sits within
    // retention (nothing degraded) and on the legacy path (which never reconstructs).
    ZonedDateTime reconstructionClampedSince,
    // SOURCE DISCLOSURE (board task #38): true when these numbers came from the posture-endpoint diff
    // (window-endpoint reconstruction), false on the legacy pairwise-across-releases diff. At org scope
    // both semantics share this one payload (an unseeded org transparently serves legacy), so without
    // this flag a client cannot tell WHICH semantics it is rendering -- and the numbers legitimately jump
    // when an org flips paths after its backfill certifies.
    boolean postureDiffApplied
) {
    public static final FindingChangesWithAttribution EMPTY = new FindingChangesWithAttribution(
        List.of(), List.of(), List.of(), 0, 0, 0, 0, null, false);

    /**
     * Back-compat convenience constructor for the legacy pairwise-diff path, which does not compute
     * the "Worsened" annotation counts and never reconstructs (so no clamp / source disclosure).
     */
    public FindingChangesWithAttribution(
            List<VulnerabilityWithAttribution> vulnerabilities,
            List<ViolationWithAttribution> violations,
            List<WeaknessWithAttribution> weaknesses,
            int totalAppeared,
            int totalResolved) {
        this(vulnerabilities, violations, weaknesses, totalAppeared, totalResolved, 0, 0, null, false);
    }
}

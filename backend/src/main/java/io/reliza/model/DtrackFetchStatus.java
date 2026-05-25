/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Per-artifact lifecycle of the ReARM-side fetch from Dependency-Track's
 * vulnerability / violation endpoints (distinct from the SUBMISSION side, which
 * is tracked separately by {@code dtrackSubmissionFailed} / {@code dtrackSubmissionAttempts}
 * on the same {@link ArtifactData.DependencyTrackIntegration}).
 *
 * <p>The fetch is paginated and can fail mid-drain (HTTP/parse). When it does,
 * the artifact stays on its previous good vulnerability list and the scheduler
 * stops re-attempting until the backoff window elapses — see
 * {@code dtrackFetchSkipUntil} on {@link ArtifactData.DependencyTrackIntegration}
 * and {@code BackoffPolicy.dtrackFetchSkipSeconds}.
 */
public enum DtrackFetchStatus {
    /** Default for never-attempted artifacts. Treated as eligible by the scheduler. */
    PENDING,
    /** Most recent fetch attempt completed. Cleared failure state. */
    OK,
    /** Most recent fetch attempt threw. {@code dtrackFetchSkipUntil} pushes the next attempt out. */
    FAILED
}

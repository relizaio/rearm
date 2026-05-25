/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.util;

/**
 * Centralized backoff curves for ReARM's async retry flows.
 *
 * <p>Currently consumed by the per-artifact Dependency-Track fetch-failure
 * tracking on {@code ArtifactData.DependencyTrackIntegration}. The dormant
 * SBOM-reconcile failure path (defined as {@code ReleaseRepository.recordSbomReconcileFailure}
 * but not yet wired in production) is the next intended consumer — when it
 * lands, add its curve here rather than inlining the table at the call site.
 */
public final class BackoffPolicy {

    private BackoffPolicy() {}

    /**
     * Exponential backoff for per-artifact Dependency-Track fetch failures:
     * 1, 2, 4, 8, 16, 32, then 60 minutes (cap).
     *
     * @param failureCount post-increment failure count (1 = first failure).
     * @return seconds to push {@code dtrackFetchSkipUntil} forward by.
     */
    public static int dtrackFetchSkipSeconds(int failureCount) {
        if (failureCount <= 0) return 60;            // defensive — caller should pass >=1
        if (failureCount >= 7) return 60 * 60;       // cap at 1 hour
        return (1 << (failureCount - 1)) * 60;       // 1,2,4,8,16,32 minutes
    }
}

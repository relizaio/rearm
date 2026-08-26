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

    /**
     * Exponential backoff for auto-integrate failures: 2, 4, 8, 16, 32, then 64 minutes (cap).
     *
     * <p>Slower to start than {@link #dtrackFetchSkipSeconds} and slower to cap, because the two
     * are retrying different things. That curve chases an external fetch that usually succeeds on
     * the next try; this one retries a product-release create whose common permanent cause is a
     * misconfiguration only a human will clear. Each attempt logs at ERROR and ERROR drives
     * operator alerting, so a fixed cadence would turn one stuck feature set into hundreds of
     * alerts a day. Retries never stop -- giving up would silently abandon the release, which is
     * the failure this backoff exists to survive rather than hide.
     *
     * @param failureCount post-increment failure count (1 = first failure).
     * @return seconds to push {@code autoIntegrateSkipUntil} forward by.
     */
    public static int autoIntegrateSkipSeconds(int failureCount) {
        if (failureCount <= 0) return 120;           // defensive - caller should pass >=1
        if (failureCount >= 6) return 64 * 60;       // cap at 64 minutes
        return (1 << (failureCount - 1)) * 120;      // 2,4,8,16,32 minutes
    }

    /**
     * Backoff for a notification-outbox event deferred because the releases it
     * affects are not resolvable yet: 30s, 60s, then 2 minutes per attempt (cap).
     *
     * <p>Deliberately NOT a reuse of {@link #dtrackFetchSkipSeconds}. That curve
     * reaches an hour, which is the right shape for retrying a failed external
     * fetch and the wrong shape here -- the first re-look wants to land within a
     * tick or two of the ~1/min synthetic-SBOM pass that writes the artifact
     * metrics being waited on, and a notification deferred by an hour is a
     * notification nobody wants.
     *
     * <p>Caps instead of running off the end of a fixed table, so raising the
     * caller's attempt ceiling lengthens the window in fixed 2-minute steps
     * rather than silently flat-lining.
     *
     * @param attemptCount post-increment attempt count (1 = first deferral),
     *                     matching {@link #dtrackFetchSkipSeconds}'s contract.
     * @return seconds to push {@code next_attempt_at} forward by.
     */
    public static int enrichmentDeferSeconds(int attemptCount) {
        if (attemptCount <= 1) return 30;
        if (attemptCount == 2) return 60;
        return 120;                                  // cap
    }
}

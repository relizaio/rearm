import { ref, type Ref } from 'vue'
import { loadReleaseSupportCoverage } from './releaseSupportCoverage'
import type { ReleaseSupportCoverage } from './releaseSupportCoverage'
import type { DriftFallbackClient } from './graphqlDriftFallback'

/**
 * Release-scoped coverage state, extracted from ReleaseView.vue so it can be tested.
 *
 * The first version of this lived inline and was "guarded" by a spec that scanned the SFC
 * source for a call site. That guard was theatre: review demonstrated three mutations --
 * commenting the call out, conditionalising it on forceRefresh, and deleting the
 * release-change reset -- that all left the suite green, and the first of those is a
 * verbatim restoration of a bug that had already shipped once. A source scan cannot
 * distinguish code from a comment, and an earlier probe of mine passed only because it
 * DELETED the line rather than commenting it, which removes the text the scan looks for.
 *
 * Behaviour has to be asserted by running it. This is the same conclusion the component
 * list reached one commit earlier on this feature, for the same reason.
 *
 * Carries its own request fence for the same hazard the list has: a response for release A
 * landing after the operator has navigated to B would caption B with A's disclosure count,
 * which is exactly what the synchronous reset cannot prevent.
 */
export interface ReleaseSupportCoverageState {
    coverage: Ref<ReleaseSupportCoverage | null>
    loading: Ref<boolean>
    /**
     * Set when a load FAILED, as opposed to a server that cannot answer. The two must not
     * share a rendering: "this server does not support release coverage" is a durable
     * statement about capability, and saying it because a request 502'd is a false claim
     * that also hides the fact a retry would work.
     */
    error: Ref<string | null>
    load: (orgUuid?: string, releaseUuid?: string) => Promise<void>
    reset: () => void
}

export function useReleaseSupportCoverage (
    client: DriftFallbackClient
): ReleaseSupportCoverageState {
    const coverage: Ref<ReleaseSupportCoverage | null> = ref(null)
    const loading = ref(false)
    const error: Ref<string | null> = ref(null)

    let seq = 0
    // Counter, not a boolean: with two overlapping loads a boolean lets the first `finally`
    // report "done" while the second is still running.
    let inFlight = 0

    async function load (orgUuid?: string, releaseUuid?: string): Promise<void> {
        if (!orgUuid || !releaseUuid) return
        const mySeq = ++seq
        const myRelease = releaseUuid
        inFlight += 1
        loading.value = true
        try {
            const result = await loadReleaseSupportCoverage(client, orgUuid, releaseUuid)
            if (mySeq !== seq) return
            coverage.value = result
            error.value = null
        } catch (err: any) {
            if (mySeq !== seq) return
            // A failed request is NOT an unanswerable server. Keep them apart.
            coverage.value = null
            error.value = err?.message || String(err)
        } finally {
            inFlight -= 1
            if (inFlight === 0) loading.value = false
            void myRelease
        }
    }

    /** Release changed: everything about the old one is wrong, including anything in flight. */
    function reset (): void {
        seq += 1
        coverage.value = null
        error.value = null
        loading.value = false
        inFlight = 0
    }

    return { coverage, loading, error, load, reset }
}

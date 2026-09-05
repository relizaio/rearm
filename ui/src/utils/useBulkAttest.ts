import { ref, type Ref } from 'vue'
import gql from 'graphql-tag'
import { loadSbomComponentsPage } from './sbomComponentsQuery'
import type { SupportAttestationFilter } from './sbomComponentsQuery'
import type { MutationClient } from './setSbomComponentSupport'
import type { DriftFallbackClient } from './graphqlDriftFallback'

/** Page size for the collect walk. */
export const BULK_WALK_LIMIT = 500

/**
 * Components per write.
 *
 * There is no server-side cap on the id list, and each item runs in its own REQUIRES_NEW
 * transaction so a batch holds no long lock. The real bound is wall-clock per request against
 * the ingress timeout, and per-item outcomes make a mid-batch failure recoverable. 200 keeps
 * a batch to a few seconds and means one 500-id collect page never fans out to more than
 * three writes.
 */
export const BULK_BATCH_SIZE = 200

/**
 * Ceiling on a single sweep.
 *
 * A browser is the wrong place to hold an unbounded id list, and an operator who has
 * accidentally cleared the filter should be stopped rather than served. Refusing beats
 * truncating: a silently truncated sweep would report success over a fraction of the release.
 */
export const BULK_MAX_IDS = 5000

export const BULK_SET_SUPPORT = gql`
    mutation bulkSetSbomComponentSupport(
        $sbomComponentUuids: [ID!]!
        $levelOfSupport: LevelOfSupport
        $justification: String
        $supportParty: SupportParty
        $endOfGuaranteedSupportDate: String
        $endOfSupportDate: String
        $endOfLifeDate: String
        $reason: String
    ) {
        bulkSetSbomComponentSupport(
            sbomComponentUuids: $sbomComponentUuids
            levelOfSupport: $levelOfSupport
            justification: $justification
            supportParty: $supportParty
            endOfGuaranteedSupportDate: $endOfGuaranteedSupportDate
            endOfSupportDate: $endOfSupportDate
            endOfLifeDate: $endOfLifeDate
            reason: $reason
        ) {
            appliedCount
            skippedCount
            failedCount
            results { sbomComponentUuid outcome message }
        }
    }`

/**
 * NO supportNotes argument, deliberately.
 *
 * Notes are stored against a milestone and are the evidence for THAT date on THAT component.
 * A fan-out cannot carry per-component evidence: one notes string applied to hundreds of
 * components would assert the same act of checking for every one of them. Bulk is for the
 * unattested backlog -- level, basis, party -- and the per-component form is where evidence
 * belongs.
 */
export interface BulkAttestInput {
    levelOfSupport?: string | null
    justification?: string | null
    party?: string | null
    endOfGuaranteedSupportDate?: string | null
    endOfSupportDate?: string | null
    endOfLifeDate?: string | null
    reason?: string | null
}

export interface BulkOutcome {
    applied: number
    skippedAttested: number
    skippedRoot: number
    failed: number
    results: Array<{ sbomComponentUuid: string, outcome: string, message: string | null }>
    /** A batch threw; earlier batches still landed. */
    aborted: boolean
    error: string | null
    /**
     * True when anything came back SKIPPED_ATTESTED. On a fresh walk that should be zero --
     * the walk collected only UNATTESTED components -- so a non-zero count means somebody
     * attested one between the walk and the write. Surfaced rather than hidden: it tells the
     * operator their sweep raced a colleague.
     */
    concurrentWriteDetected: boolean
}

export function useBulkAttest () {
    const collecting = ref(false)
    const submitting = ref(false)
    const collected: Ref<string[]> = ref([])
    const progress = ref(0)
    const error: Ref<string | null> = ref(null)

    /**
     * Walk the filter and collect ids. WRITES NOTHING.
     *
     * Collect-then-write, never interleaved. A keyset cursor is safe to walk while writing,
     * but every APPLIED removes a row from the UNATTESTED set the cursor is walking, and a
     * walk that does not write cannot be wrong about that at all. Obvious beats clever on a
     * surface that records a regulatory claim.
     */
    async function collect (
        client: DriftFallbackClient,
        releaseUuid: string,
        attestation: SupportAttestationFilter,
        search: string
    ): Promise<{ ids: string[], tooLarge: boolean }> {
        collecting.value = true
        error.value = null
        const ids: string[] = []
        let after: string | null = null
        let tooLarge = false
        try {
            for (;;) {
                const page = await loadSbomComponentsPage(client, releaseUuid, {
                    attestation, search, limit: BULK_WALK_LIMIT, after
                })
                for (const row of page.items) {
                    ids.push(row.sbomComponentUuid || row.component?.uuid)
                }
                if (ids.length > BULK_MAX_IDS) {
                    tooLarge = true
                    error.value = `This selection has more than ${BULK_MAX_IDS} components --`
                        + ' too large for bulk attest from the browser. Narrow it with the'
                        + ' search box and sweep in parts.'
                    break
                }
                if (!page.hasMore || !page.endCursor) break
                after = page.endCursor
            }
        } catch (err: any) {
            error.value = err?.message || String(err)
        } finally {
            collecting.value = false
        }
        collected.value = tooLarge ? [] : ids
        return { ids, tooLarge }
    }

    /** Write in batches, aggregating per-item outcomes. */
    async function submit (
        client: MutationClient,
        ids: string[],
        input: BulkAttestInput
    ): Promise<BulkOutcome> {
        submitting.value = true
        progress.value = 0
        const out: BulkOutcome = {
            applied: 0, skippedAttested: 0, skippedRoot: 0, failed: 0,
            results: [], aborted: false, error: null, concurrentWriteDetected: false
        }
        try {
            for (let i = 0; i < ids.length; i += BULK_BATCH_SIZE) {
                const slice = ids.slice(i, i + BULK_BATCH_SIZE)
                const resp = await client.mutate({
                    mutation: BULK_SET_SUPPORT,
                    variables: {
                        sbomComponentUuids: slice,
                        levelOfSupport: input.levelOfSupport || null,
                        justification: input.justification || null,
                        supportParty: input.party || null,
                        endOfGuaranteedSupportDate: input.endOfGuaranteedSupportDate || null,
                        endOfSupportDate: input.endOfSupportDate || null,
                        endOfLifeDate: input.endOfLifeDate || null,
                        reason: input.reason || null
                    }
                })
                const r = (resp.data as any)?.bulkSetSbomComponentSupport
                if (!r) throw new Error('the server returned no result for this batch')
                out.results.push(...r.results)
                for (const item of r.results) {
                    if (item.outcome === 'APPLIED') out.applied += 1
                    else if (item.outcome === 'SKIPPED_ATTESTED') out.skippedAttested += 1
                    else if (item.outcome === 'SKIPPED_ROOT') out.skippedRoot += 1
                    else if (item.outcome === 'FAILED') out.failed += 1
                }
                progress.value = Math.min(i + slice.length, ids.length)
            }
        } catch (err: any) {
            // Batches that landed are kept: the operator needs to know how much of the work
            // is done before deciding whether to re-run. Re-running is safe -- already
            // attested components come back SKIPPED_ATTESTED -- but only if they can see it.
            out.aborted = true
            out.error = err?.message || String(err)
        } finally {
            submitting.value = false
        }
        out.concurrentWriteDetected = out.skippedAttested > 0
        return out
    }

    return { collect, submit, collecting, submitting, collected, progress, error }
}

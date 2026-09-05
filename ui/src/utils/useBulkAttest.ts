import { ref, type Ref } from 'vue'
import gql from 'graphql-tag'
import { loadSbomComponentsPage } from './sbomComponentsQuery'
import type { SupportAttestationFilter } from './sbomComponentsQuery'
import { isSchemaDriftError } from './graphqlDriftFallback'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { MutationClient } from './setSbomComponentSupport'
import type { LevelOfSupport, SupportParty } from './supportAttestationInput'

// One definition of the backend enums, shared with the per-component form. A local copy
// would be a second place for them to drift from the schema, and this feature has already
// shipped two invented members that reached the operator as "your server is out of date".

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
        $assessedAt: String
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
            assessedAt: $assessedAt
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
    levelOfSupport?: LevelOfSupport | null
    justification?: string | null
    party?: SupportParty | null
    endOfGuaranteedSupportDate?: string | null
    endOfSupportDate?: string | null
    endOfLifeDate?: string | null
    reason?: string | null
    /**
     * ONE instant for the whole sweep, captured when the operator confirms.
     *
     * Omitting it makes the server stamp `now` inside each per-item transaction, so 800
     * components carry 800 slightly different assessment instants SPREAD ACROSS the sweep --
     * a record that reads as a stream of individual assessments rather than the one action
     * it was. The instant is what the exported BOM publishes as "a human looked, and when".
     */
    assessedAt?: string | null
}

export interface BulkOutcome {
    applied: number
    skippedAttested: number
    skippedRoot: number
    failed: number
    results: Array<{ sbomComponentUuid: string, outcome: string, message: string | null }>
    /** Outcomes this build does not recognise, so the counters cannot silently stop summing. */
    unknownOutcomes: number
    /** A batch threw; earlier batches still landed. */
    aborted: boolean
    /** The operator stopped it between batches; everything already sent has landed. */
    stoppedEarly: boolean
    error: string | null
    /**
     * True when anything came back SKIPPED_ATTESTED. On a fresh walk that should be zero --
     * the walk collected only UNATTESTED components -- so a non-zero count means somebody
     * attested one between the walk and the write. Surfaced rather than hidden: it tells the
     * operator their sweep raced a colleague.
     */
    concurrentWriteDetected: boolean
}

/** How many components the confirmation shows by name. A count alone hides a wrong filter. */
export const SAMPLE_SIZE = 10

export interface BulkCollectResult {
    ids: string[]
    /** Refused outright -- too large, drift, or an error. `ids` is empty. */
    refused: boolean
    /** The filter's total from the server, for "800 of 2,431 undisclosed". */
    backlogTotal: number
    sample: Array<{ uuid: string, name: string, version: string }>
}

/**
 * Problems that would fail EVERY item, checked before a multi-minute walk.
 *
 * Without this the operator picks ABANDONED, leaves the basis blank, waits for the walk, and
 * gets back 800 identical FAILED outcomes -- the server rejects a negative level with no
 * justification per item, and rejects a wholly empty attestation as "must record something".
 */
export function validateBulkInput (input: BulkAttestInput): string[] {
    const out: string[] = []
    const hasBasis = !!(input.justification && input.justification.trim())
    const hasDate = !!(input.endOfGuaranteedSupportDate || input.endOfSupportDate
        || input.endOfLifeDate)
    if (!input.levelOfSupport && !hasDate && !hasBasis) {
        out.push('Record something: a level of support, a date, or a justification.')
    }
    // Mandatory for a sweep even though the server only demands it for un-retracting.
    // Nothing else in the record distinguishes one action across 800 components from 800
    // individual judgements: assessmentSource is MANUAL either way. reason is per-write,
    // audit-only and never exported, which makes it the right carrier for the method.
    if (!input.reason || !input.reason.trim()) {
        out.push('A bulk sweep needs a reason. It is written to every audit row and is the'
            + ' only record of why this batch happened -- and the only thing that marks these'
            + ' as one action rather than hundreds of separate judgements.')
    }
    if ((input.levelOfSupport === 'NO_LONGER_MAINTAINED'
            || input.levelOfSupport === 'ABANDONED') && !hasBasis) {
        out.push(`A level of ${input.levelOfSupport} is a negative claim about someone else's`
            + ' project, so it needs a justification -- and in a sweep that one basis is'
            + ' asserted for every component selected.')
    }
    return out
}

function emptyOutcome (why: string): BulkOutcome {
    return {
        applied: 0, skippedAttested: 0, skippedRoot: 0, failed: 0, results: [],
        aborted: true, error: why, concurrentWriteDetected: false, stoppedEarly: false,
        unknownOutcomes: 0
    }
}

/**
 * Bulk support attestation: walk a filter, collect ids, write them in batches.
 *
 * The shape is collect-then-write and the reasoning is in `collect`. Everything else here
 * exists because a sweep writes a regulatory claim across up to five thousand components on
 * one operator action, so every ambiguous state has to resolve toward refusing.
 */
export function useBulkAttest () {
    const collecting = ref(false)
    const submitting = ref(false)
    const collected: Ref<string[]> = ref([])
    const progress = ref(0)
    const error: Ref<string | null> = ref(null)
    const sample: Ref<Array<{ uuid: string, name: string, version: string }>> = ref([])
    const backlogTotal = ref(0)
    let cancelRequested = false

    /** Stop after the batch in flight. Nothing already sent can be recalled. */
    function requestCancel (): void { cancelRequested = true }

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
    ): Promise<BulkCollectResult> {
        collecting.value = true
        error.value = null
        collected.value = []
        sample.value = []
        backlogTotal.value = 0
        const ids: string[] = []
        let after: string | null = null
        let refused = false
        try {
            for (;;) {
                const page = await loadSbomComponentsPage(client, releaseUuid, {
                    attestation, search, limit: BULK_WALK_LIMIT, after
                })
                // A DEGRADED page is the whole release, unfiltered -- the loader falls back
                // to the unpaged query when the server cannot page or filter. Walking it
                // would turn "sweep the 800 undisclosed matching log4j" into "sweep
                // everything", and the confirmation count would be honest about the size
                // while being wrong about WHICH. The cap does not catch this: the set is a
                // legitimate size, just the wrong set. Abort.
                if (page.degraded) {
                    refused = true
                    error.value = 'This server cannot filter or page SBOM components, so a'
                        + ' bulk sweep here would attest every component in the release'
                        + ' rather than the ones you selected. Bulk attest is unavailable.'
                    break
                }
                if (!ids.length) backlogTotal.value = page.totalCount
                // Refused on the FIRST page when the server already says the set is too big,
                // rather than after eleven round trips that fetch full component rows to
                // keep one field each.
                if (page.totalCount > BULK_MAX_IDS) {
                    refused = true
                    error.value = `This selection has ${page.totalCount} components, more than`
                        + ` the ${BULK_MAX_IDS} a browser sweep will attempt. Narrow it with`
                        + ' the search box and sweep in parts.'
                    break
                }
                const before = ids.length
                for (const row of page.items) {
                    // Skip rather than substitute the component uuid: the two are equal today
                    // but the mutation wants sbom_components.uuid, and guessing manufactures
                    // a FAILED outcome out of missing data.
                    const id = row.sbomComponentUuid
                    if (!id) continue
                    ids.push(id)
                    if (sample.value.length < SAMPLE_SIZE) {
                        sample.value.push({
                            uuid: id,
                            name: row.component?.name ?? '(unnamed)',
                            version: row.component?.version ?? ''
                        })
                    }
                }
                if (!page.hasMore || !page.endCursor) break
                // A page that adds nothing while claiming more would loop forever. The
                // current server cannot do that, but the loop should not depend on a
                // server invariant to terminate.
                if (ids.length === before) {
                    refused = true
                    error.value = 'The server returned an empty page while reporting more'
                        + ' results. Bulk attest stopped rather than looping.'
                    break
                }
                after = page.endCursor
            }
        } catch (err: any) {
            refused = true
            error.value = err?.message || String(err)
        } finally {
            collecting.value = false
        }
        // NO ids when refusing. Handing back a partial list next to a flag invites a caller
        // to destructure { ids } and sweep a truncated set -- exactly the silent partial the
        // refusal exists to prevent.
        if (refused) return { ids: [], refused: true, backlogTotal: backlogTotal.value, sample: [] }
        // Deduped. A duplicate straddling two batches is written twice, and the second write
        // re-asserts and re-dates the attestation the first just made -- the server dedupes
        // only within one request.
        const unique = Array.from(new Set(ids))
        collected.value = unique
        return { ids: unique, refused: false, backlogTotal: backlogTotal.value, sample: sample.value }
    }

    /** Write in batches, aggregating per-item outcomes. */
    async function submit (
        client: MutationClient,
        ids: string[],
        input: BulkAttestInput,
        sweptFilter: SupportAttestationFilter = 'UNATTESTED'
    ): Promise<BulkOutcome> {
        // Checked, not merely set. Two concurrent submits with the same ids both see an
        // empty already-attested set server-side and both write, so the second re-asserts
        // and re-dates an attestation the first just made.
        if (submitting.value) return emptyOutcome('a sweep is already running')
        submitting.value = true
        cancelRequested = false
        // One composable, one place to read a failure from. Leaving a failed collect's
        // message on screen through a successful submit is its own kind of wrong answer.
        error.value = null
        progress.value = 0
        const out: BulkOutcome = {
            applied: 0, skippedAttested: 0, skippedRoot: 0, failed: 0,
            results: [], aborted: false, error: null, concurrentWriteDetected: false,
            stoppedEarly: false, unknownOutcomes: 0
        }
        try {
            for (let i = 0; i < ids.length; i += BULK_BATCH_SIZE) {
                // Checked between batches, never mid-batch: a batch already sent has already
                // committed, item by item, so there is nothing to stop. "Stop after the
                // current batch" is the only honest offer.
                if (cancelRequested) { out.stoppedEarly = true; break }
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
                        reason: input.reason || null,
                        assessedAt: input.assessedAt || null
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
                    // No silent else. A future or misspelled outcome would otherwise be
                    // counted nowhere, so the four counters would stop summing to
                    // results.length and a batch that did something would report 0/0/0.
                    else out.unknownOutcomes += 1
                }
                progress.value = Math.min(i + slice.length, ids.length)
            }
        } catch (err: any) {
            // A write must never degrade. The CE mirror does not declare this mutation at
            // all, so a drifted server means the sweep cannot be performed -- not that it
            // should be attempted narrower. Named, so the operator knows it is the server
            // and not their input.
            if (isSchemaDriftError(err)) {
                out.aborted = true
                out.error = 'This server cannot perform a bulk attestation: it does not'
                    + ' support the bulk mutation, so nothing further was written.'
                submitting.value = false
                out.concurrentWriteDetected = false
                return out
            }
            // Batches that landed are kept: the operator needs to know how much of the work
            // is done before deciding whether to re-run. Re-running is safe -- already
            // attested components come back SKIPPED_ATTESTED -- but only if they can see it.
            out.aborted = true
            out.error = err?.message || String(err)
        } finally {
            submitting.value = false
        }
        // Only a race signal when the walk selected UNATTESTED components. Sweeping under
        // ALL returns SKIPPED_ATTESTED for every already-attested row by design, and so does
        // the recommended re-run after a partial failure. Telling the operator a colleague
        // intervened in either case would be crying wolf, and the wolf message names a
        // colleague.
        out.concurrentWriteDetected = sweptFilter === 'UNATTESTED' && out.skippedAttested > 0
        return out
    }

    return {
        collect, submit, requestCancel, validateBulkInput,
        collecting, submitting, collected, progress, error, sample, backlogTotal
    }
}

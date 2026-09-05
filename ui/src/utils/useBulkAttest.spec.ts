import { describe, expect, it, vi } from 'vitest'
import {
    useBulkAttest, validateBulkInput,
    BULK_BATCH_SIZE, BULK_WALK_LIMIT, BULK_MAX_IDS
} from './useBulkAttest'

/** A client whose page responses are scripted, and whose mutations are recorded. */
function scripted (pages: Array<{ items: string[], hasMore: boolean }>) {
    let call = 0
    const query = vi.fn(async () => {
        const p = pages[call++]
        return {
            data: {
                getReleaseSbomComponentsPage: {
                    items: p.items.map(id => ({ sbomComponentUuid: id, component: { uuid: id } })),
                    totalCount: 999,
                    endCursor: p.items[p.items.length - 1] ?? null,
                    hasMore: p.hasMore
                }
            }
        }
    })
    const mutate = vi.fn(async (opts: any) => ({
        data: {
            bulkSetSbomComponentSupport: {
                appliedCount: opts.variables.sbomComponentUuids.length,
                skippedCount: 0,
                failedCount: 0,
                results: opts.variables.sbomComponentUuids.map((id: string) =>
                    ({ sbomComponentUuid: id, outcome: 'APPLIED', message: null }))
            }
        }
    }))
    return { client: { query, mutate } as any, query, mutate }
}
const ids = (n: number, p = 'c') => Array.from({ length: n }, (_, i) => `${p}${i}`)

describe('collecting the work queue', () => {
    it('walks every page before writing anything', async () => {
        const { client, query, mutate } = scripted([
            { items: ids(500), hasMore: true },
            { items: ids(120, 'd'), hasMore: false }
        ])
        const b = useBulkAttest()
        const res = await b.collect(client, 'rel-1', 'UNATTESTED', '')
        expect(res.ids.length).toBe(620)
        expect(query).toHaveBeenCalledTimes(2)
        // COLLECT-THEN-WRITE: nothing is written during the walk. Keyset makes interleaving
        // safe, but each APPLIED shrinks the UNATTESTED set under the cursor, and a walk that
        // does not write cannot be wrong about it at all.
        expect(mutate).not.toHaveBeenCalled()
    })

    it('walks at the page limit the ruling fixed', async () => {
        const { client, query } = scripted([{ items: ids(3), hasMore: false }])
        const b = useBulkAttest()
        await b.collect(client, 'rel-1', 'UNATTESTED', '')
        expect(query.mock.calls[0][0].variables.limit).toBe(BULK_WALK_LIMIT)
        expect(query.mock.calls[0][0].variables.attestation).toBe('UNATTESTED')
    })

    it('follows the cursor rather than re-reading page one', async () => {
        const { client, query } = scripted([
            { items: ids(500), hasMore: true },
            { items: ids(10, 'd'), hasMore: false }
        ])
        const b = useBulkAttest()
        await b.collect(client, 'rel-1', 'UNATTESTED', '')
        expect(query.mock.calls[0][0].variables.after).toBeNull()
        expect(query.mock.calls[1][0].variables.after).toBe('c499')
    })

    /**
     * A browser is the wrong place to hold an unbounded id list, and an operator who has
     * accidentally cleared the filter should be stopped rather than served. Refuses with an
     * instruction, not a truncated result -- a silently truncated sweep would report success
     * over a fraction of the release.
     */
    it('refuses a release larger than the cap instead of truncating', async () => {
        const client = {
            query: vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
                items: ids(500).map(id => ({ sbomComponentUuid: id, component: { uuid: id } })),
                totalCount: 12000, endCursor: 'c499', hasMore: true
            } } }))
        } as any
        const b = useBulkAttest()
        const res = await b.collect(client, 'rel-1', 'UNATTESTED', '')
        expect(res.refused).toBe(true)
        // Unusable, not truncated. The previous version asserted the overshoot length,
        // encoding the very leak the refusal exists to prevent: a caller destructuring
        // { ids } got up to 5,499 and could sweep a fraction of the release reporting
        // success.
        expect(res.ids).toEqual([])
        expect(b.collected.value).toEqual([])
        expect(b.error.value).toMatch(/narrow/i)
    })

    it('stops cleanly on an empty release', async () => {
        const { client } = scripted([{ items: [], hasMore: false }])
        const b = useBulkAttest()
        expect((await b.collect(client, 'rel-1', 'UNATTESTED', '')).ids).toEqual([])
    })
})

describe('the walk refuses anything it cannot vouch for', () => {
    /**
     * THE critical one, flagged independently by four review lenses. loadSbomComponentsPage
     * falls back to the UNPAGED, UNFILTERED query on schema drift -- and isSchemaDriftError
     * treats ANY http 400 as drift, including one whose body an edge proxy stripped. So a
     * transient 400 turns "sweep the 40 undisclosed matching openssl" into "sweep every
     * undisclosed component in the release", with hasMore false so the walk looks complete.
     *
     * The count in the confirmation would be honest about the size and wrong about WHICH.
     * The cap does not catch it: the set is a legitimate size, just the wrong set.
     */
    it('refuses a degraded page instead of sweeping the whole release', async () => {
        // Routed by document: the paged query drifts, the unpaged fallback answers with the
        // whole release. That is exactly what a stripped-body 400 at the edge produces.
        const drifting = vi.fn(async (opts: any) => {
            const body = opts.query?.loc?.source?.body ?? ''
            if (body.includes('getReleaseSbomComponentsPage')) {
                // The realistic trigger: a server that does not declare the paged query.
                // isSchemaDriftError matches on the validation phrasing (or an HTTP 400
                // signal), and this is what a CE backend actually returns.
                throw new Error('Cannot query field "getReleaseSbomComponentsPage" on type "Query"')
            }
            return { data: { getReleaseSbomComponents:
                Array.from({ length: 700 }, (_, i) => ({ sbomComponentUuid: `x${i}` })) } }
        })
        const b = useBulkAttest()
        const res = await b.collect({ query: drifting } as any, 'rel-1', 'UNATTESTED', 'openssl')
        expect(res.refused).toBe(true)
        expect(res.ids).toEqual([])
        expect(b.error.value).toMatch(/cannot filter|unavailable/i)
    })

    /**
     * A walk that dies half way must not present a prefix as the work queue. The backend
     * documents a rejected cursor as a ROUTINE event -- leave the tab open while the SBOM is
     * re-uploaded -- and errors deliberately so the caller can decide. Swallowing that into
     * a partial list throws the decision away.
     */
    it('refuses when the walk fails part way rather than returning a prefix', async () => {
        let n = 0
        const query = vi.fn(async () => {
            if (++n === 2) throw new Error('unknown pagination cursor: abc')
            return { data: { getReleaseSbomComponentsPage: {
                items: ids(500).map(id => ({ sbomComponentUuid: id, component: { uuid: id } })),
                totalCount: 900, endCursor: 'c499', hasMore: true
            } } }
        })
        const b = useBulkAttest()
        const res = await b.collect({ query } as any, 'rel-1', 'UNATTESTED', '')
        expect(res.refused).toBe(true)
        expect(res.ids).toEqual([])
    })

    // Refusing on page one, from the server's own total, rather than after eleven round
    // trips that fetch full component rows to keep one field each.
    it('refuses on the first page when the server says the set is too large', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
            items: ids(500).map(id => ({ sbomComponentUuid: id, component: { uuid: id } })),
            totalCount: 12000, endCursor: 'c499', hasMore: true
        } } }))
        const b = useBulkAttest()
        const res = await b.collect({ query } as any, 'rel-1', 'UNATTESTED', '')
        expect(res.refused).toBe(true)
        expect(query).toHaveBeenCalledTimes(1)
        expect(b.error.value).toContain('12000')
    })

    it('stops rather than looping when a page adds nothing but claims more', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
            items: [], totalCount: 10, endCursor: 'stuck', hasMore: true
        } } }))
        const b = useBulkAttest()
        const res = await b.collect({ query } as any, 'rel-1', 'UNATTESTED', '')
        expect(res.refused).toBe(true)
        expect(query.mock.calls.length).toBeLessThan(5)
    })

    it('dedupes, so no component is written twice across batches', async () => {
        const dup = ['a', 'b', 'a', 'c', 'b']
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
            items: dup.map(id => ({ sbomComponentUuid: id, component: { uuid: id } })),
            totalCount: 5, endCursor: 'b', hasMore: false
        } } }))
        const b = useBulkAttest()
        expect((await b.collect({ query } as any, 'rel-1', 'UNATTESTED', '')).ids)
            .toEqual(['a', 'b', 'c'])
    })

    it('skips a row with no sbomComponentUuid rather than sending undefined', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
            items: [{ sbomComponentUuid: 'a' }, { component: {} }, { sbomComponentUuid: 'c' }],
            totalCount: 3, endCursor: 'c', hasMore: false
        } } }))
        const b = useBulkAttest()
        expect((await b.collect({ query } as any, 'rel-1', 'UNATTESTED', '')).ids).toEqual(['a', 'c'])
    })

    it('passes the search through -- its silent loss is what the degraded case costs', async () => {
        const { client, query } = scripted([{ items: ids(2), hasMore: false }])
        const b = useBulkAttest()
        await b.collect(client, 'rel-1', 'UNATTESTED', 'log4j')
        expect(query.mock.calls[0][0].variables.search).toBe('log4j')
    })
})

describe('the sweep is recorded as one action', () => {
    /**
     * One instant for the whole sweep, captured at confirmation. Omitted, the server stamps
     * `now` inside each per-item transaction, so the components carry instants spread across
     * the sweep -- and that instant is what the exported BOM publishes as "a human looked,
     * and when". Spread instants describe a stream of individual assessments.
     */
    it('sends the same assessedAt on every batch', async () => {
        const { client, mutate } = scripted([])
        const b = useBulkAttest()
        const at = '2026-09-05T13:40:00Z'
        await b.submit(client, ids(450), {
            levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'swept',
            reason: 'closing the backlog', assessedAt: at
        })
        expect(mutate).toHaveBeenCalledTimes(3)
        for (const call of mutate.mock.calls) expect(call[0].variables.assessedAt).toBe(at)
    })

    // Mandatory for bulk even though the server only requires it for un-retracting.
    it('refuses a sweep with no reason', () => {
        expect(validateBulkInput({ levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'x' })
            .some(e => e.includes('needs a reason'))).toBe(true)
        expect(validateBulkInput({
            levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'x', reason: 'closing the backlog'
        })).toEqual([])
    })

    it('still refuses a negative level with no basis, reason or not', () => {
        expect(validateBulkInput({ levelOfSupport: 'ABANDONED', reason: 'r' })
            .some(e => e.includes('negative claim'))).toBe(true)
    })
})

describe('submitting', () => {
    it('batches at the ruled size', async () => {
        const { client, mutate } = scripted([])
        const b = useBulkAttest()
        await b.submit(client, ids(450), { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'swept' })
        expect(mutate).toHaveBeenCalledTimes(3)
        expect(mutate.mock.calls[0][0].variables.sbomComponentUuids.length).toBe(BULK_BATCH_SIZE)
        expect(mutate.mock.calls[2][0].variables.sbomComponentUuids.length).toBe(50)
    })

    it('aggregates outcomes across batches', async () => {
        const { client } = scripted([])
        const b = useBulkAttest()
        const r = await b.submit(client, ids(250), { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'swept' })
        expect(r.applied).toBe(250)
        expect(r.results.length).toBe(250)
    })

    /**
     * Zero on a fresh walk, because the walk collected only UNATTESTED components. Non-zero
     * means somebody attested one of them between the walk and the write -- worth surfacing,
     * not hiding: it tells the operator their sweep raced a colleague.
     */
    it('surfaces SKIPPED_ATTESTED as a concurrency signal', async () => {
        const mutate = vi.fn(async (opts: any) => ({
            data: { bulkSetSbomComponentSupport: {
                appliedCount: 1, skippedCount: 1, failedCount: 0,
                results: [
                    { sbomComponentUuid: 'a', outcome: 'APPLIED', message: null },
                    { sbomComponentUuid: 'b', outcome: 'SKIPPED_ATTESTED', message: null }
                ]
            } }
        }))
        const b = useBulkAttest()
        const r = await b.submit({ mutate } as any, ['a', 'b'], { justification: 'swept' })
        expect(r.skippedAttested).toBe(1)
        expect(r.concurrentWriteDetected).toBe(true)
    })

    /**
     * A batch failing mid-sweep must not discard the batches that landed: the operator needs
     * to know how much of the work is done before deciding whether to re-run.
     */
    /**
     * The counters must not silently stop summing to results.length. A server that renames
     * or adds an outcome would otherwise report "0 attested." over components it wrote.
     */
    it('counts an outcome it does not recognise rather than dropping it', async () => {
        const { client, mutate } = scripted([])
        mutate.mockImplementation(async (o: any) => ({
            data: {
                bulkSetSbomComponentSupport: {
                    results: o.variables.sbomComponentUuids.map((id: string, i: number) =>
                        ({ sbomComponentUuid: id, outcome: i === 0 ? 'DEFERRED' : 'APPLIED',
                            message: null }))
                }
            }
        }))
        const b = useBulkAttest()
        const out = await b.submit(client, ids(4), { levelOfSupport: 'ACTIVELY_MAINTAINED',
            justification: 'j', reason: 'r' } as any)
        expect(out.unknownOutcomes).toBe(1)
        expect(out.applied).toBe(3)
        expect(out.applied + out.skippedAttested + out.skippedRoot + out.failed
            + out.unknownOutcomes).toBe(out.results.length)
    })

    /**
     * "Stop after this batch" is the only honest offer: a batch already sent has committed
     * item by item. So the cancel must take effect BETWEEN batches -- not lose the batch in
     * flight, and not keep going.
     */
    it('stops between batches, keeping what the batch in flight wrote', async () => {
        const { client, mutate } = scripted([])
        const b = useBulkAttest()
        mutate.mockImplementation(async (o: any) => {
            b.requestCancel()
            return {
                data: {
                    bulkSetSbomComponentSupport: {
                        results: o.variables.sbomComponentUuids.map((id: string) =>
                            ({ sbomComponentUuid: id, outcome: 'APPLIED', message: null }))
                    }
                }
            }
        })
        const out = await b.submit(client, ids(BULK_BATCH_SIZE * 3),
            { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'j', reason: 'r' } as any)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(out.stoppedEarly).toBe(true)
        expect(out.applied).toBe(BULK_BATCH_SIZE)
        // Re-running is the advertised recovery, so it must not be reported as unretryable.
        expect(out.aborted).toBe(false)
    })

    /**
     * Two concurrent submits with the same ids both see an empty already-attested set
     * server-side, so both write -- the second re-asserting and re-dating what the first
     * just recorded. The refusal must also not claim a retry will help while the first is
     * still running.
     */
    it('refuses a second concurrent sweep, and does not offer a retry', async () => {
        const { client, mutate } = scripted([])
        let release: () => void = () => {}
        mutate.mockImplementation(async (o: any) => {
            await new Promise<void>(r => { release = r })
            return {
                data: {
                    bulkSetSbomComponentSupport: {
                        results: o.variables.sbomComponentUuids.map((id: string) =>
                            ({ sbomComponentUuid: id, outcome: 'APPLIED', message: null }))
                    }
                }
            }
        })
        const b = useBulkAttest()
        const input = { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'j', reason: 'r' }
        const first = b.submit(client, ids(2), input as any)
        await Promise.resolve()
        const second = await b.submit(client, ids(2), input as any)
        expect(second.applied).toBe(0)
        expect(second.aborted).toBe(true)
        expect(second.retryable).toBe(false)
        expect(second.error).toMatch(/already running/)
        release()
        expect((await first).applied).toBe(2)
        expect(mutate).toHaveBeenCalledTimes(1)
    })

    /**
     * A drifted server can never accept this mutation, so "re-running completes the
     * remainder" is advice that cannot come true. A transient failure is the opposite. The
     * UI must not have to tell them apart by reading the message.
     */
    it('marks drift unretryable and a transient failure retryable', async () => {
        const input = { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'j', reason: 'r' }
        const drift = scripted([])
        drift.mutate.mockRejectedValue(new Error(
            'Unknown field \'bulkSetSbomComponentSupport\' on type \'Mutation\''))
        const a = await useBulkAttest().submit(drift.client, ids(2), input as any)
        expect(a.retryable).toBe(false)
        expect(a.error).toMatch(/does not.*support the bulk mutation/)

        const flaky = scripted([])
        flaky.mutate.mockRejectedValue(new Error('Network error: 503'))
        const c = await useBulkAttest().submit(flaky.client, ids(2), input as any)
        expect(c.retryable).toBe(true)
    })

    it('keeps the outcomes of batches that landed when a later one throws', async () => {
        let n = 0
        const mutate = vi.fn(async (opts: any) => {
            if (++n === 2) throw new Error('gateway timeout')
            return { data: { bulkSetSbomComponentSupport: {
                appliedCount: opts.variables.sbomComponentUuids.length, skippedCount: 0, failedCount: 0,
                results: opts.variables.sbomComponentUuids.map((id: string) =>
                    ({ sbomComponentUuid: id, outcome: 'APPLIED', message: null }))
            } } }
        })
        const b = useBulkAttest()
        const r = await b.submit({ mutate } as any, ids(300), { justification: 'swept' })
        expect(r.applied).toBe(BULK_BATCH_SIZE)
        expect(r.aborted).toBe(true)
        expect(r.error).toContain('gateway timeout')
    })
})

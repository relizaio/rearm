import { describe, expect, it, vi } from 'vitest'
import { useBulkAttest, BULK_BATCH_SIZE, BULK_WALK_LIMIT, BULK_MAX_IDS } from './useBulkAttest'

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

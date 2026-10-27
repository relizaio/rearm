import { describe, expect, it, vi } from 'vitest'
import {
    useBulkAttest, validateBulkInput, groupNotWritten,
    BULK_BATCH_SIZE, BULK_WALK_LIMIT, BULK_MAX_IDS, SAMPLE_SIZE
} from './useBulkAttest'
import type { BulkLabel, BulkOutcome } from './useBulkAttest'

/** A client whose page responses are scripted, and whose mutations are recorded. */
function scripted (pages: Array<{ items: string[], hasMore: boolean }>) {
    let call = 0
    const query = vi.fn(async () => {
        const p = pages[call++]
        return {
            data: {
                getReleaseSbomComponentsPage: {
                    items: p.items.map(id => ({
                        sbomComponentUuid: id,
                        component: { uuid: id, name: `n-${id}`, version: `v-${id}` }
                    })),
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

    // The done stage names every entry not written, and the walk is the only moment the UI
    // holds the names: a label for every collected id, not only the confirm stage's sample.
    it('keeps a label for every collected id, not only the sample', async () => {
        const { client } = scripted([
            { items: ids(20), hasMore: true },
            { items: ids(3, 'd'), hasMore: false }
        ])
        const b = useBulkAttest()
        const res = await b.collect(client, 'rel-1', 'UNATTESTED', '')
        expect(Object.keys(res.labels)).toHaveLength(23)
        expect(res.labels.c0).toEqual({ name: 'n-c0', version: 'v-c0' })
        expect(res.labels.c19).toEqual({ name: 'n-c19', version: 'v-c19' })
        expect(res.labels.d2).toEqual({ name: 'n-d2', version: 'v-d2' })
        expect(res.sample).toHaveLength(SAMPLE_SIZE)
        expect(res.sample.map(x => x.uuid)).toEqual(ids(10))
        expect(b.labels.value).toEqual(res.labels)
    })

    it('labels a row with no name or version by the same fallbacks as the sample', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
            items: [
                { sbomComponentUuid: 'a', component: { uuid: 'a', version: '1.0' } },
                { sbomComponentUuid: 'b', component: { uuid: 'b', name: 'bee' } },
                { component: { uuid: 'x', name: 'no id', version: '9' } },
                { sbomComponentUuid: 'c' }
            ],
            totalCount: 4, endCursor: 'c', hasMore: false
        } } }))
        const res = await useBulkAttest().collect({ query } as any, 'rel-1', 'UNATTESTED', '')
        expect(res.labels).toEqual({
            a: { name: '(unnamed)', version: '1.0' },
            b: { name: 'bee', version: '' },
            c: { name: '(unnamed)', version: '' }
        })
    })

    it('keeps the first row\'s label for a duplicate id', async () => {
        const query = vi.fn(async () => ({ data: { getReleaseSbomComponentsPage: {
            items: [
                { sbomComponentUuid: 'a', component: { name: 'first', version: '1' } },
                { sbomComponentUuid: 'b', component: { name: 'bee', version: '2' } },
                { sbomComponentUuid: 'a', component: { name: 'second', version: '3' } }
            ],
            totalCount: 3, endCursor: 'a', hasMore: false
        } } }))
        const res = await useBulkAttest().collect({ query } as any, 'rel-1', 'UNATTESTED', '')
        expect(res.ids).toEqual(['a', 'b'])
        expect(res.labels).toEqual({ a: { name: 'first', version: '1' }, b: { name: 'bee', version: '2' } })
    })

    it('hands back no labels from a refused walk', async () => {
        const page = (totalCount: number, hasMore: boolean, items: string[]) => ({ data: {
            getReleaseSbomComponentsPage: {
                items: items.map(id => ({ sbomComponentUuid: id, component: { name: id, version: '1' } })),
                totalCount, endCursor: 'cur', hasMore
            }
        } })
        // too large
        const big = useBulkAttest()
        expect((await big.collect({ query: vi.fn(async () => page(12000, true, ids(500))) } as any,
            'rel-1', 'UNATTESTED', '')).labels).toEqual({})
        expect(big.labels.value).toEqual({})
        // a thrown page, after a page that did collect labels
        let n = 0
        const thrown = useBulkAttest()
        const res = await thrown.collect({ query: vi.fn(async () => {
            if (++n === 2) throw new Error('unknown pagination cursor: cur')
            return page(900, true, ids(500))
        }) } as any, 'rel-1', 'UNATTESTED', '')
        expect(res.refused).toBe(true)
        expect(res.labels).toEqual({})
        expect(thrown.labels.value).toEqual({})
        // an empty page claiming more
        const stuck = useBulkAttest()
        expect((await stuck.collect({ query: vi.fn(async () => page(10, true, [])) } as any,
            'rel-1', 'UNATTESTED', '')).labels).toEqual({})
        // the ref is reset by the next walk, not left over from the last one
        const reused = useBulkAttest()
        const ok = scripted([{ items: ids(2), hasMore: false }])
        await reused.collect(ok.client, 'rel-1', 'UNATTESTED', '')
        expect(Object.keys(reused.labels.value)).toHaveLength(2)
        await reused.collect({ query: vi.fn(async () => page(12000, true, ids(500))) } as any,
            'rel-1', 'UNATTESTED', '')
        expect(reused.labels.value).toEqual({})
    })
})

describe('the walk refuses anything it cannot vouch for', () => {

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

    it('passes the search through -- sweeping an unfiltered set is the cost of losing it', async () => {
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
    /**
     * NOT a drift assertion -- a design guard on the client document, and it outlived the CE
     * sync that removed its file-mates. supportNotes is per-component EVIDENCE: what someone
     * checked about THAT component. Fanning one string across a sweep would attach the same
     * evidence to hundreds of components nobody looked at individually, which is worse than
     * no evidence because it reads as work that was done. The mutation accepts the argument
     * on both schemas, so nothing but this stops it being sent.
     */
    it('does not send supportNotes, whatever the caller passes', async () => {
        const { client, mutate } = scripted([])
        await useBulkAttest().submit(client, ids(3), {
            levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'j', reason: 'r',
            supportNotes: 'checked the upstream release notes'
        } as any)
        const vars = mutate.mock.calls[0][0].variables
        expect(Object.keys(vars)).not.toContain('supportNotes')
        expect(JSON.stringify(vars)).not.toContain('upstream release notes')
    })

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
        await b.submit(client, ids(450),
            { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'swept', reason: 'r' } as any)
        expect(mutate).toHaveBeenCalledTimes(3)
        expect(mutate.mock.calls[0][0].variables.sbomComponentUuids.length).toBe(BULK_BATCH_SIZE)
        expect(mutate.mock.calls[2][0].variables.sbomComponentUuids.length).toBe(50)
    })

    it('aggregates outcomes across batches', async () => {
        const { client } = scripted([])
        const b = useBulkAttest()
        const r = await b.submit(client, ids(250),
            { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'swept', reason: 'r' } as any)
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
        const r = await b.submit({ mutate } as any, ['a', 'b'],
            { justification: 'swept', reason: 'r' } as any)
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
    /**
     * The reason rule must not be enforceable only by the caller's disabled-button binding.
     * A sweep with no reason produces hundreds of audit rows that record nothing about why
     * the batch happened, and the server will accept every one of them.
     */
    it('refuses to write a sweep with no reason, whatever the caller did', async () => {
        const { client, mutate } = scripted([])
        const out = await useBulkAttest().submit(client, ids(3),
            { levelOfSupport: 'ACTIVELY_MAINTAINED', justification: 'j', reason: '   ' } as any)
        expect(mutate, 'nothing may be written').not.toHaveBeenCalled()
        expect(out.applied).toBe(0)
        expect(out.aborted).toBe(true)
        expect(out.retryable).toBe(false)
        expect(out.error).toMatch(/needs a reason/)
    })

    /** The same gate covers the rest of validateBulkInput, not just the reason. */
    it('refuses a negative level with no basis at the write, not only in the form', async () => {
        const { client, mutate } = scripted([])
        const out = await useBulkAttest().submit(client, ids(3),
            { levelOfSupport: 'ABANDONED', justification: '', reason: 'r' } as any)
        expect(mutate).not.toHaveBeenCalled()
        expect(out.error).toMatch(/negative claim/)
    })

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
        const r = await b.submit({ mutate } as any, ids(300),
            { justification: 'swept', reason: 'r' } as any)
        expect(r.applied).toBe(BULK_BATCH_SIZE)
        expect(r.aborted).toBe(true)
        expect(r.error).toContain('gateway timeout')
    })
})

describe('naming what was not written', () => {
    type Result = BulkOutcome['results'][number]
    const r = (sbomComponentUuid: string, outcome: string, message: string | null = null): Result =>
        ({ sbomComponentUuid, outcome, message })
    const outcomeOf = (results: Result[], flags: Partial<BulkOutcome> = {}): BulkOutcome => ({
        applied: results.filter(x => x.outcome === 'APPLIED').length,
        skippedAttested: 0, skippedRoot: 0, failed: 0, unknownOutcomes: 0, results,
        aborted: false, stoppedEarly: false, error: null, retryable: false,
        concurrentWriteDetected: false, ...flags
    })
    const labelsFor = (list: string[]): Record<string, BulkLabel> =>
        Object.fromEntries(list.map(id => [id, { name: `n-${id}`, version: `v-${id}` }]))
    const uuids = (g: { entries: Array<{ uuid: string }> } | undefined) => g?.entries.map(e => e.uuid)

    it('groups every entry not written by reason, in a fixed order, with its name and detail', () => {
        const all = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']
        const outcome = outcomeOf([
            r('a', 'APPLIED'), r('b', 'SKIPPED_ROOT'), r('c', 'SKIPPED_ATTESTED'),
            r('d', 'FAILED', 'component not found in this organization'), r('e', 'FAILED'),
            r('f', 'DEFERRED'), r('g', 'APPLIED')
        ], { aborted: true, retryable: true })
        const groups = groupNotWritten(all, outcome, labelsFor(all.filter(x => x !== 'd')))
        expect(groups.map(g => g.reason))
            .toEqual(['SKIPPED_ROOT', 'SKIPPED_ATTESTED', 'FAILED', 'UNKNOWN', 'NO_RESULT'])
        expect(groups.map(uuids)).toEqual([['b'], ['c'], ['d', 'e'], ['f'], ['h']])
        const entry = (id: string) => groups.flatMap(g => g.entries).find(e => e.uuid === id)
        expect(entry('b')?.label).toBe('n-b v-b')
        expect(entry('d')?.label).toBe('d')
        expect(entry('d')?.detail).toBe('component not found in this organization')
        expect(entry('e')?.detail).toBe('no reason given')
        expect(entry('f')?.detail).toBe('DEFERRED')
        expect(entry('b')?.detail).toBeNull()
        expect(entry('h')?.detail).toBeNull()
        expect(entry('a')).toBeUndefined()
        expect(entry('g')).toBeUndefined()
        expect(groups.map(g => g.title)).toEqual([
            '1 skipped: the release\'s own component',
            '1 skipped: already attested',
            '2 failed',
            '1 with a result this version cannot interpret',
            '1 with no result'
        ])
        for (const g of groups) expect(g.explanation.length).toBeGreaterThan(0)
        expect(groups[0].explanation).toContain('FDA support checks')
        expect(groups[0].explanation).toContain('coverage gauge')
        const listed = groups.reduce((n, g) => n + g.entries.length, 0)
        expect(listed).toBe(all.length - outcome.applied)
    })

    it('inflects the ROOT title at more than one', () => {
        const groups = groupNotWritten(['a', 'b'],
            outcomeOf([r('a', 'SKIPPED_ROOT'), r('b', 'SKIPPED_ROOT')]), {})
        expect(groups.map(g => g.title)).toEqual(['2 skipped: the release\'s own components'])
    })

    it('lists nothing when everything was written', () => {
        expect(groupNotWritten(['a', 'b'], outcomeOf([r('a', 'APPLIED'), r('b', 'APPLIED')]),
            labelsFor(['a', 'b']))).toEqual([])
    })

    it('lists a result for an id the walk did not collect, by its uuid', () => {
        const groups = groupNotWritten(['a'],
            outcomeOf([r('a', 'APPLIED'), r('stray', 'FAILED', 'boom')]), labelsFor(['a']))
        expect(groups).toHaveLength(1)
        expect(groups[0].entries).toEqual([{ uuid: 'stray', label: 'stray', detail: 'boom' }])
    })

    it('leaves no trailing space when the version is empty', () => {
        const groups = groupNotWritten(['a'], outcomeOf([r('a', 'SKIPPED_ATTESTED')]),
            { a: { name: 'lib', version: '' } })
        expect(groups[0].entries[0].label).toBe('lib')
    })

    describe('the NO_RESULT explanation follows how the sweep ended', () => {
        const all = Array.from({ length: 450 }, (_, i) => `id-${i}`)
        const firstBatch = all.slice(0, 200).map(id => r(id, 'APPLIED'))
        const noResult = (outcome: BulkOutcome) => {
            const groups = groupNotWritten(all, outcome, {})
            return { groups, g: groups.find(x => x.reason === 'NO_RESULT') }
        }

        it('a thrown batch: may have been written, re-running settles it', () => {
            const { g } = noResult(outcomeOf(firstBatch, { aborted: true, retryable: true }))
            expect(uuids(g)).toEqual(all.slice(200))
            expect(g?.explanation).toContain('may still have been written')
            expect(g?.explanation).toContain('re-running')
        })

        it('a validation abort: refused, and no re-run is promised', () => {
            const { g } = noResult(outcomeOf(firstBatch, { aborted: true, retryable: false }))
            expect(g?.entries).toHaveLength(250)
            expect(g?.explanation).toContain('refused')
            expect(g?.explanation).not.toContain('re-run')
            expect(g?.explanation).not.toContain('Re-running')
        })

        it('nothing sent at all: every id, the same refused explanation, no other group', () => {
            const { groups, g } = noResult(outcomeOf([], { aborted: true, retryable: false }))
            expect(groups).toHaveLength(1)
            expect(uuids(g)).toEqual(all)
            expect(g?.explanation).toContain('refused')
            expect(g?.explanation).not.toContain('re-run')
            expect(g?.explanation).not.toContain('Re-running')
        })

        // retryable stays false on a stop, so a rule keyed on retryable alone would call a
        // stop a refusal and withhold the re-run the stopped alert offers.
        it('a stop between batches: not sent, and the re-run is promised', () => {
            const { g } = noResult(outcomeOf(firstBatch, { stoppedEarly: true, retryable: false }))
            expect(g?.entries).toHaveLength(250)
            expect(g?.explanation).toContain('stopped')
            expect(g?.explanation).toContain('Re-running')
            expect(g?.explanation).not.toContain('refused')
        })

        it('a batch answered short: no result came back', () => {
            const { g } = noResult(outcomeOf(all.filter(id => id !== 'id-7').map(id => r(id, 'APPLIED'))))
            expect(uuids(g)).toEqual(['id-7'])
            expect(g?.explanation).toContain('No result came back')
        })

        it('finds a missing result by membership, not by position', () => {
            const results = firstBatch.filter(x => x.sbomComponentUuid !== 'id-100')
            const { g } = noResult(outcomeOf(results, { aborted: true, retryable: true }))
            const listed = uuids(g) ?? []
            expect(listed).toHaveLength(251)
            expect(listed).toContain('id-100')
            expect(listed).toContain('id-200')
            expect(listed).not.toContain('id-199')
            expect(listed[0]).toBe('id-100')
        })
    })
})

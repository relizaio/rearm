import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { useSbomComponentsPaging } from './useSbomComponentsPaging'

/** A client whose responses are resolved by the test, so ordering is explicit. */
function deferredClient () {
    const pending: Array<{ vars: any, resolve: (v: any) => void, reject: (e: any) => void }> = []
    const query = vi.fn((opts: any) => new Promise((resolve, reject) => {
        pending.push({ vars: opts.variables, resolve, reject })
    }))
    return { client: { query } as any, pending, query }
}

function page (items: any[], totalCount: number, endCursor: string | null, hasMore: boolean) {
    return { data: { getReleaseSbomComponentsPage: { items, totalCount, endCursor, hasMore } } }
}
const row = (id: string) => ({ uuid: id, sbomComponentUuid: id })

describe('useSbomComponentsPaging request fencing', () => {
    // THE bug this composable exists for. A load-more issued under ALL must not append its
    // rows to a list that has since been replaced by an UNATTESTED load -- and must not
    // overwrite the cursor, which would make the next page resume from a position in the
    // other ordering: rows skipped, rows repeated, under a filter label describing neither.
    it('discards a load-more that lands after the filter changed', async () => {
        const { client, pending } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn() })

        p.load()
        pending[0].resolve(page([row('a1'), row('a2')], 99, 'ca2', true))
        await Promise.resolve(); await Promise.resolve()
        expect(p.items.value.map((r: any) => r.uuid)).toEqual(['a1', 'a2'])

        const more = p.loadMore()            // issued under ALL
        p.filter.value = 'UNATTESTED'
        p.onFilterChange()                   // replaces the list
        pending[2].resolve(page([row('u1')], 5, 'cu1', false))
        await Promise.resolve(); await Promise.resolve()
        pending[1].resolve(page([row('a3')], 99, 'ca3', true))   // the stale one, last
        await more
        await Promise.resolve()

        expect(p.items.value.map((r: any) => r.uuid)).toEqual(['u1'])
        expect(p.totalCount.value).toBe(5)
        expect(p.hasMore.value).toBe(false)
        expect(p.appliedFilter.value).toBe('UNATTESTED')
    })

    it('keeps the last-ISSUED search when two resolve out of order', async () => {
        const { client, pending } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn(), debounceMs: 0 })
        vi.useFakeTimers()

        p.searchInput.value = 'log4j'
        p.onSearchInput(); vi.advanceTimersByTime(1)
        p.searchInput.value = 'jackson'
        p.onSearchInput(); vi.advanceTimersByTime(1)
        vi.useRealTimers()

        // The FIRST request resolves last.
        pending[1].resolve(page([row('jackson-1')], 1, null, false))
        await Promise.resolve(); await Promise.resolve()
        pending[0].resolve(page([row('log4j-1')], 1, null, false))
        await Promise.resolve(); await Promise.resolve()

        expect(p.items.value.map((r: any) => r.uuid)).toEqual(['jackson-1'])
        expect(p.appliedSearch.value).toBe('jackson')
    })

    // Release B showing release A's components, persistently, because the stale load set
    // loaded=true and every later load then early-returned.
    it('discards a response for a release the user has navigated away from', async () => {
        const { client, pending } = deferredClient()
        let current = 'rel-A'
        const p = useSbomComponentsPaging({ client, releaseUuid: () => current, onError: vi.fn() })

        p.load()
        current = 'rel-B'
        p.reset()
        pending[0].resolve(page([row('from-A')], 7, null, false))
        await Promise.resolve(); await Promise.resolve()

        expect(p.items.value).toEqual([])
        expect(p.loaded.value).toBe(false)
        expect(p.totalCount.value).toBe(0)
    })

    it('reset clears every piece of paging state, not just the rows', async () => {
        const { client, pending } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn() })
        p.load()
        pending[0].resolve(page([row('a1')], 312, 'c1', true))
        await Promise.resolve(); await Promise.resolve()
        expect(p.totalCount.value).toBe(312)

        p.reset()
        expect(p.items.value).toEqual([])
        expect(p.totalCount.value).toBe(0)
        expect(p.hasMore.value).toBe(false)
        expect(p.loaded.value).toBe(false)
        expect(p.degraded.value).toBe(false)
    })
})

describe('useSbomComponentsPaging debounce lifecycle', () => {
    beforeEach(() => { vi.useFakeTimers() })
    afterEach(() => { vi.useRealTimers() })

    it('never fires a trailing search after dispose', () => {
        const { client, query } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn() })
        p.searchInput.value = 'log4j'
        p.onSearchInput()
        p.dispose()
        vi.advanceTimersByTime(1000)
        expect(query).not.toHaveBeenCalled()
    })

    it('never fires a trailing search after the release changed', () => {
        const { client, query } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-A', onError: vi.fn() })
        p.searchInput.value = 'log4j'
        p.onSearchInput()
        p.reset()
        vi.advanceTimersByTime(1000)
        expect(query).not.toHaveBeenCalled()
    })

    it('collapses a burst of keystrokes into one request', () => {
        const { client, query } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn() })
        for (const q of ['l', 'lo', 'log', 'log4', 'log4j']) {
            p.searchInput.value = q
            p.onSearchInput()
            vi.advanceTimersByTime(50)
        }
        vi.advanceTimersByTime(300)
        expect(query).toHaveBeenCalledOnce()
        expect(query.mock.calls[0][0].variables.search).toBe('log4j')
    })
})

describe('useSbomComponentsPaging failure and degraded handling', () => {
    // Leaving the previous filter's rows on screen under the new filter's label reads as a
    // successful, differently-filtered result. It is not one.
    it('clears the list when a filter change fails rather than relabelling old rows', async () => {
        const { client, pending } = deferredClient()
        const onError = vi.fn()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError })
        p.load()
        pending[0].resolve(page([row('a1'), row('a2')], 2, null, false))
        await Promise.resolve(); await Promise.resolve()

        p.filter.value = 'UNATTESTED'
        p.onFilterChange()
        pending[1].reject(new Error('boom'))
        await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

        expect(p.items.value).toEqual([])
        expect(p.failed.value).toBe(true)
        expect(onError).toHaveBeenCalledOnce()
    })

    // A CE fallback returns the WHOLE release unfiltered. Reporting the requested filter as
    // applied would label rows the server never filtered.
    it('reports a degraded page as unfiltered whatever was asked for', async () => {
        const query = vi.fn()
            .mockRejectedValueOnce(new Error('Cannot query field "getReleaseSbomComponentsPage" on type "Query"'))
            .mockResolvedValueOnce({ data: { getReleaseSbomComponents: [row('x'), row('y')] } })
        const p = useSbomComponentsPaging({
            client: { query } as any, releaseUuid: () => 'rel-1', onError: vi.fn()
        })
        p.filter.value = 'UNATTESTED'
        p.searchInput.value = 'log4j'
        await p.load(true)

        expect(p.degraded.value).toBe(true)
        expect(p.items.value.length).toBe(2)
        expect(p.appliedFilter.value).toBe('ALL')
        expect(p.appliedSearch.value).toBe('')
        expect(p.hasMore.value).toBe(false)
    })

    it('does not send after: null when hasMore is true but the cursor is missing', async () => {
        const { client, pending, query } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn() })
        p.load()
        pending[0].resolve(page([row('a1')], 9, null, true))
        await Promise.resolve(); await Promise.resolve()
        expect(p.hasMore.value).toBe(true)

        await p.loadMore()
        expect(query).toHaveBeenCalledOnce()
    })

    it('leaves the cursor intact when load-more fails, so a retry resumes', async () => {
        const { client, pending } = deferredClient()
        const p = useSbomComponentsPaging({ client, releaseUuid: () => 'rel-1', onError: vi.fn() })
        p.load()
        pending[0].resolve(page([row('a1')], 9, 'c1', true))
        await Promise.resolve(); await Promise.resolve()

        const more = p.loadMore()
        pending[1].reject(new Error('network'))
        await more
        expect(p.hasMore.value).toBe(true)

        // Not awaited: the deferred client never resolves this one, and the assertion is
        // about what was REQUESTED, not what came back.
        p.loadMore()
        await Promise.resolve()
        expect(pending[2].vars.after).toBe('c1')
    })
})

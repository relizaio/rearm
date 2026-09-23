import { describe, expect, it, vi } from 'vitest'
import { useReleaseSupportCoverage } from './useReleaseSupportCoverage'

function deferredClient () {
    const pending: Array<{ resolve: (v: any) => void, reject: (e: any) => void }> = []
    const query = vi.fn(() => new Promise((resolve, reject) => { pending.push({ resolve, reject }) }))
    return { client: { query } as any, pending, query }
}
const cov = (total: number, attested: number, state = 'PARTIAL') =>
    ({ data: { sbomComponentSupportCoverage: { total, attested, supportExportState: state } } })

describe('useReleaseSupportCoverage', () => {
    it('loads coverage for the release it is given', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const p = s.load('org-1', 'rel-1')
        pending[0].resolve(cov(10, 3))
        await p
        expect(s.coverage.value).toEqual({ total: 10, attested: 3, exportState: 'PARTIAL' })
        expect(s.error.value).toBeNull()
    })

    it('does nothing without both ids, rather than querying with undefined', async () => {
        const { client, query } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        await s.load(undefined, 'rel-1')
        await s.load('org-1', undefined)
        expect(query).not.toHaveBeenCalled()
    })

    /**
     * The hazard the synchronous reset cannot cover: a response for release A landing after
     * the operator has navigated to B would caption B with A's disclosure count. That is the
     * same ordering bug the component list hit, on the same component.
     */
    it('discards a response for a release the user has navigated away from', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const p = s.load('org-1', 'rel-A')
        s.reset()
        pending[0].resolve(cov(99, 99))
        await p
        expect(s.coverage.value).toBeNull()
    })

    it('keeps the last-issued load when two overlap', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const first = s.load('org-1', 'rel-A')
        const second = s.load('org-1', 'rel-B')
        pending[1].resolve(cov(5, 5))
        await second
        pending[0].resolve(cov(77, 0))
        await first
        expect(s.coverage.value).toEqual({ total: 5, attested: 5, exportState: 'PARTIAL' })
    })

    /**
     * A failed request is not an unanswerable server. The loader separates drift (null) from
     * real errors (thrown); this keeps them separate in state, so the UI can say "retry"
     * rather than making a durable claim about what the server supports.
     */
    it('records an error distinctly from an unanswerable server', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const p = s.load('org-1', 'rel-1')
        pending[0].reject(new Error('502 Bad Gateway'))
        await p
        expect(s.coverage.value).toBeNull()
        expect(s.error.value).toContain('502')
    })

    it('clears a previous error on a successful reload', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const bad = s.load('org-1', 'rel-1')
        pending[0].reject(new Error('boom'))
        await bad
        expect(s.error.value).toBeTruthy()
        const good = s.load('org-1', 'rel-1')
        pending[1].resolve(cov(2, 2))
        await good
        expect(s.error.value).toBeNull()
    })

    // Boolean loading with two overlapping loads would unhide the gauge while the second is
    // still running, flashing a stale or absent number.
    it('stays loading until the LAST overlapping request settles', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const a = s.load('org-1', 'rel-1')
        const b = s.load('org-1', 'rel-1')
        pending[0].resolve(cov(1, 1))
        await a
        expect(s.loading.value).toBe(true)
        pending[1].resolve(cov(1, 1))
        await b
        expect(s.loading.value).toBe(false)
    })

    it('reset clears coverage, error and loading together', async () => {
        const { client, pending } = deferredClient()
        const s = useReleaseSupportCoverage(client)
        const p = s.load('org-1', 'rel-1')
        pending[0].resolve(cov(4, 1))
        await p
        s.reset()
        expect(s.coverage.value).toBeNull()
        expect(s.error.value).toBeNull()
        expect(s.loading.value).toBe(false)
    })
})

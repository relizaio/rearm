import { describe, expect, it, vi } from 'vitest'
import { loadSbomComponentsPage } from './sbomComponentsQuery'

function clientReturning (page: any) {
    const query = vi.fn().mockResolvedValue({ data: { getReleaseSbomComponentsPage: page } })
    return { client: { query } as any, query }
}

describe('loadSbomComponentsPage', () => {
    it('defaults to the whole release, unsearched, one page', async () => {
        const { client, query } = clientReturning({ items: [], totalCount: 0, endCursor: null, hasMore: false })
        await loadSbomComponentsPage(client, 'rel-1')
        expect(query.mock.calls[0][0].variables).toEqual({
            releaseUuid: 'rel-1', attestation: 'ALL', search: null, limit: 50, after: null
        })
    })

    // Never cache this. The bulk-attest walk re-reads the SAME filter after writing to it,
    // and a cached page would hand back rows that have just been attested -- re-attesting
    // them, or reporting them as still missing.
    it('always hits the network', async () => {
        const { client, query } = clientReturning({ items: [], totalCount: 0, endCursor: null, hasMore: false })
        await loadSbomComponentsPage(client, 'rel-1')
        expect(query.mock.calls[0][0].fetchPolicy).toBe('network-only')
    })

    it('passes the filter, the trimmed search and the cursor through', async () => {
        const { client, query } = clientReturning({ items: [], totalCount: 0, endCursor: null, hasMore: false })
        await loadSbomComponentsPage(client, 'rel-1', {
            attestation: 'UNATTESTED', search: '  log4j  ', limit: 10, after: 'cursor-9'
        })
        expect(query.mock.calls[0][0].variables).toEqual({
            releaseUuid: 'rel-1', attestation: 'UNATTESTED', search: 'log4j', limit: 10, after: 'cursor-9'
        })
    })

    // An empty or whitespace search is NO search, not a search for "". The server would
    // match every row either way, but sending it makes an unfiltered request look filtered.
    it.each(['', '   '])('sends null rather than a blank search (%s)', async (search) => {
        const { client, query } = clientReturning({ items: [], totalCount: 0, endCursor: null, hasMore: false })
        await loadSbomComponentsPage(client, 'rel-1', { search })
        expect(query.mock.calls[0][0].variables.search).toBeNull()
    })

    it('surfaces the page fields', async () => {
        const { client } = clientReturning({
            items: [{ uuid: 'a' }], totalCount: 312, endCursor: 'c-1', hasMore: true
        })
        expect(await loadSbomComponentsPage(client, 'rel-1')).toEqual({
            items: [{ uuid: 'a' }], totalCount: 312, endCursor: 'c-1', hasMore: true
        })
    })

    // hasMore is the server's over-fetch answer. Deriving it from totalCount would be wrong
    // under UNATTESTED, where the population shrinks as the caller attests.
    it('does not infer hasMore from totalCount', async () => {
        const { client } = clientReturning({
            items: [{ uuid: 'a' }], totalCount: 999, endCursor: 'c-1', hasMore: false
        })
        expect((await loadSbomComponentsPage(client, 'rel-1')).hasMore).toBe(false)
    })

    it('degrades to an empty page when the field is absent', async () => {
        const query = vi.fn().mockResolvedValue({ data: {} })
        expect(await loadSbomComponentsPage({ query } as any, 'rel-1')).toEqual({
            items: [], totalCount: 0, endCursor: null, hasMore: false
        })
    })

    // A rejected cursor is a server error and must reach the caller. Swallowing it and
    // returning an empty page would make a cursor walk look finished when it had failed.
    it('propagates a rejected cursor rather than returning an empty page', async () => {
        const query = vi.fn().mockRejectedValue(new Error('unknown pagination cursor: abc'))
        await expect(loadSbomComponentsPage({ query } as any, 'rel-1', { after: 'abc' }))
            .rejects.toThrow('unknown pagination cursor')
    })
})

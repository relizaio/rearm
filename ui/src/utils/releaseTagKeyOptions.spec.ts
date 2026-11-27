import { describe, it, expect, vi, afterEach } from 'vitest'
import { print } from 'graphql'
import { RELEASE_TAG_KEYS_QUERY, loadReleaseTagKeyOptions } from '@/utils/releaseTagKeyOptions'

const ORG = '00000000-0000-0000-0000-0000000000aa'

function stub (answer: () => Promise<any>) {
    return { query: vi.fn(answer) }
}

afterEach(() => {
    vi.restoreAllMocks()
})

describe('loadReleaseTagKeyOptions', () => {
    it('sends no request for a user who cannot write the release', async () => {
        const client = stub(async () => ({ data: { releaseTagKeys: ['a'] } }))
        await expect(loadReleaseTagKeyOptions(client, ORG, false)).resolves.toEqual([])
        expect(client.query).not.toHaveBeenCalled()
    })

    it('sends no request without an organization', async () => {
        const client = stub(async () => ({ data: { releaseTagKeys: ['a'] } }))
        await expect(loadReleaseTagKeyOptions(client, undefined, true)).resolves.toEqual([])
        await expect(loadReleaseTagKeyOptions(client, '', true)).resolves.toEqual([])
        await expect(loadReleaseTagKeyOptions(client, null, true)).resolves.toEqual([])
        expect(client.query).not.toHaveBeenCalled()
    })

    it('maps each key to a select option, in answer order', async () => {
        const client = stub(async () => ({ data: { releaseTagKeys: ['b', 'a'] } }))
        await expect(loadReleaseTagKeyOptions(client, ORG, true)).resolves.toEqual([
            { label: 'b', value: 'b' },
            { label: 'a', value: 'a' }
        ])
        expect(client.query).toHaveBeenCalledTimes(1)
        const opts = client.query.mock.calls[0][0] as any
        expect(opts.query).toBe(RELEASE_TAG_KEYS_QUERY)
        expect(opts.variables).toEqual({ orgId: ORG })
    })

    it('keeps the query text: operation releaseTagKeys, root field releaseTagKeys(orgUuid: $orgId)', () => {
        const text = print(RELEASE_TAG_KEYS_QUERY).replace(/\s+/g, ' ')
        expect(text).toContain('query releaseTagKeys($orgId: ID!)')
        expect(text).toContain('releaseTagKeys(orgUuid: $orgId)')
    })

    it('answers an empty list for a null or absent field, without a warning', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        await expect(loadReleaseTagKeyOptions(stub(async () => ({ data: { releaseTagKeys: null } })), ORG, true))
            .resolves.toEqual([])
        await expect(loadReleaseTagKeyOptions(stub(async () => ({ data: {} })), ORG, true)).resolves.toEqual([])
        expect(warn).not.toHaveBeenCalled()
    })

    it('never rejects on an Error: one warning, an empty list', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        const client = stub(async () => { throw new Error('Not authorized') })
        await expect(loadReleaseTagKeyOptions(client, ORG, true)).resolves.toEqual([])
        expect(warn).toHaveBeenCalledTimes(1)
    })

    it('never rejects on a GraphQL error object: one warning, an empty list', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        const graphqlError = { name: 'CombinedGraphQLErrors', message: 'Not authorized',
            errors: [{ message: 'Not authorized', path: ['releaseTagKeys'] }] }
        const client = stub(() => Promise.reject(graphqlError))
        await expect(loadReleaseTagKeyOptions(client, ORG, true)).resolves.toEqual([])
        expect(warn).toHaveBeenCalledTimes(1)
    })
})

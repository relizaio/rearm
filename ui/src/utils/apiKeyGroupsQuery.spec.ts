import { describe, expect, it } from 'vitest'
import { print } from 'graphql'
import { API_KEY_GROUPS_OF_ORG_QUERY, kindOf, loadApiKeyGroups } from './apiKeyGroupsQuery'

// TPF-92 (architecture 4.5, test row 18): the probing loader swallows schema drift only.

// The validationError() fixture of graphqlDriftFallback.spec.ts 20-26.
function validationError (field = 'apiKeyGroupsOfOrg'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}

const rejecting = (e: any) => ({ query: async () => { throw e } })

describe('loadApiKeyGroups (row 18)', () => {
    it('returns the groups and supported true', async () => {
        const g = { uuid: 'g1', name: 'ci' }
        const calls: any[] = []
        const client = { query: async (opts: any) => { calls.push(opts); return { data: { apiKeyGroupsOfOrg: [g] } } } }
        expect(await loadApiKeyGroups(client, 'o1', 'INTERNAL')).toEqual({ groups: [g], supported: true })
        expect(calls).toHaveLength(1)
        expect(calls[0].query).toBe(API_KEY_GROUPS_OF_ORG_QUERY)
        expect(calls[0].variables).toEqual({ org: 'o1', kind: 'INTERNAL' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
    })

    it('answers supported false with no groups on a drift-shaped error', async () => {
        expect(await loadApiKeyGroups(rejecting(validationError()), 'o1', 'INTERNAL')).toEqual({ groups: [], supported: false })
    })

    it('rethrows a network error', async () => {
        const e = new Error('Failed to fetch')
        await expect(loadApiKeyGroups(rejecting(e), 'o1', 'INTERNAL')).rejects.toBe(e)
    })

    it('rethrows a Not authorized GraphQL error', async () => {
        const e: any = new Error('Not authorized')
        e.errors = [{ message: 'Not authorized', extensions: { classification: 'DataFetchingException' } }]
        await expect(loadApiKeyGroups(rejecting(e), 'o1', 'INTERNAL')).rejects.toBe(e)
    })
})

// FDAW-40 row 22c (FDAW-25 3.12): the one list, split by kind.
describe('loadApiKeyGroups by kind (row 22c)', () => {
    it('sends the CUSTOMER kind as the variable', async () => {
        const calls: any[] = []
        const client = { query: async (opts: any) => { calls.push(opts); return { data: { apiKeyGroupsOfOrg: [] } } } }
        expect(await loadApiKeyGroups(client, 'o1', 'CUSTOMER')).toEqual({ groups: [], supported: true })
        expect(calls).toHaveLength(1)
        expect(calls[0].variables).toEqual({ org: 'o1', kind: 'CUSTOMER' })
    })

    it('declares $kind: ApiKeyGroupKind, passes it to the field and selects kind', () => {
        const text = print(API_KEY_GROUPS_OF_ORG_QUERY)
        expect(text).toContain('$kind: ApiKeyGroupKind')
        expect(text).toContain('apiKeyGroupsOfOrg(org: $org, kind: $kind)')
        expect(text).toMatch(/\bstatus\s+kind\s+apiKeys\b/)
    })

    it('kindOf reads an absent, null or INTERNAL kind as INTERNAL and CUSTOMER as CUSTOMER', () => {
        expect(kindOf({ uuid: 'g1' })).toBe('INTERNAL')
        expect(kindOf({ uuid: 'g1', kind: null })).toBe('INTERNAL')
        expect(kindOf({ uuid: 'g1', kind: 'INTERNAL' })).toBe('INTERNAL')
        expect(kindOf({ uuid: 'g1', kind: 'CUSTOMER' })).toBe('CUSTOMER')
        expect(kindOf(null)).toBe('INTERNAL')
    })
})

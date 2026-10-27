import { describe, expect, it } from 'vitest'
import { API_KEY_GROUPS_OF_ORG_QUERY, loadApiKeyGroups } from './apiKeyGroupsQuery'

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
        expect(await loadApiKeyGroups(client, 'o1')).toEqual({ groups: [g], supported: true })
        expect(calls).toHaveLength(1)
        expect(calls[0].query).toBe(API_KEY_GROUPS_OF_ORG_QUERY)
        expect(calls[0].variables).toEqual({ org: 'o1' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
    })

    it('answers supported false with no groups on a drift-shaped error', async () => {
        expect(await loadApiKeyGroups(rejecting(validationError()), 'o1')).toEqual({ groups: [], supported: false })
    })

    it('rethrows a network error', async () => {
        const e = new Error('Failed to fetch')
        await expect(loadApiKeyGroups(rejecting(e), 'o1')).rejects.toBe(e)
    })

    it('rethrows a Not authorized GraphQL error', async () => {
        const e: any = new Error('Not authorized')
        e.errors = [{ message: 'Not authorized', extensions: { classification: 'DataFetchingException' } }]
        await expect(loadApiKeyGroups(rejecting(e), 'o1')).rejects.toBe(e)
    })
})

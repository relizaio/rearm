import { describe, expect, it } from 'vitest'
import {
    COMBINED_API_KEY_ORG_PERMISSIONS_QUERY, SET_API_KEY_GROUPS_OF_KEY_MUTATION, desiredGroupUuids,
    loadCombinedApiKeyOrgPermissions, membershipDiff, membershipRows, setApiKeyGroupsOfKey
} from './apiKeyGroupMembership'

// TPF-124 (architecture 4.5, test rows 16 and 19): the key side of API key group membership.

// The validationError() fixture of apiKeyGroupsQuery.spec.ts 7-14 (graphqlDriftFallback.spec.ts shape).
function validationError (field: string): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}
function notAuthorized (): any {
    const err: any = new Error('Not authorized')
    err.errors = [{ message: 'Not authorized', extensions: { classification: 'DataFetchingException' } }]
    return err
}

const G1 = { uuid: 'g1', name: 'ci', status: 'ACTIVE', apiKeys: ['k1'] }
const G2 = { uuid: 'g2', name: 'Alpha', status: 'ACTIVE', apiKeys: [] }
const G3 = { uuid: 'g3', name: 'old', status: 'INACTIVE', apiKeys: ['k1'] }
const G4 = { uuid: 'g4', name: 'zed', status: 'INACTIVE', apiKeys: [] }

describe('membershipRows (row 16)', () => {
    it('sorts by name base-insensitively and marks members and disabled rows', () => {
        const rows = membershipRows([G1, G2, G3, G4], 'k1')
        expect(rows.map(r => r.name)).toEqual(['Alpha', 'ci', 'old', 'zed'])
        expect(rows.filter(r => r.member).map(r => r.uuid)).toEqual(['g1', 'g3'])
        expect(rows.filter(r => r.disabled).map(r => r.uuid)).toEqual(['g4'])
        expect(rows.find(r => r.uuid === 'g3')?.disabled).toBe(false)
    })

    it('gives no rows for null groups and no member for a null key', () => {
        expect(membershipRows(null, 'k1')).toEqual([])
        expect(membershipRows([G1, G3], null).some(r => r.member)).toBe(false)
        expect(membershipRows([G1, G3], undefined).some(r => r.member)).toBe(false)
    })
})

describe('desiredGroupUuids and membershipDiff (row 16)', () => {
    const rows = membershipRows([G1, G2, G3, G4], 'k1')

    it('drops disabled and unknown uuids and duplicates', () => {
        expect(desiredGroupUuids(rows, ['g2', 'g4', 'g9', 'g2'])).toEqual(['g2'])
    })

    it('reports what a save would add and remove', () => {
        expect(membershipDiff(rows, ['g2', 'g3'])).toEqual({ added: ['g2'], removed: ['g1'] })
        expect(membershipDiff(rows, ['g1', 'g3'])).toEqual({ added: [], removed: [] })
    })
})

describe('loadCombinedApiKeyOrgPermissions (rows 16 and 19)', () => {
    it('returns the rows and supported true with the exported document and no cache', async () => {
        const perms = [{ org: 'o1', scope: 'ORGANIZATION', object: 'o1', type: 'READ_ONLY', sources: [{ kind: 'OWN' }] }]
        const calls: any[] = []
        const client = { query: async (opts: any) => { calls.push(opts); return { data: { combinedApiKeyOrgPermissions: { apiKeyUuid: 'k1', org: 'o1', permissions: perms } } } } }
        expect(await loadCombinedApiKeyOrgPermissions(client, 'o1', 'k1')).toEqual({ rows: perms, supported: true })
        expect(calls).toHaveLength(1)
        expect(calls[0].query).toBe(COMBINED_API_KEY_ORG_PERMISSIONS_QUERY)
        expect(calls[0].variables).toEqual({ orgUuid: 'o1', apiKeyUuid: 'k1' })
        expect(calls[0].fetchPolicy).toBe('no-cache')
    })

    it('answers supported false with no rows on a drift-shaped error', async () => {
        const client = { query: async () => { throw validationError('combinedApiKeyOrgPermissions') } }
        expect(await loadCombinedApiKeyOrgPermissions(client, 'o1', 'k1')).toEqual({ rows: [], supported: false })
    })

    it('rethrows a transport error and a refusal as the same object', async () => {
        for (const e of [new Error('Failed to fetch'), notAuthorized()]) {
            const client = { query: async () => { throw e } }
            await expect(loadCombinedApiKeyOrgPermissions(client, 'o1', 'k1')).rejects.toBe(e)
        }
    })
})

describe('setApiKeyGroupsOfKey (rows 16 and 19)', () => {
    it('sends the desired set and returns the groups', async () => {
        const groups = [{ uuid: 'g2', name: 'Alpha', status: 'ACTIVE', apiKeys: ['k1'] }]
        const calls: any[] = []
        const client = { mutate: async (opts: any) => { calls.push(opts); return { data: { setApiKeyGroupsOfKey: groups } } } }
        expect(await setApiKeyGroupsOfKey(client, 'o1', 'k1', ['g2'])).toEqual(groups)
        expect(calls).toHaveLength(1)
        expect(calls[0].mutation).toBe(SET_API_KEY_GROUPS_OF_KEY_MUTATION)
        expect(calls[0].variables).toEqual({ orgUuid: 'o1', apiKeyUuid: 'k1', groupUuids: ['g2'] })
    })

    it('rethrows every error, the drift fixture included', async () => {
        for (const e of [validationError('setApiKeyGroupsOfKey'), new Error('Failed to fetch'), notAuthorized(), new Error('API key group is inactive: g4')]) {
            const client = { mutate: async () => { throw e } }
            await expect(setApiKeyGroupsOfKey(client, 'o1', 'k1', ['g2'])).rejects.toBe(e)
        }
    })
})

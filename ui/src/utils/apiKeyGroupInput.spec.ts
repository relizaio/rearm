import { describe, expect, it, vi } from 'vitest'
import {
    apiKeyGroupsOfKey, buildApiKeyGroupUpdateInput, freeformKeyOptions, permissionsFromScopedModel, scopedModelFromPermissions
} from './apiKeyGroupInput'

// TPF-92 (architecture 4.5, test row 13): the pure parts of the API Key Groups editor.

const ORG = 'o1'

describe('buildApiKeyGroupUpdateInput (row 13 a)', () => {
    it('sends the whole member list, the permissions, and no approvals', () => {
        const p = { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_ONLY', functions: [], approvals: [] }
        const input = buildApiKeyGroupUpdateInput({ uuid: 'g1' }, [p])
        expect(input.groupId).toBe('g1')
        expect(input.apiKeys).toEqual([])
        expect(input.permissions).toEqual([p])
        expect('approvals' in input).toBe(false)
        expect(input.status).toBeUndefined()
    })

    it('passes apiKeys and status through unchanged', () => {
        const input = buildApiKeyGroupUpdateInput({ uuid: 'g1', name: 'ci', apiKeys: ['k1', 'k2'], status: 'ACTIVE' }, [])
        expect(input.apiKeys).toEqual(['k1', 'k2'])
        expect(input.status).toBe('ACTIVE')
        expect(input.name).toBe('ci')
    })
})

describe('permissionsFromScopedModel (row 13 b)', () => {
    it('yields no ORGANIZATION row for NONE', () => {
        expect(permissionsFromScopedModel({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] }, ORG)).toEqual([])
    })

    it('puts the ORGANIZATION row first and drops NONE scoped rows', () => {
        const out = permissionsFromScopedModel({
            orgPermission: { type: 'READ_WRITE', functions: ['RESOURCE'], approvals: ['QA'] },
            scopedPermissions: [
                { scope: 'COMPONENT', objectId: 'c1', objectName: 'api', type: 'READ_ONLY' },
                { scope: 'PERSPECTIVE', objectId: 'p1', type: 'NONE', functions: ['X'] },
                { scope: 'BOARD', objectId: 'b1', type: 'READ_WRITE', functions: ['AGENT'], approvals: ['A'] },
            ],
        }, ORG)
        expect(out).toEqual([
            { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_WRITE', functions: ['RESOURCE'], approvals: ['QA'] },
            { scope: 'COMPONENT', objectId: 'c1', type: 'READ_ONLY', functions: [], approvals: [] },
            { scope: 'BOARD', objectId: 'b1', type: 'READ_WRITE', functions: ['AGENT'], approvals: ['A'] },
        ])
    })
})

describe('scopedModelFromPermissions (row 13 c)', () => {
    it('builds the editor model from the group permissions of this org only', async () => {
        const nameOf = vi.fn(async (scope: string, id: string) => `${scope}:${id}`)
        const model = await scopedModelFromPermissions([
            { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'READ_ONLY', functions: ['AGENT'], approvals: null },
            { org: ORG, scope: 'COMPONENT', object: 'c1', type: 'READ_WRITE', functions: null, approvals: ['QA'] },
            { org: 'other', scope: 'COMPONENT', object: 'c2', type: 'READ_WRITE' },
            { org: ORG, scope: 'RESOURCE_GROUP', object: 'r1', type: 'READ_WRITE' },
        ], ORG, nameOf)
        expect(model.orgPermission).toEqual({ type: 'READ_ONLY', functions: ['AGENT'], approvals: [] })
        expect(model.scopedPermissions).toEqual([
            { scope: 'COMPONENT', objectId: 'c1', objectName: 'COMPONENT:c1', type: 'READ_WRITE', functions: [], approvals: ['QA'] },
        ])
        expect(nameOf).toHaveBeenCalledTimes(1)
    })

    it('gives the NONE model for null', async () => {
        expect(await scopedModelFromPermissions(null, ORG, async () => 'x')).toEqual({
            orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [],
        })
    })
})

describe('freeformKeyOptions (row 13 d)', () => {
    const keys = [
        { uuid: 'aaaa1111-0000', type: 'FREEFORM', status: 'ACTIVE', declaredName: 'zeta' },
        { uuid: 'bbbb2222-0000', type: 'FREEFORM', status: 'REQUESTED', declaredName: 'Alpha' },
        { uuid: 'cccc3333-0000', type: 'FREEFORM', status: 'INACTIVE', declaredName: 'beta' },
        { uuid: 'dddd4444-0000', type: 'FREEFORM', status: 'DENIED', declaredName: 'gamma' },
        { uuid: 'eeee5555-0000', type: 'USER', status: 'ACTIVE', declaredName: 'user' },
        { uuid: 'ffff6666-0000', type: 'COMPONENT', status: 'ACTIVE' },
        { uuid: '99997777-0000', type: 'EXTERNAL', status: 'ACTIVE' },
    ]

    it('keeps FREEFORM keys only, with their status, sorted by label', () => {
        const options = freeformKeyOptions(keys, keys)
        expect(options.map(o => o.value)).toEqual(['bbbb2222-0000', 'cccc3333-0000', 'dddd4444-0000', 'aaaa1111-0000'])
        expect(options.map(o => o.status)).toEqual(['REQUESTED', 'INACTIVE', 'DENIED', 'ACTIVE'])
        expect(options[0].label).toBe('Alpha · bbbb2222')
    })

    it('adds a fallback option for a member outside the key list', () => {
        const details = [{ uuid: '12345678-aaaa', type: 'FREEFORM', status: 'INACTIVE', declaredName: 'old ci' }]
        const options = freeformKeyOptions(keys, keys, ['aaaa1111-0000', '12345678-aaaa', '87654321-bbbb'], details)
        const fromDetails = options.find(o => o.value === '12345678-aaaa')!
        expect(fromDetails.label).toBe('old ci · 12345678')
        expect(fromDetails.status).toBe('INACTIVE')
        const unknown = options.find(o => o.value === '87654321-bbbb')!
        expect(unknown.label).toBe('Key · 87654321 · unknown key')
        expect(unknown.label.endsWith('unknown key')).toBe(true)
        expect(options.filter(o => o.value === 'aaaa1111-0000')).toHaveLength(1)
    })
})

describe('apiKeyGroupsOfKey (row 13 e)', () => {
    it('returns the ACTIVE groups holding the key, sorted by name base-insensitively', () => {
        const groups = [
            { uuid: 'g1', name: 'b', status: 'ACTIVE', apiKeys: ['k'] },
            { uuid: 'g2', name: 'A', status: 'ACTIVE', apiKeys: ['x', 'k'] },
            { uuid: 'g3', name: 'c', status: 'ACTIVE', apiKeys: ['k'] },
            { uuid: 'g4', name: 'inactive', status: 'INACTIVE', apiKeys: ['k'] },
            { uuid: 'g5', name: 'other', status: 'ACTIVE', apiKeys: ['x'] },
        ]
        expect(apiKeyGroupsOfKey(groups, 'k')).toEqual([
            { uuid: 'g2', name: 'A' }, { uuid: 'g1', name: 'b' }, { uuid: 'g3', name: 'c' },
        ])
    })

    it('gives [] on empty input', () => {
        expect(apiKeyGroupsOfKey([], 'k')).toEqual([])
        expect(apiKeyGroupsOfKey(null, 'k')).toEqual([])
    })
})

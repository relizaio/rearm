import { describe, expect, it } from 'vitest'
import {
    MY_PERMISSION_TEMPLATES_QUERY, REDUCTIONS_SENTENCE, clipModelToBounds, countCodePoints, loadMyPermissionTemplates,
    templateInputFromModel, templateOptions, templateSummary, templateUpdateInput
} from './permissionTemplates'

// TPF-113 (architecture 5, test row 10): the pure parts of the permission templates UI.

// The validationError() fixture of graphqlDriftFallback.spec.ts 20-26.
function validationError (field = 'myPermissionTemplates'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}
const rejecting = (e: any) => ({ query: async () => { throw e } })

const ORG = 'o1'
const model = (type: string, orgFunctions: string[] = [], scoped: any[] = []) => ({
    orgPermission: { type, functions: orgFunctions, approvals: [] },
    scopedPermissions: scoped,
})
const row = (scope: string, objectId: string, objectName: string, functions: string[], type = 'READ_WRITE') =>
    ({ scope, objectId, objectName, type, functions, approvals: [] })

describe('clipModelToBounds (row 10)', () => {
    it('reduces an organization-wide ADMIN to READ_WRITE with the text', () => {
        const r = clipModelToBounds(model('ADMIN'), 'READ_WRITE')
        expect(r.model.orgPermission.type).toBe('READ_WRITE')
        expect(r.reductions).toEqual(['organization-wide ADMIN reduced to READ_WRITE'])
    })

    it('intersects the organization row functions with orgFunctions (an owner holding a function on one component only)', () => {
        // the owner holds RESOURCE on component c1 only: the union allows it, the organization row does not
        const m = model('READ_WRITE', ['RESOURCE', 'SBOM_PROBING'], [row('COMPONENT', 'c1', 'api', ['RESOURCE'])])
        const r = clipModelToBounds(m, 'READ_WRITE', ['RESOURCE'], [])
        expect(r.model.orgPermission.functions).toEqual([])
        expect(r.model.scopedPermissions[0].functions).toEqual(['RESOURCE'])
        expect(r.reductions).toEqual(['organization-wide functions reduced to none'])
    })

    it('intersects each scoped row functions with allowedFunctions, never re-typing or dropping the row', () => {
        const m = model('NONE', [], [
            row('COMPONENT', 'c1', 'api', ['RESOURCE', 'SBOM_PROBING'], 'ADMIN'),
            row('PERSPECTIVE', 'p1', 'prod', ['RESOURCE']),
        ])
        const r = clipModelToBounds(m, 'READ_ONLY', ['RESOURCE'], [])
        expect(r.model.scopedPermissions).toEqual([
            row('COMPONENT', 'c1', 'api', ['RESOURCE'], 'ADMIN'),
            row('PERSPECTIVE', 'p1', 'prod', ['RESOURCE']),
        ])
        expect(r.reductions).toEqual(['component api functions reduced to RESOURCE'])
    })

    it('leaves a model within bounds untouched with no reductions, and does not change its input', () => {
        const m = model('READ_ONLY', ['RESOURCE'], [row('COMPONENT', 'c1', 'api', ['RESOURCE'])])
        const before = JSON.parse(JSON.stringify(m))
        const r = clipModelToBounds(m, 'READ_WRITE', ['RESOURCE'], ['RESOURCE'])
        expect(r.model).toEqual(before)
        expect(r.reductions).toEqual([])
        const clipped = clipModelToBounds(model('ADMIN', ['RESOURCE']), 'READ_ONLY', [], [])
        expect(clipped.reductions).toHaveLength(2)
        expect(m).toEqual(before)
    })

    it('with every bound undefined returns the model unchanged', () => {
        const m = model('ADMIN', ['RESOURCE'], [row('COMPONENT', 'c1', 'api', ['RESOURCE', 'SBOM_PROBING'], 'ADMIN')])
        const r = clipModelToBounds(m, undefined, undefined, undefined)
        expect(r.model).toEqual(m)
        expect(r.reductions).toEqual([])
    })

    it('the sentence under the picker is the design text', () => {
        expect(REDUCTIONS_SENTENCE).toBe('Reduced to what you hold here. The server may reduce this further, object grants and functions included.')
    })
})

describe('templateInputFromModel and templateUpdateInput (row 10)', () => {
    const m = model('READ_WRITE', ['RESOURCE'], [row('COMPONENT', 'c1', 'api', ['RESOURCE']), row('PERSPECTIVE', 'p1', 'prod', [], 'NONE')])
    const rows = [
        { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
        { scope: 'COMPONENT', objectId: 'c1', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
    ]

    it('trims the name and carries the ORGANIZATION row and the scoped rows', () => {
        expect(templateInputFromModel('  ci  ', 'for CI', ORG, m)).toEqual({ org: ORG, name: 'ci', description: 'for CI', permissions: rows })
    })

    it('the update input carries the uuid and the whole permission set', () => {
        expect(templateUpdateInput('t1', ' ci ', '', m, ORG)).toEqual({ uuid: 't1', name: 'ci', description: '', permissions: rows })
    })
})

describe('templateOptions and templateSummary (row 10)', () => {
    it('sorts the options base-insensitively', () => {
        const opts = templateOptions([{ uuid: 'b', name: 'beta' }, { uuid: 'a', name: 'Alpha' }, { uuid: 'c', name: 'alpine' }])
        expect(opts).toEqual([{ label: 'Alpha', value: 'a' }, { label: 'alpine', value: 'c' }, { label: 'beta', value: 'b' }])
        expect(templateOptions(null)).toEqual([])
    })

    it('summarises the organization-wide level and the grants', () => {
        const t = { permissions: { permissions: [
            { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'ADMIN' },
            { org: ORG, scope: 'COMPONENT', object: 'c1', type: 'READ_ONLY' },
            { org: ORG, scope: 'BOARD', object: 'b1', type: 'READ_ONLY' },
            { org: ORG, scope: 'PERSPECTIVE', object: 'p1', type: 'READ_ONLY' },
        ] } }
        expect(templateSummary(t, ORG)).toBe('ADMIN \u00B7 3 grants')
        expect(templateSummary({ permissions: { permissions: [] } }, ORG)).toBe('none')
    })

    it('counts code points, not UTF-16 units', () => {
        expect(countCodePoints('\u{1F600}'.repeat(3))).toBe(3)
        expect(countCodePoints('')).toBe(0)
    })
})

describe('loadMyPermissionTemplates (row 10)', () => {
    it('returns the list and supported true on success', async () => {
        const t = { uuid: 't1', name: 'ci' }
        const calls: any[] = []
        const client = { query: async (opts: any) => { calls.push(opts); return { data: { myPermissionTemplates: [t] } } } }
        expect(await loadMyPermissionTemplates(client, ORG)).toEqual({ templates: [t], supported: true })
        expect(calls[0].query).toBe(MY_PERMISSION_TEMPLATES_QUERY)
        expect(calls[0].variables).toEqual({ org: ORG })
        expect(calls[0].fetchPolicy).toBe('no-cache')
    })

    it('answers supported false with no templates on the drift fixture', async () => {
        expect(await loadMyPermissionTemplates(rejecting(validationError()), ORG)).toEqual({ templates: [], supported: false })
    })

    it('rethrows a plain error', async () => {
        await expect(loadMyPermissionTemplates(rejecting(new Error('Not authorized')), ORG)).rejects.toThrow('Not authorized')
    })
})

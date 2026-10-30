import { describe, expect, it } from 'vitest'
import { print } from 'graphql'
import {
    CREATE_PERMISSION_TEMPLATE_MUTATION, DELETE_PERMISSION_TEMPLATE_MUTATION, MY_PERMISSION_TEMPLATES_QUERY,
    PERM_ORDER, UPDATE_PERMISSION_TEMPLATE_MUTATION, clipModelToBounds, countCodePoints, grantsText,
    loadMyPermissionTemplates, templateGrantCount, templateInputFromModel, templateOptions, templateOrgType,
    templateSummary, templateUpdateInput
} from './permissionTemplates'

// TPF-113 mutation round: the edges of the pure parts that permissionTemplates.spec.ts leaves open, and the
// four documents themselves (the specs mock the client, so nothing else reads what the documents select).

const ORG = 'o1'
const model = (type: string | undefined, orgFunctions: string[] | undefined, scoped: any[] | undefined): any => ({
    orgPermission: { type, functions: orgFunctions, approvals: [] },
    scopedPermissions: scoped,
})
const row = (scope: string, objectId: string, objectName: string | undefined, functions: string[] | undefined, type = 'READ_WRITE'): any =>
    ({ scope, objectId, objectName, type, functions, approvals: [] })

/** The operation, its variable definitions, and the selection of its one root field, as plain text. */
function shape (doc: any) {
    const op = doc.definitions[0]
    const root = op.selectionSet.selections[0]
    return {
        kind: op.operation,
        name: op.name.value,
        variables: op.variableDefinitions.map((v: any) => print(v)),
        root: root.name.value,
        args: (root.arguments || []).map((a: any) => `${a.name.value}: ${print(a.value)}`),
        fields: (root.selectionSet?.selections || []).map((s: any) => s.selectionSet
            ? `${s.name.value} { ${s.selectionSet.selections.map((t: any) => t.selectionSet
                ? `${t.name.value} { ${t.selectionSet.selections.map((u: any) => u.name.value).join(' ')} }` : t.name.value).join(' ')} }`
            : s.name.value),
    }
}
const TEMPLATE_FIELDS = ['uuid', 'name', 'description', 'org', 'user',
    'permissions { permissions { org scope object type meta approvals functions } }', 'createdDate', 'lastUpdatedBy']

describe('the four documents', () => {
    it('the list query asks for the caller\'s templates of one organization, with the whole record', () => {
        expect(shape(MY_PERMISSION_TEMPLATES_QUERY)).toEqual({
            kind: 'query', name: 'myPermissionTemplates', variables: ['$org: ID'],
            root: 'myPermissionTemplates', args: ['org: $org'], fields: TEMPLATE_FIELDS,
        })
    })

    it('create and update take their whole input and return the whole record', () => {
        expect(shape(CREATE_PERMISSION_TEMPLATE_MUTATION)).toEqual({
            kind: 'mutation', name: 'createPermissionTemplate', variables: ['$template: CreatePermissionTemplateInput!'],
            root: 'createPermissionTemplate', args: ['template: $template'], fields: TEMPLATE_FIELDS,
        })
        expect(shape(UPDATE_PERMISSION_TEMPLATE_MUTATION)).toEqual({
            kind: 'mutation', name: 'updatePermissionTemplate', variables: ['$template: UpdatePermissionTemplateInput!'],
            root: 'updatePermissionTemplate', args: ['template: $template'], fields: TEMPLATE_FIELDS,
        })
    })

    it('delete takes a required uuid and selects nothing', () => {
        expect(shape(DELETE_PERMISSION_TEMPLATE_MUTATION)).toEqual({
            kind: 'mutation', name: 'deletePermissionTemplate', variables: ['$uuid: ID!'],
            root: 'deletePermissionTemplate', args: ['uuid: $uuid'], fields: [],
        })
    })
})

describe('the organization-wide order and the clip of the level', () => {
    it('lists the levels lowest first', () => {
        expect(PERM_ORDER).toEqual(['NONE', 'ESSENTIAL_READ', 'READ_ONLY', 'READ_WRITE', 'ADMIN'])
    })

    it('reduces a level only when it is above the bound, for every pair of neighbouring levels', () => {
        const cases: [string, string, boolean][] = [
            ['ESSENTIAL_READ', 'NONE', true], ['NONE', 'ESSENTIAL_READ', false], ['ESSENTIAL_READ', 'ESSENTIAL_READ', false],
            ['READ_ONLY', 'ESSENTIAL_READ', true], ['ESSENTIAL_READ', 'READ_ONLY', false], ['READ_ONLY', 'READ_ONLY', false],
            ['READ_WRITE', 'READ_ONLY', true], ['READ_ONLY', 'READ_WRITE', false], ['READ_WRITE', 'READ_WRITE', false],
            ['ADMIN', 'READ_WRITE', true], ['READ_WRITE', 'ADMIN', false], ['ADMIN', 'ADMIN', false],
        ]
        for (const [type, max, reduced] of cases) {
            const r = clipModelToBounds(model(type, [], []), max)
            expect(r.model.orgPermission.type, `${type} under ${max}`).toBe(reduced ? max : type)
            expect(r.reductions, `${type} under ${max}`).toEqual(reduced ? [`organization-wide ${type} reduced to ${max}`] : [])
        }
    })

    it('a model without a level is NONE and is never reduced', () => {
        const r = clipModelToBounds(model(undefined, [], []), 'ESSENTIAL_READ')
        expect(r.reductions).toEqual([])
        expect(r.model.orgPermission.type).toBeUndefined()
    })

    it('does not change its input when it reduces, and answers a copy', () => {
        const m = model('ADMIN', ['RESOURCE', 'SBOM_PROBING'], [row('COMPONENT', 'c1', 'api', ['RESOURCE', 'SBOM_PROBING'])])
        const before = JSON.parse(JSON.stringify(m))
        const r = clipModelToBounds(m, 'READ_ONLY', ['RESOURCE'], ['SBOM_PROBING'])
        expect(r.reductions).toHaveLength(3)
        expect(m).toEqual(before)
        expect(r.model).not.toBe(m)
        expect(r.model.orgPermission).not.toBe(m.orgPermission)
        expect(r.model.scopedPermissions[0]).not.toBe(m.scopedPermissions[0])
    })

    it('a missing model, a missing level row and a missing list become an empty NONE model', () => {
        expect(clipModelToBounds(null as any, 'READ_ONLY', [], []).model).toEqual({
            orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [],
        })
        const r = clipModelToBounds({ orgPermission: null, scopedPermissions: undefined } as any, undefined, ['RESOURCE'], ['RESOURCE'])
        expect(r.model).toEqual({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })
        expect(r.reductions).toEqual([])
    })
})

describe('the clip of the functions', () => {
    it('keeps the allowed ones in their order and names what is left, joined by comma and space', () => {
        const r = clipModelToBounds(model('READ_WRITE', ['A', 'B', 'C'], []), undefined, undefined, ['A', 'B'])
        expect(r.model.orgPermission.functions).toEqual(['A', 'B'])
        expect(r.reductions).toEqual(['organization-wide functions reduced to A, B'])
    })

    it('says nothing when every function is kept, and nothing when the bound is wider than the set', () => {
        const r = clipModelToBounds(model('READ_WRITE', ['A', 'B'], [row('COMPONENT', 'c1', 'api', ['A'])]), undefined, ['A', 'B'], ['A', 'B', 'C'])
        expect(r.reductions).toEqual([])
        expect(r.model.orgPermission.functions).toEqual(['A', 'B'])
        expect(r.model.scopedPermissions[0].functions).toEqual(['A'])
    })

    it('reduces every scoped row, names each one by its lowercase scope and name, and falls back to the uuid', () => {
        const r = clipModelToBounds(model('NONE', [], [
            row('COMPONENT', 'c1', 'api', ['A', 'B', 'C']),
            row('BOARD', 'b-uuid', undefined, ['A', 'D']),
            row('PERSPECTIVE', 'p1', 'prod', ['D']),
            row('INSTANCE', 'i1', 'host', ['A']),
        ]), undefined, ['A', 'B'])
        expect(r.model.scopedPermissions.map((s: any) => s.functions)).toEqual([['A', 'B'], ['A'], [], ['A']])
        expect(r.reductions).toEqual([
            'component api functions reduced to A, B',
            'board b-uuid functions reduced to A',
            'perspective prod functions reduced to none',
        ])
    })

    it('a model whose rows carry no function list is not reduced and does not fail', () => {
        const m = model('READ_ONLY', undefined, [row('COMPONENT', 'c1', 'api', undefined)])
        const r = clipModelToBounds(m, 'READ_WRITE', ['A'], ['A'])
        expect(r.reductions).toEqual([])
    })

    it('a bound of nothing removes every function', () => {
        const r = clipModelToBounds(model('READ_ONLY', ['A'], [row('COMPONENT', 'c1', 'api', ['A'])]), undefined, [], [])
        expect(r.model.orgPermission.functions).toEqual([])
        expect(r.model.scopedPermissions[0].functions).toEqual([])
        expect(r.reductions).toEqual(['organization-wide functions reduced to none', 'component api functions reduced to none'])
    })

    it('never touches the type of a row or the rows themselves', () => {
        const r = clipModelToBounds(model('ADMIN', [], [row('COMPONENT', 'c1', 'api', ['A', 'B'], 'ADMIN'), row('PERSPECTIVE', 'p1', 'prod', ['A'], 'READ_ONLY')]),
            'READ_ONLY', ['A'], [])
        expect(r.model.scopedPermissions.map((s: any) => [s.scope, s.objectId, s.type])).toEqual([['COMPONENT', 'c1', 'ADMIN'], ['PERSPECTIVE', 'p1', 'READ_ONLY']])
    })
})

describe('the inputs', () => {
    const m = model('READ_ONLY', [], [])

    it('a description that is null or missing is sent as it is, not as an empty string', () => {
        expect(templateInputFromModel('ci', null, ORG, m).description).toBeNull()
        expect(templateInputFromModel('ci', undefined, ORG, m).description).toBeUndefined()
        expect(templateUpdateInput('t1', 'ci', null, m, ORG).description).toBeNull()
        expect(templateUpdateInput('t1', 'ci', undefined, m, ORG).description).toBeUndefined()
        expect(templateInputFromModel('ci', '', ORG, m).description).toBe('')
    })

    it('a missing name is sent as empty text', () => {
        expect(templateInputFromModel(undefined as any, '', ORG, m).name).toBe('')
        expect(templateUpdateInput('t1', undefined as any, '', m, ORG).name).toBe('')
    })

    it('the update names the template it changes, and the create names the organization', () => {
        expect(Object.keys(templateUpdateInput('t1', 'ci', '', m, ORG)).sort()).toEqual(['description', 'name', 'permissions', 'uuid'])
        expect(Object.keys(templateInputFromModel('ci', '', ORG, m)).sort()).toEqual(['description', 'name', 'org', 'permissions'])
        expect(templateUpdateInput('t1', 'ci', '', m, ORG).uuid).toBe('t1')
    })
})

describe('the summary of a template', () => {
    const t = (...rows: any[]) => ({ permissions: { permissions: rows } })
    const r = (scope: string, object: string, type: string, org = ORG) => ({ org, scope, object, type })

    it('the level is the ORGANIZATION row of this organization, whatever comes before it', () => {
        const template = t(r('PERSPECTIVE', ORG, 'READ_WRITE'), r('ORGANIZATION', 'o2', 'ADMIN', 'o2'), r('ORGANIZATION', ORG, 'READ_ONLY'))
        expect(templateOrgType(template, ORG)).toBe('READ_ONLY')
        expect(templateOrgType(template, 'o2')).toBe('ADMIN')
    })

    it('a template without a row, or with a row without a level, is NONE', () => {
        expect(templateOrgType(t(), ORG)).toBe('NONE')
        expect(templateOrgType(t(r('COMPONENT', 'c1', 'ADMIN')), ORG)).toBe('NONE')
        expect(templateOrgType(t({ org: ORG, scope: 'ORGANIZATION', object: ORG }), ORG)).toBe('NONE')
        expect(templateOrgType(null, ORG)).toBe('NONE')
    })

    it('counts the grants of this organization only, of the scopes the editor keeps', () => {
        const template = t(r('ORGANIZATION', ORG, 'ADMIN'), r('COMPONENT', 'c1', 'READ_ONLY'), r('COMPONENT', 'c2', 'READ_ONLY', 'o2'),
            r('INSTANCE', 'i1', 'READ_ONLY'), r('PRODUCT_NOT_A_SCOPE', 'x', 'READ_ONLY'))
        expect(templateGrantCount(template, ORG)).toBe(2)
        expect(templateGrantCount(template, 'o2')).toBe(1)
        expect(templateGrantCount(null, ORG)).toBe(0)
    })

    it('writes the grants in the singular for one and the plural otherwise', () => {
        expect([0, 1, 2, 11].map(grantsText)).toEqual(['0 grants', '1 grant', '2 grants', '11 grants'])
    })

    it('a level with no grants, and grants with no level, are both summarised', () => {
        expect(templateSummary(t(r('ORGANIZATION', ORG, 'ADMIN')), ORG)).toBe('ADMIN · 0 grants')
        expect(templateSummary(t(r('COMPONENT', 'c1', 'READ_ONLY')), ORG)).toBe('NONE · 1 grant')
        expect(templateSummary(t(r('ORGANIZATION', ORG, 'NONE')), ORG)).toBe('none')
        expect(templateSummary(t(r('ORGANIZATION', ORG, 'READ_ONLY'), r('BOARD', 'b1', 'READ_ONLY'), r('PERSPECTIVE', 'p1', 'READ_ONLY')), ORG))
            .toBe('READ_ONLY · 2 grants')
    })
})

describe('the options and the count', () => {
    it('names that differ only in case, or only in an accent, keep the order they were listed in', () => {
        const same = (names: string[]) => templateOptions(names.map((name, i) => ({ uuid: 'u' + i, name }))).map(o => o.label)
        expect(same(['Zed', 'zed'])).toEqual(['Zed', 'zed'])
        expect(same(['zed', 'Zed'])).toEqual(['zed', 'Zed'])
        expect(same(['résumé', 'resume'])).toEqual(['résumé', 'resume'])
    })

    it('a template without a name sorts first and does not fail the list', () => {
        expect(templateOptions([{ uuid: 'b', name: 'beta' }, { uuid: 'x' }, { uuid: 'a', name: 'alpha' }]).map(o => o.value)).toEqual(['x', 'a', 'b'])
        expect(templateOptions([{ uuid: 'a', name: 'alpha' }, { uuid: 'x' }]).map(o => o.value)).toEqual(['x', 'a'])
    })

    it('does not reorder the list it is given', () => {
        const given = [{ uuid: 'b', name: 'beta' }, { uuid: 'a', name: 'alpha' }]
        templateOptions(given)
        expect(given.map(g => g.uuid)).toEqual(['b', 'a'])
    })

    it('counts a missing text as zero code points', () => {
        expect(countCodePoints(undefined as any)).toBe(0)
        expect(countCodePoints('abc')).toBe(3)
        expect(countCodePoints('a\u{1F600}b')).toBe(3)
    })
})

describe('loading', () => {
    it('answers an empty list, still supported, when the server answers nothing', async () => {
        for (const data of [{ myPermissionTemplates: null }, {}, null]) {
            const client = { query: async () => ({ data }) }
            expect(await loadMyPermissionTemplates(client, ORG)).toEqual({ templates: [], supported: true })
        }
        expect(await loadMyPermissionTemplates({ query: async () => null }, ORG)).toEqual({ templates: [], supported: true })
    })

    it('passes the organization it was asked about, also when it is none', async () => {
        const seen: any[] = []
        const client = { query: async (o: any) => { seen.push(o.variables); return { data: { myPermissionTemplates: [] } } } }
        await loadMyPermissionTemplates(client, null)
        await loadMyPermissionTemplates(client, 'o9')
        expect(seen).toEqual([{ org: null }, { org: 'o9' }])
    })
})

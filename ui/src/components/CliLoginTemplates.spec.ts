// @vitest-environment happy-dom
//
// TPF-113 mutation round: the template picker as CliLogin hosts it. permissionTemplatesWiring.spec.ts reads the
// source for what is bound; this runs the page: the bounds the owner's permissions give the picker, what a pick
// does to the form and to the reductions block, what a change of organization does to both, and the names the
// picker is given for the grants of a template.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const ORG = 'o1'
const state: any = {}
const query = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
const mutate = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q), mutate: (m: any) => mutate(m) } }))
vi.mock('../utils/graphql', () => ({ default: { query: (q: any) => query(q), mutate: (m: any) => mutate(m) } }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { user_code: 'ABCD-EFGH' } }) }))
const dispatch = vi.fn(async (name: string, arg?: any): Promise<any> => name === 'fetchComponent' ? state.fetchComponent(arg) : [])
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch: (name: string, arg?: any) => dispatch(name, arg),
        getters: {
            get myuser () { return state.myuser },
            allOrganizations: [{ uuid: 'o1', name: 'Org one' }, { uuid: 'o2', name: 'Org two' }],
            orgById: () => ({ approvalRoles: [] }),
            componentsOfOrg: () => state.components,
            productsOfOrg: () => state.products,
            instancesOfOrg: () => state.instances,
        },
    }),
}))
const notifyError = vi.fn()
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useNotification: () => ({ error: notifyError, success: vi.fn(), warning: vi.fn(), info: vi.fn() }) }
})

const { default: CliLogin } = await import('./CliLogin.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')
const { default: Picker } = await import('./PermissionTemplatePicker.vue')
const { REDUCTIONS_SENTENCE } = await import('@/utils/permissionTemplates')
const { NSelect } = await import('naive-ui')

const row = (scope: string, object: string, type: string, functions: string[] = [], org = ORG) => ({ org, scope, object, type, functions, approvals: [] })
const ADMIN_T = {
    uuid: 't-admin', name: 'deploy', description: '', org: ORG, user: 'u1',
    permissions: { permissions: [
        row('ORGANIZATION', ORG, 'ADMIN', ['SBOM_PROBING', 'RESOURCE']),
        row('COMPONENT', 'c1', 'READ_WRITE', ['RESOURCE', 'DOWNLOAD', 'ARTIFACT_DOWNLOAD']),
    ] },
}
const PLAIN_T = { uuid: 't-plain', name: 'plain', description: '', org: ORG, user: 'u1', permissions: { permissions: [row('ORGANIZATION', ORG, 'READ_ONLY')] } }
const NAMES_T = {
    uuid: 't-names', name: 'names', description: '', org: ORG, user: 'u1',
    permissions: { permissions: [
        row('COMPONENT', 'c1', 'READ_ONLY'), row('COMPONENT', 'p9', 'READ_ONLY'), row('COMPONENT', 'cx', 'READ_ONLY'),
        row('COMPONENT', 'cy', 'READ_ONLY'), row('COMPONENT', 'cz', 'READ_ONLY'),
        row('BOARD', 'b1', 'READ_ONLY'),
        row('PERSPECTIVE', 'p1', 'READ_ONLY'), row('PERSPECTIVE', 'p2', 'READ_ONLY'),
        row('INSTANCE', 'i1', 'READ_ONLY'), row('INSTANCE', 'i2', 'READ_ONLY'), row('INSTANCE', 'ci1', 'READ_ONLY'), row('INSTANCE', 'ci2', 'READ_ONLY'),
        row('INSTANCE', 'ci3', 'READ_ONLY'), row('INSTANCE', 'cl1', 'READ_ONLY'), row('INSTANCE', 'cl2', 'READ_ONLY'),
        row('INSTANCE', 'i-old', 'READ_ONLY'), row('INSTANCE', 'i-gone', 'READ_ONLY'),
    ] },
}

function resetState () {
    state.myuser = { uuid: 'u1', installationType: 'SAAS', isGlobalAdmin: false }
    state.ownerRows = [row('ORGANIZATION', ORG, 'READ_WRITE', ['RESOURCE']), row('COMPONENT', 'c1', 'READ_ONLY', ['DOWNLOAD'])]
    state.ownerFails = false
    state.templates = [ADMIN_T, PLAIN_T, NAMES_T]
    state.components = [{ uuid: 'c1', name: 'api' }]
    state.products = [{ uuid: 'p9', name: 'Shop' }]
    state.instances = [
        { uuid: 'i1', revision: -1, status: 'ACTIVE', instanceType: 'STANDALONE_INSTANCE', uri: 'https://i1', name: 'n1' },
        { uuid: 'i2', revision: -1, instanceType: 'STANDALONE_INSTANCE', name: 'n2' },
        { uuid: 'ci1', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER_INSTANCE', namespace: 'ns' },
        { uuid: 'ci2', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER_INSTANCE', namespace: 'ns2' },
        { uuid: 'ci3', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER_INSTANCE' },
        { uuid: 'cl1', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER', name: 'Prod cluster', uri: 'https://cl', instances: ['ci1'] },
        { uuid: 'cl2', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER', uri: 'https://cl2' },
        { uuid: 'i-old', revision: 3, status: 'ACTIVE', instanceType: 'STANDALONE_INSTANCE', uri: 'https://old' },
        { uuid: 'i-gone', revision: -1, status: 'ARCHIVED', instanceType: 'STANDALONE_INSTANCE', uri: 'https://gone' },
    ]
    state.fetchComponent = (uuid: string) => {
        if (uuid === 'cx') return { uuid, name: 'Fetched' }
        if (uuid === 'cy') throw new Error('gone')
        return {}
    }
}
function wire () {
    query.mockImplementation(async (q: any) => {
        const body = q.query.loc?.source.body || ''
        if (body.includes('cliLoginRequest(')) return { data: { cliLoginRequest: { uuid: 'r1', userCode: 'ABCD-EFGH', status: 'PENDING', requestedFrom: 'host', deviceInfo: {} } } }
        if (body.includes('myApiKeys')) return { data: { myApiKeys: [] } }
        if (body.includes('perspectives(')) return { data: { perspectives: [{ uuid: 'p1', name: 'prod', org: q.variables.org, type: 'X' }] } }
        if (body.includes('combinedUserOrgPermissions(')) {
            if (state.ownerFails) throw new Error('permissions refused')
            return { data: { combinedUserOrgPermissions: { permissions: state.ownerRows } } }
        }
        if (body.includes('myPermissionTemplates(')) return { data: { myPermissionTemplates: state.templates } }
        return { data: {} }
    })
}

const global = { stubs: { ScopedPermissions: true } }
async function open () {
    const w = mount(CliLogin, { global })
    await flushPromises()
    await flushPromises()
    return w
}
const picker = (w: any) => w.findComponent(Picker)
const editor = (w: any) => w.findComponent(ScopedPermissions)
const block = (w: any) => w.find('[data-testid="template-reductions"]')
async function pickTemplate (w: any, uuid: string) {
    picker(w).findComponent(NSelect).vm.$emit('update:value', uuid)
    await flushPromises()
}
const bounds = (w: any) => ({
    maxOrgType: picker(w).props('maxOrgType'),
    allowedFunctions: picker(w).props('allowedFunctions'),
    orgFunctions: picker(w).props('orgFunctions'),
})

describe('CliLogin with the template picker', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); dispatch.mockClear(); notifyError.mockClear()
        resetState(); wire()
        mutate.mockImplementation(async () => ({ data: {} }))
    })

    it('gives the picker what the approver holds: the level, the union of the functions, and the organization row\'s own', async () => {
        const w = await open()
        expect(bounds(w)).toEqual({ maxOrgType: 'READ_WRITE', allowedFunctions: ['RESOURCE', 'DOWNLOAD'], orgFunctions: ['RESOURCE'] })
        expect(picker(w).props('orgUuid')).toBe(ORG)
        expect(picker(w).props('disabled')).toBe(false)
        expect(picker(w).props('model')).toEqual(editor(w).props('modelValue'))
    })

    it('an approver with no organization row holds NONE, and no functions on that row', async () => {
        state.ownerRows = [row('COMPONENT', 'c1', 'READ_ONLY', ['DOWNLOAD'])]
        const w = await open()
        expect(bounds(w)).toEqual({ maxOrgType: 'NONE', allowedFunctions: ['DOWNLOAD'], orgFunctions: [] })
    })

    it('an organization row with no function list gives an empty bound, not no bound', async () => {
        state.ownerRows = [{ org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'READ_ONLY' }]
        const w = await open()
        expect(bounds(w)).toEqual({ maxOrgType: 'READ_ONLY', allowedFunctions: [], orgFunctions: [] })
    })

    it('an organization administrator and a global administrator are bounded in nothing but the level', async () => {
        state.ownerRows = [row('ORGANIZATION', ORG, 'ADMIN', [])]
        let w = await open()
        expect(bounds(w)).toEqual({ maxOrgType: 'ADMIN', allowedFunctions: undefined, orgFunctions: undefined })
        resetState(); wire()
        state.myuser = { uuid: 'u1', installationType: 'SAAS', isGlobalAdmin: true }
        state.ownerRows = []
        w = await open()
        expect(bounds(w)).toEqual({ maxOrgType: 'ADMIN', allowedFunctions: undefined, orgFunctions: undefined })
    })

    it('an approver whose permissions could not be read is bounded in nothing, and is told so', async () => {
        state.ownerFails = true
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        const w = await open()
        expect(bounds(w)).toEqual({ maxOrgType: undefined, allowedFunctions: undefined, orgFunctions: undefined })
        expect(w.text()).toContain('permissions refused')
        warn.mockRestore()
    })

    it('a pick fills the form with the clipped template and shows the sentence and each reduction, once picked', async () => {
        const w = await open()
        expect(block(w).exists()).toBe(false)
        await pickTemplate(w, 't-admin')
        expect(editor(w).props('modelValue')).toEqual({
            orgPermission: { type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
            scopedPermissions: [{ scope: 'COMPONENT', objectId: 'c1', objectName: 'api', type: 'READ_WRITE', functions: ['RESOURCE', 'DOWNLOAD'], approvals: [] }],
        })
        expect(block(w).exists()).toBe(true)
        expect(block(w).find('div').text()).toBe(REDUCTIONS_SENTENCE)
        expect(block(w).findAll('li').map((l: any) => l.text())).toEqual([
            'organization-wide ADMIN reduced to READ_WRITE',
            'organization-wide functions reduced to RESOURCE',
            'component api functions reduced to RESOURCE, DOWNLOAD',
        ])
        expect(mutate).not.toHaveBeenCalled()
    })

    it('a pick that reduces nothing still shows the sentence, with no list', async () => {
        state.ownerRows = [row('ORGANIZATION', ORG, 'ADMIN')]
        const w = await open()
        await pickTemplate(w, 't-plain')
        expect(block(w).exists()).toBe(true)
        expect(block(w).find('div').text()).toBe(REDUCTIONS_SENTENCE)
        expect(block(w).find('ul').exists()).toBe(false)
        expect(editor(w).props('modelValue').orgPermission.type).toBe('READ_ONLY')
    })

    it('a pick of the next template replaces the form and the reductions of the one before', async () => {
        const w = await open()
        await pickTemplate(w, 't-admin')
        expect(block(w).findAll('li')).toHaveLength(3)
        await pickTemplate(w, 't-plain')
        expect(editor(w).props('modelValue').orgPermission.type).toBe('READ_ONLY')
        expect(editor(w).props('modelValue').scopedPermissions).toEqual([])
        expect(block(w).exists()).toBe(true)
        expect(block(w).find('ul').exists()).toBe(false)
    })

    it('approving after a pick sends the form, never a template', async () => {
        const w = await open()
        await pickTemplate(w, 't-admin')
        const approve = w.findAll('button').find((b: any) => b.text().startsWith('Approve'))!
        await approve.trigger('click')
        await flushPromises()
        const vars = mutate.mock.calls[0][0].variables
        expect(vars.createKeyOrgUuid).toBe(ORG)
        expect(vars.permissionType).toBe('READ_WRITE')
        expect(vars.permissions).toEqual([
            { org: ORG, scope: 'ORGANIZATION', type: 'READ_WRITE', object: ORG, functions: ['RESOURCE'], approvals: [] },
            { org: ORG, scope: 'COMPONENT', type: 'READ_WRITE', object: 'c1', functions: ['RESOURCE', 'DOWNLOAD'], approvals: [] },
        ])
        expect(JSON.stringify(vars)).not.toContain('t-admin')
    })

    it('another organization empties the form and takes the reductions away', async () => {
        const w = await open()
        await pickTemplate(w, 't-admin')
        expect(block(w).exists()).toBe(true)
        const orgSelect = w.findAllComponents(NSelect).find((s: any) => (s.props('options') || []).some((o: any) => o.value === 'o2'))!
        orgSelect.vm.$emit('update:value', 'o2')
        await flushPromises()
        await flushPromises()
        expect(block(w).exists()).toBe(false)
        expect(editor(w).props('modelValue')).toEqual({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })
        expect(picker(w).props('orgUuid')).toBe('o2')
    })

    it('names the grants of a template for the picker by what the page has loaded, and by the uuid when it has not', async () => {
        const w = await open()
        const nameOf = picker(w).props('nameOf') as (scope: string, id: string) => Promise<string>
        const names: [string, string][] = [
            ['COMPONENT', 'c1'], ['COMPONENT', 'p9'], ['COMPONENT', 'cx'], ['COMPONENT', 'cy'], ['COMPONENT', 'cz'],
            ['BOARD', 'b1'], ['PERSPECTIVE', 'p1'], ['PERSPECTIVE', 'p2'],
            ['INSTANCE', 'i1'], ['INSTANCE', 'i2'], ['INSTANCE', 'ci1'], ['INSTANCE', 'ci2'], ['INSTANCE', 'ci3'],
            ['INSTANCE', 'cl1'], ['INSTANCE', 'cl2'], ['INSTANCE', 'i-old'], ['INSTANCE', 'i-gone'], ['INSTANCE', 'i-nowhere'],
        ]
        const resolved: string[] = []
        for (const [scope, id] of names) resolved.push(await nameOf(scope, id))
        expect(resolved).toEqual([
            'api', 'Shop', 'Fetched', 'cy', 'cz',
            'b1', 'prod', 'p2',
            'https://i1', 'n2', 'Prod cluster / ns', '(cluster) / ns2', '(cluster) / ci3',
            'Prod cluster', 'https://cl2', 'i-old', 'i-gone', 'i-nowhere',
        ])
        // only a component the page does not have is looked up, never a board, a perspective or an instance
        expect(dispatch.mock.calls.filter((c: any) => c[0] === 'fetchComponent').map((c: any) => c[1])).toEqual(['cx', 'cy', 'cz'])
    })

    it('a pick of a template with every kind of grant puts those names in the form', async () => {
        const w = await open()
        state.ownerRows = [row('ORGANIZATION', ORG, 'ADMIN')]
        await pickTemplate(w, 't-names')
        const named = editor(w).props('modelValue').scopedPermissions.map((s: any) => s.objectName)
        expect(named.slice(0, 8)).toEqual(['api', 'Shop', 'Fetched', 'cy', 'cz', 'b1', 'prod', 'p2'])
    })

    it('an OSS installation has no instances to name', async () => {
        state.myuser = { uuid: 'u1', installationType: 'OSS', isGlobalAdmin: false }
        const w = await open()
        const nameOf = picker(w).props('nameOf') as (scope: string, id: string) => Promise<string>
        expect(await nameOf('INSTANCE', 'i1')).toBe('i1')
        expect(dispatch.mock.calls.some((c: any) => c[0] === 'fetchInstances')).toBe(false)
    })
})

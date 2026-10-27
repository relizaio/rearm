// @vitest-environment happy-dom
//
// TPF-113 mutation round: what PermissionTemplatesPanel.spec.ts leaves open. The GraphQL client, the store and
// naive-ui's dialog are mocked as there; ScopedPermissions is stubbed and the editor modal renders in place. The
// store here is configurable, so the lists the editor is fed and the names it resolves can be pinned.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'

const query = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
const mutate = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q), mutate: (m: any) => mutate(m) } }))

const ORG = 'o1'
const state: any = {}
const dispatch = vi.fn(async (name: string, arg?: any): Promise<any> => {
    if (name === 'fetchAgentBoardNamesOfOrg') return state.boards
    if (name === 'fetchComponent') return state.fetchComponent(arg)
    return []
})
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch: (name: string, arg?: any) => dispatch(name, arg),
        getters: {
            get myuser () { return { installationType: state.installationType } },
            orgById: (id: string) => id === ORG ? { approvalRoles: state.approvalRoles } : undefined,
            componentsOfOrg: (id: string) => id === ORG ? state.components : [{ uuid: 'other', name: 'other' }],
            productsOfOrg: (id: string) => id === ORG ? state.products : [{ uuid: 'other', name: 'other' }],
            instancesOfOrg: (id: string) => id === ORG ? state.instances : [],
        },
    }),
}))
const dialogWarning = vi.fn((opts: any) => opts.onPositiveClick())
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useDialog: () => ({ warning: dialogWarning }) }
})

const { default: Panel } = await import('./PermissionTemplatesPanel.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')
const { NInput, NModal, NSpin, NTooltip } = await import('naive-ui')
const { extractError } = await import('@/utils/notificationsCommon')

const org = (scope: string, object: string, type = 'READ_WRITE', functions: string[] = []) => ({ org: ORG, scope, object, type, functions, approvals: [] })
const tpl = (uuid: string, name: string, description: string | null, ...rows: any[]) =>
    ({ uuid, name, description, org: ORG, user: 'u1', permissions: { permissions: rows } })

const NAMES_T = tpl('t-names', 'names', 'each kind of grant',
    org('ORGANIZATION', ORG, 'ADMIN'),
    org('COMPONENT', 'c1'), org('COMPONENT', 'p9'), org('COMPONENT', 'cx'), org('COMPONENT', 'cy'), org('COMPONENT', 'cz'),
    org('BOARD', 'b1'), org('BOARD', 'b2'),
    org('PERSPECTIVE', 'p1'), org('PERSPECTIVE', 'p2'),
    org('INSTANCE', 'i1'), org('INSTANCE', 'i2'), org('INSTANCE', 'ci1'), org('INSTANCE', 'ci2'), org('INSTANCE', 'ci3'),
    org('INSTANCE', 'cl1'), org('INSTANCE', 'i-old'), org('INSTANCE', 'i-gone'), org('INSTANCE', 'i-nowhere'))
const PLAIN_T = tpl('t-plain', 'plain', 'a plain one', org('ORGANIZATION', ORG, 'READ_ONLY'), org('COMPONENT', 'c1'))
const NO_DESCRIPTION_T = tpl('t-nodesc', 'nodesc', null, org('ORGANIZATION', ORG, 'READ_ONLY'))

function resetState () {
    state.installationType = 'SAAS'
    state.approvalRoles = ['r1']
    state.components = [{ uuid: 'c1', name: 'api' }]
    state.products = [{ uuid: 'p9', name: 'Shop' }]
    state.boards = [{ uuid: 'b1', name: 'Release board' }]
    state.instances = [
        { uuid: 'i1', revision: -1, status: 'ACTIVE', instanceType: 'STANDALONE_INSTANCE', uri: 'https://i1', name: 'n1' },
        { uuid: 'i2', revision: -1, instanceType: 'STANDALONE_INSTANCE', name: 'n2' },
        { uuid: 'ci1', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER_INSTANCE', namespace: 'ns' },
        { uuid: 'ci2', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER_INSTANCE', namespace: 'ns2' },
        { uuid: 'ci3', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER_INSTANCE' },
        { uuid: 'cl1', revision: -1, status: 'ACTIVE', instanceType: 'CLUSTER', name: 'Prod cluster', uri: 'https://cl', instances: ['ci1'] },
        { uuid: 'i-old', revision: 3, status: 'ACTIVE', instanceType: 'STANDALONE_INSTANCE', uri: 'https://old' },
        { uuid: 'i-gone', revision: -1, status: 'ARCHIVED', instanceType: 'STANDALONE_INSTANCE', uri: 'https://gone' },
    ]
    state.fetchComponent = (uuid: string) => {
        if (uuid === 'cx') return { uuid, name: 'Fetched' }
        if (uuid === 'cy') throw new Error('gone')
        return {}
    }
}
const serve = (templates: any[]) => query.mockImplementation(async (q: any) => {
    const body = q.query.loc?.source.body || ''
    if (body.includes('perspectives(')) return { data: { perspectives: [{ uuid: 'p1', name: 'prod', org: ORG, type: 'X' }] } }
    return { data: { myPermissionTemplates: templates } }
})
const deferred = () => { let resolve!: (v: any) => void; let reject!: (e: any) => void; const promise = new Promise<any>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const templateQueries = () => query.mock.calls.filter((c: any) => c[0].query.loc?.source.body.includes('myPermissionTemplates(')).length

const global = { stubs: { ScopedPermissions: true, Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }
async function open (props: Record<string, any> = {}) {
    const notify = vi.fn()
    const w = mount(Panel, { props: { orgUuid: ORG, notify, ...props }, global })
    await flushPromises()
    return { w, notify }
}
const rows = (w: any) => w.findAll('[data-testid="permission-templates-table"] tbody tr')
const editButtons = (w: any) => w.findAll('[data-testid="edit-permission-template"]')
async function editRow (w: any, i: number) {
    await editButtons(w)[i].trigger('click')
    await flushPromises()
}
const editor = (w: any) => w.findComponent(ScopedPermissions)
const saveButton = (w: any) => w.find('[data-testid="permission-template-save"]')
const nameInput = (w: any) => w.find('[data-testid="permission-template-name"] input')
const descriptionInput = (w: any) => w.find('[data-testid="permission-template-description"] input')
const editorOpen = (w: any) => w.find('[data-testid="permission-template-editor"]').exists()

describe('PermissionTemplatesPanel edges', () => {
    beforeEach(() => {
        query.mockReset(); mutate.mockReset(); dispatch.mockClear(); dialogWarning.mockClear()
        resetState()
        serve([NAMES_T, PLAIN_T, NO_DESCRIPTION_T])
        mutate.mockImplementation(async () => ({ data: {} }))
    })

    it('renders nothing until the server has answered', async () => {
        const wait = deferred()
        query.mockImplementation(() => wait.promise)
        const { w } = await open()
        expect(w.find('[data-testid="permission-templates-table"]').exists()).toBe(false)
        wait.resolve({ data: { myPermissionTemplates: [PLAIN_T] } })
        await flushPromises()
        expect(rows(w)).toHaveLength(1)
    })

    it('orders the rows by name ignoring case, and keeps the listed order of names that differ only in case', async () => {
        serve([tpl('a', 'Zed', ''), tpl('b', 'zed', ''), tpl('c', 'Alpha', '')])
        const { w } = await open()
        expect(rows(w).map((r: any) => r.find('td').text())).toEqual(['Alpha', 'Zed', 'zed'])
        serve([tpl('a', 'zed', ''), tpl('b', 'Zed', '')])
        await (w.vm as any).reload()
        await flushPromises()
        expect(rows(w).map((r: any) => r.find('td').text())).toEqual(['zed', 'Zed'])
    })

    it('feeds the editor the organization\'s lists and the roles, and loads each of them for the organization', async () => {
        const { w } = await open()
        await editRow(w, 1)
        const e = editor(w)
        expect(e.props('orgUuid')).toBe(ORG)
        expect(e.props('approvalRoles')).toEqual(['r1'])
        expect(e.props('perspectives')).toEqual([{ uuid: 'p1', name: 'prod', org: ORG, type: 'X' }])
        expect(e.props('components')).toEqual([{ uuid: 'c1', name: 'api' }])
        expect(e.props('products')).toEqual([{ uuid: 'p9', name: 'Shop' }])
        expect(e.props('boards')).toEqual([{ uuid: 'b1', name: 'Release board' }])
        expect(e.props('instances').map((x: any) => x.uuid)).toEqual(['i1', 'i2', 'ci1', 'ci2', 'ci3'])
        expect(e.props('clusters').map((x: any) => x.uuid)).toEqual(['cl1'])
        expect(e.props('showSbomProbing')).toBe(true)
        const perspectiveQuery = query.mock.calls.find((c: any) => c[0].query.loc?.source.body.includes('perspectives('))![0]
        expect(perspectiveQuery.variables).toEqual({ org: ORG })
        expect(perspectiveQuery.fetchPolicy).toBe('no-cache')
        for (const name of ['fetchAgentBoardNamesOfOrg', 'fetchComponents', 'fetchProducts', 'fetchInstances']) {
            expect(dispatch.mock.calls.filter((c: any) => c[0] === name), name).toEqual([[name, ORG]])
        }
    })

    it('a self-hosted OSS installation has no instances to name and loads none', async () => {
        state.installationType = 'OSS'
        const { w } = await open()
        await editRow(w, rows(w).findIndex((r: any) => r.text().includes('names')))
        expect(editor(w).props('instances')).toEqual([])
        expect(editor(w).props('clusters')).toEqual([])
        expect(dispatch.mock.calls.some((c: any) => c[0] === 'fetchInstances')).toBe(false)
        const names = editor(w).props('modelValue').scopedPermissions.filter((s: any) => s.scope === 'INSTANCE').map((s: any) => s.objectName)
        expect(names).toEqual(['i1', 'i2', 'ci1', 'ci2', 'ci3', 'cl1', 'i-old', 'i-gone', 'i-nowhere'])
    })

    it('names every kind of grant the way the key modal does, and keeps the uuid of what it cannot name', async () => {
        const { w } = await open()
        await editRow(w, rows(w).findIndex((r: any) => r.text().includes('names')))
        const model = editor(w).props('modelValue')
        expect(model.orgPermission.type).toBe('ADMIN')
        expect(model.scopedPermissions.map((s: any) => [s.scope, s.objectId, s.objectName])).toEqual([
            ['COMPONENT', 'c1', 'api'],
            ['COMPONENT', 'p9', 'Shop'],
            ['COMPONENT', 'cx', 'Fetched'],
            ['COMPONENT', 'cy', 'cy'],
            ['COMPONENT', 'cz', 'cz'],
            ['BOARD', 'b1', 'Release board'],
            ['BOARD', 'b2', 'b2'],
            ['PERSPECTIVE', 'p1', 'prod'],
            ['PERSPECTIVE', 'p2', 'p2'],
            ['INSTANCE', 'i1', 'https://i1'],
            ['INSTANCE', 'i2', 'n2'],
            ['INSTANCE', 'ci1', 'Prod cluster / ns'],
            ['INSTANCE', 'ci2', '(cluster) / ns2'],
            ['INSTANCE', 'ci3', '(cluster) / ci3'],
            ['INSTANCE', 'cl1', 'Prod cluster'],
            ['INSTANCE', 'i-old', 'i-old'],
            ['INSTANCE', 'i-gone', 'i-gone'],
            ['INSTANCE', 'i-nowhere', 'i-nowhere'],
        ])
        expect(dispatch.mock.calls.filter((c: any) => c[0] === 'fetchComponent').map((c: any) => c[1])).toEqual(['cx', 'cy', 'cz'])
    })

    it('a cluster named only by its address, and one with neither, are named by what it has', async () => {
        state.instances = [
            { uuid: 'cl1', revision: -1, instanceType: 'CLUSTER', uri: 'https://cl' },
            { uuid: 'cl2', revision: -1, instanceType: 'CLUSTER' },
        ]
        serve([tpl('t', 'clusters', '', org('INSTANCE', 'cl1'), org('INSTANCE', 'cl2'))])
        const { w } = await open()
        await editRow(w, 0)
        expect(editor(w).props('modelValue').scopedPermissions.map((s: any) => s.objectName)).toEqual(['https://cl', 'cl2'])
    })

    it('the editor still opens, with what loaded, when one of the lists cannot be loaded', async () => {
        query.mockImplementation(async (q: any) => {
            if (q.query.loc?.source.body.includes('perspectives(')) throw new Error('perspectives refused')
            return { data: { myPermissionTemplates: [NAMES_T] } }
        })
        dispatch.mockImplementation(async (name: string) => { if (name === 'fetchComponents') throw new Error('components refused'); return name === 'fetchAgentBoardNamesOfOrg' ? state.boards : [] })
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        const { w } = await open()
        await editRow(w, 0)
        expect(editorOpen(w)).toBe(true)
        const model = editor(w).props('modelValue')
        expect(model.scopedPermissions.find((s: any) => s.objectId === 'b1').objectName).toBe('Release board')
        expect(model.scopedPermissions.find((s: any) => s.objectId === 'p1').objectName).toBe('p1')
        expect(warn).toHaveBeenCalled()
        warn.mockRestore()
        dispatch.mockImplementation(async (name: string, arg?: any) => name === 'fetchAgentBoardNamesOfOrg' ? state.boards : name === 'fetchComponent' ? state.fetchComponent(arg) : [])
    })

    it('a list that loads with nothing in it leaves the editor with empty lists', async () => {
        state.boards = null
        query.mockImplementation(async (q: any) => q.query.loc?.source.body.includes('perspectives(')
            ? { data: { perspectives: null } } : { data: { myPermissionTemplates: [PLAIN_T] } })
        const { w } = await open()
        await editRow(w, 0)
        expect(editor(w).props('perspectives')).toEqual([])
        expect(editor(w).props('boards')).toEqual([])
    })

    it('the editor is titled with the template, shows its name and description, and bounds both inputs', async () => {
        const { w } = await open()
        const idx = rows(w).findIndex((r: any) => r.text().includes('plain'))
        await editRow(w, idx)
        expect((w.findComponent(NModal).vm as any).$attrs.title).toBe('Permission template plain')
        expect(nameInput(w).element.value).toBe('plain')
        expect(descriptionInput(w).element.value).toBe('a plain one')
        expect(nameInput(w).attributes('maxlength')).toBe('128')
        const description = w.findAllComponents(NInput).find((c: any) => c.attributes('data-testid') === 'permission-template-description')!
        expect(description.props('maxlength')).toBe('512')
        expect(description.props('countGraphemes')('a\u{1F600}')).toBe(2)
    })

    it('a template with no description is edited with an empty one and saved with an empty one', async () => {
        const { w } = await open()
        const idx = rows(w).findIndex((r: any) => r.text().includes('nodesc'))
        await editRow(w, idx)
        expect(descriptionInput(w).element.value).toBe('')
        await nameInput(w).setValue('renamed')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables.template.description).toBe('')
        expect(mutate.mock.calls[0][0].variables.template.uuid).toBe('t-nodesc')
    })

    it('Save follows what the editor holds: off until something changed, on for the grants alone, off again when put back', async () => {
        const { w } = await open()
        const idx = rows(w).findIndex((r: any) => r.text().includes('plain'))
        await editRow(w, idx)
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        const original = JSON.parse(JSON.stringify(editor(w).props('modelValue')))
        const changed = JSON.parse(JSON.stringify(original))
        changed.orgPermission.type = 'ADMIN'
        editor(w).vm.$emit('update:modelValue', changed)
        await flushPromises()
        expect(saveButton(w).attributes('disabled')).toBeUndefined()
        editor(w).vm.$emit('update:modelValue', original)
        await flushPromises()
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        await descriptionInput(w).setValue('another')
        expect(saveButton(w).attributes('disabled')).toBeUndefined()
    })

    it('the grants alone are saved as the editor holds them, not as the template had them', async () => {
        const { w } = await open()
        const idx = rows(w).findIndex((r: any) => r.text().includes('plain'))
        await editRow(w, idx)
        const changed = JSON.parse(JSON.stringify(editor(w).props('modelValue')))
        changed.orgPermission.type = 'ADMIN'
        changed.scopedPermissions = []
        editor(w).vm.$emit('update:modelValue', changed)
        await flushPromises()
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables.template.permissions).toEqual([
            { scope: 'ORGANIZATION', objectId: ORG, type: 'ADMIN', functions: [], approvals: [] },
        ])
        expect(mutate.mock.calls[0][0].variables.template.name).toBe('plain')
    })

    it('opening a second template starts from empty and cannot be saved before its lists have loaded', async () => {
        const { w } = await open()
        const first = rows(w).findIndex((r: any) => r.text().includes('plain'))
        await editRow(w, first)
        await nameInput(w).setValue('plain2')
        w.findComponent(NModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(editorOpen(w)).toBe(false)
        const gate = deferred()
        query.mockImplementation(async (q: any) => q.query.loc?.source.body.includes('perspectives(') ? gate.promise : { data: { myPermissionTemplates: [NAMES_T, PLAIN_T, NO_DESCRIPTION_T] } })
        const second = rows(w).findIndex((r: any) => r.text().includes('nodesc'))
        await editRow(w, second)
        expect(editorOpen(w)).toBe(true)
        expect(w.findComponent(NSpin).props('show')).toBe(true)
        expect(editor(w).props('modelValue')).toEqual({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })
        await nameInput(w).setValue('typed meanwhile')
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        gate.resolve({ data: { perspectives: [] } })
        await flushPromises()
        expect(w.findComponent(NSpin).props('show')).toBe(false)
        expect(editor(w).props('modelValue').orgPermission.type).toBe('READ_ONLY')
    })

    it('Cancel and the dialog\'s own close both close the editor', async () => {
        const { w } = await open()
        await editRow(w, 1)
        const cancel = w.findAll('button').find((b: any) => b.text() === 'Cancel')!
        await cancel.trigger('click')
        expect(editorOpen(w)).toBe(false)
        await editRow(w, 1)
        w.findComponent(NModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(editorOpen(w)).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
    })

    it('a saved template closes the editor and reloads the list; Save is off while it is under way', async () => {
        const { w, notify } = await open()
        await editRow(w, 1)
        const before = templateQueries()
        await nameInput(w).setValue('plain2')
        const wait = deferred()
        mutate.mockImplementation(() => wait.promise)
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        wait.resolve({ data: {} })
        await flushPromises()
        expect(editorOpen(w)).toBe(false)
        expect(templateQueries()).toBe(before + 1)
        expect(notify).toHaveBeenCalledWith('success', 'Saved', 'Saved template "plain2"')
    })

    it('a refused save keeps the editor open with the server\'s text, and Save is on again', async () => {
        const { w, notify } = await open()
        await editRow(w, 1)
        const before = templateQueries()
        await nameInput(w).setValue('plain2')
        const refusal = new Error("A template with the name 'plain2' already exists.")
        mutate.mockImplementation(async () => { throw refusal })
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Error', extractError(refusal))
        expect(extractError(refusal)).toBe("A template with the name 'plain2' already exists.")
        expect(editorOpen(w)).toBe(true)
        expect(templateQueries()).toBe(before)
        expect(saveButton(w).attributes('disabled')).toBeUndefined()
    })

    it('save does nothing without a template being edited, a name, or an organization', async () => {
        const { w } = await open()
        const vm = w.vm as any
        await vm.save()
        expect(mutate).not.toHaveBeenCalled()
        await editRow(w, 1)
        await nameInput(w).setValue('   ')
        await vm.save()
        expect(mutate).not.toHaveBeenCalled()
        await nameInput(w).setValue('plain2')
        await w.setProps({ orgUuid: null })
        await vm.save()
        expect(mutate).not.toHaveBeenCalled()
    })

    it('save does nothing, and says nothing, for a name with no template being edited behind it', async () => {
        const { w, notify } = await open()
        const vm = w.vm as any
        vm.form = { name: 'typed', description: '' }
        await vm.save()
        expect(mutate).not.toHaveBeenCalled()
        expect(notify).not.toHaveBeenCalled()
    })

    it('what the page above reads through its reference: whether the server has templates, and the way to reload', async () => {
        const panel = ref<any>(null)
        const notify = vi.fn()
        const Host = defineComponent({ setup: () => () => h(Panel, { ref: panel, orgUuid: ORG, notify }) })
        mount(Host, { global })
        expect(panel.value.supported).toBeNull()
        await flushPromises()
        expect(panel.value.supported).toBe(true)
        const before = templateQueries()
        await panel.value.reload()
        expect(templateQueries()).toBe(before + 1)
        query.mockImplementation(async () => { throw Object.assign(new Error("Validation error of type FieldUndefined: Field 'myPermissionTemplates' is undefined"), { errors: [{ message: "Validation error of type FieldUndefined: Field 'myPermissionTemplates' is undefined", extensions: { classification: 'ValidationError' } }] }) })
        await panel.value.reload()
        expect(panel.value.supported).toBe(false)
    })

    it('delete asks first, names the template, and reloads the list after it', async () => {
        const { w, notify } = await open()
        const before = templateQueries()
        const idx = rows(w).findIndex((r: any) => r.text().includes('plain'))
        await w.findAll('[data-testid="delete-permission-template"]')[idx].trigger('click')
        await flushPromises()
        const asked = dialogWarning.mock.calls[0][0]
        expect(asked.title).toBe('Delete permission template')
        expect(asked.content).toBe('Delete the template "plain"? Keys and sessions approved from it keep their permissions.')
        expect(asked.positiveText).toBe('Delete')
        expect(asked.negativeText).toBe('Cancel')
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: 't-plain' })
        expect(templateQueries()).toBe(before + 1)
        expect(notify).toHaveBeenCalledWith('success', 'Deleted', 'Deleted template "plain"')
    })

    it('a refused delete shows the server\'s text and does not reload', async () => {
        const { w, notify } = await open()
        const before = templateQueries()
        mutate.mockImplementation(async () => { throw new Error('Not authorized') })
        await w.findAll('[data-testid="delete-permission-template"]')[0].trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Error', 'Not authorized')
        expect(templateQueries()).toBe(before)
    })

    it('shows the description as a tooltip on the name, for a description with text only', async () => {
        serve([tpl('a', 'with', 'deploy keys'), tpl('b', 'blank', '   '), tpl('c', 'empty', ''), tpl('d', 'none', null)])
        const { w } = await open()
        const tips = rows(w).map((r: any) => r.findAllComponents(NTooltip).length)
        expect(tips).toEqual([0, 0, 0, 1]) // sorted: blank, empty, none, with
        const tip = w.findAllComponents(NTooltip)
        expect(tip).toHaveLength(1)
        const slot = (tip[0].vm as any).$slots.default()
        expect(String(slot[0].children)).toBe('deploy keys')
    })

    it('the organization-wide column says none for NONE and the level otherwise; the manage icons carry their titles', async () => {
        serve([tpl('a', 'a-none', '', org('ORGANIZATION', ORG, 'NONE')), tpl('b', 'b-admin', '', org('ORGANIZATION', ORG, 'ADMIN')), tpl('c', 'c-empty', '')])
        const { w } = await open()
        const cells = rows(w).map((r: any) => r.findAll('td')[1].text())
        expect(cells).toEqual(['none', 'ADMIN', 'none'])
        expect(rows(w).map((r: any) => r.findAll('td')[2].text())).toEqual(['0 grants', '0 grants', '0 grants'])
        expect(w.find('[data-testid="edit-permission-template"]').attributes('title')).toBe('Edit template')
        expect(w.find('[data-testid="delete-permission-template"]').attributes('title')).toBe('Delete template')
    })

    it('shows the grants of the organization it is on, and nothing when it has none', async () => {
        const { w } = await open()
        await w.setProps({ orgUuid: null })
        await flushPromises()
        expect(rows(w)).toHaveLength(0)
        expect(templateQueries()).toBe(1)
    })

    it('another organization closes the editor and loads that organization\'s templates', async () => {
        const { w } = await open()
        await editRow(w, 1)
        expect(editorOpen(w)).toBe(true)
        serve([PLAIN_T])
        await w.setProps({ orgUuid: 'o2' })
        await flushPromises()
        expect(editorOpen(w)).toBe(false)
        expect(templateQueries()).toBe(2)
        const last = query.mock.calls.filter((c: any) => c[0].query.loc?.source.body.includes('myPermissionTemplates('))[1][0]
        expect(last.variables).toEqual({ org: 'o2' })
    })

    it('an answer that arrives after the organization changed is dropped', async () => {
        const first = deferred()
        query.mockImplementationOnce(() => first.promise)
        const { w } = await open()
        serve([PLAIN_T])
        await w.setProps({ orgUuid: 'o2' })
        await flushPromises()
        expect(rows(w)).toHaveLength(1)
        first.resolve({ data: { myPermissionTemplates: [NAMES_T, PLAIN_T, NO_DESCRIPTION_T] } })
        await flushPromises()
        expect(rows(w)).toHaveLength(1)
    })

    it('a failed load is shown with the server\'s own text', async () => {
        const err = new Error('Not authorized')
        query.mockImplementation(async () => { throw err })
        const { notify } = await open()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Error', 'Not authorized')
    })
})

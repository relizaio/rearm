// @vitest-environment happy-dom
//
// TPF-92 (architecture 4.4, test row 14): the API Key Groups pane. The GraphQL client, the store and
// naive-ui's dialog are mocked; ScopedPermissions is stubbed and the editor modal renders in place.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mutate = vi.fn(async (_: any): Promise<any> => ({ data: {} }))
vi.mock('@/utils/graphql', () => ({ default: { mutate: (m: any) => mutate(m), query: vi.fn() } }))
vi.mock('vuex', () => ({
    useStore: () => ({ dispatch: vi.fn(async () => null), getters: { myuser: { installationType: 'SAAS' } } }),
}))
const dialogWarning = vi.fn()
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useDialog: () => ({ warning: dialogWarning }) }
})

const { default: Pane } = await import('./ApiKeyGroupsOfOrg.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')
const { NSelect, NSwitch } = await import('naive-ui')

const ORG = 'o1'
const G1 = {
    uuid: 'g1', name: 'ci', description: 'CI keys', org: ORG, status: 'ACTIVE', apiKeys: ['k1', 'k2'],
    apiKeyDetails: [
        { uuid: 'k1', keyOrder: 1, type: 'FREEFORM', status: 'ACTIVE', notes: 'one', declaredName: null },
        { uuid: 'k2', keyOrder: 2, type: 'FREEFORM', status: 'ACTIVE', notes: 'two', declaredName: null },
    ],
    permissions: { permissions: [
        { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'READ_ONLY', functions: [], approvals: [] },
        { org: ORG, scope: 'COMPONENT', object: 'c1', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
    ] },
}
const G2 = { uuid: 'g2', name: 'deploy', description: '', org: ORG, status: 'ACTIVE', apiKeys: [], apiKeyDetails: [], permissions: { permissions: [] } }
const G3 = { uuid: 'g3', name: 'old', description: '', org: ORG, status: 'INACTIVE', apiKeys: ['k1'], apiKeyDetails: [], permissions: { permissions: [] } }
const KEYS = [
    { uuid: 'k1', type: 'FREEFORM', status: 'ACTIVE', notes: 'one' },
    { uuid: 'k2', type: 'FREEFORM', status: 'REQUESTED', notes: 'two' },
    { uuid: 'k3', type: 'USER', status: 'ACTIVE', notes: 'mine' },
]

// The editor modal teleports: render it in place.
const global = { stubs: { ScopedPermissions: true, Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }

function open (overrides: Record<string, any> = {}) {
    const notify = vi.fn()
    const w = mount(Pane, {
        props: {
            orguuid: ORG, isWritable: true, groups: [G1, G2, G3], supported: true, keys: KEYS,
            approvalRoles: [], perspectives: [], products: [], components: [{ uuid: 'c1', name: 'api' }],
            instances: [], clusters: [], boards: [], notify, ...overrides,
        },
        global,
    })
    return { w, notify }
}
const button = (w: any, text: string) => w.findAll('button').find((b: any) => b.text() === text)
async function editFirst (w: any) {
    await w.find('[data-testid="edit-api-key-group"]').trigger('click')
    await flushPromises()
}
async function showInactive (w: any) {
    w.findComponent(NSwitch).vm.$emit('update:value', true)
    await flushPromises()
}

describe('ApiKeyGroupsOfOrg (row 14)', () => {
    beforeEach(() => { mutate.mockReset(); mutate.mockImplementation(async () => ({ data: {} })); dialogWarning.mockReset() })

    it('(a) shows member counts and hides inactive groups until the switch is on', async () => {
        const { w } = open()
        await flushPromises()
        const table = w.find('[data-testid="api-key-groups-table"]')
        expect(table.text()).toContain('2 keys')
        expect(table.text()).toContain('0 keys')
        expect(table.text()).not.toContain('old')
        await showInactive(w)
        expect(w.find('[data-testid="api-key-groups-table"]').text()).toContain('old')
    })

    it('(b) opens the editor with FREEFORM options only, the member list and the FREEFORM key function set', async () => {
        const { w } = open()
        await editFirst(w)
        expect(w.find('[data-testid="api-key-group-editor"]').exists()).toBe(true)
        const select = w.findComponent(NSelect)
        expect(select.props('options').map((o: any) => o.value)).toEqual(['k1', 'k2'])
        expect(select.props('value')).toEqual(['k1', 'k2'])
        expect(w.findComponent(ScopedPermissions).props('showSbomProbing')).toBe(true)
    })

    it('(c, f) saves the whole member list and the assembled permissions, then emits changed once', async () => {
        const { w, notify } = open()
        await editFirst(w)
        await w.find('[data-testid="api-key-group-name"] input').setValue('ci2')
        await button(w, 'Save changes').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        const vars = mutate.mock.calls[0][0].variables
        expect(vars.apiKeyGroup.groupId).toBe('g1')
        expect(vars.apiKeyGroup.name).toBe('ci2')
        expect(vars.apiKeyGroup.apiKeys).toEqual(['k1', 'k2'])
        expect(vars.apiKeyGroup.permissions).toEqual([
            { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_ONLY', functions: [], approvals: [] },
            { scope: 'COMPONENT', objectId: 'c1', type: 'READ_WRITE', functions: ['RESOURCE'], approvals: [] },
        ])
        expect(w.emitted('changed')).toHaveLength(1)
        expect(notify).toHaveBeenCalledWith('success', 'Saved', 'Updated API key group "ci2"')
    })

    it('(d) on an unsupported server shows the alert and no table or Add button', async () => {
        const { w } = open({ supported: false })
        await flushPromises()
        expect(w.find('[data-testid="api-key-groups-unsupported"]').exists()).toBe(true)
        expect(w.find('[data-testid="api-key-groups-table"]').exists()).toBe(false)
        expect(w.find('[data-testid="add-api-key-group"]').exists()).toBe(false)
    })

    it('(e, f) restore sends the status only and emits changed once', async () => {
        const { w } = open()
        await showInactive(w)
        await w.find('[data-testid="restore-api-key-group"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { groupId: 'g3', status: 'ACTIVE' } })
        expect(w.emitted('changed')).toHaveLength(1)
    })

    it('(f) deactivate confirms first, then sends deleteApiKeyGroup and emits changed once', async () => {
        const { w } = open()
        await flushPromises()
        await w.find('[data-testid="deactivate-api-key-group"]').trigger('click')
        expect(mutate).not.toHaveBeenCalled()
        expect(dialogWarning).toHaveBeenCalledTimes(1)
        const opts = dialogWarning.mock.calls[0][0]
        expect(opts.content).toContain('Keys in "ci" lose its permissions on their next call')
        await opts.onPositiveClick()
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ groupUuid: 'g1' })
        expect(w.emitted('changed')).toHaveLength(1)
    })

    it('(f) create sends the trimmed name, emits changed once and reopens on the created group', async () => {
        const created = { ...G2, uuid: 'g9', name: 'new' }
        mutate.mockImplementation(async () => ({ data: { createApiKeyGroup: created } }))
        const { w } = open()
        await w.find('[data-testid="add-api-key-group"]').trigger('click')
        await flushPromises()
        expect(w.findComponent(NSelect).exists()).toBe(false)
        await w.find('[data-testid="api-key-group-name"] input').setValue('  new  ')
        await button(w, 'Create group').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { name: 'new', description: '', org: ORG } })
        expect(w.emitted('changed')).toHaveLength(1)
        expect(w.findComponent(NSelect).exists()).toBe(true)
    })

    it('(g) a refusal reaches notify with the server text and emits nothing', async () => {
        mutate.mockImplementation(async () => { throw new Error('System is sealed. Please unseal the system first.') })
        const { w, notify } = open()
        await editFirst(w)
        await w.find('[data-testid="api-key-group-name"] input').setValue('ci2')
        await button(w, 'Save changes').trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        const [type, title, content] = notify.mock.calls[0]
        expect(type).toBe('error')
        expect(title).toBe('Error')
        expect(content).toContain('System is sealed. Please unseal the system first.')
        expect(w.emitted('changed')).toBeUndefined()
    })

    it('(h) Save changes is disabled with the name cleared', async () => {
        const { w } = open()
        await editFirst(w)
        await w.find('[data-testid="api-key-group-name"] input').setValue('   ')
        expect(button(w, 'Save changes').attributes('disabled')).toBeDefined()
    })
})

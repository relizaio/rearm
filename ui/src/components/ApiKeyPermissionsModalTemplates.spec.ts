// @vitest-environment happy-dom
//
// TPF-113 mutation round: the template picker as the key request modal hosts it. A pick fills the proposal by
// value and nothing is clipped here (the admins decide); the other modes of the modal have no picker.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch: vi.fn(async () => []),
        getters: {
            myuser: { installationType: 'SAAS' },
            orgById: () => ({ approvalRoles: [] }),
            componentsOfOrg: () => [{ uuid: 'c1', name: 'api' }],
            productsOfOrg: () => [],
            instancesOfOrg: () => [],
        },
    }),
}))
vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(async () => ({ data: { perspectives: [] } })), mutate: vi.fn() } }))
vi.mock('../utils/graphql', () => ({ default: { query: vi.fn(async () => ({ data: { perspectives: [] } })), mutate: vi.fn() } }))

const { default: Modal } = await import('./ApiKeyPermissionsModal.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')
const { default: Picker } = await import('./PermissionTemplatePicker.vue')

const PICKED = {
    orgPermission: { type: 'READ_WRITE', functions: ['RESOURCE'], approvals: ['A'] },
    scopedPermissions: [{ scope: 'COMPONENT', objectId: 'c1', objectName: 'api', type: 'READ_ONLY', functions: [], approvals: [] }],
}

async function open (extra: Record<string, any> = {}) {
    const w = mount(Modal, {
        props: { show: false, apiKey: null, orgUuid: 'o1', notify: vi.fn(), mode: 'request', ...extra },
        global: { stubs: { ScopedPermissions: true, SessionLimitEditor: true, PermissionTemplatePicker: true } },
        attachTo: document.body,
    })
    await w.setProps({ show: true })
    await flushPromises()
    return w
}

describe('ApiKeyPermissionsModal request mode with the template picker', () => {
    beforeEach(() => { document.body.innerHTML = '' })

    it('mounts the picker over the proposal, with the organization and no bound', async () => {
        const w = await open()
        const picker = w.findComponent(Picker)
        expect(picker.exists()).toBe(true)
        expect(picker.props('orgUuid')).toBe('o1')
        expect(picker.props('disabled')).toBe(false)
        expect(picker.props('model')).toEqual(w.findComponent(ScopedPermissions).props('modelValue'))
        expect(picker.props('maxOrgType')).toBeUndefined()
        expect(picker.props('allowedFunctions')).toBeUndefined()
        expect(picker.props('orgFunctions')).toBeUndefined()
        expect(picker.props('nameOf')).toBeTypeOf('function')
        expect(picker.props('notify')).toBeTypeOf('function')
    })

    it('a pick replaces the whole proposal, the level and the grants alike, by value', async () => {
        const w = await open()
        w.findComponent(Picker).vm.$emit('apply', PICKED, ['ignored here'])
        await flushPromises()
        const held = w.findComponent(ScopedPermissions).props('modelValue')
        expect(held).toEqual(PICKED)
        expect(w.findComponent(Picker).props('model')).toEqual(PICKED)
    })

    it('a second pick replaces the first', async () => {
        const w = await open()
        w.findComponent(Picker).vm.$emit('apply', PICKED, [])
        await flushPromises()
        const second = { orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] }
        w.findComponent(Picker).vm.$emit('apply', second, [])
        await flushPromises()
        expect(w.findComponent(ScopedPermissions).props('modelValue')).toEqual(second)
    })

    it('the picker names a component the modal has loaded, and keeps the uuid of one it has not', async () => {
        const w = await open()
        const nameOf = w.findComponent(Picker).props('nameOf') as (scope: string, id: string) => Promise<string>
        expect(await nameOf('COMPONENT', 'c1')).toBe('api')
        expect(await nameOf('PERSPECTIVE', 'p-unknown')).toBe('p-unknown')
    })

    it('the other modes have no picker', async () => {
        for (const mode of ['edit', 'view']) {
            const w = await open({ mode, apiKey: { uuid: 'k1', type: 'FREEFORM', object: 'o1', notes: '', permissions: { permissions: [] } } })
            expect(w.findComponent(Picker).exists(), mode).toBe(false)
            w.unmount()
            document.body.innerHTML = ''
        }
    })
})

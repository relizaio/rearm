// @vitest-environment happy-dom
//
// TPF-124 (architecture 4.7, test rows 17 and 21): the API key groups of one Free Form key. The GraphQL
// client is mocked and the dialog renders in place (the teleporting modal is stubbed).
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mutate = vi.fn(async (_: any): Promise<any> => ({ data: { setApiKeyGroupsOfKey: [] } }))
vi.mock('@/utils/graphql', () => ({ default: { mutate: (m: any) => mutate(m), query: vi.fn() } }))

const { default: MembershipModal } = await import('./ApiKeyGroupMembershipModal.vue')
const { NCheckbox } = await import('naive-ui')

const G1 = { uuid: 'g1', name: 'ci', status: 'ACTIVE', apiKeys: ['k1'] }
const G2 = { uuid: 'g2', name: 'Alpha', status: 'ACTIVE', apiKeys: [] }
const G3 = { uuid: 'g3', name: 'old', status: 'INACTIVE', apiKeys: ['k1'] }
const G4 = { uuid: 'g4', name: 'zed', status: 'INACTIVE', apiKeys: [] }
const KEY = { uuid: 'k1', type: 'FREEFORM', notes: 'one' }

const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }

function open (overrides: Record<string, any> = {}) {
    const notify = vi.fn()
    const w = mount(MembershipModal, {
        props: { show: true, apiKey: KEY, groups: [G1, G2, G3, G4], orgUuid: 'o1', notify, ...overrides },
        global,
    })
    return { w, notify }
}
const boxes = (w: any) => w.findAllComponents(NCheckbox)
const box = (w: any, uuid: string) => boxes(w).find((b: any) => b.attributes('data-testid') === 'api-key-group-of-key-' + uuid)
const state = (w: any) => Object.fromEntries(boxes(w).map((b: any) => [b.attributes('data-testid'), { checked: b.props('checked'), disabled: b.props('disabled') }]))
const save = (w: any) => w.find('[data-testid="api-key-groups-of-key-save"]')
async function set (w: any, uuid: string, v: boolean) {
    box(w, uuid).vm.$emit('update:checked', v)
    await flushPromises()
}

beforeEach(() => {
    mutate.mockReset()
    mutate.mockImplementation(async () => ({ data: { setApiKeyGroupsOfKey: [] } }))
})

describe('ApiKeyGroupMembershipModal (row 17)', () => {
    it('(a) lists every group by name with members checked and inactive non-members disabled', () => {
        const { w } = open()
        expect(boxes(w).map((b: any) => b.attributes('data-testid'))).toEqual([
            'api-key-group-of-key-g2', 'api-key-group-of-key-g1', 'api-key-group-of-key-g3', 'api-key-group-of-key-g4',
        ])
        const s = state(w)
        expect(s['api-key-group-of-key-g1']).toEqual({ checked: true, disabled: false })
        expect(s['api-key-group-of-key-g2']).toEqual({ checked: false, disabled: false })
        expect(s['api-key-group-of-key-g3']).toEqual({ checked: true, disabled: false })
        expect(s['api-key-group-of-key-g4']).toEqual({ checked: false, disabled: true })
        expect(box(w, 'g3').text()).toContain('INACTIVE')
        expect(box(w, 'g4').text()).toContain('INACTIVE')
        expect(box(w, 'g1').text()).not.toContain('INACTIVE')
        expect(save(w).attributes('disabled')).toBeDefined()
        expect(w.find('[data-testid="api-key-groups-of-key-editor"]').exists()).toBe(true)
        expect(w.find('[data-testid="api-key-groups-of-key-list"]').exists()).toBe(true)
        // TPF-124 security F-7: the info line names the federated workloads bound to the key too
        expect(w.text()).toContain('including calls made through a federated trust rule bound to this key')
    })

    it('(b) saves the complete desired set, toasts the diff, emits saved and closes', async () => {
        const { w, notify } = open()
        await set(w, 'g2', true)
        await set(w, 'g1', false)
        await set(w, 'g3', false)
        expect(save(w).attributes('disabled')).toBeUndefined()
        await save(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ orgUuid: 'o1', apiKeyUuid: 'k1', groupUuids: ['g2'] })
        expect(notify).toHaveBeenCalledWith('success', 'Saved', expect.stringContaining('added to 1 group(s), removed from 2'))
        expect(w.emitted('saved')).toEqual([[{ added: ['g2'], removed: ['g1', 'g3'] }]])
        const shows = w.emitted('update:show') || []
        expect(shows[shows.length - 1]).toEqual([false])
    })

    it('(c) never checks an inactive non-member', async () => {
        const { w } = open()
        await set(w, 'g4', true)
        expect(box(w, 'g4').props('checked')).toBe(false)
        expect(save(w).attributes('disabled')).toBeDefined()
    })

    it('(d) shows the refusal, stays open and emits nothing', async () => {
        mutate.mockImplementation(async () => { throw new Error('API key group is inactive: g4') })
        const { w, notify } = open()
        await set(w, 'g2', true)
        await save(w).trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledWith('error', 'Error', 'API key group is inactive: g4')
        expect(w.emitted('saved')).toBeUndefined()
        expect(w.emitted('update:show')).toBeUndefined()
        expect(save(w).attributes('disabled')).toBeUndefined()
    })

    it('(e) shows a note and no checkboxes for a key that is not Free Form', () => {
        const { w } = open({ apiKey: { uuid: 'k3', type: 'USER' } })
        expect(w.find('[data-testid="api-key-groups-of-key-not-freeform"]').exists()).toBe(true)
        expect(boxes(w)).toHaveLength(0)
    })

    it('(f) says so when the organization has no groups', () => {
        const { w } = open({ groups: [] })
        expect(w.find('[data-testid="api-key-groups-of-key-empty"]').exists()).toBe(true)
    })

    it('(g) resets the boxes to the member set on reopening', async () => {
        const { w } = open()
        await set(w, 'g2', true)
        await set(w, 'g1', false)
        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        const s = state(w)
        expect(s['api-key-group-of-key-g1'].checked).toBe(true)
        expect(s['api-key-group-of-key-g2'].checked).toBe(false)
        expect(s['api-key-group-of-key-g3'].checked).toBe(true)
        expect(save(w).attributes('disabled')).toBeDefined()
    })
})

describe('ApiKeyGroupMembershipModal double submit (row 21)', () => {
    it('sends one mutation for two clicks while saving', async () => {
        let release: (v: any) => void = () => {}
        mutate.mockImplementation(() => new Promise(resolve => { release = resolve }))
        const { w } = open()
        await set(w, 'g2', true)
        await save(w).trigger('click')
        await save(w).trigger('click')
        release({ data: { setApiKeyGroupsOfKey: [] } })
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(w.emitted('saved')).toHaveLength(1)
    })
})

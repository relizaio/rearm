// @vitest-environment happy-dom
//
// The key permissions modal on an EXTERNAL key (task TEA-3, design 4.10): the grants header and lead
// line, no Device Login tab, and the scope editor in its externalKey mode. A FREEFORM key keeps the
// usual editor. The GraphQL client and the store are mocked; the scope editor is stubbed, since its
// externalKey mode has its own spec.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NTab } from 'naive-ui'

const dispatch = vi.fn(async () => [])
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch,
        getters: {
            myuser: { installationType: 'SAAS' },
            orgById: () => ({ approvalRoles: [] }),
            componentsOfOrg: () => [{ uuid: 'c1', name: 'api' }],
            productsOfOrg: () => [{ uuid: 'prod1', name: 'Shop' }],
            instancesOfOrg: () => [],
        },
    }),
}))
vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(async () => ({ data: { perspectives: [] } })), mutate: vi.fn() } }))
vi.mock('../utils/graphql', () => ({ default: { query: vi.fn(async () => ({ data: { perspectives: [] } })), mutate: vi.fn() } }))

const { default: Modal } = await import('./ApiKeyPermissionsModal.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')

const key = (type: string) => ({
    uuid: 'k-' + type.toLowerCase(), type, object: 'o1', notes: '',
    permissions: { permissions: [{ org: 'o1', scope: 'COMPONENT', object: 'c1', type: 'READ_ONLY', functions: ['TEA_READ'], approvals: [] }] },
})

async function open (type: string) {
    const w = mount(Modal, {
        props: { show: false, apiKey: key(type), orgUuid: 'o1', notify: vi.fn() },
        global: { stubs: { ScopedPermissions: true, SessionLimitEditor: true } },
        attachTo: document.body,
    })
    await w.setProps({ show: true })
    await flushPromises()
    return w
}

// The tab headers rendered (n-tabs mounts only the active pane, but renders every header).
const tabs = (w: any) => w.findAllComponents(NTab).map((t: any) => t.text())

describe('ApiKeyPermissionsModal: EXTERNAL keys', () => {
    // the modal teleports to the body; a closed one may linger there through its leave transition
    beforeEach(() => { document.body.innerHTML = '' })

    it('edits the grants of an external key: lead line, no Device Login tab, the editor in externalKey mode', async () => {
        const w = await open('EXTERNAL')
        expect(document.body.textContent).toContain('Grants of external key k-external')
        expect(document.body.textContent).toContain('Read-only access to what is published on TEA. Pick the organization, '
            + 'or the perspectives, products and components the outside party may read.')
        expect(tabs(w)).toEqual(['Permissions', 'Notes'])
        const editor = w.findComponent(ScopedPermissions)
        expect(editor.props('externalKey')).toBe(true)
        expect(editor.props('modelValue').scopedPermissions).toEqual([
            expect.objectContaining({ scope: 'COMPONENT', objectId: 'c1', type: 'READ_ONLY' }),
        ])
        w.unmount()
    })

    it('keeps the usual editor for a Free Form key', async () => {
        const w = await open('FREEFORM')
        expect(document.body.textContent).toContain('Edit key k-freeform')
        expect(document.body.textContent).not.toContain('Read-only access to what is published on TEA')
        expect(tabs(w)).toEqual(['Permissions', 'Device Login', 'Notes'])
        expect(w.findComponent(ScopedPermissions).props('externalKey')).toBe(false)
        w.unmount()
    })
})

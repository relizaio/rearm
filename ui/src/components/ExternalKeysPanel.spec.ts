// @vitest-environment happy-dom
//
// The External Keys panel (task TEA-3, design 4.10 cases 49-51): the banner and the token URL, the
// rows (key id, holder, grants summary), and the create prompt. The GraphQL client, Swal and the
// store are mocked as the TEA-2 specs mock them.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { NAlert, NDataTable } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
const dispatch = vi.fn(async () => [])
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a), showValidationMessage: vi.fn() } }))
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch,
        getters: { productsOfOrg: () => [{ uuid: 'prod1', name: 'Shop' }], myuser: { installationType: 'SAAS' } },
    }),
}))

const { default: Panel } = await import('./ExternalKeysPanel.vue')
const { default: graphqlQueries } = await import('@/utils/graphqlQueries')

const ORG = 'o1'
const API_BASE = 'https://rearm.example.com/tea/7d0b5a0e-1111-4222-8333-944445555666'
const grant = (scope: string, object: string, type = 'READ_ONLY') => ({ org: ORG, scope, object, type, functions: ['TEA_READ'] })
const key = (uuid: string, holderName: string, permissions: any[], createdDate = '2026-10-01T10:00:00Z') => ({
    uuid, keyId: `EXTERNAL__7d0b5a0e-1111-4222-8333-944445555666__ord__${uuid}-order`, holderName,
    teaOrg: '7d0b5a0e-1111-4222-8333-944445555666', object: ORG, type: 'EXTERNAL', keyOrder: `${uuid}-order`,
    createdDate, accessDate: null, notes: '', status: 'ACTIVE', adminDisabled: false,
    secrets: [], permissions: { permissions },
})
const KEYS = [
    key('k1', 'Acme Auditors', [grant('ORGANIZATION', ORG)], '2026-10-03T10:00:00Z'),
    key('k2', 'Globex', [grant('PERSPECTIVE', 'p1'), grant('COMPONENT', 'prod1'), grant('COMPONENT', 'c1'), grant('COMPONENT', 'c2')], '2026-10-02T10:00:00Z'),
    key('k3', 'Initech', [], '2026-10-01T10:00:00Z'),
]

function answer (apiBase: string | null, keys: any[] = KEYS) {
    query.mockImplementation(async (opts: any) => {
        if (opts.query === graphqlQueries.ExternalApiKeysGql) return { data: { externalApiKeys: keys } }
        if (opts.query === graphqlQueries.TeaOrgDiscoveryGql) {
            return { data: { teaOrgDiscovery: { org: ORG, teaUuid: apiBase ? 'tea' : null, apiBase, wellKnownDocument: null } } }
        }
        throw new Error('unexpected query')
    })
}

const notify = vi.fn()
async function mountPanel () {
    const w = mount(Panel, {
        props: { orgUuid: ORG, notify, isOrgAdmin: true },
        global: { stubs: { ApiKeyPermissionsModal: true } },
    })
    await flushPromises()
    return w
}

const externalKeysCalls = () => query.mock.calls.filter((c: any[]) => c[0].query === graphqlQueries.ExternalApiKeysGql)

describe('ExternalKeysPanel', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        notify.mockReset()
    })

    it('shows a warning banner that cannot be closed, naming the token URL on the TEA API base', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        const banner = w.find('[data-testid="external-keys-banner"]')
        expect(banner.exists()).toBe(true)
        const alert = w.findComponent(NAlert)
        expect(alert.props('type')).toBe('warning')
        expect(alert.props('closable')).toBe(false)
        expect(banner.text()).toContain('External keys are meant for parties outside your organization.')
        expect(banner.text()).toContain('no GraphQL, no downloads, no CLI')
        expect(w.find('[data-testid="external-keys-token-url"]').text()).toBe(API_BASE + '/v1.0.0/token')
        expect(w.find('[data-testid="external-keys-token-url-missing"]').exists()).toBe(false)
        expect(externalKeysCalls()[0][0].variables).toEqual({ orgUuid: ORG })
    })

    it('says the TEA id is minted with the first key while the organization has none', async () => {
        answer(null, [])
        const w = await mountPanel()
        expect(w.find('[data-testid="external-keys-token-url"]').exists()).toBe(false)
        expect(w.find('[data-testid="external-keys-token-url-missing"]').text())
            .toBe("the organization's TEA id is minted when the first external key is created")
    })

    it('renders the printed key id, the holder and the grants summary of each row', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        expect(w.findAll('[data-testid="external-key-holder"]').map(x => x.text())).toEqual(['Acme Auditors', 'Globex', 'Initech'])
        const grants = w.findAll('[data-testid="external-key-grants"]')
        expect(grants.map(x => x.text())).toEqual(['organization-wide', '1 perspective, 1 product, 2 components', 'none: reads nothing yet'])
        expect(grants[2].attributes('style')).toContain('color')
        expect(grants[0].attributes('style') ?? '').not.toContain('color')

        // The IDs tooltip carries the server's printed id, not one derived from the object.
        const table = w.findComponent(NDataTable)
        const ids = (table.props('columns') as any[]).find((c: any) => c.key === 'ids')
        const tooltip = ids.render(KEYS[1])
        const content = mount({ render: () => tooltip.children.default() })
        expect(content.text()).toContain(KEYS[1].keyId)
        expect(content.text()).not.toContain('EXTERNAL__' + ORG)
    })

    it('sends nothing when the create prompt is cancelled', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        fire.mockResolvedValueOnce({ isConfirmed: false, isDismissed: true })
        await w.find('[data-testid="external-key-create"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledTimes(1)
        expect(mutate).not.toHaveBeenCalled()
    })

    it('creates the key with the holder name and notes, then reloads and says what comes next', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        const before = externalKeysCalls().length
        fire.mockResolvedValueOnce({ isConfirmed: true, value: { holderName: 'Hooli', notes: 'SBOM consumer' } })
        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockResolvedValueOnce({ data: { createExternalApiKey: { uuid: 'k4', keyId: 'EXTERNAL__x__ord__y', holderName: 'Hooli', teaOrg: 'x' } } })
        await w.find('[data-testid="external-key-create"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(graphqlQueries.CreateExternalApiKeyGql)
        expect(mutate.mock.calls[0][0].variables).toEqual({ orgUuid: ORG, holderName: 'Hooli', notes: 'SBOM consumer' })
        expect(externalKeysCalls().length).toBe(before + 1)
        expect(fire.mock.calls[1][0].text).toBe('Key id created with no secret. Mint its first secret from the Secrets column, '
            + 'set its grants, and hand the id and the secret to Hooli.')
    })

    // ---- TPF-88: search, uuid column and sorters (ARCHITECTURE round 3, 3.9 and rows 35-38) ----

    const holders = (w: any): string[] => w.findAll('[data-testid="external-key-holder"]').map((x: any) => x.text())
    async function searchFor (w: any, term: string) {
        await w.find('[data-testid="keys-search-externalKeys"] input').setValue(term)
        await nextTick()
    }
    async function clickHeader (w: any, title: string) {
        const th = w.findAll('thead th').find((x: any) => x.text() === title)
        expect(th, title).toBeTruthy()
        await th.trigger('click')
        await nextTick()
    }

    it('row 35: the search box filters by uuid, key id prefix, holder and notes', async () => {
        answer(API_BASE, [{ ...KEYS[0], uuid: '0dfce17a-fc79-4245-937d-eff5213fdfcb', notes: 'SBOM consumer' }, KEYS[1], KEYS[2]])
        const w = await mountPanel()
        const boxes = w.findAll('[data-testid="keys-search-externalKeys"]')
        expect(boxes.length).toBe(1)
        const box = boxes[0].element
        const table = w.find('[data-testid="external-keys-table"]').element
        expect(box.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        const before = externalKeysCalls().length

        await searchFor(w, 'glob')
        expect(holders(w)).toEqual(['Globex'])
        await searchFor(w, '0dfc')
        expect(holders(w)).toEqual(['Acme Auditors'])
        await searchFor(w, '0DFC')
        expect(holders(w)).toEqual(['Acme Auditors'])
        await searchFor(w, 'external__7d0b')
        expect(holders(w)).toEqual(['Acme Auditors', 'Globex', 'Initech'])
        await searchFor(w, 'sbom')
        expect(holders(w)).toEqual(['Acme Auditors'])
        await searchFor(w, 'k1-order')
        expect(holders(w)).toEqual([])
        await searchFor(w, 'nobody')
        expect(holders(w)).toEqual([])
        expect(w.find('[data-testid="keys-empty-externalKeys"]').text()).toBe('No keys match "nobody"')
        await searchFor(w, '')
        expect(holders(w)).toEqual(['Acme Auditors', 'Globex', 'Initech'])
        expect(w.find('[data-testid="keys-empty-externalKeys"]').exists()).toBe(false)
        expect(externalKeysCalls().length).toBe(before)
    })

    it('row 36: a UUID column with a copy button follows the IDs column', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        expect(w.findAll('[data-testid="externalKeys-key-uuid"]').map(x => x.text())).toEqual(['k1', 'k2', 'k3'])
        const copies = w.findAll('[data-testid="externalKeys-key-uuid-copy"]')
        expect(copies.length).toBe(3)
        for (const c of copies) {
            expect(c.element.tagName).toBe('BUTTON')
            expect(c.attributes('aria-label')).toBe('Copy Key UUID')
        }
        const table = w.findComponent(NDataTable)
        const columns = table.props('columns') as any[]
        const at = columns.findIndex((c: any) => c.key === 'uuid')
        expect(at).toBe(columns.findIndex((c: any) => c.key === 'ids') + 1)
        expect(columns[at].width).toBe(320)
        expect(w.findAll('thead th').map(x => x.text()))
            .toEqual(['Type', 'IDs', 'UUID', 'Holder', 'Created', 'Last Accessed', 'Grants', 'Status', 'Secrets', 'Notes', 'Manage'])
        expect(table.props('scrollX')).toBe(2520)
    })

    it('row 37: Holder, Created, Last Accessed and Notes sort; the other columns do not', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        const columns = w.findComponent(NDataTable).props('columns') as any[]
        const col = (k: string) => columns.find((c: any) => c.key === k)
        for (const k of ['holderName', 'createdDate', 'accessDate', 'notes']) expect(typeof col(k).sorter, k).toBe('function')
        expect(col('createdDate').defaultSortOrder).toBe('descend')
        for (const k of ['holderName', 'accessDate', 'notes']) expect(col(k).defaultSortOrder, k).toBeUndefined()
        for (const k of ['grants', 'status', 'secrets', 'ids', 'type', 'uuid', 'controls']) expect(col(k).sorter, k).toBeUndefined()

        await clickHeader(w, 'Holder')
        expect(holders(w)).toEqual(['Initech', 'Globex', 'Acme Auditors'])
        await clickHeader(w, 'Holder')
        expect(holders(w)).toEqual(['Acme Auditors', 'Globex', 'Initech'])
        w.unmount()

        answer(API_BASE, [{ ...KEYS[0], accessDate: null }, { ...KEYS[1], accessDate: '2026-10-10T00:00:00Z' }, KEYS[2]])
        const v = await mountPanel()
        await clickHeader(v, 'Last Accessed')
        expect(holders(v)).toEqual(['Globex', 'Initech', 'Acme Auditors'])
        await clickHeader(v, 'Last Accessed')
        expect(holders(v)).toEqual(['Acme Auditors', 'Initech', 'Globex'])
        const texts = v.findAll('tbody tr').map(tr => tr.findAll('td').map(td => td.text()))
        const lastAccessed = 5
        const created = 4
        expect(texts[0][lastAccessed]).toBe('Never')
        expect(texts[0][created]).toBe(new Date('2026-10-03T10:00:00Z').toLocaleString('en-CA'))
    })

    it('row 38: the mount order follows Created, newest first, not the server order', async () => {
        answer(API_BASE)
        const w = await mountPanel()
        expect(holders(w)).toEqual(['Acme Auditors', 'Globex', 'Initech'])
        w.unmount()
        answer(API_BASE, [
            key('k1', 'Acme Auditors', [grant('ORGANIZATION', ORG)], '2026-10-01T10:00:00Z'),
            key('k2', 'Globex', [], '2026-10-02T10:00:00Z'),
            key('k3', 'Initech', [], '2026-10-03T10:00:00Z'),
        ])
        const v = await mountPanel()
        expect(holders(v)).toEqual(['Initech', 'Globex', 'Acme Auditors'])
    })
})

// @vitest-environment happy-dom
//
// FDAW-40 row 22b (FDAW-25 3.12, FDAW-40 3.5): the Customers panel. The GraphQL client and naive-ui's
// dialog are mocked as ApiKeyGroupsOfOrg.spec.ts mocks them; ScopedPermissions is stubbed and the editor
// modal renders in place, with its title.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('vuex', () => ({
    useStore: () => ({ dispatch: vi.fn(async () => null), getters: { myuser: { installationType: 'SAAS' } } }),
}))
const dialogWarning = vi.fn()
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useDialog: () => ({ warning: dialogWarning }) }
})

const { default: Panel } = await import('./TeaCustomersPanel.vue')
const { default: ScopedPermissions } = await import('./ScopedPermissions.vue')
const { default: graphqlQueries } = await import('@/utils/graphqlQueries')
const { API_KEY_GROUPS_OF_ORG_QUERY } = await import('@/utils/apiKeyGroupsQuery')
const { CREATE_CUSTOMER_GROUP, UPDATE_CUSTOMER_GROUP, DEACTIVATE_CUSTOMER_GROUP } = await import('@/utils/teaCustomers')
const { NInput, NModal, NSwitch, NTag } = await import('naive-ui')

const ORG = 'o1'
const ORG_READ = { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'READ_ONLY', functions: ['TEA_READ'] }
const PRODUCT_READ = { org: ORG, scope: 'COMPONENT', object: 'prod1', type: 'READ_ONLY', functions: ['TEA_READ'] }
const INTERNAL = { uuid: 'g1', name: 'ci', description: '', org: ORG, status: 'ACTIVE', apiKeys: [], permissions: { permissions: [] } }
const ACME = {
    uuid: 'c1', name: 'Acme', description: 'Hospital', org: ORG, status: 'ACTIVE', kind: 'CUSTOMER', apiKeys: ['k1', 'k2'],
    permissions: { permissions: [ORG_READ] },
}
const GLOBEX = {
    uuid: 'c2', name: 'Globex', description: null, org: ORG, status: 'ACTIVE', kind: 'CUSTOMER', apiKeys: [],
    permissions: { permissions: [PRODUCT_READ] },
}
const OLD = { uuid: 'c3', name: 'Old', description: '', org: ORG, status: 'INACTIVE', kind: 'CUSTOMER', apiKeys: [], permissions: { permissions: [] } }
const KEYS = [
    { uuid: 'k1', type: 'EXTERNAL', holderName: 'Zeta', status: 'ACTIVE' },
    { uuid: 'k2', type: 'EXTERNAL', holderName: 'Acme', status: 'ACTIVE' },
    { uuid: 'k9', type: 'EXTERNAL', holderName: 'Initech', status: 'ACTIVE' },
]
// the ORGANIZATION READ_ONLY grant as the editor sends it (permissionsFromScopedModel)
const ORG_READ_SENT = { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_ONLY', functions: [], approvals: [] }
const ORG_READ_MODEL = { orgPermission: { type: 'READ_ONLY', functions: [], approvals: [] }, scopedPermissions: [] }

function drift (): any {
    const err: any = new Error("Validation error of type FieldUndefined: Field 'apiKeyGroupsOfOrg' is undefined")
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
function refusal (text: string): any {
    const err: any = new Error(text)
    err.graphQLErrors = [{ message: text }]
    return err
}

function answer (groups: any[] | Error = [ACME, GLOBEX, OLD, INTERNAL], keys: any[] = KEYS) {
    query.mockImplementation(async (opts: any) => {
        if (opts.query === API_KEY_GROUPS_OF_ORG_QUERY) {
            if (groups instanceof Error) throw groups
            return { data: { apiKeyGroupsOfOrg: groups } }
        }
        if (opts.query === graphqlQueries.ExternalApiKeysGql) return { data: { externalApiKeys: keys } }
        throw new Error('unexpected query')
    })
}

const global = {
    stubs: {
        ScopedPermissions: true,
        Modal: { props: ['show', 'title'], template: '<div v-if="show"><div data-testid="editor-title">{{ title }}</div><slot/></div>' },
    },
}
async function open (overrides: Record<string, any> = {}) {
    const notify = vi.fn()
    const w = mount(Panel, {
        props: {
            orgUuid: ORG, notify, isOrgAdmin: true, products: [{ uuid: 'prod1', name: 'Shop' }], components: [], perspectives: [],
            ...overrides,
        },
        global,
    })
    await flushPromises()
    return { w, notify }
}
const groupCalls = () => query.mock.calls.filter((c: any[]) => c[0].query === API_KEY_GROUPS_OF_ORG_QUERY)
const keyCalls = () => query.mock.calls.filter((c: any[]) => c[0].query === graphqlQueries.ExternalApiKeysGql)
const names = (w: any): string[] => w.findAll('[data-testid="customers-name"]').map((x: any) => x.text())
const title = (w: any): string => w.find('[data-testid="editor-title"]').text()
const saveButton = (w: any) => w.find('[data-testid="customer-save"]')
async function click (w: any, testid: string, at = 0) {
    await w.findAll(`[data-testid="${testid}"]`)[at].trigger('click')
    await flushPromises()
}
async function typeName (w: any, v: string) {
    await w.find('[data-testid="customer-name"] input').setValue(v)
}
async function checkKey (w: any, uuid: string, v = true) {
    w.findAllComponents({ name: 'Checkbox' }).find((b: any) => b.attributes('data-testid') === 'customer-key-' + uuid).vm.$emit('update:checked', v)
    await flushPromises()
}
async function setGrants (w: any, model: any) {
    w.findComponent(ScopedPermissions).vm.$emit('update:modelValue', model)
    await flushPromises()
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    dialogWarning.mockReset()
})

describe('TeaCustomersPanel list (row 22b)', () => {
    it('loads the CUSTOMER groups and the external keys of the organization', async () => {
        answer()
        await open()
        expect(groupCalls()).toHaveLength(1)
        expect(groupCalls()[0][0].variables).toEqual({ org: ORG, kind: 'CUSTOMER' })
        expect(keyCalls()).toHaveLength(1)
        expect(keyCalls()[0][0].variables).toEqual({ orgUuid: ORG })
    })

    it('shows the ACTIVE customers only, both customers with the switch on, never an INTERNAL group', async () => {
        answer([INTERNAL, ACME, OLD])
        const { w } = await open()
        expect(names(w)).toEqual(['Acme'])
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        expect(names(w)).toEqual(['Acme', 'Old'])
        expect(w.find('[data-testid="customers-table"]').text()).not.toContain('ci')
    })

    it('shows the heading, the lead sentence, the key count with the holders, the grants and the row actions', async () => {
        answer()
        const { w } = await open()
        expect(w.find('h5').text()).toBe('Customers')
        expect(w.find('[data-testid="customers-lead"]').text()).toBe('A customer is a group of external keys that reads the same products and '
            + 'components on TEA. Grants are read-only and TEA-only; deactivating a customer stops every key in it at once.')
        const counts = w.findAll('[data-testid="customers-keys-count"]')
        expect(counts.map((x: any) => x.text())).toEqual(['2', '0'])
        expect(counts[0].attributes('title')).toBe('Acme, Zeta')
        expect(w.findAll('[data-testid="customers-grants"]').map((x: any) => x.text())).toEqual(['organization-wide', '1 product'])
        expect(w.findAll('[data-testid="customer-edit"]')).toHaveLength(2)
        expect(w.findAll('[data-testid="customer-deactivate"]')).toHaveLength(2)
        expect(w.find('[data-testid="customer-restore"]').exists()).toBe(false)
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        expect(w.findAll('[data-testid="customer-restore"]')).toHaveLength(1)
        expect(w.findAll('[data-testid="customer-edit"]')).toHaveLength(2)
        expect(w.findAll('[data-testid="customer-deactivate"]')).toHaveLength(2)
    })

    it('says the server does not serve customers on a drift answer, with no table or create icon', async () => {
        answer(drift())
        const { w, notify } = await open()
        expect(w.find('[data-testid="customers-unsupported"]').text()).toBe('This server does not serve customer groups yet.')
        expect(w.find('[data-testid="customers-table"]').exists()).toBe(false)
        expect(w.find('[data-testid="customer-create"]').exists()).toBe(false)
        expect(notify).not.toHaveBeenCalled()
    })

    it('notifies a Not authorized error with its text', async () => {
        answer(refusal('Not authorized'))
        const { notify } = await open()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Error', 'Not authorized')
    })

    it('renders a name holding markup as text', async () => {
        answer([{ ...ACME, name: '<b>x</b>' }])
        const { w } = await open()
        expect(names(w)).toEqual(['<b>x</b>'])
        expect(w.find('[data-testid="customers-table"] b').exists()).toBe(false)
    })

    it('reloads both lists for a new organization', async () => {
        answer()
        const { w } = await open()
        await w.setProps({ orgUuid: 'o2' })
        await flushPromises()
        expect(groupCalls()[1][0].variables).toEqual({ org: 'o2', kind: 'CUSTOMER' })
        expect(keyCalls()[1][0].variables).toEqual({ orgUuid: 'o2' })
    })
})

describe('TeaCustomersPanel editor (row 22b)', () => {
    const HINT = 'Customer names and API key group names are one list in this organization, inactive ones included: '
        + 'a name already used on the API Key Groups tab is refused.'

    it('opens titled Create customer with the bounds, the hint, the keys and the external-key grants editor', async () => {
        answer([ACME], [...KEYS])
        const { w } = await open()
        await click(w, 'customer-create')
        expect(title(w)).toBe('Create customer')
        const inputs = w.findAllComponents(NInput)
        const nameInput = inputs.find((i: any) => i.attributes('data-testid') === 'customer-name')
        const descInput = inputs.find((i: any) => i.attributes('data-testid') === 'customer-description')
        expect(nameInput.props('maxlength')).toBe(512)
        expect(descInput.props('maxlength')).toBe(2048)
        expect(w.find('[data-testid="customer-name-hint"]').text()).toBe(HINT)
        expect(w.findComponent(ScopedPermissions).props('externalKey')).toBe(true)
        expect(w.find('[data-testid="customer-key-k2"]').text()).toBe('Acme (k2)')
    })

    it('opens titled Edit customer <name> from a row, with the hint and a member missing from the key list by uuid, checked', async () => {
        answer([{ ...ACME, apiKeys: ['k2', 'kx'] }])
        const { w } = await open()
        await click(w, 'customer-edit')
        expect(title(w)).toBe('Edit customer Acme')
        expect(w.find('[data-testid="customer-name-hint"]').text()).toBe(HINT)
        expect(w.find('[data-testid="customer-key-kx"]').text()).toBe('kx')
        const box = w.findAllComponents({ name: 'Checkbox' }).find((b: any) => b.attributes('data-testid') === 'customer-key-kx')
        expect(box.props('checked')).toBe(true)
    })

    it('creates with a key and a grant: createApiKeyGroup with kind CUSTOMER, then updateApiKeyGroup with the members and grants', async () => {
        answer([])
        const created = { uuid: 'c-new', name: 'Acme', description: null, status: 'ACTIVE', kind: 'CUSTOMER', apiKeys: [], permissions: { permissions: [] } }
        mutate.mockImplementation(async (opts: any) => opts.mutation === CREATE_CUSTOMER_GROUP
            ? { data: { createApiKeyGroup: created } } : { data: { updateApiKeyGroup: created } })
        const { w, notify } = await open()
        await click(w, 'customer-create')
        await typeName(w, ' Acme ')
        await checkKey(w, 'k9')
        await setGrants(w, ORG_READ_MODEL)
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(2)
        expect(mutate.mock.calls[0][0].mutation).toBe(CREATE_CUSTOMER_GROUP)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { org: ORG, name: 'Acme', description: null, kind: 'CUSTOMER' } })
        expect(mutate.mock.calls[1][0].mutation).toBe(UPDATE_CUSTOMER_GROUP)
        expect(mutate.mock.calls[1][0].variables).toEqual({ apiKeyGroup: { groupId: 'c-new', apiKeys: ['k9'], permissions: [ORG_READ_SENT] } })
        expect(mutate.mock.calls[1][0].variables.apiKeyGroup).not.toHaveProperty('approvals')
        expect(notify).toHaveBeenCalledWith('success', 'Created', 'Created customer "Acme"')
        expect(w.find('[data-testid="customer-editor"]').exists()).toBe(false)
    })

    it('creates with neither key nor grant in one mutation', async () => {
        answer([])
        mutate.mockResolvedValue({ data: { createApiKeyGroup: { uuid: 'c-new', name: 'Acme' } } })
        const { w } = await open()
        await click(w, 'customer-create')
        await typeName(w, 'Acme')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(CREATE_CUSTOMER_GROUP)
    })

    it('a refused follow-up shows the refusal, reloads, keeps the editor open on the created customer, and the next Save is one update', async () => {
        answer([])
        const created = { uuid: 'c-new', name: 'Acme', description: null, status: 'ACTIVE', kind: 'CUSTOMER', apiKeys: [], permissions: { permissions: [] } }
        mutate.mockImplementation(async (opts: any) => {
            if (opts.mutation === CREATE_CUSTOMER_GROUP) return { data: { createApiKeyGroup: created } }
            throw refusal('Only external keys can join a customer group: k9')
        })
        const { w, notify } = await open()
        await click(w, 'customer-create')
        await typeName(w, 'Acme')
        await checkKey(w, 'k9')
        await setGrants(w, ORG_READ_MODEL)
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Error', 'Only external keys can join a customer group: k9')
        expect(groupCalls()).toHaveLength(2)
        expect(w.find('[data-testid="customer-editor"]').exists()).toBe(true)
        expect(title(w)).toBe('Edit customer Acme')

        mutate.mockReset()
        mutate.mockResolvedValue({ data: { updateApiKeyGroup: created } })
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(UPDATE_CUSTOMER_GROUP)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { groupId: 'c-new', apiKeys: ['k9'], permissions: [ORG_READ_SENT] } })
    })

    it('a refused create (an inactive API key group holds the name) is shown verbatim and keeps the create form', async () => {
        answer([])
        const text = "An inactive group with the name 'Acme' already exists. Please restore it from the inactive groups list instead of creating a new one."
        mutate.mockRejectedValue(refusal(text))
        const { w, notify } = await open()
        await click(w, 'customer-create')
        await typeName(w, 'Acme')
        await checkKey(w, 'k2')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledWith('error', 'Error', text)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(CREATE_CUSTOMER_GROUP)
        expect(title(w)).toBe('Create customer')
        expect((w.find('[data-testid="customer-name"] input').element as HTMLInputElement).value).toBe('Acme')
    })

    it('an edit of the description sends only it; Save is disabled with nothing changed or the name cleared', async () => {
        answer([ACME])
        mutate.mockResolvedValue({ data: { updateApiKeyGroup: ACME } })
        const { w, notify } = await open()
        await click(w, 'customer-edit')
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        await typeName(w, '   ')
        expect(saveButton(w).attributes('disabled')).toBeDefined()
        await typeName(w, 'Acme')
        await w.find('[data-testid="customer-description"] textarea').setValue('Clinic')
        expect(saveButton(w).attributes('disabled')).toBeUndefined()
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { groupId: 'c1', description: 'Clinic' } })
        expect(notify).toHaveBeenCalledWith('success', 'Updated', 'Updated customer "Acme"')
    })

    it('sends one mutation for two Save clicks while pending', async () => {
        answer([ACME])
        let release: (v: any) => void = () => {}
        mutate.mockImplementation(() => new Promise(resolve => { release = resolve }))
        const { w } = await open()
        await click(w, 'customer-edit')
        await w.find('[data-testid="customer-description"] textarea').setValue('Clinic')
        await saveButton(w).trigger('click')
        await saveButton(w).trigger('click')
        release({ data: { updateApiKeyGroup: ACME } })
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
    })
})

describe('TeaCustomersPanel deactivate and restore (row 22b)', () => {
    it('deactivates only once confirmed', async () => {
        answer([ACME])
        mutate.mockResolvedValue({ data: { deleteApiKeyGroup: { ...ACME, status: 'INACTIVE' } } })
        const { w, notify } = await open()
        await click(w, 'customer-deactivate')
        expect(mutate).not.toHaveBeenCalled()
        expect(dialogWarning).toHaveBeenCalledTimes(1)
        const d = dialogWarning.mock.calls[0][0]
        expect(d.title).toBe('Deactivate customer')
        expect(d.content).toBe('Keys of "Acme" stop reading what it grants on TEA at their next request. The customer can be restored later.')
        await d.onPositiveClick()
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(DEACTIVATE_CUSTOMER_GROUP)
        expect(mutate.mock.calls[0][0].variables).toEqual({ groupUuid: 'c1' })
        expect(notify).toHaveBeenCalledWith('success', 'Deactivated', 'Deactivated customer "Acme"')
    })

    it('restores with status ACTIVE', async () => {
        answer([OLD])
        mutate.mockResolvedValue({ data: { updateApiKeyGroup: { ...OLD, status: 'ACTIVE' } } })
        const { w, notify } = await open()
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        await click(w, 'customer-restore')
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(UPDATE_CUSTOMER_GROUP)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { groupId: 'c3', status: 'ACTIVE' } })
        expect(notify).toHaveBeenCalledWith('success', 'Restored', 'Restored customer "Old"')
    })
})

// FDAW-40 mutation round: cells the surviving mutants showed missing.
describe('TeaCustomersPanel list details (FDAW-40 mutation)', () => {
    it('gives a reader no create icon and no row actions', async () => {
        answer()
        const { w } = await open({ isOrgAdmin: false })
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        expect(names(w)).toEqual(['Acme', 'Globex', 'Old'])
        for (const id of ['customer-create', 'customer-edit', 'customer-deactivate', 'customer-restore']) {
            expect(w.find(`[data-testid="${id}"]`).exists()).toBe(false)
        }
    })

    it('sorts the customers by name, shows the description, and marks an inactive row', async () => {
        answer([OLD, GLOBEX, ACME])
        const { w } = await open()
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        expect(names(w)).toEqual(['Acme', 'Globex', 'Old'])
        const rows = w.findAll('[data-testid="customers-table"] tbody tr')
        expect(rows[0].text()).toContain('Hospital')
        expect(rows.map((r: any) => r.classes().includes('inactive-row'))).toEqual([false, false, true])
        expect(w.findAllComponents(NTag).map((t: any) => t.props('type'))).toEqual(['success', 'success', 'default'])
    })

    it('loads nothing without an organization, and reads the external keys past the cache', async () => {
        answer()
        await open({ orgUuid: '' })
        expect(query).not.toHaveBeenCalled()
        await open()
        expect(keyCalls()[0][0].fetchPolicy).toBe('no-cache')
    })
})

describe('TeaCustomersPanel editor state (FDAW-40 mutation)', () => {
    const scopedModel = (w: any) => w.findComponent(ScopedPermissions).props('modelValue')

    it('names each grant from the list of its scope, the uuid when it is not listed', async () => {
        const grant = (scope: string, object: string) => ({ org: ORG, scope, object, type: 'READ_ONLY', functions: ['TEA_READ'] })
        answer([{ ...ACME, permissions: { permissions: [
            grant('PERSPECTIVE', 'p1'), grant('COMPONENT', 'comp1'), grant('COMPONENT', 'prod1'), grant('COMPONENT', 'gone')
        ] } }])
        const { w } = await open({ perspectives: [{ uuid: 'p1', name: 'EU' }], components: [{ uuid: 'comp1', name: 'Engine' }] })
        await click(w, 'customer-edit')
        expect(scopedModel(w).scopedPermissions.map((p: any) => p.objectName)).toEqual(['EU', 'Engine', 'Shop', 'gone'])
    })

    it('opens a create after an edit with no stored customer, an empty form and no grants', async () => {
        answer([ACME])
        const { w } = await open()
        await click(w, 'customer-edit')
        expect(scopedModel(w).orgPermission.type).toBe('READ_ONLY')
        await click(w, 'customer-cancel')
        await click(w, 'customer-create')
        expect(title(w)).toBe('Create customer')
        expect((w.find('[data-testid="customer-name"] input').element as HTMLInputElement).value).toBe('')
        expect(w.findAllComponents({ name: 'Checkbox' }).map((b: any) => b.props('checked'))).toEqual([false, false, false])
        expect(scopedModel(w)).toEqual({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })
    })

    it('unchecking a member sends the rest and leaves the stored list as it was', async () => {
        const acme = { ...ACME, apiKeys: ['k1', 'k2'] }
        answer([acme])
        mutate.mockResolvedValue({ data: { updateApiKeyGroup: acme } })
        const { w } = await open()
        await click(w, 'customer-edit')
        await checkKey(w, 'k1', false)
        expect(acme.apiKeys).toEqual(['k1', 'k2'])
        expect(saveButton(w).attributes('disabled')).toBeUndefined()
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { groupId: 'c1', apiKeys: ['k2'] } })
    })
})

describe('TeaCustomersPanel save outcomes (FDAW-40 mutation)', () => {
    it('a saved edit closes the editor, names the trimmed name and reloads', async () => {
        answer([ACME])
        mutate.mockResolvedValue({ data: { updateApiKeyGroup: ACME } })
        const { w, notify } = await open()
        await click(w, 'customer-edit')
        await typeName(w, '  Acme Two  ')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyGroup: { groupId: 'c1', name: 'Acme Two' } })
        expect(notify).toHaveBeenCalledWith('success', 'Updated', 'Updated customer "Acme Two"')
        expect(w.find('[data-testid="customer-editor"]').exists()).toBe(false)
        expect(groupCalls()).toHaveLength(2)
    })

    it('a refused edit is shown and keeps the editor open with the form', async () => {
        answer([ACME])
        mutate.mockRejectedValue(refusal("A group with the name 'ci' already exists"))
        const { w, notify } = await open()
        await click(w, 'customer-edit')
        await typeName(w, 'ci')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledWith('error', 'Error', "A group with the name 'ci' already exists")
        expect(title(w)).toBe('Edit customer Acme')
        expect((w.find('[data-testid="customer-name"] input').element as HTMLInputElement).value).toBe('ci')
        expect(groupCalls()).toHaveLength(1)
    })

    it('a create reloads the list', async () => {
        answer([])
        mutate.mockResolvedValue({ data: { createApiKeyGroup: { uuid: 'c-new', name: 'Acme' } } })
        const { w } = await open()
        await click(w, 'customer-create')
        await typeName(w, 'Acme')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(groupCalls()).toHaveLength(2)
    })

    it('a deactivate and a restore each reload; a refused restore is shown', async () => {
        answer([ACME, OLD])
        mutate.mockResolvedValue({ data: {} })
        const { w, notify } = await open()
        await click(w, 'customer-deactivate')
        await dialogWarning.mock.calls[0][0].onPositiveClick()
        await flushPromises()
        expect(groupCalls()).toHaveLength(2)
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        await click(w, 'customer-restore')
        expect(groupCalls()).toHaveLength(3)
        mutate.mockRejectedValue(refusal('Not authorized'))
        await click(w, 'customer-restore')
        expect(notify).toHaveBeenLastCalledWith('error', 'Error', 'Not authorized')
    })
})

describe('TeaCustomersPanel while a save is in flight (FDAW-40 mutation)', () => {
    it('ignores create, edit, deactivate, a confirmed deactivate and a second restore, then takes actions again', async () => {
        answer([ACME, OLD])
        let release: (v: any) => void = () => {}
        mutate.mockImplementation(() => new Promise(resolve => { release = resolve }))
        const { w } = await open()
        await click(w, 'customer-deactivate')
        const confirm = dialogWarning.mock.calls[0][0]
        w.findComponent(NSwitch).vm.$emit('update:value', true)
        await flushPromises()
        await click(w, 'customer-restore')
        expect(mutate).toHaveBeenCalledTimes(1)
        await click(w, 'customer-create')
        await click(w, 'customer-edit')
        expect(w.find('[data-testid="customer-editor"]').exists()).toBe(false)
        await click(w, 'customer-deactivate')
        expect(dialogWarning).toHaveBeenCalledTimes(1)
        await confirm.onPositiveClick()
        await click(w, 'customer-restore')
        expect(mutate).toHaveBeenCalledTimes(1)
        release({ data: {} })
        await flushPromises()
        await click(w, 'customer-create')
        expect(title(w)).toBe('Create customer')
    })

    it('takes actions again after a deactivate', async () => {
        answer([ACME])
        mutate.mockResolvedValue({ data: {} })
        const { w } = await open()
        await click(w, 'customer-deactivate')
        await dialogWarning.mock.calls[0][0].onPositiveClick()
        await flushPromises()
        await click(w, 'customer-create')
        expect(title(w)).toBe('Create customer')
    })

    it('disables the keys and Cancel, ignores a key toggle and a close, and the follow-up sends the keys as saved', async () => {
        answer([])
        let release: (v: any) => void = () => {}
        mutate.mockImplementation((opts: any) => opts.mutation === CREATE_CUSTOMER_GROUP
            ? new Promise(resolve => { release = resolve }) : Promise.resolve({ data: { updateApiKeyGroup: {} } }))
        const { w } = await open()
        await click(w, 'customer-create')
        await typeName(w, 'Acme')
        await checkKey(w, 'k9')
        await saveButton(w).trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="customer-cancel"]').attributes('disabled')).toBeDefined()
        expect(w.findAllComponents({ name: 'Checkbox' }).every((b: any) => b.props('disabled'))).toBe(true)
        await checkKey(w, 'k2')
        w.findComponent(NModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(w.find('[data-testid="customer-editor"]').exists()).toBe(true)
        release({ data: { createApiKeyGroup: { uuid: 'c-new', name: 'Acme' } } })
        await flushPromises()
        expect(mutate.mock.calls[1][0].variables.apiKeyGroup.apiKeys).toEqual(['k9'])
    })
})

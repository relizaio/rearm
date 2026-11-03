// @vitest-environment happy-dom
//
// System Settings, TEA Custom Domains (task TPF-144, architecture round 3 section 6.1): a global
// administrator on Pro sees the hosts the server lists, with the ReARM host flagged and its note, a
// searchable organization select per unassigned host, assign and unassign per row, and the server's
// refusal text under the row that failed. Nobody else, and no CE install, sees the section or sends
// its reads. The real Vuex store holds the user (fetchMyUser through the mocked GraphQL client);
// every answer is a clone of its fixture and every mounted page is unmounted after its row.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { NButton, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))

const store = (await import('@/store')).default.store
const { default: SystemSettings } = await import('./SystemSettings.vue')

const TEA_DOMAINS_HELP = 'Assign a domain to one organization. Its administrator then chooses, in the organization TEA settings, whether TEA is served on it.'
const TEA_DOMAINS_EMPTY = 'No TEA domain can be assigned: the TEA custom domain allow-list is empty and the instance host is not a DNS name.'
const REARM_HOST_NOTE = 'The ReARM host is unassigned by default. Assigning it exposes the ReARM host\'s /.well-known/tea and /tea/v1.0.0/ for that organization once the organization serves TEA on its domain.'

const CUSTOM_A = 'tea.acme.example'
const CUSTOM_B = 'tea.beta.example'
const REARM = 'rearm.example.com'
const STALE = 'old.gamma.example'
const ACME = { uuid: '11111111-1111-4111-8111-111111111111', name: 'Acme' }
const BETA = { uuid: '22222222-2222-4222-8222-222222222222', name: 'beta corp' }
const HOSTILE = { uuid: '33333333-3333-4333-8333-333333333333', name: '<img src=x onerror=alert(1)>' }
const ROWS = [
    { host: CUSTOM_A, org: ACME.uuid, orgName: 'Acme', isBaseuriHost: false, inAllowList: true, teaServing: 'DOMAIN' },
    { host: CUSTOM_B, org: null, orgName: null, isBaseuriHost: false, inAllowList: true, teaServing: null },
    { host: REARM, org: null, orgName: null, isBaseuriHost: true, inAllowList: true, teaServing: null },
    { host: STALE, org: HOSTILE.uuid, orgName: HOSTILE.name, isBaseuriHost: false, inAllowList: false, teaServing: 'NONE' },
]
const ASSIGNED_ROW = { ...ROWS[1], org: BETA.uuid, orgName: 'beta corp', teaServing: 'NONE' }

const PRO_ADMIN = { uuid: 'u1', installationType: 'MANAGED_SERVICE', isGlobalAdmin: true }
const PRO_USER = { ...PRO_ADMIN, isGlobalAdmin: false }
const CE_ADMIN = { ...PRO_ADMIN, installationType: 'OSS' }

/** A refusal as Apollo 4 hands it over: the server's entry, then graphql-java's null-propagation entry. */
function refusal (msg: string) {
    const e: any = new Error(msg + ' ... Cannot return null for non-nullable field')
    e.errors = [{ message: msg, extensions: { classification: 'BAD_REQUEST' } },
        { message: 'The field at path ... was declared as a non null type', extensions: { classification: 'NullValueInNonNullableField' } }]
    return e
}

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const byId = (w: any, id: string) => w.find('[data-testid="' + id + '"]')
const callsOf = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name)

function answers (user: any, rows: any[] = ROWS, orgs: any[] = [BETA, ACME, HOSTILE],
    hostsAnswer?: () => Promise<any>) {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'user': return { data: { user: structuredClone(user) } }
        case 'getSystemInfoIsSet': return { data: { getSystemInfoIsSet: { emailDetailsSet: false, defaultOrg: null, defaultOrgDetails: null } } }
        case 'getLicense': return { data: { license: null } }
        case 'TeaHostAssignments': return hostsAnswer ? hostsAnswer() : { data: { teaHostAssignments: structuredClone(rows) } }
        case 'AllOrganizations': return { data: { allOrganizations: structuredClone(orgs) } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

const mounted: VueWrapper[] = []

async function mountPage () {
    const w = mount(SystemSettings, { global: { plugins: [store] } })
    mounted.push(w)
    await flushPromises()
    await flushPromises()
    return w
}

async function chooseOrg (w: any, host: string, org: string) {
    byId(w, 'tea-domain-org-select-' + host).findComponent(NSelect).vm.$emit('update:value', org)
    await flushPromises()
}

async function click (w: any, id: string) {
    await byId(w, id).find('button').trigger('click')
    await flushPromises()
}

describe('System Settings, TEA Custom Domains', () => {
    let errors: any

    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        store.commit('SET_MY_USER', {})
        errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    })

    afterEach(() => {
        mounted.splice(0).forEach(w => w.unmount())
        errors.mockRestore()
    })

    it('1: a global administrator on Pro sees the hosts in the server order, the ReARM host flagged with its note', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        expect(store.getters.myuser.isGlobalAdmin).toBe(true)
        const block = byId(w, 'tea-domains-block')
        expect(block.exists()).toBe(true)
        expect(block.find('h4').text()).toBe('TEA Custom Domains:')
        expect(byId(w, 'tea-domains-help').text()).toBe(TEA_DOMAINS_HELP)
        expect(callsOf('TeaHostAssignments')).toHaveLength(1)
        expect(callsOf('TeaHostAssignments')[0][0].fetchPolicy).toBe('no-cache')
        expect(callsOf('AllOrganizations')).toHaveLength(1)
        expect(w.findAll('[data-testid^="tea-domain-row-"]').map(r => r.attributes('data-testid')))
            .toEqual(ROWS.map(r => 'tea-domain-row-' + r.host))
        expect(byId(w, 'tea-domain-rearm-host-' + REARM).text()).toBe('ReARM host')
        expect(w.findAll('[data-testid^="tea-domain-rearm-host-"]').filter(e => e.attributes('data-testid') !== 'tea-domain-rearm-host-note')
            .map(e => e.attributes('data-testid'))).toEqual(['tea-domain-rearm-host-' + REARM])
        expect(byId(w, 'tea-domain-rearm-host-note').attributes('aria-label')).toBe(REARM_HOST_NOTE)
        expect(byId(w, 'tea-domain-row-' + REARM).find('[data-testid="tea-domain-rearm-host-note"]').exists()).toBe(true)
        expect(w.findAll('[data-testid="tea-domain-rearm-host-note"]')).toHaveLength(1)
        expect(byId(w, 'tea-domain-unlisted-' + STALE).text()).toBe('not in the allow-list')
        expect(byId(w, 'tea-domain-unlisted-' + REARM).exists()).toBe(false)
        expect(byId(w, 'tea-domain-org-' + CUSTOM_A).text()).toContain('Acme')
        expect(byId(w, 'tea-domain-serving-' + CUSTOM_A).text()).toBe('TEA serving: On the assigned domain')
        expect(byId(w, 'tea-domain-org-' + CUSTOM_B).text()).toBe('Unassigned')
        for (const r of ROWS) {
            const assigned = !!r.org
            expect(byId(w, 'tea-domain-unassign-' + r.host).exists()).toBe(assigned)
            expect(byId(w, 'tea-domain-org-select-' + r.host).exists()).toBe(!assigned)
            expect(byId(w, 'tea-domain-assign-' + r.host).exists()).toBe(!assigned)
        }
        expect(errors).not.toHaveBeenCalled()
    })

    it('2: not a global administrator, or CE: no section and no host or organization read', async () => {
        for (const user of [PRO_USER, CE_ADMIN]) {
            store.commit('SET_MY_USER', {})
            query.mockReset()
            answers(user)
            const w = await mountPage()
            expect(byId(w, 'tea-domains-block').exists()).toBe(false)
            expect(callsOf('TeaHostAssignments')).toHaveLength(0)
            expect(callsOf('AllOrganizations')).toHaveLength(0)
            expect(w.text()).not.toContain('TEA Custom Domains')
            if (user === CE_ADMIN) expect(w.text()).toContain('Email Sending Configuration')
            mounted.splice(0).forEach(m => m.unmount())
        }
        expect(errors).not.toHaveBeenCalled()
    })

    it('3: the organization select is searchable and sorted by name; Assign waits for a choice', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        const sel = byId(w, 'tea-domain-org-select-' + CUSTOM_B).findComponent(NSelect)
        expect(sel.props('filterable')).toBe(true)
        const expected = [{ label: 'Acme', value: ACME.uuid }, { label: 'beta corp', value: BETA.uuid },
            { label: HOSTILE.name, value: HOSTILE.uuid }].sort((a, b) => a.label.localeCompare(b.label))
        expect(sel.props('options')).toEqual(expected)
        const button = () => byId(w, 'tea-domain-assign-' + CUSTOM_B).findComponent(NButton)
        expect(button().props('disabled')).toBe(true)
        await chooseOrg(w, CUSTOM_B, BETA.uuid)
        expect(button().props('disabled')).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('4: Assign sends the host and the organization and shows the answered row without a re-read', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        await chooseOrg(w, CUSTOM_B, BETA.uuid)
        mutate.mockResolvedValueOnce({ data: { assignTeaHost: structuredClone(ASSIGNED_ROW) } })
        await click(w, 'tea-domain-assign-' + CUSTOM_B)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation(mutate.mock.calls[0][0])).toBe('AssignTeaHost')
        expect(mutate.mock.calls[0][0].variables).toEqual({ host: CUSTOM_B, org: BETA.uuid })
        expect(byId(w, 'tea-domain-org-' + CUSTOM_B).text()).toContain('beta corp')
        expect(byId(w, 'tea-domain-serving-' + CUSTOM_B).text()).toBe('TEA serving: Not served')
        expect(byId(w, 'tea-domain-unassign-' + CUSTOM_B).exists()).toBe(true)
        expect(byId(w, 'tea-domain-org-select-' + CUSTOM_B).exists()).toBe(false)
        expect(w.findAll('[data-testid^="tea-domain-error-"]')).toHaveLength(0)
        expect(callsOf('TeaHostAssignments')).toHaveLength(1)
        expect(errors).not.toHaveBeenCalled()
    })

    it('5: an Assign refusal renders the server text verbatim under its row and leaves the row unassigned', async () => {
        for (const msg of ['Host tea.beta.example is already assigned to organization Acme',
            'Host tea.beta.example is not in the TEA custom domain allow-list (relizaprops.teaCustomDomains) and is not the instance host']) {
            errors.mockClear()
            mutate.mockReset()
            answers(PRO_ADMIN)
            const w = await mountPage()
            await chooseOrg(w, CUSTOM_B, BETA.uuid)
            mutate.mockRejectedValueOnce(refusal(msg))
            await click(w, 'tea-domain-assign-' + CUSTOM_B)
            expect(byId(w, 'tea-domain-error-' + CUSTOM_B).text()).toBe(msg)
            expect(w.findAll('[data-testid^="tea-domain-error-"]')).toHaveLength(1)
            expect(byId(w, 'tea-domain-org-' + CUSTOM_B).text()).toBe('Unassigned')
            expect(byId(w, 'tea-domain-org-select-' + CUSTOM_B).exists()).toBe(true)
            expect(errors).toHaveBeenCalledTimes(1)

            mutate.mockResolvedValueOnce({ data: { assignTeaHost: structuredClone(ASSIGNED_ROW) } })
            await click(w, 'tea-domain-assign-' + CUSTOM_B)
            expect(byId(w, 'tea-domain-error-' + CUSTOM_B).exists()).toBe(false)
            mounted.splice(0).forEach(m => m.unmount())
        }
    })

    it('6a: unassigning a host off the allow-list removes its row', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        mutate.mockResolvedValueOnce({ data: { unassignTeaHost: true } })
        await click(w, 'tea-domain-unassign-' + STALE)
        expect(operation(mutate.mock.calls[0][0])).toBe('UnassignTeaHost')
        expect(mutate.mock.calls[0][0].variables).toEqual({ host: STALE })
        expect(byId(w, 'tea-domain-row-' + STALE).exists()).toBe(false)
        expect(byId(w, 'tea-domain-unlisted-' + STALE).exists()).toBe(false)
        expect(w.findAll('[data-testid^="tea-domain-row-"]').map(r => r.attributes('data-testid')))
            .toEqual([CUSTOM_A, CUSTOM_B, REARM].map(h => 'tea-domain-row-' + h))
        expect(byId(w, 'tea-domain-org-' + CUSTOM_A).text()).toContain('Acme')
        expect(errors).not.toHaveBeenCalled()
    })

    it('6b: an Unassign refusal renders verbatim and the row stays assigned', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        const msg = 'Organization Acme serves TEA on tea.acme.example; set its TEA serving to NONE or LEGACY_UUID first'
        mutate.mockRejectedValueOnce(refusal(msg))
        await click(w, 'tea-domain-unassign-' + CUSTOM_A)
        expect(byId(w, 'tea-domain-error-' + CUSTOM_A).text()).toBe(msg)
        expect(byId(w, 'tea-domain-org-' + CUSTOM_A).text()).toContain('Acme')
        expect(byId(w, 'tea-domain-unassign-' + CUSTOM_A).exists()).toBe(true)
        expect(errors).toHaveBeenCalledTimes(1)
    })

    it('6c: unassigning a listed host keeps the row, unassigned, with the select and Assign', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        mutate.mockResolvedValueOnce({ data: { unassignTeaHost: true } })
        await click(w, 'tea-domain-unassign-' + CUSTOM_A)
        expect(byId(w, 'tea-domain-row-' + CUSTOM_A).exists()).toBe(true)
        expect(byId(w, 'tea-domain-org-' + CUSTOM_A).text()).toBe('Unassigned')
        expect(byId(w, 'tea-domain-serving-' + CUSTOM_A).exists()).toBe(false)
        expect(byId(w, 'tea-domain-org-select-' + CUSTOM_A).exists()).toBe(true)
        expect(byId(w, 'tea-domain-assign-' + CUSTOM_A).exists()).toBe(true)
        expect(errors).not.toHaveBeenCalled()
    })

    it('7a: an empty list says why nothing can be assigned', async () => {
        answers(PRO_ADMIN, [])
        const w = await mountPage()
        expect(byId(w, 'tea-domains-empty').text()).toBe(TEA_DOMAINS_EMPTY)
        expect(byId(w, 'tea-domains-table').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('7b: a failed host read shows the server text and no table', async () => {
        answers(PRO_ADMIN, ROWS, [BETA, ACME, HOSTILE], async () => { throw refusal('Not authorized') })
        const w = await mountPage()
        expect(byId(w, 'tea-domains-load-error').text()).toBe('Not authorized')
        expect(byId(w, 'tea-domains-table').exists()).toBe(false)
        expect(errors).toHaveBeenCalled()
    })

    it('7c: the section says it is loading until the reads settle', async () => {
        answers(PRO_ADMIN, ROWS, [BETA, ACME, HOSTILE], () => new Promise(() => {}))
        const w = mount(SystemSettings, { global: { plugins: [store] } })
        mounted.push(w)
        await flushPromises()
        expect(byId(w, 'tea-domains-loading').exists()).toBe(true)
        expect(errors).not.toHaveBeenCalled()
    })

    it('8: an organization name stays text in the cell and in the select', async () => {
        answers(PRO_ADMIN)
        const w = await mountPage()
        expect(byId(w, 'tea-domain-org-' + STALE).text()).toContain('<img src=x onerror=alert(1)>')
        expect(w.find('img').exists()).toBe(false)
        const options = byId(w, 'tea-domain-org-select-' + CUSTOM_B).findComponent(NSelect).props('options') as any[]
        expect(options.find(o => o.value === HOSTILE.uuid).label).toBe('<img src=x onerror=alert(1)>')
        expect(errors).not.toHaveBeenCalled()
    })

    it('9: wiring and source pins', () => {
        const page = readFileSync(resolve(__dirname, 'SystemSettings.vue'), 'utf8')
        const comp = readFileSync(resolve(__dirname, 'TeaDomainAssignments.vue'), 'utf8')
        const count = (s: string, sub: string) => s.split(sub).length - 1
        // (a)
        expect(count(page, "import TeaDomainAssignments from './TeaDomainAssignments.vue'")).toBe(1)
        const tag = '<TeaDomainAssignments v-if="myUser && myUser.installationType !== \'OSS\'" />'
        expect(count(page, tag)).toBe(1)
        expect(page.indexOf(tag)).toBeGreaterThan(page.indexOf('class="defaultOrgBlock"'))
        expect(page.indexOf(tag)).toBeLessThan(page.indexOf('class="finalizeAllReleasesBlock"'))
        // (b)
        expect(count(page, "import graphqlQueries from '@/utils/graphqlQueries'")).toBe(1)
        expect(page).toContain('graphqlQueries.AllOrganizationsGql')
        expect(page).not.toContain('query allOrganizations')
        // (c)
        expect(count(comp, "import { graphqlRefusalText } from '@/utils/graphqlErrorText'")).toBe(1)
        expect(count(comp, "import graphqlQueries from '@/utils/graphqlQueries'")).toBe(1)
        for (const d of ['TeaHostAssignmentsGql', 'AssignTeaHostGql', 'UnassignTeaHostGql', 'AllOrganizationsGql']) {
            expect(comp).toContain('graphqlQueries.' + d)
        }
        expect(comp).toContain('filterable')
        // (d)
        expect(page).not.toContain('v-html')
        expect(comp).not.toContain('v-html')
        expect(comp).not.toMatch(/\bgql`/)
        expect(comp).not.toContain('Swal')
        // (e)
        expect(count(comp, REARM_HOST_NOTE)).toBe(1)
    })
})

// @vitest-environment happy-dom
//
// The organization TEA panel's serving choices (task TPF-144, architecture round 3 section 6.2): the
// assigned domain or the notice without one, the serving mode (NONE, the legacy TEA id path, the
// assigned domain) saved on change with the effective API base the server answers, the
// /.well-known/tea hosting choice with its URL, the not-served states, the refusal text with the
// server's state re-read, and, on CE, the Discovery block of before with no serving control. The
// GraphQL client is mocked and answers by operation with clones of the fixtures; the profile editor
// is stubbed; the edition rows take installationType from the real store. Task TPF-151 (rows 1, 7a, 7c,
// 9, 14, 15, 16): the legacy TEA id path is offered without a TEA id, since the server mints one on a
// serving mode other than NONE, and the save renders the minted id and API base it answers.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { NNotificationProvider, NRadio, NRadioGroup } from 'naive-ui'
import { createMemoryHistory, createRouter } from 'vue-router'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))
vi.mock('./TeaProfileEditor.vue', () => ({ default: { name: 'TeaProfileEditor', render: () => null } }))

const { default: Panel } = await import('./TeaOrgPanel.vue')
const store = (await import('@/store')).default.store
const graphqlQueries = (await import('@/utils/graphqlQueries')).default

const ORG = '44444444-4444-4444-8444-444444444444'
const TEA = '55555555-5555-4555-8555-555555555555'
const HOST = 'tea.acme.example'
const LEGACY_API = 'https://rearm.example.com/tea/' + TEA
const DOMAIN_API = 'https://' + HOST + '/tea'
const WK = 'https://' + HOST + '/.well-known/tea'
const doc = (api: string) => '{"schemaVersion":1,"endpoints":[{"url":"' + api + '","versions":["1.0.0"],"priority":1}]}'

const NONE_NO_DOMAIN = { org: ORG, teaUuid: TEA, apiBase: null, wellKnownDocument: null, assignedHost: null,
    teaServing: 'NONE', teaWellKnownHosting: 'NONE', wellKnownUrl: null }
const NONE_DOMAIN = { ...NONE_NO_DOMAIN, assignedHost: HOST }
const DOMAIN_SERVED = { ...NONE_DOMAIN, teaServing: 'DOMAIN', apiBase: DOMAIN_API, wellKnownDocument: doc(DOMAIN_API) }
const DOMAIN_HOSTED = { ...DOMAIN_SERVED, teaWellKnownHosting: 'DOMAIN', wellKnownUrl: WK }
const LEGACY_DOMAIN = { ...NONE_DOMAIN, teaServing: 'LEGACY_UUID', apiBase: LEGACY_API, wellKnownDocument: doc(LEGACY_API) }
const DOMAIN_NO_ID = { ...NONE_DOMAIN, teaUuid: null, teaServing: 'DOMAIN' }
const DOMAIN_LOST = { ...NONE_NO_DOMAIN, teaServing: 'DOMAIN' }
const CE_DISCOVERY = { org: ORG, teaUuid: TEA, apiBase: LEGACY_API, wellKnownDocument: doc(LEGACY_API) }
const LEGACY_NO_DOMAIN = { ...LEGACY_DOMAIN, assignedHost: null }
const NONE_NO_ID = { ...NONE_NO_DOMAIN, teaUuid: null }
const NONE_DOMAIN_NO_ID = { ...NONE_DOMAIN, teaUuid: null }
// The server's answer after the mint: teaUuid is TEA.
const LEGACY_MINTED = { ...NONE_NO_DOMAIN, teaServing: 'LEGACY_UUID', apiBase: LEGACY_API, wellKnownDocument: doc(LEGACY_API) }
// DOMAIN stored without an id and without an assignable host.
const DOMAIN_LOST_NO_ID = { ...DOMAIN_LOST, teaUuid: null }

const TEA_NO_DOMAIN_NOTICE = 'No TEA domain is assigned to this organization. A global administrator can assign one in System Settings.'
const TEA_NOT_SERVED = 'Not served: the TEA API of this organization answers nothing. Choose a serving mode above to enable it.'
const TEA_NOT_SERVED_NO_ID = 'Not served yet: this organization has no TEA id. Choosing Not served and then this serving mode again mints one, as does choosing the Legacy path with the organization TEA id.'
const TEA_DOMAIN_LOST = 'Not served: this organization has no assignable TEA domain any more. A global administrator can assign one in System Settings, or choose another serving mode.'
const TEA_HOSTING_NEEDS_DOMAIN = 'The instance hosts /.well-known/tea on the domain only while the serving mode is On the assigned domain.'
const TEA_SERVING_PUBLIC_NOTE = "Choosing a serving mode makes this organization's public TEA content reachable at the API base below at once."
const NO_DOMAIN_REFUSAL = 'No TEA domain is assigned to this organization; a global administrator assigns one'
const PRO_NOT_MINTED = 'Organization TEA id: not minted yet: choosing a serving mode other than Not served mints it, as does enabling publishing on a profile.'
const CE_NOT_MINTED = 'Organization TEA id: not minted yet: enable publishing on a profile to mint it.'

// TeaOrgPanel.vue lines 6-16 at 76021180, the Discovery block before TPF-144 (the CE branch keeps it).
const CE_MARKUP_76021180 = `<div v-if="discovery?.teaUuid">
<div><strong>Organization TEA id:</strong> <code data-testid="tea-org-uuid">{{ discovery.teaUuid }}</code></div>
<div><strong>TEA API base:</strong> <code data-testid="tea-api-base">{{ discovery.apiBase }}</code></div>
<p class="text-muted mt-2">Host this document at https://&lt;your TEI domain&gt;/.well-known/tea</p>
<n-input data-testid="tea-well-known" type="textarea" readonly :value="discovery.wellKnownDocument"
:autosize="{ minRows: 3, maxRows: 8 }" />
<n-button size="small" class="mt-2" data-testid="tea-well-known-copy" @click="copyWellKnown">Copy</n-button>
</div>
<div v-else data-testid="tea-org-uuid-missing" class="text-muted">
Organization TEA id: not minted yet: enable publishing on a profile to mint it.
</div>`

const PRO_TESTIDS = ['tea-assigned-domain', 'tea-no-domain-notice', 'tea-serving-mode', 'tea-effective-base', 'tea-not-served',
    'tea-not-served-no-id', 'tea-domain-lost', 'tea-well-known-hosting', 'tea-hosting-help', 'tea-well-known-hosted',
    'tea-well-known-url', 'tea-well-known-self-host', 'tea-serving-error']

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
const servingGroup = (w: any) => byId(w, 'tea-serving-mode').findComponent(NRadioGroup)
const hostingGroup = (w: any) => byId(w, 'tea-well-known-hosting').findComponent(NRadioGroup)
const checked = (w: any, id: string) => (byId(w, id).find('input').element as HTMLInputElement).checked
// What the browser renders: NRadio's disabled prop defaults to undefined, so props('disabled') cannot tell unset from false.
const inputDisabled = (w: any, id: string) => (byId(w, id).find('input').element as HTMLInputElement).disabled
const missingText = (w: any) => byId(w, 'tea-org-uuid-missing').text().replace(/\s+/g, ' ').trim()

/** The n-th TeaOrgServing read answers the n-th discovery (the last repeats; a function is called and may throw). */
function answers (...discoveries: any[]) {
    let n = 0
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'TeaOrgServing': {
            const d = discoveries[Math.min(n++, discoveries.length - 1)]
            return { data: { teaOrgDiscovery: structuredClone(typeof d === 'function' ? d() : d) } }
        }
        case 'TeaOrgDiscovery': return { data: { teaOrgDiscovery: structuredClone(CE_DISCOVERY) } }
        case 'TeaProfilesOfOrg': return { data: { teaProfilesOfOrg: [] } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

const mounted: VueWrapper[] = []

async function mountPanel (props: Record<string, any> = {}) {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:any(.*)*', component: { render: () => null } }] })
    const all = { orgUuid: ORG, isOrgAdmin: true, installationType: 'MANAGED_SERVICE', components: [], perspectives: [], ...props }
    const w = mount(defineComponent({ render: () => h(NNotificationProvider, () => h(Panel, all)) }), { global: { plugins: [router] } })
    mounted.push(w)
    await flushPromises()
    return w
}

async function mountWith (discovery: any, props: Record<string, any> = {}) {
    answers(discovery)
    return mountPanel(props)
}

async function emitServing (w: any, v: string) {
    servingGroup(w).vm.$emit('update:value', v)
    await flushPromises()
}

function setResult (d: any) {
    mutate.mockResolvedValueOnce({ data: { setTeaOrgServing: structuredClone(d) } })
}

describe('TeaOrgPanel serving choices', () => {
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

    it('1: no domain, default NONE: the notice, NONE and LEGACY_UUID only, not served', async () => {
        const w = await mountWith(NONE_NO_DOMAIN)
        expect(byId(w, 'tea-no-domain-notice').text()).toBe(TEA_NO_DOMAIN_NOTICE)
        expect(byId(w, 'tea-assigned-domain').exists()).toBe(false)
        expect(byId(w, 'tea-serving-option-NONE').exists()).toBe(true)
        expect(byId(w, 'tea-serving-option-LEGACY_UUID').exists()).toBe(true)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(false)
        expect(servingGroup(w).props('value')).toBe('NONE')
        expect(inputDisabled(w, 'tea-serving-option-LEGACY_UUID')).toBe(false)
        expect(byId(w, 'tea-serving-public-note').text()).toBe(TEA_SERVING_PUBLIC_NOTE)
        expect(byId(w, 'tea-not-served').text()).toBe(TEA_NOT_SERVED)
        for (const id of ['tea-api-base', 'tea-not-served-no-id', 'tea-domain-lost', 'tea-well-known-hosting', 'tea-well-known-url', 'tea-well-known']) {
            expect(byId(w, id).exists()).toBe(false)
        }
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(errors).not.toHaveBeenCalled()
    })

    it('2: a domain assigned: the domain, its serving option, the hosting choice with DOMAIN disabled', async () => {
        const w = await mountWith(NONE_DOMAIN)
        expect(byId(w, 'tea-assigned-domain').text()).toContain(HOST)
        expect(byId(w, 'tea-no-domain-notice').exists()).toBe(false)
        expect(byId(w, 'tea-serving-option-DOMAIN').text()).toBe('On the assigned domain')
        expect(byId(w, 'tea-well-known-hosting').exists()).toBe(true)
        const domainHosting = byId(w, 'tea-hosting-option-DOMAIN')
        expect(domainHosting.findComponent(NRadio).props('disabled')).toBe(true)
        expect(domainHosting.text()).toBe('Hosted on tea.acme.example')
        expect(byId(w, 'tea-hosting-help').text()).toBe(TEA_HOSTING_NEEDS_DOMAIN)
        expect(errors).not.toHaveBeenCalled()
    })

    it('3: NONE to DOMAIN calls the mutation and the API base follows the answer', async () => {
        const w = await mountWith(NONE_DOMAIN)
        setResult(DOMAIN_SERVED)
        await emitServing(w, 'DOMAIN')
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation(mutate.mock.calls[0][0])).toBe('SetTeaOrgServing')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'DOMAIN', teaWellKnownHosting: 'NONE' })
        expect(byId(w, 'tea-api-base').text()).toBe(DOMAIN_API)
        expect(byId(w, 'tea-not-served').exists()).toBe(false)
        expect(servingGroup(w).props('value')).toBe('DOMAIN')
        expect(byId(w, 'tea-hosting-option-DOMAIN').findComponent(NRadio).props('disabled')).toBe(false)
        expect(byId(w, 'tea-hosting-help').exists()).toBe(false)
        expect(byId(w, 'tea-well-known-self-host').exists()).toBe(true)
        expect((byId(w, 'tea-well-known').find('textarea').element as HTMLTextAreaElement).value).toBe(doc(DOMAIN_API))
        expect(byId(w, 'tea-well-known-url').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('4: hosting NONE to DOMAIN calls the mutation and the hosted URL follows the answer', async () => {
        const w = await mountWith(DOMAIN_SERVED)
        setResult(DOMAIN_HOSTED)
        hostingGroup(w).vm.$emit('update:value', 'DOMAIN')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'DOMAIN', teaWellKnownHosting: 'DOMAIN' })
        expect(byId(w, 'tea-well-known-url').text()).toBe(WK)
        expect(byId(w, 'tea-well-known-self-host').exists()).toBe(false)
        expect(byId(w, 'tea-api-base').text()).toBe(DOMAIN_API)
        expect(errors).not.toHaveBeenCalled()
    })

    it('5: leaving DOMAIN sends hosting NONE', async () => {
        const w = await mountWith(DOMAIN_HOSTED)
        setResult(LEGACY_DOMAIN)
        await emitServing(w, 'LEGACY_UUID')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'LEGACY_UUID', teaWellKnownHosting: 'NONE' })
        expect(byId(w, 'tea-api-base').text()).toBe(LEGACY_API)
        expect(byId(w, 'tea-well-known-url').exists()).toBe(false)
        expect(byId(w, 'tea-well-known-self-host').exists()).toBe(true)
        expect(byId(w, 'tea-hosting-help').exists()).toBe(true)

        setResult(NONE_DOMAIN)
        await emitServing(w, 'NONE')
        expect(mutate.mock.calls[1][0].variables).toEqual({ org: ORG, teaServing: 'NONE', teaWellKnownHosting: 'NONE' })
        expect(byId(w, 'tea-not-served').exists()).toBe(true)
        expect(byId(w, 'tea-api-base').exists()).toBe(false)
        expect(byId(w, 'tea-well-known').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('6: a refusal renders verbatim after the discovery is re-read, and the next success clears it', async () => {
        const w = await mountWith(NONE_DOMAIN)
        mutate.mockRejectedValueOnce(refusal(NO_DOMAIN_REFUSAL))
        await emitServing(w, 'DOMAIN')
        const reads = callsOf('TeaOrgServing')
        expect(reads).toHaveLength(2)
        expect(reads[1][0].variables).toEqual({ org: ORG })
        expect(reads[1][0].fetchPolicy).toBe('no-cache')
        expect(callsOf('TeaProfilesOfOrg')).toHaveLength(1)
        expect(byId(w, 'tea-serving-error').text()).toBe(NO_DOMAIN_REFUSAL)
        expect(servingGroup(w).props('value')).toBe('NONE')
        expect(byId(w, 'tea-not-served').exists()).toBe(true)
        expect(byId(w, 'tea-api-base').exists()).toBe(false)
        expect(errors).toHaveBeenCalledTimes(1)

        setResult(DOMAIN_SERVED)
        await emitServing(w, 'DOMAIN')
        expect(byId(w, 'tea-serving-error').exists()).toBe(false)
    })

    it('7a: not NONE without a TEA id (stored before the mint): not served yet, LEGACY_UUID offered', async () => {
        const w = await mountWith(DOMAIN_NO_ID)
        expect(byId(w, 'tea-not-served-no-id').text()).toBe(TEA_NOT_SERVED_NO_ID)
        expect(byId(w, 'tea-domain-lost').exists()).toBe(false)
        expect(byId(w, 'tea-api-base').exists()).toBe(false)
        expect(byId(w, 'tea-not-served').exists()).toBe(false)
        expect(inputDisabled(w, 'tea-serving-option-LEGACY_UUID')).toBe(false)
        expect(missingText(w)).toBe(PRO_NOT_MINTED)
        expect(errors).not.toHaveBeenCalled()
    })

    it('7b: DOMAIN without an assignable host: the checked DOMAIN option and the domain-lost text', async () => {
        const w = await mountWith(DOMAIN_LOST)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(true)
        expect(servingGroup(w).props('value')).toBe('DOMAIN')
        expect(byId(w, 'tea-domain-lost').text()).toBe(TEA_DOMAIN_LOST)
        expect(byId(w, 'tea-no-domain-notice').exists()).toBe(true)
        expect(byId(w, 'tea-well-known-hosting').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('7c: DOMAIN without a TEA id and without an assignable host: domain lost, LEGACY_UUID offered and minting', async () => {
        const w = await mountWith(DOMAIN_LOST_NO_ID)
        // (a)
        expect(byId(w, 'tea-domain-lost').text()).toBe(TEA_DOMAIN_LOST)
        for (const id of ['tea-not-served-no-id', 'tea-not-served', 'tea-api-base']) expect(byId(w, id).exists(), id).toBe(false)
        expect(byId(w, 'tea-no-domain-notice').exists()).toBe(true)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(true)
        expect(servingGroup(w).props('value')).toBe('DOMAIN')
        expect(inputDisabled(w, 'tea-serving-option-LEGACY_UUID')).toBe(false)
        expect(missingText(w)).toBe(PRO_NOT_MINTED)
        // (b)
        setResult(LEGACY_MINTED)
        await emitServing(w, 'LEGACY_UUID')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'LEGACY_UUID', teaWellKnownHosting: 'NONE' })
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(byId(w, 'tea-api-base').text()).toBe(LEGACY_API)
        expect(byId(w, 'tea-domain-lost').exists()).toBe(false)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('8: read-only for a non-administrator', async () => {
        const w = await mountWith(NONE_DOMAIN, { isOrgAdmin: false })
        expect(servingGroup(w).props('disabled')).toBe(true)
        expect(hostingGroup(w).props('disabled')).toBe(true)
        await emitServing(w, 'DOMAIN')
        expect(mutate).not.toHaveBeenCalled()
    })

    it('9: wiring and source pins', () => {
        const source = readFileSync(resolve(__dirname, 'TeaOrgPanel.vue'), 'utf8')
        const count = (sub: string) => source.split(sub).length - 1
        // (a)
        expect(count("import { graphqlRefusalText } from '@/utils/graphqlErrorText'")).toBe(1)
        const teaProfileImport = source.split('\n').find(l => l.includes("from '@/utils/teaProfile'"))!
        expect(teaProfileImport).toContain('TEA_SERVING_LABELS')
        // (b)
        expect(source).toContain('graphqlQueries.TeaOrgServingGql')
        expect(source).toContain('graphqlQueries.SetTeaOrgServingGql')
        expect(count('graphqlQueries.TeaOrgDiscoveryGql')).toBe(1)
        expect(count('proEdition.value ? graphqlQueries.TeaOrgServingGql : graphqlQueries.TeaOrgDiscoveryGql')).toBe(1)
        // (c)
        expect(source).not.toContain('v-html')
        // (d)
        for (const s of ["'https://' +", "+ '/.well-known/tea'", 'assignedHost +', 'apiBase +']) expect(source).not.toContain(s)
        // (e)
        for (const t of [TEA_NOT_SERVED, TEA_NOT_SERVED_NO_ID, TEA_DOMAIN_LOST, TEA_HOSTING_NEEDS_DOMAIN, TEA_NO_DOMAIN_NOTICE]) {
            expect(count(t)).toBe(1)
        }
        // (g)
        expect(count(PRO_NOT_MINTED)).toBe(1)
        expect(count(CE_NOT_MINTED)).toBe(1)
        expect(source.indexOf(CE_NOT_MINTED)).toBeLessThan(source.indexOf('<template v-else>'))
        // (h)
        expect(source).not.toContain('disabled: !discovery.value?.teaUuid')
        expect(source).not.toContain('LEGACY_UUID needs a TEA id')
        const options = source.slice(source.indexOf('const servingOptions'))
        expect(options.slice(0, options.indexOf('})')).includes('teaUuid')).toBe(false)
        // (i)
        expect(count('v-else-if="!discovery.teaUuid && discovery.assignedHost" data-testid="tea-not-served-no-id"')).toBe(1)
        // (f)
        expect(count('async function refreshDiscovery')).toBe(1)
        const setServing = source.slice(source.indexOf('async function setServing'), source.indexOf('async function refreshDiscovery'))
        expect(setServing.indexOf('await refreshDiscovery()')).toBeGreaterThan(-1)
        expect(setServing.indexOf('await refreshDiscovery()')).toBeLessThan(setServing.indexOf('servingError.value = text'))
    })

    it('10a: a refusal after the domain was unassigned elsewhere renders the server state and keeps the text', async () => {
        answers(LEGACY_DOMAIN, LEGACY_NO_DOMAIN)
        const w = await mountPanel()
        expect(byId(w, 'tea-assigned-domain').exists()).toBe(true)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(true)
        expect(byId(w, 'tea-well-known-hosting').exists()).toBe(true)
        mutate.mockRejectedValueOnce(refusal(NO_DOMAIN_REFUSAL))
        await emitServing(w, 'DOMAIN')
        expect(byId(w, 'tea-no-domain-notice').text()).toBe(TEA_NO_DOMAIN_NOTICE)
        expect(byId(w, 'tea-assigned-domain').exists()).toBe(false)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(false)
        expect(byId(w, 'tea-well-known-hosting').exists()).toBe(false)
        expect(servingGroup(w).props('value')).toBe('LEGACY_UUID')
        expect(byId(w, 'tea-api-base').text()).toBe(LEGACY_API)
        expect(byId(w, 'tea-serving-error').text()).toBe(NO_DOMAIN_REFUSAL)
        expect(servingGroup(w).props('disabled')).toBe(false)
        expect(errors).toHaveBeenCalledTimes(1)
    })

    it('10b: a refusal whose re-read fails keeps the previous state, the text and the stored choice', async () => {
        answers(LEGACY_DOMAIN, () => { throw new Error('boom') })
        const w = await mountPanel()
        mutate.mockRejectedValueOnce(refusal(NO_DOMAIN_REFUSAL))
        await emitServing(w, 'DOMAIN')
        expect(byId(w, 'tea-serving-error').text()).toBe(NO_DOMAIN_REFUSAL)
        expect(byId(w, 'tea-assigned-domain').exists()).toBe(true)
        expect(errors).toHaveBeenCalledTimes(2)
        // D9 (F-5): a refusal never shows the refused choice.
        expect(servingGroup(w).props('value')).toBe('LEGACY_UUID')
        expect(checked(w, 'tea-serving-option-LEGACY_UUID')).toBe(true)
        expect(checked(w, 'tea-serving-option-DOMAIN')).toBe(false)
    })

    it('14: an organization without a TEA id offers LEGACY_UUID; saving it renders the minted id and base', async () => {
        store.commit('SET_MY_USER', { uuid: 'u1', installationType: 'MANAGED_SERVICE' })
        answers(NONE_NO_ID)
        const w = await mountPanel({ installationType: store.getters.myuser.installationType })
        // (a)
        expect(byId(w, 'tea-serving-option-LEGACY_UUID').exists()).toBe(true)
        expect(inputDisabled(w, 'tea-serving-option-LEGACY_UUID')).toBe(false)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(false)
        expect(servingGroup(w).props('value')).toBe('NONE')
        expect(byId(w, 'tea-not-served').text()).toBe(TEA_NOT_SERVED)
        expect(byId(w, 'tea-not-served-no-id').exists()).toBe(false)
        expect(byId(w, 'tea-org-uuid').exists()).toBe(false)
        expect(missingText(w)).toBe(PRO_NOT_MINTED)
        // (b)
        setResult(LEGACY_MINTED)
        await emitServing(w, 'LEGACY_UUID')
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation(mutate.mock.calls[0][0])).toBe('SetTeaOrgServing')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'LEGACY_UUID', teaWellKnownHosting: 'NONE' })
        // (c)
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(byId(w, 'tea-org-uuid-missing').exists()).toBe(false)
        expect(byId(w, 'tea-api-base').text()).toBe(LEGACY_API)
        expect(byId(w, 'tea-not-served').exists()).toBe(false)
        expect(byId(w, 'tea-not-served-no-id').exists()).toBe(false)
        expect(servingGroup(w).props('value')).toBe('LEGACY_UUID')
        expect((byId(w, 'tea-well-known').find('textarea').element as HTMLTextAreaElement).value).toBe(doc(LEGACY_API))
        expect(byId(w, 'tea-well-known-self-host').exists()).toBe(true)
        expect(byId(w, 'tea-serving-error').exists()).toBe(false)
        expect(callsOf('TeaOrgServing')).toHaveLength(1)
        expect(errors).not.toHaveBeenCalled()
    })

    it('15: DOMAIN without a TEA id: the rule is unchanged and the save renders the answer', async () => {
        // (a)
        let w = await mountWith(NONE_NO_ID)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(false)
        // (b)
        w = await mountWith(NONE_DOMAIN_NO_ID)
        expect(byId(w, 'tea-serving-option-DOMAIN').exists()).toBe(true)
        expect(byId(w, 'tea-serving-option-LEGACY_UUID').exists()).toBe(true)
        expect(inputDisabled(w, 'tea-serving-option-DOMAIN')).toBe(false)
        expect(inputDisabled(w, 'tea-serving-option-LEGACY_UUID')).toBe(false)
        setResult(DOMAIN_SERVED)
        await emitServing(w, 'DOMAIN')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'DOMAIN', teaWellKnownHosting: 'NONE' })
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(byId(w, 'tea-api-base').text()).toBe(DOMAIN_API)
        expect(byId(w, 'tea-org-uuid-missing').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('16: an organization with a TEA id is unchanged', async () => {
        const w = await mountWith(NONE_NO_DOMAIN)
        expect(inputDisabled(w, 'tea-serving-option-LEGACY_UUID')).toBe(false)
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(byId(w, 'tea-org-uuid-missing').exists()).toBe(false)
        setResult(LEGACY_MINTED)
        await emitServing(w, 'LEGACY_UUID')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, teaServing: 'LEGACY_UUID', teaWellKnownHosting: 'NONE' })
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(byId(w, 'tea-api-base').text()).toBe(LEGACY_API)
        expect(errors).not.toHaveBeenCalled()
    })

    it('11: CE renders the Discovery block of before and sends only the TeaOrgDiscovery read', async () => {
        store.commit('SET_MY_USER', { uuid: 'u1', installationType: 'OSS' })
        answers(NONE_DOMAIN)
        const w = await mountPanel({ installationType: store.getters.myuser.installationType })
        // (a)
        const ce = callsOf('TeaOrgDiscovery')
        expect(ce).toHaveLength(1)
        expect(ce[0][0].query).toBe(graphqlQueries.TeaOrgDiscoveryGql)
        const body = ce[0][0].query.loc.source.body
        expect(body).toContain('teaOrgDiscovery(org: $org)')
        for (const f of ['assignedHost', 'teaServing', 'teaWellKnownHosting', 'wellKnownUrl']) expect(body).not.toContain(f)
        expect(callsOf('TeaOrgServing')).toHaveLength(0)
        expect([...new Set(query.mock.calls.map(c => operation(c[0])))].sort()).toEqual(['TeaOrgDiscovery', 'TeaProfilesOfOrg'])
        // (b)
        expect(byId(w, 'tea-org-uuid').text()).toBe(TEA)
        expect(byId(w, 'tea-api-base').text()).toBe(LEGACY_API)
        expect(byId(w, 'tea-discovery').text()).toContain('Host this document at https://<your TEI domain>/.well-known/tea')
        expect((byId(w, 'tea-well-known').find('textarea').element as HTMLTextAreaElement).value).toBe(doc(LEGACY_API))
        expect(byId(w, 'tea-well-known-copy').exists()).toBe(true)
        expect(byId(w, 'tea-docs-link').exists()).toBe(true)
        // (c)
        for (const id of PRO_TESTIDS) expect(byId(w, id).exists(), id).toBe(false)
        expect(w.findAll('[data-testid^="tea-serving-option-"]')).toHaveLength(0)
        expect(w.findAll('[data-testid^="tea-hosting-option-"]')).toHaveLength(0)
        expect(w.findAllComponents(NRadioGroup)).toHaveLength(0)
        // (d)
        expect(w.findAllComponents(NRadio)).toHaveLength(0)
        expect(mutate).not.toHaveBeenCalled()
        expect(errors).not.toHaveBeenCalled()
    })

    it('11e: CE without a TEA id shows the not-minted line and no Pro state', async () => {
        store.commit('SET_MY_USER', { uuid: 'u1', installationType: 'OSS' })
        answers(NONE_DOMAIN)
        const base = query.getMockImplementation()!
        query.mockImplementation(async (opts: any) => operation(opts) === 'TeaOrgDiscovery'
            ? { data: { teaOrgDiscovery: { org: ORG, teaUuid: null, apiBase: null, wellKnownDocument: null } } }
            : base(opts))
        const w = await mountPanel({ installationType: store.getters.myuser.installationType })
        expect(byId(w, 'tea-org-uuid-missing').text().replace(/\s+/g, ' ').trim())
            .toBe('Organization TEA id: not minted yet: enable publishing on a profile to mint it.')
        for (const id of PRO_TESTIDS) expect(byId(w, id).exists(), id).toBe(false)
        expect(w.findAll('[data-testid^="tea-serving-option-"]')).toHaveLength(0)
        expect(w.findAll('[data-testid^="tea-hosting-option-"]')).toHaveLength(0)
        expect(errors).not.toHaveBeenCalled()
    })

    it('12: Pro sends the serving read and renders the controls', async () => {
        store.commit('SET_MY_USER', { uuid: 'u1', installationType: 'MANAGED_SERVICE' })
        answers(NONE_DOMAIN)
        const w = await mountPanel({ installationType: store.getters.myuser.installationType })
        const reads = callsOf('TeaOrgServing')
        expect(reads).toHaveLength(1)
        expect(reads[0][0].query).toBe(graphqlQueries.TeaOrgServingGql)
        expect(callsOf('TeaOrgDiscovery')).toHaveLength(0)
        expect(byId(w, 'tea-serving-mode').exists()).toBe(true)
        expect(byId(w, 'tea-assigned-domain').exists()).toBe(true)
        expect(byId(w, 'tea-well-known-hosting').exists()).toBe(true)
        setResult(DOMAIN_SERVED)
        await emitServing(w, 'DOMAIN')
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(operation(mutate.mock.calls[0][0])).toBe('SetTeaOrgServing')
        expect(errors).not.toHaveBeenCalled()
    })

    it('12: an absent installationType takes the default OSS and behaves as CE', async () => {
        answers(NONE_DOMAIN)
        const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:any(.*)*', component: { render: () => null } }] })
        const w = mount(defineComponent({ render: () => h(NNotificationProvider, () => h(Panel,
            { orgUuid: ORG, isOrgAdmin: true, components: [], perspectives: [] })) }), { global: { plugins: [router] } })
        mounted.push(w)
        await flushPromises()
        expect(callsOf('TeaOrgDiscovery')).toHaveLength(1)
        expect(callsOf('TeaOrgDiscovery')[0][0].query).toBe(graphqlQueries.TeaOrgDiscoveryGql)
        expect(callsOf('TeaOrgServing')).toHaveLength(0)
        expect(byId(w, 'tea-serving-mode').exists()).toBe(false)
        expect(errors).not.toHaveBeenCalled()
    })

    it('13: edition wiring pins', () => {
        const source = readFileSync(resolve(__dirname, 'TeaOrgPanel.vue'), 'utf8')
        const org = readFileSync(resolve(__dirname, 'OrgSettings.vue'), 'utf8')
        const count = (s: string, sub: string) => s.split(sub).length - 1
        // (a)
        expect(count(source, "import { isProEdition } from '@/utils/editionCapabilities'")).toBe(1)
        expect(count(source, 'isProEdition(props.installationType)')).toBe(1)
        // (b)
        const guard = 'if (!proEdition.value || !props.isOrgAdmin || !discovery.value) return'
        expect(count(source, guard)).toBe(1)
        expect(source.indexOf(guard)).toBeLessThan(source.indexOf('graphqlQueries.SetTeaOrgServingGql'))
        // (c)
        const open = '<template v-if="!proEdition">'
        expect(count(source, open)).toBe(1)
        const start = source.indexOf(open)
        const branch = source.slice(start, source.indexOf('</template>', start))
        const strip = (s: string) => s.split('\n').map(l => l.replace(/^\s+/, '')).join('\n')
        expect(strip(branch)).toContain(CE_MARKUP_76021180)
        // (d)
        expect(source).not.toMatch(/catch[\s\S]{0,200}TeaOrgDiscoveryGql/)
        // (e)
        expect(org).toContain('const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)')
        const tag = org.slice(org.indexOf('<TeaOrgPanel'), org.indexOf('/>', org.indexOf('<TeaOrgPanel')))
        expect(tag).toContain(':installation-type="myUser.installationType"')
    })
})

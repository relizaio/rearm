// @vitest-environment happy-dom
//
// The Transparency Exchange table of the org settings (task TEA-9, design 4.5 cases 30-31): every
// row is named, an archived component or product the lists leave out is read once by uuid and
// tagged "archived", and a failed read leaves only that row on its uuid. The GraphQL client is
// mocked and answers by document; the embedded organization editor is stubbed.
//
// The Discovery block (task TEA-11, design 4.3 cases 8-9): the API base and the hosted
// .well-known/tea document are shown exactly as the server built them, the UI's token URL carries
// the version the document advertises, and a documentation pointer follows the branding settings.
//
// The overrides table: its heading and help line, its empty text, and each object linked to where
// its TEA profile is edited (a component's or product's settings on the TEA tab, the organization's
// Perspectives tab).
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'
import { createMemoryHistory, createRouter } from 'vue-router'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('./TeaProfileEditor.vue', () => ({ default: { name: 'TeaProfileEditor', render: () => null } }))

const { default: Panel } = await import('./TeaOrgPanel.vue')
const { brandingState } = await import('@/utils/branding')
const { teaTokenUrl } = await import('@/utils/apiKeyControls')

const LISTED = '11111111-1111-4111-8111-111111111111'
const ARCHIVED = '22222222-2222-4222-8222-222222222222'
const PERSPECTIVE = '33333333-3333-4333-8333-333333333333'
const ORG = '44444444-4444-4444-8444-444444444444'
const TEA = '55555555-5555-4555-8555-555555555555'
const API = 'https://rearm.example.com/tea/' + TEA
// The exact string TeaProfileService writes (Jackson compact, field order as served).
const DOC = '{"schemaVersion":1,"endpoints":[{"url":"' + API + '","versions":["1.0.0"],"priority":1}]}'
const NOT_MINTED = { org: ORG, teaUuid: null }
const MINTED = { org: ORG, teaUuid: TEA, apiBase: API, wellKnownDocument: DOC }

const operation = (opts: any) => opts.query?.definitions?.[0]?.name?.value
const nameReads = () => query.mock.calls.filter(c => operation(c[0]) === 'TeaComponentName')

const PRODUCT = '66666666-6666-4666-8666-666666666666'

const row = (scope: string, object: string | null, changes: Record<string, any> = {}) =>
    ({ uuid: 'row-' + (object ?? 'org'), scope, object, mode: scope === 'COMPONENT' ? 'OVERRIDE' : null,
        publishing: 'DISABLED', visibility: 'PRIVATE', ...changes })

const ALL_ROWS = () => [row('ORGANIZATION', null), row('COMPONENT', LISTED), row('PERSPECTIVE', PERSPECTIVE),
    row('COMPONENT', ARCHIVED)]

function answers (archivedRead: () => any, discovery: Record<string, any> = NOT_MINTED, profileRows: () => any[] = ALL_ROWS) {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'TeaOrgDiscovery': return { data: { teaOrgDiscovery: discovery } }
        case 'TeaProfilesOfOrg': return { data: { teaProfilesOfOrg: profileRows() } }
        case 'TeaComponentName': return archivedRead()
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

async function mountPanel () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:any(.*)*', component: { render: () => null } }] })
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: ORG, isOrgAdmin: true, installationType: 'SAAS',
            components: [{ uuid: LISTED, name: 'widget', status: 'ACTIVE', type: 'COMPONENT' },
                { uuid: PRODUCT, name: 'suite', status: 'ACTIVE', type: 'PRODUCT' }],
            perspectives: [{ uuid: PERSPECTIVE, name: 'Payments' }] })),
    }), { global: { plugins: [router] } })
    await flushPromises()
    return w
}

describe('TeaOrgPanel', () => {
    beforeEach(() => {
        query.mockReset()
    })

    it('names every row, reads only the unlisted component, and tags it archived', async () => {
        answers(() => ({ data: { component: { uuid: ARCHIVED, name: 'old-widget', type: 'PRODUCT', status: 'ARCHIVED' } } }))
        const w = await mountPanel()
        expect(nameReads()).toHaveLength(1)
        expect(nameReads()[0][0].variables).toEqual({ componentUuid: ARCHIVED })

        const table = w.find('[data-testid="tea-profile-rows"]')
        const text = table.text()
        expect(text).toContain('widget')
        expect(text).toContain('Payments')
        expect(text).toContain('old-widget')
        for (const uuid of [LISTED, ARCHIVED, PERSPECTIVE, ORG]) expect(text).not.toContain(uuid)
        const tags = table.findAll('[data-testid="tea-archived-tag"]')
        expect(tags).toHaveLength(1)
        expect(tags[0].text()).toBe('archived')
        const archivedRow = table.findAll('tr').find(r => r.text().includes('old-widget'))!
        expect(archivedRow.find('[data-testid="tea-archived-tag"]').exists()).toBe(true)
        const listedRow = table.findAll('tr').find(r => r.text().includes('widget') && !r.text().includes('old-widget'))!
        expect(listedRow.find('[data-testid="tea-archived-tag"]').exists()).toBe(false)
    })

    it('keeps the uuid of a row whose read fails, logs it, and renders the others', async () => {
        const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
        answers(() => { throw new Error('Component not found') })
        const w = await mountPanel()
        const text = w.find('[data-testid="tea-profile-rows"]').text()
        expect(text).toContain('widget')
        expect(text).toContain('Payments')
        expect(text).toContain(ARCHIVED)
        expect(w.findAll('[data-testid="tea-archived-tag"]')).toHaveLength(0)
        expect(errors).toHaveBeenCalled()
        errors.mockRestore()
    })

    it('shows the API base and the hosted .well-known/tea document the server built, and points at the documentation', async () => {
        answers(() => ({ data: { component: { uuid: ARCHIVED, name: 'old-widget', type: 'PRODUCT', status: 'ARCHIVED' } } }), MINTED)
        const w = await mountPanel()
        expect(w.find('[data-testid="tea-org-uuid"]').text()).toBe(TEA)
        expect(w.find('[data-testid="tea-api-base"]').text()).toBe(API)
        expect(w.find('[data-testid="tea-org-uuid-missing"]').exists()).toBe(false)
        const shown = (w.find('[data-testid="tea-well-known"] textarea').element as HTMLTextAreaElement).value
        expect(shown).toBe(DOC)

        const served = JSON.parse(DOC)
        expect(served.schemaVersion).toBe(1)
        expect(served.endpoints[0].url).toBe(w.find('[data-testid="tea-api-base"]').text())
        expect(served.endpoints[0].versions).toEqual(['1.0.0'])
        // The token URL the External Keys panel shows carries the version the document advertises.
        expect(teaTokenUrl(API)).toBe(API + '/v' + served.endpoints[0].versions[0] + '/token')

        const link = w.find('[data-testid="tea-docs-link"]')
        expect(link.exists()).toBe(true)
        expect(link.attributes('href')).toBe(brandingState.documentationUrl + '/tea/')
        expect(link.attributes('href')).toBe('https://docs.rearmhq.com/tea/')
        expect(link.attributes('target')).toBe('_blank')
        expect(link.attributes('rel')).toContain('noopener')
    })

    it('the documentation pointer shows before the TEA id is minted and names no documentation host of its own', async () => {
        answers(() => ({ data: { component: { uuid: ARCHIVED, name: 'old-widget', type: 'PRODUCT', status: 'ARCHIVED' } } }))
        const w = await mountPanel()
        expect(w.find('[data-testid="tea-org-uuid-missing"]').exists()).toBe(true)
        expect(w.find('[data-testid="tea-api-base"]').exists()).toBe(false)
        const link = w.find('[data-testid="tea-docs-link"]')
        expect(link.exists()).toBe(true)
        expect(link.attributes('href')).toBe(brandingState.documentationUrl + '/tea/')

        const source = readFileSync(resolve(__dirname, 'TeaOrgPanel.vue'), 'utf8')
        expect(source).not.toContain('docs.rearmhq.com')
    })

    it('heads the table "Overrides below the organization" with its help line', async () => {
        answers(() => ({ data: { component: { uuid: ARCHIVED, name: 'old-widget', type: 'PRODUCT', status: 'ARCHIVED' } } }))
        const w = await mountPanel()
        expect(w.find('[data-testid="tea-overrides-heading"]').text()).toBe('Overrides below the organization')
        expect(w.find('[data-testid="tea-overrides-help"]').text().replace(/\s+/g, ' ')).toBe('TEA profiles saved on a perspective or '
            + 'a component. Edit or remove them there; components and perspectives without one inherit the organization profile.')
        expect(w.text()).not.toContain('Other TEA profiles of the organization')
    })

    it('says no component or perspective overrides the organization profile when only the organization row exists', async () => {
        answers(() => null, NOT_MINTED, () => [row('ORGANIZATION', null)])
        const w = await mountPanel()
        const table = w.find('[data-testid="tea-profile-rows"]')
        expect(table.find('[data-testid="tea-overrides-empty"]').text())
            .toBe('No component or perspective overrides the organization profile.')
        expect(table.findAll('[data-testid="tea-object-link"]')).toHaveLength(0)
    })

    it('links a component, a product and an archived object to their settings TEA tab, a perspective to the Perspectives tab', async () => {
        answers(() => ({ data: { component: { uuid: ARCHIVED, name: 'old-widget', type: 'COMPONENT', status: 'ARCHIVED' } } }),
            NOT_MINTED, () => [...ALL_ROWS(), row('COMPONENT', PRODUCT)])
        const w = await mountPanel()
        const table = w.find('[data-testid="tea-profile-rows"]')
        expect(table.find('[data-testid="tea-overrides-empty"]').exists()).toBe(false)
        const links = table.findAll('[data-testid="tea-object-link"]')
        const href = (name: string) => links.find(l => l.text() === name)!.attributes('href')
        expect(links.map(l => l.text()).sort()).toEqual(['Payments', 'old-widget', 'suite', 'widget'])
        expect(href('widget')).toBe('/componentsOfOrg/' + ORG + '/' + LISTED + '?componentSettingsView=true&componentSettingsTab=tea')
        expect(href('old-widget')).toBe('/componentsOfOrg/' + ORG + '/' + ARCHIVED + '?componentSettingsView=true&componentSettingsTab=tea')
        expect(href('suite')).toBe('/productsOfOrg/' + ORG + '/' + PRODUCT + '?componentSettingsView=true&componentSettingsTab=tea')
        expect(href('Payments')).toBe('/orgSettings/' + ORG + '?tab=perspectives')
        // The archived tag stays beside its link.
        const archivedRow = table.findAll('tr').find(r => r.text().includes('old-widget'))!
        expect(archivedRow.find('[data-testid="tea-object-link"]').exists()).toBe(true)
        expect(archivedRow.find('[data-testid="tea-archived-tag"]').exists()).toBe(true)
    })
})

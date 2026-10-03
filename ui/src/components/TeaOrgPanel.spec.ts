// @vitest-environment happy-dom
//
// The Transparency Exchange table of the org settings (task TEA-9, design 4.5 cases 30-31): every
// row is named, an archived component or product the lists leave out is read once by uuid and
// tagged "archived", and a failed read leaves only that row on its uuid. The GraphQL client is
// mocked and answers by document; the embedded organization editor is stubbed.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('./TeaProfileEditor.vue', () => ({ default: { name: 'TeaProfileEditor', render: () => null } }))

const { default: Panel } = await import('./TeaOrgPanel.vue')

const LISTED = '11111111-1111-4111-8111-111111111111'
const ARCHIVED = '22222222-2222-4222-8222-222222222222'
const PERSPECTIVE = '33333333-3333-4333-8333-333333333333'
const ORG = '44444444-4444-4444-8444-444444444444'

const operation = (opts: any) => opts.query?.definitions?.[0]?.name?.value
const nameReads = () => query.mock.calls.filter(c => operation(c[0]) === 'TeaComponentName')

const row = (scope: string, object: string | null, changes: Record<string, any> = {}) =>
    ({ uuid: 'row-' + (object ?? 'org'), scope, object, mode: scope === 'COMPONENT' ? 'OVERRIDE' : null,
        publishing: 'DISABLED', visibility: 'PRIVATE', ...changes })

function answers (archivedRead: () => any) {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'TeaOrgDiscovery': return { data: { teaOrgDiscovery: { org: ORG, teaUuid: null } } }
        case 'TeaProfilesOfOrg': return { data: { teaProfilesOfOrg: [row('ORGANIZATION', null), row('COMPONENT', LISTED),
            row('PERSPECTIVE', PERSPECTIVE), row('COMPONENT', ARCHIVED)] } }
        case 'TeaComponentName': return archivedRead()
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

async function mountPanel () {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: ORG, isOrgAdmin: true, installationType: 'SAAS',
            components: [{ uuid: LISTED, name: 'widget', status: 'ACTIVE' }],
            perspectives: [{ uuid: PERSPECTIVE, name: 'Payments' }] })),
    }))
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
})

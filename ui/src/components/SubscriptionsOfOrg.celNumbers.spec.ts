// @vitest-environment happy-dom
//
// TPF-224 (T-451): the subscription editor's CEL help for numbers. A score key is a decimal and a
// count a whole number; the hint says either kind compares with either literal and that arithmetic
// keeps both sides of one kind. It shows only with the filter switch on, beside the other CEL help.
// Mounted as SubscriptionsOfOrg.boardDocuments.spec.ts mounts the pane (its case T-17 is the pattern).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))
vi.mock('vuex', () => ({
    useStore: () => ({ dispatch: vi.fn(async () => null), getters: { perspectivesOfOrg: () => [] } }),
}))
vi.mock('vue-router', () => ({
    useRoute: () => ({ query: {}, path: '/org' }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return {
        ...actual,
        useDialog: () => ({ warning: vi.fn(), info: vi.fn(), success: vi.fn() }),
        useMessage: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn(), loading: () => ({ destroy: vi.fn() }) }),
    }
})

const { default: Pane } = await import('./SubscriptionsOfOrg.vue')
const { NSelect, NSwitch } = await import('naive-ui')

const norm = (s: string) => s.replace(/\s+/g, ' ').trim()

const ORG = 'o1'
const NUMBERS_HELP_TEXT = 'Numbers: event.cvssScore >= 7 and event.epssScore > 0.5 both work.'
    + ' Scores are decimals (CVSS 0 to 10, EPSS 0 to 1) and a vulnerability without a score reads as 0;'
    + ' counts such as event.addedCount are whole numbers. Either kind can be compared with a'
    + ' whole or a decimal number. In arithmetic keep both sides of one kind:'
    + ' event.epssScore * 100.0 > 50, not * 100.'

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value

function answers () {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'notificationSubscriptions': return { data: { notificationSubscriptions: [] } }
        case 'notificationChannels': return { data: { notificationChannels: [
            { uuid: 'ch-1', org: ORG, resourceGroup: null, name: 'slack', type: 'SLACK', status: 'ACTIVE', revision: 1 }] } }
        case 'notificationChannelGroups': return { data: { notificationChannelGroups: [] } }
        case 'getTeamsForRoutes': return { data: { getTeams: [] } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

const mounted: VueWrapper[] = []
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show"><slot/></div>' } } }

async function mountPane () {
    answers()
    const w = mount(Pane, { props: { orguuid: ORG, isWritable: true, installationType: 'SAAS' }, global })
    mounted.push(w)
    await flushPromises()
    return w
}

const byId = (w: any, id: string) => w.find('[data-testid="' + id + '"]')
const comp = (w: any, type: any, id: string) => w.findAllComponents(type).find((c: any) => c.attributes('data-testid') === id)
async function openCreate (w: any) {
    await byId(w, 'add-subscription').trigger('click')
    await flushPromises()
}
async function selectTypes (w: any, types: string[]) {
    comp(w, NSelect, 'sub-eventtypes').vm.$emit('update:value', types)
    await flushPromises()
}
async function setFilterSwitch (w: any, on: boolean) {
    comp(w, NSwitch, 'filter-advanced-toggle').vm.$emit('update:value', on)
    await flushPromises()
}

describe('SubscriptionsOfOrg: CEL help for numbers (TPF-224)', () => {
    let errors: any

    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    })

    afterEach(() => {
        expect(errors).not.toHaveBeenCalled()
        mounted.splice(0).forEach(w => w.unmount())
        errors.mockRestore()
    })

    it('T-451: the numbers hint shows only with the filter switch on, with the whole text', async () => {
        const w = await mountPane()
        await openCreate(w)
        await selectTypes(w, ['NEW_VULN_AFFECTS_RELEASES'])
        expect(byId(w, 'cel-help-numbers').exists()).toBe(false)

        await setFilterSwitch(w, true)
        const hint = byId(w, 'cel-help-numbers')
        expect(hint.exists()).toBe(true)
        const text = norm(hint.text())
        expect(text).toContain('event.cvssScore >= 7')
        expect(text).toContain('event.epssScore * 100.0 > 50')
        expect(text).toBe(NUMBERS_HELP_TEXT)
        expect(hint.classes()).toContain('field-hint')

        await setFilterSwitch(w, false)
        expect(byId(w, 'cel-help-numbers').exists()).toBe(false)
    })

    it('T-451: the numbers hint sits right after the event.componentKind hint', async () => {
        const w = await mountPane()
        await openCreate(w)
        await selectTypes(w, ['RELEASE_BOM_DIFF'])
        await setFilterSwitch(w, true)
        const kind = byId(w, 'cel-help-component-kind')
        expect(kind.exists()).toBe(true)
        expect(kind.element.nextElementSibling?.getAttribute('data-testid')).toBe('cel-help-numbers')
    })
})

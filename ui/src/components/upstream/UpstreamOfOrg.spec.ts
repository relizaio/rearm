// @vitest-environment happy-dom
//
// TPF-229 (TPF-185 design 3.7, no row of its own): the Upstream page of an organization. Its Follows tab reads the
// follows and the sources at mount, shows the table, the empty states and for an administrator
// the Follow an item button and the start modal; a member sees neither; the source filter of the
// route is passed on only when it is a uuid; a refused first read is the forbidden state only for
// the plain Not authorized. The page never sends a mutation.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))

const route: any = { params: {}, query: {} }
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Page } = await import('./UpstreamOfOrg.vue')
const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { default: OneOffPanel } = await import('./UpstreamOneOffPanel.vue')
const { GET_UPSTREAM_FOLLOWS } = await import('@/utils/upstreamFollowQueries')
const { GET_UPSTREAM_SOURCES } = await import('@/utils/upstreamSourceQueries')
const { GET_UPSTREAM_ONE_OFFS } = await import('@/utils/upstreamOneOffQueries')
const { forbiddenSentence } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const FOLLOW = 'cccccccc-0000-4000-8000-000000000001'
const SEALED = 'System is sealed. Please unseal the system first.'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }, RouterLink } }

const ADMIN = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }] } }
const MEMBER = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_ONLY' }] } }

function source (status = 'ACTIVE') {
    return { uuid: SOURCE, org: ORG, kind: 'TEA', name: 'Acme TEA', status, tea: { root: 'https://tea.example.com/tea' } }
}

function aFollow () {
    return {
        uuid: FOLLOW, source: SOURCE, status: 'ACTIVE', onDrift: 'STOP', createdDate: '2026-11-01T10:00:00Z', unfollowedDate: null, pendingCount: 0,
        item: { uuid: '88888888-0000-4000-8000-000000000001', kind: 'PRODUCT', upstreamUuid: 'dddddddd-0000-4000-8000-000000000001', upstreamName: 'Widget Suite',
            component: 'eeeeeeee-0000-4000-8000-000000000001', depth: 2, depthOrigin: 'PROPOSED', updateState: 'UPDATED' },
        lastRun: null,
    }
}

function refusal (...messages: string[]): any {
    const err: any = new Error(messages[0])
    err.errors = messages.map(message => ({ message }))
    return err
}

// The mock is routed by document, not by order: follows first or sources first makes no difference.
function answer (follows: any, sources: any[] = [source()]) {
    query.mockImplementation(async (args: any) => {
        if (args.query === GET_UPSTREAM_FOLLOWS) {
            if (follows instanceof Error) throw follows
            return { data: { upstreamFollows: follows } }
        }
        if (args.query === GET_UPSTREAM_SOURCES) return { data: { upstreamSources: sources } }
        throw new Error('unexpected query')
    })
}

async function mountPage (user: any) {
    getters.myuser = user
    const w = mount(Page, { global, attachTo: document.body })
    await flushPromises()
    return w
}

function callsOf (doc: any): any[] {
    return query.mock.calls.filter(c => c[0].query === doc).map(c => c[0])
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    route.params = { orguuid: ORG }
    route.query = {}
})

describe('the Upstream page of an organization', () => {
    it('sends the follows query and the sources query at mount', async () => {
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        expect(query.mock.calls[0][0].query).toBe(GET_UPSTREAM_FOLLOWS)
        expect(callsOf(GET_UPSTREAM_FOLLOWS)[0].variables).toEqual({ org: ORG, source: null, statuses: ['ACTIVE'] })
        expect(callsOf(GET_UPSTREAM_SOURCES)[0].variables).toEqual({ org: ORG, statuses: ['ACTIVE', 'ARCHIVED'] })
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('shows the follows table with the source name', async () => {
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        const table = w.get('[data-testid="upstream-follows-table"]')
        expect(table.text()).toContain('Widget Suite')
        expect(table.text()).toContain('Acme TEA')
        w.unmount()
    })

    it('shows an administrator the Follow an item button and mounts the start modal', async () => {
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        expect(w.find('[data-testid="upstream-follow-an-item"]').exists()).toBe(true)
        expect(w.get('[data-testid="upstream-follow-an-item"]').text()).toBe('Follow an item')
        expect(w.findComponent(StartModal).exists()).toBe(true)
        w.unmount()
    })

    it('gives the start modal the active sources only', async () => {
        const archived = { ...source('ARCHIVED'), uuid: 'aaaaaaaa-0000-4000-8000-000000000002', name: 'Old TEA' }
        answer([aFollow()], [source(), archived])
        const w = await mountPage(ADMIN)
        expect(w.findComponent(StartModal).props('sources')).toEqual([source()])
        w.unmount()
    })

    it('shows a member neither the button nor the start modal', async () => {
        answer([aFollow()])
        const w = await mountPage(MEMBER)
        expect(w.find('[data-testid="upstream-follow-an-item"]').exists()).toBe(false)
        expect(w.findComponent(StartModal).exists()).toBe(false)
        expect(w.find('[data-testid="upstream-follows-table"]').exists()).toBe(true)
        w.unmount()
    })

    it('shows the empty text of an administrator', async () => {
        answer([])
        const w = await mountPage(ADMIN)
        expect(w.get('[data-testid="upstream-follows-empty"]').text()).toContain('Nothing is followed yet. Choose Follow an item to start.')
        expect(w.find('[data-testid="upstream-follows-no-source"]').exists()).toBe(false)
        w.unmount()
    })

    it('shows the empty text of a member', async () => {
        answer([])
        const w = await mountPage(MEMBER)
        expect(w.get('[data-testid="upstream-follows-empty"]').text()).toBe('Nothing is followed yet.')
        w.unmount()
    })

    it('asks for a source first, with a link to the sources tab, when no source is active', async () => {
        answer([], [source('ARCHIVED')])
        const w = await mountPage(ADMIN)
        expect(w.find('[data-testid="upstream-follows-no-source"]').exists()).toBe(true)
        const hint = w.get('[data-testid="upstream-follows-no-source"]')
        expect(hint.text()).toContain('Add an upstream source first.')
        const to = JSON.parse(hint.get('a.rl').attributes('data-to'))
        expect(to).toEqual({ name: 'OrgSettings', params: { orguuid: ORG }, query: { tab: 'upstreamSources' } })
        w.unmount()
    })

    it('passes a uuid source filter of the route to the follows query', async () => {
        route.query = { source: SOURCE }
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        expect(callsOf(GET_UPSTREAM_FOLLOWS)[0].variables.source).toBe(SOURCE)
        w.unmount()
    })

    it('asks every state for All and the one state otherwise', async () => {
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        const select = w.findAllComponents({ name: 'Select' }).find((s: any) => s.attributes('data-testid') === 'upstream-follows-state-filter')!
        select.vm.$emit('update:value', 'ALL')
        await flushPromises()
        expect(callsOf(GET_UPSTREAM_FOLLOWS).at(-1).variables).toEqual({ org: ORG, source: null, statuses: null })
        select.vm.$emit('update:value', 'UNFOLLOWED')
        await flushPromises()
        expect(callsOf(GET_UPSTREAM_FOLLOWS).at(-1).variables).toEqual({ org: ORG, source: null, statuses: ['UNFOLLOWED'] })
        w.unmount()
    })

    it('links a followed product to its product page and tags only an item that is no longer updated', async () => {
        const stale = { ...aFollow(), uuid: 'cccccccc-0000-4000-8000-000000000002' }
        stale.item = { ...stale.item, kind: 'COMPONENT', updateState: 'NO_LONGER_UPDATED', component: 'eeeeeeee-0000-4000-8000-000000000002' }
        answer([aFollow(), stale])
        const w = await mountPage(ADMIN)
        const rows = w.findAll('[data-testid="upstream-follows-table"] tbody tr')
        expect(rows).toHaveLength(2)
        const to = (row: any) => row.findAll('a.rl').map((a: any) => JSON.parse(a.attributes('data-to')))
        expect(to(rows[0])).toContainEqual({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: 'eeeeeeee-0000-4000-8000-000000000001' } })
        expect(to(rows[1])).toContainEqual({ name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: 'eeeeeeee-0000-4000-8000-000000000002' } })
        expect(rows[0].find('[data-testid="upstream-no-longer-updated"]').exists()).toBe(false)
        expect(rows[1].find('[data-testid="upstream-no-longer-updated"]').exists()).toBe(true)
        w.unmount()
    })

    it('ignores a source filter that is not a uuid', async () => {
        route.query = { source: '../x' }
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        expect(callsOf(GET_UPSTREAM_FOLLOWS)[0].variables.source).toBeNull()
        w.unmount()
    })

    it('shows the forbidden sentence and no table when the first read is Not authorized', async () => {
        answer(refusal('Not authorized'))
        const w = await mountPage(MEMBER)
        expect(w.find('[data-testid="upstream-follows-forbidden"]').exists()).toBe(true)
        expect(w.get('[data-testid="upstream-follows-forbidden"]').text()).toBe(forbiddenSentence)
        expect(w.find('[data-testid="upstream-follows-table"]').exists()).toBe(false)
        w.unmount()
    })

    it('shows the sealed sentence, not the forbidden one, when the first read is refused with it', async () => {
        answer(refusal(SEALED))
        const w = await mountPage(ADMIN)
        expect(w.get('[data-testid="upstream-follows-error"]').text()).toBe(SEALED)
        expect(w.text()).not.toContain(forbiddenSentence)
        expect(w.find('[data-testid="upstream-follows-table"]').exists()).toBe(false)
        w.unmount()
    })

    it('sends no mutation in any of these states', async () => {
        answer([aFollow()])
        const w = await mountPage(ADMIN)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')

// T-1163 (TPF-243 design 3.8, threat model row v): the list of a source is read only from the start
// of a follow, which the page mounts for an administrator in the Pro edition only. A member and the
// CE edition get no dialog, no button and no list request. An administrator's dialog opens on the
// list of the page's source filter.
describe('T-1163: the source list from the Upstream page', () => {
    const OSS_ADMIN = { ...ADMIN, installationType: 'OSS' }

    function answerWithList () {
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_FOLLOWS) return { data: { upstreamFollows: [aFollow()] } }
            if (args.query === GET_UPSTREAM_SOURCES) return { data: { upstreamSources: [source()] } }
            if (args.query === GET_UPSTREAM_SOURCE) {
                return { data: { upstreamSource: { ...source(), credential: { kind: 'NONE' }, probe: { state: 'DONE', result: 'REACHABLE', message: 'ok' } } } }
            }
            if (args.query === GET_UPSTREAM_OFFERED_ITEMS) {
                return { data: { upstreamOfferedItems: { source: SOURCE, kind: 'PRODUCT', failure: null, sourceFailure: null, message: null, items: [],
                    next: 'NONE', nextPageToken: null, rowsReceived: 0, rowsDropped: 0, cut: 'NONE' } } }
            }
            throw new Error('unexpected query')
        })
    }

    const listCalls = () => callsOf(GET_UPSTREAM_OFFERED_ITEMS)

    it.each([['a member', MEMBER], ['CE', OSS_ADMIN]])('%s gets no dialog, no button and no list request', async (_who, user) => {
        answerWithList()
        route.query = { source: SOURCE }
        const w = await mountPage(user)
        expect(w.findComponent(StartModal).exists()).toBe(false)
        expect(w.find('[data-testid="upstream-follow-an-item"]').exists()).toBe(false)
        expect(listCalls()).toEqual([])
        w.unmount()
    })

    it('opens the list of the filtered source for an administrator', async () => {
        answerWithList()
        route.query = { source: SOURCE }
        const w = await mountPage(ADMIN)
        expect(listCalls()).toEqual([])
        await w.get('[data-testid="upstream-follow-an-item"]').trigger('click')
        await flushPromises()
        expect(callsOf(GET_UPSTREAM_SOURCE).map(c => c.variables)).toEqual([{ uuid: SOURCE }])
        expect(listCalls().map(c => c.variables)).toEqual([{ source: SOURCE, kind: 'PRODUCT', identifier: null, pageSize: 100, pageToken: null }])
        expect(w.find('[data-testid="upstream-start-empty"]').exists()).toBe(true)
        w.unmount()
    })

    it('opens the dialog without a source and sends no list request when no filter is set', async () => {
        answerWithList()
        const w = await mountPage(ADMIN)
        await w.get('[data-testid="upstream-follow-an-item"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="upstream-start-gate"]').text()).toBe('Choose a source to see its list.')
        expect(callsOf(GET_UPSTREAM_SOURCE)).toEqual([])
        expect(listCalls()).toEqual([])
        w.unmount()
    })
})

// TPF-231 (design 3.3 and 3.10): the tab "By TEI" of the same page, opened by ?tab=tei.
describe('the tab By TEI of the Upstream page', () => {
    const WRITER = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_WRITE' }] } }

    function answerWithOneOffs () {
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_FOLLOWS) return { data: { upstreamFollows: [] } }
            if (args.query === GET_UPSTREAM_SOURCES) return { data: { upstreamSources: [source()] } }
            if (args.query === GET_UPSTREAM_ONE_OFFS) return { data: { upstreamOneOffs: [] } }
            throw new Error('unexpected query')
        })
    }

    it('shows the panel for ?tab=tei with the sources and WRITE of a writer', async () => {
        route.query = { tab: 'tei' }
        answerWithOneOffs()
        const w = await mountPage(WRITER)
        const panel = w.findComponent(OneOffPanel)
        expect(panel.exists()).toBe(true)
        expect(panel.props('orgUuid')).toBe(ORG)
        expect(panel.props('canWrite')).toBe(true)
        expect(panel.props('sources').map((s: any) => s.uuid)).toEqual([SOURCE])
        expect(w.find('[data-testid="upstream-follows-table"]').exists()).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('gives a member without WRITE the panel without the right to write', async () => {
        route.query = { tab: 'tei' }
        answerWithOneOffs()
        const w = await mountPage(MEMBER)
        expect(w.findComponent(OneOffPanel).props('canWrite')).toBe(false)
        w.unmount()
    })

    it('shows the Follows tab, not the panel, without a tab or for an unknown one', async () => {
        for (const q of [{}, { tab: 'other' }]) {
            route.query = q
            answerWithOneOffs()
            const w = await mountPage(ADMIN)
            expect(w.findComponent(OneOffPanel).exists()).toBe(false)
            expect(callsOf(GET_UPSTREAM_ONE_OFFS)).toHaveLength(0)
            w.unmount()
            query.mockReset()
        }
    })

    it('reads the sources again when the panel added one', async () => {
        route.query = { tab: 'tei' }
        answerWithOneOffs()
        const w = await mountPage(ADMIN)
        expect(callsOf(GET_UPSTREAM_SOURCES)).toHaveLength(1)
        w.findComponent(OneOffPanel).vm.$emit('source-added', SOURCE)
        await flushPromises()
        expect(callsOf(GET_UPSTREAM_SOURCES)).toHaveLength(2)
        w.unmount()
    })
})

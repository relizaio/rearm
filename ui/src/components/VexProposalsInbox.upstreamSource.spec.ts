// @vitest-environment happy-dom
//
// T-585: the VEX proposals inbox names the upstream source of a supplier's statement in a column
// "Source", read by documents of the upstream feature only behind the edition gate; a failed read
// of the origins leaves the column empty and the inbox as it was. The review page says the same
// in one line under its header.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/userLookup', () => ({ useOrgUsersIndex: () => ({ format: (u: any) => String(u ?? '') }) }))

const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))

const ORG = '11111111-2222-3333-4444-555555555555'
const route: any = { params: { orguuid: ORG }, query: {} }
const router = { push: vi.fn(), replace: vi.fn(), resolve: () => ({ fullPath: '/x' }) }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Inbox } = await import('./VexProposalsInbox.vue')
const { default: Review } = await import('./VexProposalReview.vue')
const { GET_VEX_PROPOSAL, GET_VEX_PROPOSALS } = await import('@/graphql/vexImport')
const { NMessageProvider } = await import('naive-ui')
// The review page asks for naive-ui's message API, which needs its provider around it.
const ReviewInProvider = defineComponent({ render: () => h(NMessageProvider, null, { default: () => h(Review) }) })
const { GET_VEX_PROPOSAL_ORIGIN, GET_VEX_PROPOSAL_ORIGINS } = await import('@/utils/upstreamMarkerQueries')
const { GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES } = await import('@/utils/upstreamSourceQueries')

const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const RELEASE = 'bbbbbbbb-0000-4000-8000-000000000001'
const P1 = 'cccccccc-0000-4000-8000-000000000001'
const P2 = 'cccccccc-0000-4000-8000-000000000002'
const P3 = 'cccccccc-0000-4000-8000-000000000003'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { RouterLink } }

function proposal (uuid: string, findingId: string): any {
    return { uuid, org: ORG, findingId, location: 'pkg:npm/a@1', rawLocation: 'pkg:npm/a@1', analysisState: 'NOT_AFFECTED',
        analysisJustification: null, scope: 'RELEASE', scopeUuid: RELEASE, scopeReleaseUuid: RELEASE, scopeReleaseVersion: '1.0',
        status: 'PENDING', sourceStatementJson: '{}', responses: [], findingAliases: [] }
}

const PROPOSALS = [proposal(P1, 'CVE-2026-0001'), proposal(P2, 'CVE-2026-0002'), proposal(P3, 'CVE-2026-0003')]
const ORIGIN = (presence: string) => ({ source: SOURCE, upstreamRelease: 'dddddddd-0000-4000-8000-000000000001', collectionVersion: 3, number: 2, presence })

function refusal (message: string): any {
    const err: any = new Error(message)
    err.graphQLErrors = [{ message }]
    return err
}

function answer (origins: any) {
    query.mockImplementation(async (args: any) => {
        if (args.query === GET_VEX_PROPOSALS) return { data: { getVexStatementProposals: PROPOSALS } }
        if (args.query === GET_VEX_PROPOSAL_ORIGINS) {
            if (origins instanceof Error) throw origins
            return { data: { getVexStatementProposals: origins } }
        }
        if (args.query === GET_UPSTREAM_SOURCES) return { data: { upstreamSources: [{ uuid: SOURCE, name: 'Acme TEA', status: 'ACTIVE' }] } }
        if (args.query === GET_VEX_PROPOSAL) return { data: { getVexStatementProposal: PROPOSALS[0] } }
        if (args.query === GET_VEX_PROPOSAL_ORIGIN) {
            if (origins instanceof Error) throw origins
            return { data: { getVexStatementProposal: { uuid: P1, ingested: ORIGIN('PRESENT') } } }
        }
        if (args.query === GET_UPSTREAM_SOURCE) return { data: { upstreamSource: { uuid: SOURCE, name: 'Acme TEA', status: 'ACTIVE' } } }
        return { data: {} }
    })
}

const SERVED_ORIGINS = [
    { uuid: P1, ingested: ORIGIN('PRESENT') },
    { uuid: P2, ingested: ORIGIN('GONE') },
    { uuid: P3, ingested: null },
]

const sent = (doc: any) => query.mock.calls.filter(c => c[0].query === doc)
const headers = (w: any) => w.findAll('th').map((th: any) => th.text())
const sourceCells = (w: any) => w.findAll('[data-testid="vex-proposal-source"]').map((c: any) => c.text())

async function mountInbox () {
    const w = mount(Inbox, { global })
    await flushPromises()
    return w
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    getters.myuser = { installationType: 'SAAS' }
})

describe('T-585: the Source column of the VEX proposals inbox', () => {
    it('Pro: the source\'s name, the name with "gone upstream", and "Uploaded"', async () => {
        answer(SERVED_ORIGINS)
        const w = await mountInbox()
        expect(headers(w)).toContain('Source')
        expect(sourceCells(w)).toEqual(['Acme TEA', 'Acme TEA, gone upstream', 'Uploaded'])
        expect(sent(GET_VEX_PROPOSAL_ORIGINS)[0][0].variables).toEqual({ org: ORG, status: 'PENDING' })
        expect(sent(GET_UPSTREAM_SOURCES)[0][0].variables).toEqual({ org: ORG, statuses: ['ACTIVE', 'ARCHIVED'] })
        expect(mutate).not.toHaveBeenCalled()
    })

    it('Pro with the origins read rejected: the three proposals as before, the column empty, no error', async () => {
        answer(refusal('Not authorized'))
        const w = await mountInbox()
        expect(w.text()).toContain('CVE-2026-0001')
        expect(w.text()).toContain('CVE-2026-0002')
        expect(w.text()).toContain('CVE-2026-0003')
        expect(headers(w)).toContain('Source')
        expect(sourceCells(w)).toEqual(['', '', ''])
        expect(w.text()).not.toContain('Not authorized')
    })

    it('Pro with the sources read refused: "an upstream source" stands in for the name', async () => {
        answer(SERVED_ORIGINS)
        const served = query.getMockImplementation()!
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_SOURCES) throw refusal('Not authorized')
            return served(args)
        })
        const w = await mountInbox()
        expect(sourceCells(w)).toEqual(['an upstream source', 'an upstream source, gone upstream', 'Uploaded'])
    })

    it('OSS: no column "Source", and neither the origins nor the sources are read', async () => {
        getters.myuser = { installationType: 'OSS' }
        answer(SERVED_ORIGINS)
        const w = await mountInbox()
        expect(headers(w)).not.toContain('Source')
        expect(sent(GET_VEX_PROPOSAL_ORIGINS)).toHaveLength(0)
        expect(sent(GET_UPSTREAM_SOURCES)).toHaveLength(0)
        expect(sent(GET_VEX_PROPOSALS)).toHaveLength(1)
    })
})

describe('the review page of a supplier statement (design 3.12)', () => {
    beforeEach(() => { route.params = { orguuid: ORG, uuid: P1 } })

    it('names the source and the collection version, with a link to the release', async () => {
        answer(SERVED_ORIGINS)
        const w = mount(ReviewInProvider, { global })
        await flushPromises()
        const line = w.find('[data-testid="vex-proposal-origin"]')
        expect(line.text()).toContain('Supplier statement from Acme TEA, collection version 3')
        const link = w.find('[data-testid="vex-proposal-origin-release"]')
        expect(JSON.parse(link.attributes('data-to')!)).toEqual({ name: 'ReleaseView', params: { uuid: RELEASE } })
        expect(sent(GET_VEX_PROPOSAL_ORIGIN)[0][0].variables).toEqual({ uuid: P1 })
    })

    it('a refused origin read shows no line and no error; OSS sends no origin read', async () => {
        answer(refusal('Not authorized'))
        const w = mount(ReviewInProvider, { global })
        await flushPromises()
        expect(w.find('[data-testid="vex-proposal-origin"]').exists()).toBe(false)
        expect(w.text()).not.toContain('Not authorized')
        w.unmount()
        query.mockClear()
        getters.myuser = { installationType: 'OSS' }
        const oss = mount(ReviewInProvider, { global })
        await flushPromises()
        expect(oss.find('[data-testid="vex-proposal-origin"]').exists()).toBe(false)
        expect(sent(GET_VEX_PROPOSAL_ORIGIN)).toHaveLength(0)
        expect(sent(GET_UPSTREAM_SOURCE)).toHaveLength(0)
    })
})

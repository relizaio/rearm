// @vitest-environment happy-dom
//
// T-527 (TPF-185 design 3.2 and 3.7 to 3.9): the edition gate of the three Pro-only pages. In an
// OSS edition, and while the user is not loaded, each page shows the not-available sentence and
// sends no request at all; in a Pro edition each page sends its read.
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

const { default: UpstreamOfOrg } = await import('./UpstreamOfOrg.vue')
const { default: PreparationPage } = await import('./UpstreamFollowPreparationPage.vue')
const { default: FollowPage } = await import('./UpstreamFollowPage.vue')
const { notAvailableSentence } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const SOME_UUID = 'bbbbbbbb-0000-4000-8000-000000000001'
const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }, RouterLink } }

const pages: Array<[string, any]> = [
    ['UpstreamOfOrg', UpstreamOfOrg],
    ['UpstreamFollowPreparationPage', PreparationPage],
    ['UpstreamFollowPage', FollowPage],
]

const users: Array<[string, any]> = [
    ['an OSS user', { installationType: 'OSS', permissions: { permissions: [] } }],
    ['a user that is not loaded', undefined],
]

const cases: Array<[string, any, string, any]> = []
for (const [pageName, page] of pages) {
    for (const [userName, user] of users) cases.push([pageName, page, userName, user])
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    // A harmless answer: a failed preparation does not poll; the other pages accept an empty answer.
    query.mockResolvedValue({ data: { upstreamFollowPreparation: { uuid: SOME_UUID, org: ORG, source: SOME_UUID, state: 'FAILED', failure: 'SOURCE_UNREACHABLE', message: 'x' } } })
    route.params = { orguuid: ORG, uuid: SOME_UUID }
    route.query = {}
})

describe('T-527 the edition gate of the upstream pages', () => {
    it.each(cases)('%s with %s shows the sentence and sends nothing', async (_p, page, _u, user) => {
        getters.myuser = user
        const w = mount(page, { global, attachTo: document.body })
        await flushPromises()
        expect(w.text().trim()).toBe(notAvailableSentence)
        expect(query).not.toHaveBeenCalled()
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it.each(pages)('%s sends a query in a Pro edition', async (_p, page) => {
        getters.myuser = { installationType: 'SAAS', permissions: { permissions: [] } }
        const w = mount(page, { global, attachTo: document.body })
        await flushPromises()
        expect(query.mock.calls.length).toBeGreaterThanOrEqual(1)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

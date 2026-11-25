// @vitest-environment happy-dom
//
// TPF-185 design 3.5 and 3.6: the Sources tab reads a row again every 2 seconds while its probe is
// PENDING and every 5 seconds while its last sync is RUNNING, stops when the state closes, and
// stops on unmount: the timers are cleared, and a read answered after the unmount schedules
// nothing.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/commonFunctions', () => ({ default: { dateDisplay: (d: string) => 'D:' + d } }))

const { default: Panel } = await import('./UpstreamSourcesPanel.vue')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')

const ORG = '11111111-2222-3333-4444-555555555555'
const UUID = 'aaaaaaaa-0000-4000-8000-000000000001'
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

function row (over: Record<string, any> = {}) {
    return {
        uuid: UUID, org: ORG, kind: 'TEA', name: 'Acme TEA', status: 'ACTIVE', documentClasses: ['SBOM'], vexImportMode: 'STAGE',
        autoIntegrate: 'DISABLED', tea: { root: 'https://tea.example.com/tea' }, credential: { kind: 'NONE', keyId: null, setDate: null },
        probe: { state: 'DONE', result: 'REACHABLE', message: 'ok', requestedDate: null, finishedDate: null },
        createdDate: null, lastUpdatedDate: null, archivedDate: null, syncIntervalMinutes: 1440, nextSync: null, syncFailures: 0,
        lastSync: null, documentReServing: 'WITHHELD', ...over,
    }
}
const PENDING = { probe: { state: 'PENDING', result: null, message: null, requestedDate: null, finishedDate: null } }
const RUNNING = { lastSync: { uuid: 'bbbbbbbb-0000-4000-8000-000000000001', origin: 'MANUAL', state: 'RUNNING', stopReason: null, sourceFailure: null, createdDate: null, finished: null, counters: null } }

async function mountWith (first: any) {
    query.mockResolvedValueOnce({ data: { upstreamSources: [first] } })
    const w = mount(Panel, { props: { orgUuid: ORG, isOrgAdmin: false, notify: vi.fn() }, global, attachTo: document.body })
    await flushPromises()
    return w
}

async function advance (ms: number) {
    await vi.advanceTimersByTimeAsync(ms)
    await flushPromises()
}

const rowPolls = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCE)

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
})

afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
})

describe('UpstreamSourcesPanel polling', () => {
    it('aPendingProbeIsReadAgainEveryTwoSecondsUntilItIsDone', async () => {
        const w = await mountWith(row(PENDING))
        query.mockResolvedValueOnce({ data: { upstreamSource: row(PENDING) } })
        await advance(1999)
        expect(rowPolls()).toHaveLength(0)
        await advance(1)
        expect(rowPolls()).toHaveLength(1)
        expect(rowPolls()[0][0].variables).toEqual({ uuid: UUID })

        query.mockResolvedValueOnce({ data: { upstreamSource: row() } })
        await advance(2000)
        expect(rowPolls()).toHaveLength(2)
        expect(w.text()).toContain('ok')
        await advance(20000)
        expect(rowPolls()).toHaveLength(2)
        w.unmount()
    })

    it('aRunningSyncIsReadAgainEveryFiveSeconds', async () => {
        const w = await mountWith(row(RUNNING))
        query.mockResolvedValueOnce({ data: { upstreamSource: row() } })
        await advance(4999)
        expect(rowPolls()).toHaveLength(0)
        await advance(1)
        expect(rowPolls()).toHaveLength(1)
        w.unmount()
    })

    it('unmountClearsThePendingTimer', async () => {
        const set = vi.spyOn(globalThis, 'setTimeout')
        const clear = vi.spyOn(globalThis, 'clearTimeout')
        const w = await mountWith(row(PENDING))
        const ids = set.mock.calls.map((c, i) => [c[1], set.mock.results[i].value]).filter(([delay]) => delay === 2000).map(([, id]) => id)
        expect(ids).toHaveLength(1)
        w.unmount()
        expect(clear.mock.calls.map(c => c[0])).toContain(ids[0])
        await advance(20000)
        expect(rowPolls()).toHaveLength(0)
    })

    it('aReadAnsweredAfterTheUnmountSchedulesNothing', async () => {
        const w = await mountWith(row(PENDING))
        let answer: (v: any) => void = () => {}
        query.mockImplementationOnce(() => new Promise(resolve => { answer = resolve }))
        await advance(2000)
        expect(rowPolls()).toHaveLength(1)

        const set = vi.spyOn(globalThis, 'setTimeout')
        w.unmount()
        answer({ data: { upstreamSource: row(PENDING) } })
        await flushPromises()
        expect(set.mock.calls.filter(c => c[1] === 2000)).toHaveLength(0)
        await advance(20000)
        expect(rowPolls()).toHaveLength(1)
    })
})

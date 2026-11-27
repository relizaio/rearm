// @vitest-environment happy-dom
//
// T-1145, T-1150, T-1159 (TPF-243 design 3.3, 3.8 and 3.9), mutation round: the numbers that make an
// answer stale and what each choice, a close and an unmount leave in the state of the dialog. The
// request number, the number of a set and the number of a source read are each a layer of the same
// defence: an answer of an older number changes nothing. A number that does not move cannot be seen
// by a click while another layer holds, so the state is read from the instance: the numbers rise at
// every choice that drops the sets, a close and an unmount, the wanted call is forgotten, the gate
// starts again, and an answer that arrives later finds nothing to change.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { S1, S2, items, page, probeOf, refusal, sourceRow } from './startDialogTestKit'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES } = await import('@/utils/upstreamSourceQueries')
const { answer, deferred, listCalls, sourceReads, byId, mountList, click, chooseKind, chooseSource, chooseMode, lookUp, advance } =
    makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

const unhandled: any[] = []
const onUnhandled = (e: any) => { unhandled.push(e) }

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    unhandled.length = 0
    process.on('unhandledRejection', onUnhandled)
})

afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
    vi.useRealTimers()
    expect(unhandled).toEqual([])
})

const vmOf = (w: any) => w.vm as any
const idsOf = (w: any) => ({ epoch: vmOf(w).epoch, number: vmOf(w).sourceReadNumber, browse: vmOf(w).browse.id, lookup: vmOf(w).lookup.id })

describe('the numbers rise at every choice that drops the sets', () => {
    it('raisesTheRequestNumberTheSetNumbersAndTheReadNumberByAKindAndBySource', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const first = idsOf(w)
        expect(first.browse).not.toBe(first.lookup)
        await chooseKind(w, 'COMPONENT')
        const second = idsOf(w)
        expect(second.epoch).toBeGreaterThan(first.epoch)
        expect(second.browse).toBeGreaterThan(first.browse)
        expect(second.lookup).toBeGreaterThan(first.lookup)
        expect(second.browse).not.toBe(second.lookup)
        await chooseSource(w, S2)
        const third = idsOf(w)
        expect(third.epoch).toBeGreaterThan(second.epoch)
        expect(third.browse).toBeGreaterThan(second.browse)
        expect(third.lookup).toBeGreaterThan(second.lookup)
        expect(third.number).toBeGreaterThan(second.number)
        expect(new Set([third.browse, third.lookup, second.browse, second.lookup, first.browse, first.lookup]).size).toBe(6)
        w.unmount()
    })

    it('hasNewSetsThatAreIdleEmptyAndOnTheChosenSourceAndKind', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        await chooseKind(w, 'COMPONENT')
        for (const set of [vmOf(w).lookup, vmOf(w).browse]) {
            expect(set.kind).toBe('COMPONENT')
            expect(set.source).toBe(S1)
            expect(set.identifier).toBeNull()
            expect(set.tablePage).toBe(1)
            expect(set.later).toEqual({ loading: false, failure: null, restart: false })
            expect(set.failure).toBeNull()
        }
        expect(vmOf(w).lookup.phase).toBe('IDLE')
        expect(vmOf(w).lookup.set.rows).toEqual([])
        expect(vmOf(w).view).toBe('BROWSE')
        w.unmount()
    })

    it('forgetsAWantedCallByAChoiceOfASourceAndOfAKindStartsAnotherOne', async () => {
        const d = deferred()
        const read = deferred()
        answer({ source: (vars: any) => vars.uuid === S1 ? sourceRow(S1) : read.fn(vars), list: d.fn })
        const w = await mountList()
        expect(vmOf(w).listInFlight).toBe(true)
        await chooseKind(w, 'COMPONENT')
        expect(vmOf(w).wanted).not.toBeNull()
        expect(vmOf(w).wanted.variables.kind).toBe('COMPONENT')
        await chooseSource(w, S2)
        expect(vmOf(w).wanted).toBeNull()
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('clearsTheGateOfTheOpenAndTheErrorsWhenTheDialogOpensAgain', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        const vm = vmOf(w)
        vm.gateError = 'a gate error'
        vm.probeError = 'a probe error'
        const before = vm.sourceReadNumber
        vm.reset()
        expect(vm.gateError).toBe('')
        expect(vm.probeError).toBe('')
        expect(vm.sourceReadNumber).toBeGreaterThan(before)
        expect(vm.gate).toBe('NO_SOURCE')
        expect(vm.gateSource).toBeNull()
        w.unmount()
    })
})

describe('a close and an unmount', () => {
    it('raisesTheNumbersForgetsTheWantedCallAndStartsTheGateAgainByAClose', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList()
        await chooseKind(w, 'COMPONENT')
        const vm = vmOf(w)
        expect(vm.wanted).not.toBeNull()
        vm.gateError = 'x'
        vm.probeError = 'y'
        const before = idsOf(w)
        await w.setProps({ show: false })
        expect(vm.epoch).toBeGreaterThan(before.epoch)
        expect(vm.sourceReadNumber).toBeGreaterThan(before.number)
        expect(vm.wanted).toBeNull()
        expect(vm.gate).toBe('NO_SOURCE')
        expect(vm.gateSource).toBeNull()
        expect(vm.sourceRow).toBeNull()
        expect(vm.gateError).toBe('')
        expect(vm.probeError).toBe('')
        w.unmount()
    })

    it('dropsTheAnswerOfACallInFlightAtACloseAndSendsNoWantedCall', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList()
        await chooseKind(w, 'COMPONENT')
        await w.setProps({ show: false })
        d.pending[0].resolve(page({ items: items(3) }))
        await flushPromises()
        expect(vmOf(w).browse.set.rows).toEqual([])
        expect(vmOf(w).browse.phase).not.toBe('LOADED')
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('doesNotSendAWantedCallOfAClosedDialogThatIsStillCurrent', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList()
        const vm = vmOf(w)
        vm.wanted = vm.callFor(vm.browse, 'BROWSE', false)
        await w.setProps({ show: false })
        vm.wanted = vm.callFor(vm.browse, 'BROWSE', false)
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('raisesTheNumbersAndForgetsTheWantedCallByAnUnmount', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList()
        await chooseKind(w, 'COMPONENT')
        const vm = vmOf(w)
        const before = idsOf(w)
        w.unmount()
        expect(vm.epoch).toBeGreaterThan(before.epoch)
        expect(vm.sourceReadNumber).toBeGreaterThan(before.number)
        expect(vm.wanted).toBeNull()
        expect(vm.gate).toBe('NO_SOURCE')
    })

    it('dropsTheAnswerOfACallInFlightAtAnUnmountAndSendsNoWantedCallOfAnUnmountedDialog', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList()
        const vm = vmOf(w)
        w.unmount()
        vm.wanted = vm.callFor(vm.browse, 'BROWSE', false)
        vm.wanted.epoch = vm.epoch
        d.pending[0].resolve(page({ items: items(3) }))
        await flushPromises()
        expect(vm.browse.set.rows).toEqual([])
        expect(listCalls()).toHaveLength(1)
    })

    it('doesNotReadTheSourcesAgainWhenTheDialogCloses', async () => {
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_SOURCES) return { data: { upstreamSources: [{ uuid: S1, name: 'Acme TEA', status: 'ACTIVE' }] } }
            if (args.query === GET_UPSTREAM_SOURCE) return { data: { upstreamSource: sourceRow(S1) } }
            return { data: { upstreamOfferedItems: page({ items: items(1) }) } }
        })
        const w = await mountList({ sources: undefined })
        const reads = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCES).length
        expect(reads()).toBe(1)
        await w.setProps({ show: false })
        expect(reads()).toBe(1)
        expect(vmOf(w).model.source).toBe(S1)
        await w.setProps({ show: true })
        expect(reads()).toBe(2)
        w.unmount()
    })
})

describe('the numbers of the reads of a source', () => {
    it('dropsTheAnswerOfAnEarlierReadOfTheSameSourceWhenAnotherReadWasStarted', async () => {
        const first = deferred()
        const second = deferred()
        let reads = 0
        answer({ source: (vars: any) => { reads += 1; return reads === 1 ? first.fn(vars) : second.fn(vars) }, list: page({ items: items(2) }) })
        const w = await mountList()
        expect(vmOf(w).gate).toBe('READING')
        await chooseMode(w, 'UUID')
        await chooseMode(w, 'LIST')
        expect(sourceReads()).toHaveLength(2)
        const stale = vmOf(w).sourceReadNumber - 1
        expect(stale).toBeGreaterThan(0)
        second.pending[0].resolve(sourceRow(S1))
        await flushPromises()
        expect(vmOf(w).gate).toBe('READY')
        expect(listCalls()).toHaveLength(1)
        first.pending[0].resolve(sourceRow(S1, { status: 'ARCHIVED' }))
        await flushPromises()
        expect(vmOf(w).gate).toBe('READY')
        w.unmount()
    })

    it('answersOnlyTheReadThatIsStillWantedByTheDialogTheSourceTheModeAndTheNumber', async () => {
        const d = deferred()
        answer({ source: d.fn, list: page({ items: items(2) }) })
        const w = await mountList()
        const vm = vmOf(w)
        const number = vm.sourceReadNumber
        vm.paneMode = 'UUID'
        expect(vm.stillWanted(S1, number)).toBe(false)
        vm.paneMode = 'LIST'
        expect(vm.stillWanted(S1, number)).toBe(true)
        expect(vm.stillWanted(S2, number)).toBe(false)
        expect(vm.stillWanted(S1, number - 1)).toBe(false)
        vm.unmounted = true
        expect(vm.stillWanted(S1, number)).toBe(false)
        vm.unmounted = false
        await w.setProps({ show: false })
        expect(vm.stillWanted(S1, vm.sourceReadNumber)).toBe(false)
        w.unmount()
    })

    it('doesNotReadTheSourceAgainWhenTheListComesBackAndTheGateIsReady', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await chooseMode(w, 'UUID')
        await chooseMode(w, 'LIST')
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls()).toHaveLength(1)
        expect(vmOf(w).gateSource).toBe(S1)
        w.unmount()
    })

    it('asksForTheFirstPageOfTheKindChosenByUuidWhenTheListComesBack', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await chooseMode(w, 'UUID')
        await chooseKind(w, 'COMPONENT')
        expect(listCalls()).toHaveLength(1)
        await chooseMode(w, 'LIST')
        expect(listCalls()).toHaveLength(2)
        expect(listCalls()[1].variables.kind).toBe('COMPONENT')
        expect(sourceReads()).toHaveLength(1)
        w.unmount()
    })

    it('asksForNoFirstPageWhenTheListComesBackUnlessTheGateIsReadyTheSetIdleAndTheViewTheBrowse', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const vm = vmOf(w)
        vm.browse.phase = 'IDLE'
        vm.gate = 'PROBE_FAILED'
        vm.enterList()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.gate = 'READY'
        vm.view = 'LOOKUP'
        vm.enterList()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.view = 'BROWSE'
        vm.browse.phase = 'LOADED'
        vm.enterList()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.browse.phase = 'IDLE'
        vm.enterList()
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })

    it('asksForNoFirstPageWhenASourceAnswerComesUnlessTheGateIsReadyTheSetIdleAndTheViewTheBrowse', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const vm = vmOf(w)
        vm.applySource(sourceRow(S1))
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.view = 'LOOKUP'
        vm.browse.phase = 'IDLE'
        vm.applySource(sourceRow(S1))
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.view = 'BROWSE'
        vm.applySource(sourceRow(S1))
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })

    it('hasNoSourceInTheGateWhenTheEnterOfTheListFindsNoSource', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        const vm = vmOf(w)
        vm.gate = 'READY'
        vm.model.source = null
        vm.enterList()
        expect(vm.gate).toBe('NO_SOURCE')
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('doesNotEnterTheListOfAClosedDialog', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        await w.setProps({ show: false })
        vmOf(w).enterList()
        await flushPromises()
        expect(sourceReads()).toHaveLength(1)
        w.unmount()
    })

    it('clearsTheOldTimerWhenAReadStartsSoThatOneReadFollowsAtTheTimeOfTheNewOne', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        const vm = vmOf(w)
        expect(sourceReads()).toHaveLength(1)
        vm.readSource(S1, true)
        await flushPromises()
        expect(sourceReads()).toHaveLength(2)
        await advance(2000)
        expect(sourceReads()).toHaveLength(3)
        w.unmount()
        await advance(20000)
        expect(sourceReads()).toHaveLength(3)
    })

    it('showsNoErrorOfAProbeOfAnEarlierReadWhenTheSourceIsReadAgain', async () => {
        answer({ list: page() })
        const w = await mountList({ source: null })
        const vm = vmOf(w)
        vm.probeError = 'An old refusal.'
        await chooseSource(w, S1)
        expect(vm.probeError).toBe('')
        await click(w, 'upstream-start-cancel')
        w.unmount()
    })
})

describe('an answer that arrives late', () => {
    it('dropsTheRefusalOfACallInFlightAtACloseAndAtAChoice', async () => {
        const a = deferred()
        answer({ list: a.fn })
        const w = await mountList()
        await w.setProps({ show: false })
        a.pending[0].reject(refusal('A late refusal.'))
        await flushPromises()
        expect(vmOf(w).browse.phase).toBe('LOADING')
        expect(vmOf(w).browse.failure).toBeNull()
        w.unmount()
    })

    it('dropsTheRefusalOfACallOfASetThatWasReplacedWhileTheRequestNumberStayedTheSame', async () => {
        const a = deferred()
        const b = deferred()
        answer({ list: [a.fn, b.fn] })
        const w = await mountList()
        const vm = vmOf(w)
        vm.wantFirstPage()
        a.pending[0].reject(refusal('A late refusal.'))
        await flushPromises()
        expect(vm.browse.failure).toBeNull()
        expect(vm.browse.phase).toBe('LOADING')
        b.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(vm.browse.phase).toBe('LOADED')
        w.unmount()
    })

    it('stopsPollingAPendingProbeWhenTheSourceIsCleared', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        expect(sourceReads()).toHaveLength(1)
        await chooseSource(w, null)
        await advance(60000)
        expect(sourceReads()).toHaveLength(1)
        expect(vmOf(w).gate).toBe('NO_SOURCE')
        expect(vmOf(w).gateSource).toBeNull()
        w.unmount()
    })
})

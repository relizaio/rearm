// @vitest-environment happy-dom
//
// T-1145, T-1156, T-1157 (TPF-243 design 3.2 and 3.3), mutation round: the poll of a pending probe
// (every two seconds, one read at a time, stopped by a change of source, of mode, a close and an
// unmount), the answers of an older read dropped, and the choices at the top (source, kind, mode)
// with what each drops.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { S1, S2, firstPageVars, items, page, probeOf, refusal, sourceRow } from './startDialogTestKit'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const labels = await import('@/utils/upstreamLabels')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { answer, deferred, listCalls, sourceReads, byId, mountList, click, chooseLookupType, chooseSource, chooseMode, advance } = makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
})

afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
})

const gateText = (w: any) => byId(w, 'upstream-start-gate').text()
const probeButton = (w: any) => byId(w, 'upstream-start-probe')

describe('the poll of a pending probe', () => {
    beforeEach(() => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    })

    it('readsAgainEveryTwoSecondsOneReadAtATimeAndKeepsTheGateWhileItReads', async () => {
        const d = deferred()
        const pending = sourceRow(S1, probeOf('PENDING', null))
        let reads = 0
        answer({ source: () => { reads += 1; return reads === 1 ? pending : d.fn({}) }, list: page() })
        const w = await mountList()
        expect(gateText(w)).toContain(labels.offerProbePending)
        await advance(2000)
        expect(sourceReads()).toHaveLength(2)
        expect(gateText(w)).toContain(labels.offerProbePending)
        expect(byId(w, 'upstream-start-gate').element.querySelector(':scope > .n-spin-container, :scope > .n-spin-body, :scope > .n-spin')).toBeNull()
        await advance(10000)
        expect(sourceReads()).toHaveLength(2)
        d.pending[0].resolve(pending)
        await flushPromises()
        await advance(1999)
        expect(sourceReads()).toHaveLength(2)
        await advance(1)
        expect(sourceReads()).toHaveLength(3)
        w.unmount()
    })

    it('showsTheGateOfTheNewAnswerAfterAPoll', async () => {
        answer({ source: [sourceRow(S1, probeOf('PENDING', null)), sourceRow(S1, probeOf('DONE', 'TIMED_OUT', 'No answer.'))], list: page() })
        const w = await mountList()
        await advance(2000)
        expect(gateText(w)).toContain(labels.offerProbeFailed)
        expect(probeButton(w).text()).toBe('Probe again')
        await advance(20000)
        expect(sourceReads()).toHaveLength(2)
        w.unmount()
    })

    it('stopsWhenTheSourceChangesAndPollsOnlyTheNewSource', async () => {
        answer({ source: (vars: any) => vars.uuid === S1 ? sourceRow(S1, probeOf('PENDING', null)) : sourceRow(S2), list: page() })
        const w = await mountList()
        await advance(2000)
        expect(sourceReads().map(c => c.variables.uuid)).toEqual([S1, S1])
        await chooseSource(w, S2)
        expect(sourceReads().map(c => c.variables.uuid)).toEqual([S1, S1, S2])
        await advance(60000)
        expect(sourceReads().map(c => c.variables.uuid)).toEqual([S1, S1, S2])
        w.unmount()
    })

    it('stopsWhenTheModeIsByUuidAndStartsAgainWithOneReadWhenTheListIsBack', async () => {
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        await advance(2000)
        expect(sourceReads()).toHaveLength(2)
        await chooseMode(w, 'UUID')
        await advance(60000)
        expect(sourceReads()).toHaveLength(2)
        await chooseMode(w, 'LIST')
        expect(sourceReads()).toHaveLength(3)
        expect(gateText(w)).toContain(labels.offerProbePending)
        await advance(2000)
        expect(sourceReads()).toHaveLength(4)
        w.unmount()
    })

    it('doesNotReadAgainOnReturningToTheListWhenTheProbeFailedOrWasNeverSent', async () => {
        answer({ source: sourceRow(S1, probeOf('DONE', 'TIMED_OUT', 'No answer.')), list: page() })
        const w = await mountList()
        await chooseMode(w, 'UUID')
        await chooseMode(w, 'LIST')
        expect(sourceReads()).toHaveLength(1)
        expect(gateText(w)).toContain(labels.offerProbeFailed)
        w.unmount()
    })

    it('stopsWhenTheDialogIsUnmounted', async () => {
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        w.unmount()
        await advance(60000)
        expect(sourceReads()).toHaveLength(1)
    })

    it('restartsTheReadOfAPendingProbeWhenTheDialogIsOpenedAgain', async () => {
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        await w.setProps({ show: false })
        await advance(60000)
        expect(sourceReads()).toHaveLength(1)
        await w.setProps({ show: true })
        await flushPromises()
        expect(sourceReads()).toHaveLength(2)
        await advance(2000)
        expect(sourceReads()).toHaveLength(3)
        w.unmount()
    })

    it('showsTheProbeResultPollsAndListsOnceWhenTheProbeIsSentAndAnsweredPending', async () => {
        answer({ source: [sourceRow(S1, { probe: null }), sourceRow(S1, probeOf('DONE', 'REACHABLE', 'ok'))], list: page({ items: items(1) }) })
        mutate.mockResolvedValueOnce({ data: { probeUpstreamSource: sourceRow(S1, probeOf('PENDING', null)) } })
        const w = await mountList()
        await click(w, 'upstream-start-probe')
        expect(listCalls()).toHaveLength(0)
        await advance(2000)
        expect(listCalls()).toHaveLength(1)
        expect(byId(w, 'upstream-start-gate').exists()).toBe(false)
        w.unmount()
    })
})

describe('the answers of an older read', () => {
    it('dropsTheAnswerOfASourceThatWasLeft', async () => {
        const d = deferred()
        answer({ source: (vars: any) => vars.uuid === S1 ? d.fn(vars) : sourceRow(S2, probeOf('DONE', 'TIMED_OUT', 'No answer.')), list: page() })
        const w = await mountList()
        await chooseSource(w, S2)
        d.pending[0].resolve(sourceRow(S1))
        await flushPromises()
        expect(listCalls()).toHaveLength(0)
        expect(gateText(w)).toContain(labels.offerProbeFailed)
        w.unmount()
    })

    it('dropsTheRefusalOfASourceThatWasLeft', async () => {
        const d = deferred()
        answer({ source: (vars: any) => vars.uuid === S1 ? d.fn(vars) : sourceRow(S2, probeOf('DONE', 'TIMED_OUT', 'No answer.')), list: page() })
        const w = await mountList()
        await chooseSource(w, S2)
        d.pending[0].reject(refusal('Late.'))
        await flushPromises()
        expect(gateText(w)).toContain(labels.offerProbeFailed)
        expect(gateText(w)).not.toContain('Late.')
        w.unmount()
    })

    it('dropsTheAnswerOfAReadOfAnEarlierOpenOfTheSameSource', async () => {
        const d = deferred()
        let reads = 0
        answer({ source: (vars: any) => { reads += 1; return reads === 1 ? d.fn(vars) : sourceRow(S1, probeOf('DONE', 'TIMED_OUT', 'No answer.')) }, list: page() })
        const w = await mountList()
        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        d.pending[0].resolve(sourceRow(S1))
        await flushPromises()
        expect(listCalls()).toHaveLength(0)
        expect(gateText(w)).toContain(labels.offerProbeFailed)
        w.unmount()
    })
})

describe('the choices at the top', () => {
    it('doesNothingWhenTheSourceChosenIsTheSourceAlreadyChosen', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await chooseSource(w, S1)
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('goesBackToTheSentenceToChooseASourceWhenTheSourceIsCleared', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await chooseSource(w, null)
        expect(gateText(w)).toBe(labels.offerChooseSource)
        expect(byId(w, 'upstream-start-list').exists()).toBe(false)
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('dropsTheFilterAndTheLookupFieldsWhenTheSourceChanges', async () => {
        answer({ list: page({ items: items(3) }) })
        const w = await mountList()
        await byId(w, 'upstream-start-filter').find('input').setValue('Item 2')
        await (w.vm as any).$nextTick()
        await chooseLookupType(w, 'TEI')
        await byId(w, 'upstream-start-lookup-value').find('input').setValue('tei://x')
        await click(w, 'upstream-start-lookup-submit')
        expect(w.find('.upstream-start-lookup-head').exists()).toBe(true)
        await chooseSource(w, S2)
        expect(w.find('.upstream-start-lookup-head').exists()).toBe(false)
        expect(listCalls().at(-1)!.variables).toEqual(firstPageVars({ source: S2 }))
        expect((byId(w, 'upstream-start-filter').find('input').element as HTMLInputElement).value).toBe('')
        expect((byId(w, 'upstream-start-lookup-value').find('input').element as HTMLInputElement).value).toBe('')
        expect(document.body.querySelector('[data-testid="upstream-start-lookup-type"] .n-base-selection-placeholder')?.textContent).toBe('Identifier type')
        expect(byId(w, 'upstream-start-lookup-problem').exists()).toBe(false)
        w.unmount()
    })
})

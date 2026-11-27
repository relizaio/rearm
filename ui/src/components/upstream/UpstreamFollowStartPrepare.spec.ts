// @vitest-environment happy-dom
//
// T-1149, T-1151, T-1156, T-1157 (TPF-243 design 3.2, 3.6 and 3.9), mutation round: Prepare from a row
// and by uuid with the guards of each (one preparation at a time, a row that is no uuid, no row, no
// source) and the arguments exactly as they are served; when a refusal is cleared (a new attempt,
// another mode, a new open); the answer of a preparation that is no uuid; the guards of the lookup;
// and the answers of the reads that carry no response, no data or no row.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ITEM, PREP, S1, item, items, page, refusal, sourceRow } from './startDialogTestKit'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, PROBE_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { PREPARE_UPSTREAM_FOLLOW } = await import('@/utils/upstreamFollowQueries')
const labels = await import('@/utils/upstreamLabels')
const { answer, deferred, listCalls, sourceReads, byId, mountList, click, chooseMode, chooseLookupType, lookUp } =
    makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

const unhandled: any[] = []
const onUnhandled = (e: any) => { unhandled.push(e) }

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    unhandled.length = 0
    process.on('unhandledRejection', onUnhandled)
})

afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
    expect(unhandled).toEqual([])
})

const vmOf = (w: any) => w.vm as any
const PREPARED = { data: { prepareUpstreamFollow: { uuid: PREP, state: 'PENDING' } } }
const typeUuid = (w: any, value = ITEM) => byId(w, 'upstream-start-uuid').find('input').setValue(value)
const rowAt = (n: number) => item(n)

describe('Prepare from a row, guard by guard', () => {
    async function listed () {
        answer({ list: page({ items: items(3) }) })
        return mountList()
    }

    it('sendsTheKindAndTheSourceOfTheSetAndTheUuidOfTheRowExactlyAsServed', async () => {
        const upper = 'DDDDDDDD-0000-4000-8000-000000000001'
        answer({ list: page({ items: [item(1, { upstreamUuid: upper })] }) })
        mutate.mockResolvedValueOnce(PREPARED)
        const w = await mountList({ source: S1 })
        await click(w, 'upstream-start-row-prepare')
        expect(mutate.mock.calls[0][0]).toEqual({ mutation: PREPARE_UPSTREAM_FOLLOW, variables: { source: S1, kind: 'PRODUCT', upstreamUuid: upper }, fetchPolicy: 'no-cache' })
        w.unmount()
    })

    it('usesTheKindAndTheSourceOfTheSetThatIsShownAndNotTheOnesOfTheModel', async () => {
        const w = await listed()
        const vm = vmOf(w)
        vm.model.kind = 'COMPONENT'
        vm.model.source = 'cccccccc-0000-4000-8000-00000000000c'
        mutate.mockResolvedValue(PREPARED)
        await vm.prepareRow(rowAt(2))
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: S1, kind: 'PRODUCT', upstreamUuid: rowAt(2).upstreamUuid })
        vm.lookup.kind = 'COMPONENT'
        vm.lookup.source = 'dddddddd-0000-4000-8000-00000000000d'
        vm.view = 'LOOKUP'
        await vm.prepareRow(rowAt(3))
        expect(mutate.mock.calls[1][0].variables).toEqual({ source: 'dddddddd-0000-4000-8000-00000000000d', kind: 'COMPONENT', upstreamUuid: rowAt(3).upstreamUuid })
        w.unmount()
    })

    it.each([
        ['no row', () => null],
        ['no uuid', () => ({ upstreamUuid: null })],
        ['a uuid that is none', () => ({ upstreamUuid: 'not-a-uuid' })],
    ])('prepares%sNever', async (_name, row) => {
        const w = await listed()
        expect(() => vmOf(w).prepareRow(row())).not.toThrow()
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('preparesNothingForASetWithNoSourceAndNothingWhileAPreparationIsSent', async () => {
        const d = deferred()
        const w = await listed()
        const vm = vmOf(w)
        vm.browse.source = ''
        await vm.prepareRow(rowAt(1))
        expect(mutate).not.toHaveBeenCalled()
        vm.browse.source = S1
        mutate.mockImplementationOnce(d.fn)
        vm.prepareRow(rowAt(1))
        await flushPromises()
        vm.prepareRow(rowAt(2))
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        d.pending[0].resolve(PREPARED)
        await flushPromises()
        w.unmount()
    })

    it('opensNothingForAnAnswerWhoseUuidIsNoUuid', async () => {
        const w = await listed()
        for (const uuid of ['not-a-uuid', '', null, undefined]) {
            mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid, state: 'PENDING' } } })
            await click(w, 'upstream-start-row-prepare', 0)
            expect(router.push).not.toHaveBeenCalled()
            expect(w.emitted('update:show')).toBeUndefined()
            expect(vmOf(w).sending).toBe(false)
        }
        mutate.mockResolvedValueOnce({ data: null })
        await click(w, 'upstream-start-row-prepare', 0)
        expect(router.push).not.toHaveBeenCalled()
        w.unmount()
    })

    it('opensTheConfirmationAndAsksTheParentToCloseOnceBeforeTheRouteChanges', async () => {
        const w = await listed()
        mutate.mockResolvedValueOnce(PREPARED)
        const order: string[] = []
        router.push.mockImplementation(() => { order.push(`push:sending=${vmOf(w).sending}`) })
        await click(w, 'upstream-start-row-prepare', 0)
        expect(order).toEqual(['push:sending=false'])
        expect(w.emitted('update:show')).toEqual([[false]])
        w.unmount()
    })
})

describe('when a refusal is cleared', () => {
    it('clearsTheRefusalOfAnEarlierAttemptWhenTheUuidIsPreparedAgain', async () => {
        const d = deferred()
        mutate.mockRejectedValueOnce(refusal('Refused once.'))
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList({ mode: 'UUID' })
        await typeUuid(w)
        await click(w, 'upstream-start-submit')
        expect(byId(w, 'upstream-start-error').text()).toBe('Refused once.')
        await click(w, 'upstream-start-submit')
        expect(byId(w, 'upstream-start-error').exists()).toBe(false)
        d.pending[0].reject(refusal('Refused twice.'))
        await flushPromises()
        expect(byId(w, 'upstream-start-error').text()).toBe('Refused twice.')
        await typeUuid(w, 'not a uuid')
        await click(w, 'upstream-start-submit')
        expect(byId(w, 'upstream-start-error').exists()).toBe(false)
        expect(mutate).toHaveBeenCalledTimes(2)
        w.unmount()
    })

    it('sendsTheUuidTrimmedAndLowerCasedAndTheSourceAndKindAsTheyAre', async () => {
        mutate.mockResolvedValueOnce(PREPARED)
        const w = await mountList({ mode: 'UUID', source: S1, kind: 'COMPONENT' })
        await typeUuid(w, `  ${ITEM.toUpperCase()}  `)
        await click(w, 'upstream-start-submit')
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: S1, kind: 'COMPONENT', upstreamUuid: ITEM })
        w.unmount()
    })

    it('sendsTheSourceOfTheChoiceExactlyAsItWasGiven', async () => {
        const upper = S1.toUpperCase()
        mutate.mockResolvedValueOnce(PREPARED)
        const w = await mountList({ mode: 'UUID', source: upper })
        await typeUuid(w)
        await click(w, 'upstream-start-submit')
        expect(mutate.mock.calls[0][0].variables.source).toBe(upper)
        w.unmount()
    })

    it('clearsTheRefusalOfARowWhenAnotherRowIsPrepared', async () => {
        const d = deferred()
        answer({ list: page({ items: items(3) }) })
        mutate.mockRejectedValueOnce(refusal('Refused once.'))
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList()
        await click(w, 'upstream-start-row-prepare', 0)
        expect(byId(w, 'upstream-start-error').text()).toBe('Refused once.')
        await click(w, 'upstream-start-row-prepare', 1)
        expect(byId(w, 'upstream-start-error').exists()).toBe(false)
        w.unmount()
    })

    it('clearsTheRefusalWhenTheModeChangesAndKeepsItWhenTheModeIsNone', async () => {
        answer({ list: page({ items: items(3) }) })
        mutate.mockRejectedValueOnce(refusal('Refused once.'))
        const w = await mountList()
        await click(w, 'upstream-start-row-prepare', 0)
        expect(byId(w, 'upstream-start-error').exists()).toBe(true)
        vmOf(w).onMode('OTHER')
        await flushPromises()
        expect(vmOf(w).paneMode).toBe('LIST')
        expect(byId(w, 'upstream-start-error').exists()).toBe(true)
        await chooseMode(w, 'UUID')
        expect(byId(w, 'upstream-start-error').exists()).toBe(false)
        w.unmount()
    })

    it('clearsTheAttemptAndTheRefusalWhenTheDialogIsOpenedAgain', async () => {
        mutate.mockRejectedValueOnce(refusal('Refused once.'))
        const w = await mountList({ mode: 'UUID' })
        await typeUuid(w)
        await click(w, 'upstream-start-submit')
        expect(vmOf(w).tried).toBe(true)
        expect(vmOf(w).error).toBe('Refused once.')
        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        expect(vmOf(w).tried).toBe(false)
        expect(vmOf(w).error).toBe('')
        expect(vmOf(w).model.upstreamUuid).toBe('')
        w.unmount()
    })
})

describe('the lookup, guard by guard', () => {
    it.each([
        ['the gate is not ready', (vm: any) => { vm.gate = 'PROBE_FAILED' }],
        ['no source is chosen', (vm: any) => { vm.model.source = null }],
        ['a call is in flight', (vm: any) => { vm.listInFlight = true }],
    ])('sendsNoLookupWhen%s', async (_name, spoil) => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await chooseLookupType(w, 'TEI')
        await byId(w, 'upstream-start-lookup-value').find('input').setValue('tei://x')
        spoil(vmOf(w))
        vmOf(w).lookUp()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        expect(vmOf(w).view).toBe('BROWSE')
        expect(vmOf(w).lookupProblem).toBe('')
        w.unmount()
    })

    it('showsTheProblemOfAnIdentifierAndSendsNothingAndClearsItForAGoodOne', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').text()).toBe(labels.offerLookupTypeProblem)
        await chooseLookupType(w, 'TEI')
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').text()).toBe(labels.identifierValueProblem)
        expect(listCalls()).toHaveLength(1)
        await byId(w, 'upstream-start-lookup-value').find('input').setValue('  tei://x  ')
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').exists()).toBe(false)
        expect(listCalls()[1].variables.identifier).toEqual({ idType: 'TEI', idValue: 'tei://x' })
        expect(vmOf(w).view).toBe('LOOKUP')
        w.unmount()
    })

    it('replacesTheLookupSetWithTheIdentifierAndKeepsTheListWhereItIs', async () => {
        answer({ list: [page({ items: items(2) }), page({ items: items(5, 10) })] })
        const w = await mountList()
        const before = vmOf(w).lookup.id
        await lookUp(w, 'TEI', 'tei://x')
        expect(vmOf(w).lookup.id).toBeGreaterThan(before)
        expect(vmOf(w).lookup.source).toBe(S1)
        expect(vmOf(w).lookup.kind).toBe('PRODUCT')
        expect(vmOf(w).lookup.identifier).toEqual({ idType: 'TEI', idValue: 'tei://x' })
        expect(vmOf(w).browse.set.rows).toHaveLength(2)
        w.unmount()
    })
})

describe('the answers of the reads that carry nothing', () => {
    const sourceAnswer = (answerOf: any) => query.mockImplementation(async (args: any) => {
        if (args.query === GET_UPSTREAM_SOURCE) return answerOf
        throw new Error('unexpected')
    })

    it.each([
        ['no response', null],
        ['no response at all', undefined],
        ['a response with no data', {}],
        ['a response with no data field', { data: null }],
        ['a response with no source', { data: {} }],
        ['a response with a null source', { data: { upstreamSource: null } }],
    ])('showsTheForbiddenSentenceFor%s', async (_name, response) => {
        sourceAnswer(response)
        const w = await mountList()
        expect(byId(w, 'upstream-start-gate').text()).toBe(labels.offerForbiddenSentence)
        expect(vmOf(w).gate).toBe('FORBIDDEN')
        expect(vmOf(w).sourceRow).toBeNull()
        w.unmount()
    })

    it.each([
        ['no response', null],
        ['a response with no data', {}],
        ['a response with null data', { data: null }],
        ['a response with no row', { data: {} }],
    ])('showsTheForbiddenSentenceAndNoRefusalForAProbeWith%s', async (_name, response) => {
        answer({ source: sourceRow(S1, { probe: null }) })
        mutate.mockResolvedValueOnce(response)
        const w = await mountList()
        await click(w, 'upstream-start-probe')
        expect(byId(w, 'upstream-start-gate').text()).toBe(labels.offerForbiddenSentence)
        expect(byId(w, 'upstream-start-probe-error').exists()).toBe(false)
        expect(mutate.mock.calls[0][0].mutation).toBe(PROBE_UPSTREAM_SOURCE)
        w.unmount()
    })

    it('showsNoSourceAndNoErrorForTheSourcesOfAnAnswerWithNoSourcesField', async () => {
        query.mockImplementation(async (args: any) => args.query === GET_UPSTREAM_SOURCES ? { data: {} } : { data: { upstreamSource: sourceRow(S1) } })
        const errors: any[] = []
        const w = await mountList({ sources: undefined, source: null }, errors)
        expect(errors).toEqual([])
        expect(w.findComponent({ name: 'Select' }).props('options')).toEqual([])
        w.unmount()
    })

    it('clearsTheRefusalOfAProbeWhenTheSourceIsReadAgainAndNotWhenItIsPolled', async () => {
        answer({ source: sourceRow(S1, { probe: null }), list: page() })
        const w = await mountList()
        const vm = vmOf(w)
        vm.probeError = 'A refusal.'
        vm.readSource(S1, true)
        await flushPromises()
        expect(vm.probeError).toBe('A refusal.')
        vm.readSource(S1, false)
        await flushPromises()
        expect(vm.probeError).toBe('')
        expect(sourceReads()).toHaveLength(3)
        w.unmount()
    })

    it('readsNothingForAProbeWithoutASourceAndSendsOnlyOneProbeAtATime', async () => {
        const d = deferred()
        answer({ source: sourceRow(S1, { probe: null }) })
        mutate.mockImplementation(d.fn)
        const w = await mountList()
        vmOf(w).probe()
        vmOf(w).probe()
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        d.pending[0].resolve({ data: { probeUpstreamSource: sourceRow(S1) } })
        await flushPromises()
        expect(vmOf(w).probing).toBe(false)
        vmOf(w).model.source = null
        vmOf(w).probe()
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        w.unmount()
    })
})

// @vitest-environment happy-dom
//
// T-1145, T-1156, T-1157 (TPF-243 design 3.2 and 3.3), mutation round: the top of the dialog and
// the gate in front of the list. The words and the arguments of every request of the gate, the nine
// states of the gate and what each shows, and the probe (one at a time, its refusal in its own
// place, its answer through the gate). The poll and the choices at the top are in the Poll spec.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { NRadioButton } from 'naive-ui'
import { ITEM, ORG, PREP, S1, S2, SOURCES, firstPageVars, item, items, page, probeOf, refusal, sourceRow } from './startDialogTestKit'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, PROBE_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const labels = await import('@/utils/upstreamLabels')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { answer, deferred, listCalls, sourceReads, byId, allById, mountList, click, chooseKind, chooseSource, chooseMode, advance } = makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

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

describe('the top of the dialog', () => {
    it('hasTheTitleTheWidthTheLabelsAndTheWordsOfTheDesign', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        const modal = w.find('.modal')
        expect(modal.attributes('title')).toBe('Follow an item')
        expect(modal.attributes('style')).toBe('width: 900px; max-width: 95vw;')
        expect([...document.body.querySelectorAll('.n-form-item-label')].map(l => l.textContent)).toEqual(['Source', 'Kind'])
        expect(byId(w, 'upstream-start-mode-LIST').text()).toBe('From the list')
        expect(byId(w, 'upstream-start-mode-UUID').text()).toBe('By uuid')
        expect(w.findAllComponents(NRadioButton).map(r => r.props('value'))).toEqual(['LIST', 'UUID'])
        expect(byId(w, 'upstream-start-kind-PRODUCT').text()).toBe('Product')
        expect(byId(w, 'upstream-start-kind-COMPONENT').text()).toBe('Component')
        expect(document.body.querySelector('.upstream-start-lookup-label')?.textContent).toBe('Look up by identifier')
        expect(byId(w, 'upstream-start-lookup-value').find('input').attributes('placeholder')).toBe('Exact value')
        expect(document.body.querySelector('[data-testid="upstream-start-lookup-type"] .n-base-selection-placeholder')?.textContent).toBe('Identifier type')
        expect(byId(w, 'upstream-start-filter').find('input').attributes('placeholder')).toBe(labels.offerFilterPlaceholder)
        expect(byId(w, 'upstream-start-lookup-submit').text()).toBe('Look up')
        expect(byId(w, 'upstream-start-cancel').text()).toBe('Cancel')
        expect(byId(w, 'upstream-start-submit').exists()).toBe(false)
        w.unmount()
    })

    it('hasTheUuidFieldWithItsLabelAndPlaceholderAndThePrepareButtonOnlyByUuid', async () => {
        const w = await mountList({ mode: 'UUID' })
        expect([...document.body.querySelectorAll('.n-form-item-label')].map(l => l.textContent)).toEqual(['Source', 'Kind', 'Upstream uuid'])
        expect(byId(w, 'upstream-start-uuid').find('input').attributes('placeholder')).toBe('00000000-0000-0000-0000-000000000000')
        expect(byId(w, 'upstream-start-submit').text()).toBe('Prepare')
        expect(byId(w, 'upstream-start-list-pane').exists()).toBe(false)
        expect(byId(w, 'upstream-start-mode').exists()).toBe(true)
        await chooseMode(w, 'LIST')
        expect(byId(w, 'upstream-start-uuid').exists()).toBe(false)
        expect(byId(w, 'upstream-start-submit').exists()).toBe(false)
        expect(byId(w, 'upstream-start-list-pane').exists()).toBe(true)
        w.unmount()
    })

    it('asksForASourceInTheSelectWhileNoneIsChosen', async () => {
        const w = await mountList({ source: null })
        expect(document.body.querySelector('[data-testid="upstream-start-source"] .n-base-selection-placeholder')?.textContent).toBe('Choose a source')
        w.unmount()
    })

    it('showsTheUuidProblemAsAnErrorOnlyAfterAnAttempt', async () => {
        const w = await mountList({ mode: 'UUID' })
        const item = () => byId(w, 'upstream-start-uuid').element.closest('.n-form-item') as HTMLElement
        expect(item().querySelector('.n-form-item-feedback--error')).toBeNull()
        expect(item().textContent).toContain(labels.followUuidHint)
        await byId(w, 'upstream-start-uuid').find('input').setValue('not a uuid')
        expect(item().querySelector('.n-form-item-feedback--error')).toBeNull()
        await click(w, 'upstream-start-submit')
        expect(item().querySelector('.n-form-item-feedback--error')?.textContent).toContain('Enter the uuid of the item on the supplier\'s server')
        expect(item().textContent).not.toContain(labels.followUuidHint)
        await byId(w, 'upstream-start-uuid').find('input').setValue(ITEM)
        expect(item().querySelector('.n-form-item-feedback--error')).toBeNull()
        expect(item().textContent).toContain(labels.followUuidHint)
        w.unmount()
    })

    it('showsTheSourceProblemAsAnErrorOnlyAfterAnAttempt', async () => {
        const w = await mountList({ mode: 'UUID', source: null })
        const item = () => byId(w, 'upstream-start-source').element.closest('.n-form-item') as HTMLElement
        expect(item().querySelector('.n-form-item-feedback--error')).toBeNull()
        await byId(w, 'upstream-start-uuid').find('input').setValue(ITEM)
        await click(w, 'upstream-start-submit')
        expect(item().querySelector('.n-form-item-feedback--error')?.textContent).toContain('Choose a source.')
        await chooseSource(w, S1)
        expect(item().querySelector('.n-form-item-feedback--error')).toBeNull()
        w.unmount()
    })

    it.each([
        ['the mode given', { mode: 'LIST', upstreamUuid: ITEM }, 'LIST'],
        ['the mode given by uuid', { mode: 'UUID' }, 'UUID'],
        ['a uuid and no mode', { upstreamUuid: ITEM }, 'UUID'],
        ['no uuid and no mode', {}, 'LIST'],
        ['a mode that is none of the two', { mode: 'OTHER', upstreamUuid: ITEM }, 'UUID'],
    ])('opensOn%sAs%s', async (_name, props, mode) => {
        answer({ list: page() })
        const w = await mountList(props)
        expect(byId(w, 'upstream-start-list-pane').exists()).toBe(mode === 'LIST')
        expect(byId(w, 'upstream-start-uuid').exists()).toBe(mode === 'UUID')
        w.unmount()
    })

    it('opensWithTheKindGivenOrProductWhenItIsNoneOfTheTwo', async () => {
        answer({ list: page() })
        const a = await mountList({ kind: 'COMPONENT' })
        expect(listCalls().map(c => c.variables.kind)).toEqual(['COMPONENT'])
        a.unmount()
        query.mockClear()
        answer({ list: page() })
        const b = await mountList({ kind: 'SERVICE' })
        expect(listCalls().map(c => c.variables.kind)).toEqual(['PRODUCT'])
        b.unmount()
    })
})

describe('the sources the dialog reads itself', () => {
    it('readsTheActiveSourcesOfTheOrganizationAndOffersTheirNames', async () => {
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_SOURCES) return { data: { upstreamSources: [{ uuid: S1, name: 'Acme TEA', status: 'ACTIVE' }, { uuid: S2, name: 'Old', status: 'ARCHIVED' }] } }
            throw new Error('unexpected')
        })
        const w = await mountList({ sources: undefined, source: null })
        expect(query).toHaveBeenCalledTimes(1)
        expect(query.mock.calls[0][0]).toEqual({ query: GET_UPSTREAM_SOURCES, variables: { org: ORG, statuses: ['ACTIVE'] }, fetchPolicy: 'no-cache' })
        const select = w.findComponent({ name: 'Select' })
        expect(select.props('options')).toEqual([{ label: 'Acme TEA', value: S1 }])
        w.unmount()
    })

    it('readsNothingWhenTheSourcesAreGivenOrThereIsNoOrganization', async () => {
        const given = await mountList({ source: null })
        const none = await mountList({ sources: undefined, orgUuid: '', source: null })
        expect(query).not.toHaveBeenCalled()
        given.unmount()
        none.unmount()
    })

    it('offersNoSourceForAnAnswerWithNoDataAndShowsARefusalInTheErrorPlace', async () => {
        query.mockResolvedValue({ data: null })
        const empty = await mountList({ sources: undefined, source: null })
        expect(empty.findComponent({ name: 'Select' }).props('options')).toEqual([])
        expect(byId(empty, 'upstream-start-error').exists()).toBe(false)
        empty.unmount()
        query.mockReset()
        query.mockRejectedValue(refusal('The sources could not be read.'))
        const refused = await mountList({ sources: undefined, source: null })
        expect(byId(refused, 'upstream-start-error').text()).toBe('The sources could not be read.')
        refused.unmount()
    })
})

describe('the arguments of the requests of the gate', () => {
    it('readsTheSourceWithoutTheCacheAndProbesWithoutTheCache', async () => {
        answer({ source: sourceRow(S1, { probe: null }) })
        mutate.mockResolvedValueOnce({ data: { probeUpstreamSource: sourceRow(S1, probeOf('PENDING', null)) } })
        const w = await mountList()
        expect(query.mock.calls[0][0]).toEqual({ query: GET_UPSTREAM_SOURCE, variables: { uuid: S1 }, fetchPolicy: 'no-cache' })
        await click(w, 'upstream-start-probe')
        expect(mutate.mock.calls[0][0]).toEqual({ mutation: PROBE_UPSTREAM_SOURCE, variables: { uuid: S1 }, fetchPolicy: 'no-cache' })
        w.unmount()
    })
})

describe('the states of the gate', () => {
    it('showsTheSentenceToChooseASourceAndNothingElseWhileNoneIsChosen', async () => {
        const w = await mountList({ source: null })
        expect(gateText(w)).toBe(labels.offerChooseSource)
        expect(probeButton(w).exists()).toBe(false)
        expect(byId(w, 'upstream-start-lookup-submit').exists()).toBe(false)
        expect(query).not.toHaveBeenCalled()
        w.unmount()
    })

    it('showsASpinnerAndNoWordWhileTheSourceIsRead', async () => {
        const d = deferred()
        answer({ source: d.fn, list: page() })
        const w = await mountList()
        const gate = byId(w, 'upstream-start-gate')
        expect(gate.element.querySelector(':scope > .n-spin-container, :scope > .n-spin-body, :scope > .n-spin')).not.toBeNull()
        expect(gate.text()).toBe('')
        expect(probeButton(w).exists()).toBe(false)
        d.pending[0].resolve(sourceRow())
        await flushPromises()
        expect(byId(w, 'upstream-start-gate').exists()).toBe(false)
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('showsTheArchivedSentenceForAnArchivedSourceWhateverItsProbeSays', async () => {
        answer({ source: sourceRow(S1, { status: 'ARCHIVED' }), list: page() })
        const w = await mountList()
        expect(gateText(w)).toBe(labels.offerSourceArchived)
        expect(probeButton(w).exists()).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it('showsTheForbiddenSentenceForASourceThatIsNotThereAndForARefusalNotAuthorized', async () => {
        answer({ source: null, list: page() })
        const a = await mountList()
        expect(gateText(a)).toBe(labels.offerForbiddenSentence)
        expect(a.find('.upstream-start-gate-error').exists()).toBe(false)
        expect(probeButton(a).exists()).toBe(false)
        a.unmount()
        answer({ source: refusal('Not authorized'), list: page() })
        const b = await mountList()
        expect(gateText(b)).toBe(labels.offerForbiddenSentence)
        expect(b.find('.upstream-start-gate-error').exists()).toBe(false)
        expect(listCalls()).toHaveLength(0)
        b.unmount()
    })

    it('showsTheRefusalOfAnotherReadInTheErrorStyleAndNeverTheForbiddenSentence', async () => {
        answer({ source: refusal('The source could not be read just now.'), list: page() })
        const w = await mountList()
        const gate = byId(w, 'upstream-start-gate')
        expect(gate.text()).toBe('The source could not be read just now.')
        expect(gate.find('.upstream-start-gate-error').text()).toBe('The source could not be read just now.')
        expect(gate.text()).not.toContain(labels.offerForbiddenSentence)
        expect(probeButton(w).exists()).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it.each([
        ['a probe that is none yet', { probe: null }, labels.offerNeverProbed, 'Probe'],
        ['a probe that failed to reach the server', probeOf('DONE', 'TIMED_OUT', 'No answer.'), labels.offerProbeFailed, 'Probe again'],
        ['a probe that is not done and not pending', probeOf('FAILED', 'REACHABLE', 'Broken.'), labels.offerProbeFailed, 'Probe again'],
        ['a probe of no state', probeOf(null as any, null), labels.offerProbeFailed, 'Probe again'],
        ['a probe done with no result', probeOf('DONE', null), labels.offerProbeFailed, 'Probe again'],
    ])('showsTheProbeButtonAndNoListFor%s', async (_name, over, sentence, button) => {
        answer({ source: sourceRow(S1, over), list: page() })
        const w = await mountList()
        expect(gateText(w)).toContain(sentence)
        expect(probeButton(w).text()).toBe(button)
        expect(allById(w, 'upstream-start-probe')).toHaveLength(1)
        expect(listCalls()).toHaveLength(0)
        expect(byId(w, 'upstream-start-lookup-submit').exists()).toBe(false)
        w.unmount()
    })

    it('showsTheProbeResultOfASourceWhoseRowHasNoCredentialInEveryStateThatShowsIt', async () => {
        for (const [over, marker] of [[probeOf('PENDING', null), 'upstream-probe-pending'], [probeOf('DONE', 'TIMED_OUT', 'No answer.'), 'upstream-probe-tag'], [probeOf('FAILED', null), 'upstream-probe-done']] as const) {
            answer({ source: sourceRow(S1, { ...over, credential: null }), list: page() })
            const errors: any[] = []
            const w = await mountList({}, errors)
            expect(errors).toEqual([])
            expect(byId(w, 'upstream-start-gate').find(`[data-testid="${marker}"]`).exists()).toBe(true)
            w.unmount()
        }
    })

    it('showsThePendingTextAndTheTagWithoutAProbeButton', async () => {
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        expect(gateText(w)).toContain('Probe in progress')
        expect(gateText(w)).toContain(labels.offerProbePending)
        expect(probeButton(w).exists()).toBe(false)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })

    it('listsOnlyForADoneProbeThatReachedTheServer', async () => {
        answer({ source: sourceRow(S1, probeOf('DONE', 'REACHABLE', 'ok')), list: page({ items: items(1) }) })
        const w = await mountList()
        expect(byId(w, 'upstream-start-gate').exists()).toBe(false)
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })
})

describe('the probe button', () => {
    it('isLoadingAndDisabledWhileTheProbeIsInFlightAndSendsOneProbe', async () => {
        const d = deferred()
        answer({ source: sourceRow(S1, { probe: null }) })
        mutate.mockImplementation(d.fn)
        const w = await mountList()
        expect(probeButton(w).attributes('disabled')).toBeUndefined()
        expect(probeButton(w).classes()).not.toContain('n-button--loading')
        await click(w, 'upstream-start-probe')
        expect(probeButton(w).attributes('disabled')).toBeDefined()
        expect(probeButton(w).classes()).toContain('n-button--loading')
        ;(w.vm as any).probe()
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        d.pending[0].resolve({ data: { probeUpstreamSource: sourceRow(S1, probeOf('PENDING', null)) } })
        await flushPromises()
        expect(gateText(w)).toContain(labels.offerProbePending)
        w.unmount()
    })

    it('isEnabledAgainAfterARefusalAndShowsTheTextInItsOwnPlace', async () => {
        answer({ source: sourceRow(S1, { probe: null }) })
        mutate.mockRejectedValueOnce(refusal('The probe limit of the organization is reached.'))
        mutate.mockRejectedValueOnce(refusal('Again refused.'))
        const w = await mountList()
        expect(byId(w, 'upstream-start-probe-error').exists()).toBe(false)
        await click(w, 'upstream-start-probe')
        expect(byId(w, 'upstream-start-probe-error').text()).toBe('The probe limit of the organization is reached.')
        expect(byId(w, 'upstream-start-probe-error').classes()).toContain('upstream-start-gate-error')
        expect(byId(w, 'upstream-start-error').exists()).toBe(false)
        expect(probeButton(w).attributes('disabled')).toBeUndefined()
        expect(probeButton(w).classes()).not.toContain('n-button--loading')
        await click(w, 'upstream-start-probe')
        expect(mutate).toHaveBeenCalledTimes(2)
        expect(byId(w, 'upstream-start-probe-error').text()).toBe('Again refused.')
        w.unmount()
    })

    it('clearsTheRefusalWhenAnotherProbeIsSent', async () => {
        const d = deferred()
        answer({ source: sourceRow(S1, { probe: null }) })
        mutate.mockRejectedValueOnce(refusal('Refused once.'))
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList()
        await click(w, 'upstream-start-probe')
        expect(byId(w, 'upstream-start-probe-error').text()).toBe('Refused once.')
        await click(w, 'upstream-start-probe')
        expect(byId(w, 'upstream-start-probe-error').exists()).toBe(false)
        w.unmount()
    })

    it('sendsNothingWithoutASource', async () => {
        const w = await mountList({ source: null })
        await (w.vm as any).probe()
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('listsThePageOfAProbeThatReachedTheServerAndForbidsAnAnswerWithNoRow', async () => {
        answer({ source: sourceRow(S1, { probe: null }), list: page({ items: items(2) }) })
        mutate.mockResolvedValueOnce({ data: { probeUpstreamSource: sourceRow() } })
        const a = await mountList()
        await click(a, 'upstream-start-probe')
        expect(listCalls().map(c => c.variables)).toEqual([firstPageVars()])
        a.unmount()
        answer({ source: sourceRow(S1, { probe: null }), list: page() })
        mutate.mockResolvedValueOnce({ data: { probeUpstreamSource: null } })
        const b = await mountList()
        await click(b, 'upstream-start-probe')
        expect(gateText(b)).toBe(labels.offerForbiddenSentence)
        b.unmount()
    })

    it('dropsTheAnswerOfAProbeSentForASourceThatWasLeftAndKeepsTheNewGateAndTheButtonFree', async () => {
        const d = deferred()
        answer({ source: (vars: any) => sourceRow(vars.uuid, { probe: null }), list: page() })
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList()
        await click(w, 'upstream-start-probe')
        await chooseSource(w, S2)
        expect(gateText(w)).toContain(labels.offerNeverProbed)
        d.pending[0].resolve({ data: { probeUpstreamSource: sourceRow(S1) } })
        await flushPromises()
        expect(gateText(w)).toContain(labels.offerNeverProbed)
        expect(listCalls()).toHaveLength(0)
        expect(probeButton(w).attributes('disabled')).toBeUndefined()
        w.unmount()
    })

    it('dropsTheRefusalOfAProbeSentForASourceThatWasLeft', async () => {
        const d = deferred()
        answer({ source: (vars: any) => sourceRow(vars.uuid, { probe: null }), list: page() })
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList()
        await click(w, 'upstream-start-probe')
        await chooseSource(w, S2)
        d.pending[0].reject(refusal('Late refusal.'))
        await flushPromises()
        expect(byId(w, 'upstream-start-probe-error').exists()).toBe(false)
        expect(probeButton(w).attributes('disabled')).toBeUndefined()
        w.unmount()
    })

    it('dropsTheAnswerOfAProbeAfterTheDialogWasClosed', async () => {
        const d = deferred()
        answer({ source: sourceRow(S1, { probe: null }), list: page() })
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList()
        await click(w, 'upstream-start-probe')
        await w.setProps({ show: false })
        d.pending[0].resolve({ data: { probeUpstreamSource: sourceRow() } })
        await flushPromises()
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })
})

describe('the waiting text', () => {
    it('showsTheLoadingTextAndNotTheWaitingTextWhileTheFirstPageIsInFlightAndNothingIsWanted', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList().catch((e: any) => expect.fail(`the dialog did not mount: ${e}`))
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(false)
        expect(byId(w, 'upstream-start-loading').text()).toBe(labels.offerLoading)
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(byId(w, 'upstream-start-loading').exists()).toBe(false)
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(false)
        w.unmount()
    })
})

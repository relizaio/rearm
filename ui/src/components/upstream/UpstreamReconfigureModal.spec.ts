// @vitest-environment happy-dom
//
// TPF-230 (TPF-185 design 3.9, the reconfigure dialog and the proposal): the dialog starts with
// nothing checked and every depth at the current depth, sends the input upstreamReconfigure.ts
// builds and only from Save, keeps a refused save open with its sentence, and "Propose a depth from
// the history" prepares the item, reads the preparation until it is no longer pending, shows the
// proposal read-only and sets the row's depth with "Use this depth". It never confirms anything.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

const { default: Modal } = await import('./UpstreamReconfigureModal.vue')
const { default: DepthAssessment } = await import('./UpstreamDepthAssessment.vue')
const Q = await import('@/utils/upstreamFollowQueries')
const { nothingToChangeSentence, proposalCaption, reconfigureAcceptSentence, reconfigureDriftOnlySentence, reconfigureRunSentence } = await import('@/utils/upstreamReconfigure')
const { NCheckbox, NRadioGroup, NSelect } = await import('naive-ui')

const FOLLOW = 'cccccccc-0000-4000-8000-000000000001'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const ROOT = '88888888-0000-4000-8000-000000000001'
const COMP = '88888888-0000-4000-8000-000000000002'
const UP_ROOT = 'dddddddd-0000-4000-8000-000000000001'
const UP_COMP = 'dddddddd-0000-4000-8000-000000000002'
const PREPARATION = '99999999-0000-4000-8000-000000000001'

const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

function follow (over: any = {}) {
    return {
        uuid: FOLLOW, source: SOURCE, status: 'ACTIVE', onDrift: 'STOP', pendingCount: 1,
        item: { uuid: ROOT, kind: 'PRODUCT', upstreamUuid: UP_ROOT, upstreamName: 'Widget Suite', depth: 2 },
        components: [{ uuid: COMP, kind: 'COMPONENT', upstreamUuid: UP_COMP, upstreamName: 'Widget Core', depth: 1 }],
        pending: [{ uuid: '66666666-0000-4000-8000-000000000001', item: COMP, upstreamUuid: '44444444-0000-4000-8000-000000000001', upstreamVersion: '7', drift: 'NUMBERING_RESTART' }],
        ...over,
    }
}

function preparation (state: string, over: any = {}) {
    return {
        uuid: PREPARATION, state, failure: null, sourceFailure: null, message: null,
        item: state === 'READY' ? {
            kind: 'COMPONENT', upstreamUuid: UP_COMP, upstreamName: 'Widget Core', proposedDepth: 3, depthBasis: 'HISTORY',
            depths: [
                { depth: 1, fit: 'DOES_NOT_FIT', lineCount: 1, unplaceable: 2, linesTruncated: false, lines: [] },
                { depth: 3, fit: 'FITS', lineCount: 2, unplaceable: 0, linesTruncated: false, lines: [{ lineKey: '7.0.x', branchName: '7.0.x', releases: 4, lowestVersion: '7.0.0', highestVersion: '7.0.3', newestDate: null }] },
            ],
        } : null,
        ...over,
    }
}

const byTestId = (w: any, component: any, id: string) => w.findAllComponents(component).find((c: any) => c.attributes('data-testid') === id)
const mutations = (doc: any) => mutate.mock.calls.filter(c => c[0].mutation === doc)

function mountModal (f: any = follow()) {
    return mount(Modal, { props: { show: true, follow: f }, global, attachTo: document.body })
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
})

afterEach(() => {
    expect(mutations(Q.CONFIRM_UPSTREAM_FOLLOW)).toHaveLength(0)
    vi.useRealTimers()
})

describe('the reconfigure dialog', () => {
    it('starts with the held items first, nothing checked, the current depths, the behaviour of the follow and nothing to change', async () => {
        const w = mountModal()
        await flushPromises()
        expect(w.get('[data-testid="upstream-reconfigure-name-0"]').text()).toBe('Widget Core')
        expect(w.get('[data-testid="upstream-reconfigure-held-0"]').text()).toBe('1')
        expect(w.get('[data-testid="upstream-reconfigure-name-1"]').text()).toBe('Widget Suite')
        expect(w.get('[data-testid="upstream-reconfigure-held-1"]').text()).toBe('0')
        expect(byTestId(w, NCheckbox, 'upstream-reconfigure-accept-0').props('checked')).toBe(false)
        expect(byTestId(w, NSelect, 'upstream-reconfigure-depth-0').props('value')).toBe(1)
        expect(byTestId(w, NSelect, 'upstream-reconfigure-depth-1').props('value')).toBe(2)
        expect((byTestId(w, NSelect, 'upstream-reconfigure-depth-0').props('options') as any[]).map(o => o.value)).toEqual([0, 1, 2, 3, 4])
        expect(w.findComponent(NRadioGroup).props('value')).toBe('STOP')
        expect(w.find('[data-testid="upstream-reconfigure-propose-0"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-reconfigure-propose-1"]').exists()).toBe(false)
        for (const sentence of [reconfigureAcceptSentence, reconfigureRunSentence, reconfigureDriftOnlySentence]) expect(w.text()).toContain(sentence)
        expect(w.get('[data-testid="upstream-reconfigure-nothing"]').text()).toBe(nothingToChangeSentence)
        expect(w.get('[data-testid="upstream-reconfigure-save"]').attributes('disabled')).toBeDefined()
        await w.get('[data-testid="upstream-reconfigure-save"]').trigger('click')
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('saves the checked item with its depth, emits the follow and closes', async () => {
        const w = mountModal()
        await flushPromises()
        byTestId(w, NCheckbox, 'upstream-reconfigure-accept-0').vm.$emit('update:checked', true)
        byTestId(w, NSelect, 'upstream-reconfigure-depth-0').vm.$emit('update:value', 3)
        byTestId(w, NSelect, 'upstream-reconfigure-depth-1').vm.$emit('update:value', 4)
        await flushPromises()
        expect(w.find('[data-testid="upstream-reconfigure-nothing"]').exists()).toBe(false)
        mutate.mockResolvedValueOnce({ data: { reconfigureUpstreamFollow: { uuid: FOLLOW } } })
        await w.get('[data-testid="upstream-reconfigure-save"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(Q.RECONFIGURE_UPSTREAM_FOLLOW)
        expect(mutate.mock.calls[0][0].variables).toEqual({ follow: FOLLOW, input: { items: [{ item: COMP, depth: 3 }] } })
        expect(w.emitted('saved')).toEqual([[FOLLOW]])
        expect(w.emitted('update:show')).toEqual([[false]])
        w.unmount()
    })

    it('saves a change of the behaviour on drift alone', async () => {
        const w = mountModal()
        await flushPromises()
        w.findComponent(NRadioGroup).vm.$emit('update:value', 'ADAPT')
        await flushPromises()
        mutate.mockResolvedValueOnce({ data: { reconfigureUpstreamFollow: { uuid: FOLLOW } } })
        await w.get('[data-testid="upstream-reconfigure-save"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ follow: FOLLOW, input: { onDrift: 'ADAPT' } })
        w.unmount()
    })

    it('keeps the dialog open with the sentence of a refused save and what was chosen', async () => {
        const w = mountModal()
        await flushPromises()
        byTestId(w, NCheckbox, 'upstream-reconfigure-accept-1').vm.$emit('update:checked', true)
        await flushPromises()
        const err: any = new Error('x')
        err.errors = [{ message: 'The follow has a run that has not ended.', extensions: { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'RUN_IN_PROGRESS' } }]
        mutate.mockRejectedValueOnce(err)
        await w.get('[data-testid="upstream-reconfigure-save"]').trigger('click')
        await flushPromises()
        expect(w.get('[data-testid="upstream-reconfigure-error"]').text()).toBe('The follow has a run that has not ended.')
        expect(w.emitted('update:show')).toBeUndefined()
        expect(w.emitted('saved')).toBeUndefined()
        expect(byTestId(w, NCheckbox, 'upstream-reconfigure-accept-1').props('checked')).toBe(true)
        w.unmount()
    })

    it('starts again from the follow when it is opened again', async () => {
        const w = mountModal()
        await flushPromises()
        byTestId(w, NCheckbox, 'upstream-reconfigure-accept-0').vm.$emit('update:checked', true)
        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        expect(byTestId(w, NCheckbox, 'upstream-reconfigure-accept-0').props('checked')).toBe(false)
        expect(w.find('[data-testid="upstream-reconfigure-nothing"]').exists()).toBe(true)
        w.unmount()
    })
})

describe('the proposal of a depth for held releases', () => {
    it('prepares the item, reads the preparation until it is ready, shows it read-only and sets the depth with Use this depth', async () => {
        vi.useFakeTimers()
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: PREPARATION, state: 'PENDING' } } })
        query.mockResolvedValueOnce({ data: { upstreamFollowPreparation: preparation('PENDING') } })
        query.mockResolvedValueOnce({ data: { upstreamFollowPreparation: preparation('READY') } })
        const w = mountModal()
        await flushPromises()
        await w.get('[data-testid="upstream-reconfigure-propose-0"]').trigger('click')
        await flushPromises()
        expect(mutations(Q.PREPARE_UPSTREAM_FOLLOW)).toHaveLength(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: SOURCE, kind: 'COMPONENT', upstreamUuid: UP_COMP })
        expect(query.mock.calls[0][0].query).toBe(Q.GET_UPSTREAM_FOLLOW_PREPARATION)
        expect(query.mock.calls[0][0].variables).toEqual({ uuid: PREPARATION })
        expect(w.find(`[data-testid="upstream-proposal-${COMP}"]`).text()).toContain(proposalCaption)
        expect(w.find('[data-testid="upstream-proposal-loading"]').exists()).toBe(true)
        expect(w.get('[data-testid="upstream-reconfigure-propose-0"]').attributes('disabled')).toBeDefined()

        await vi.advanceTimersByTimeAsync(2000)
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(2)
        const assessment = w.findComponent(DepthAssessment)
        expect(assessment.exists()).toBe(true)
        expect(assessment.props('readOnly')).toBe(true)
        expect(assessment.props('proposal').proposedDepth).toBe(3)
        await w.get('[data-testid="upstream-depth-use"]').trigger('click')
        expect(byTestId(w, NSelect, 'upstream-reconfigure-depth-0').props('value')).toBe(3)
        expect(byTestId(w, NCheckbox, 'upstream-reconfigure-accept-0').props('checked')).toBe(false)

        await vi.advanceTimersByTimeAsync(10000)
        expect(query).toHaveBeenCalledTimes(2)
        expect(mutate).toHaveBeenCalledTimes(1)
        w.unmount()
    })

    it('shows the sentence of a failed preparation', async () => {
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: PREPARATION, state: 'PENDING' } } })
        query.mockResolvedValueOnce({ data: { upstreamFollowPreparation: preparation('FAILED', { failure: 'ITEM_NOT_FOUND', message: 'The source does not know this item.' }) } })
        const w = mountModal()
        await flushPromises()
        await w.get('[data-testid="upstream-reconfigure-propose-0"]').trigger('click')
        await flushPromises()
        expect(w.get('[data-testid="upstream-proposal-failed"]').text()).toContain('The source does not know this item.')
        expect(w.findComponent(DepthAssessment).exists()).toBe(false)
        w.unmount()
    })

    it('shows the sentence of a refused preparation', async () => {
        const err: any = new Error('x')
        err.errors = [{ message: 'A preparation limit of the organization is reached. Prepare again later.', extensions: { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'PREPARATION_LIMIT' } }]
        mutate.mockRejectedValueOnce(err)
        const w = mountModal()
        await flushPromises()
        await w.get('[data-testid="upstream-reconfigure-propose-0"]').trigger('click')
        await flushPromises()
        expect(w.get('[data-testid="upstream-proposal-error"]').text()).toBe('A preparation limit of the organization is reached. Prepare again later.')
        expect(query).not.toHaveBeenCalled()
        w.unmount()
    })

    it('stops reading the preparation when the dialog closes', async () => {
        vi.useFakeTimers()
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: PREPARATION, state: 'PENDING' } } })
        query.mockResolvedValue({ data: { upstreamFollowPreparation: preparation('PENDING') } })
        const w = mountModal()
        await flushPromises()
        await w.get('[data-testid="upstream-reconfigure-propose-0"]').trigger('click')
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(1)
        await w.get('[data-testid="upstream-reconfigure-cancel"]').trigger('click')
        expect(w.emitted('update:show')).toEqual([[false]])
        await vi.advanceTimersByTimeAsync(10000)
        expect(query).toHaveBeenCalledTimes(1)
        w.unmount()
    })
})

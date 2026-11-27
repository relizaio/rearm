// @vitest-environment happy-dom
//
// T-1146 to T-1155 (TPF-243 design 3.5 to 3.9), mutation round: what the dialog shows for each state
// of a set and which buttons are free. The buttons that cannot be pressed while a call is in flight
// (the attribute and, apart from it, the guard of the handler), the lookup head, the filter count,
// the statement of a set with no row, Load more in flight, a later failure (a source failure with the
// tag of the probe, the others in an alert) and a source with no credential, the hint, Prepare in
// flight, Cancel and the close of the dialog. Some states are set by the instance itself where no
// click leads to them: the template must not depend on how they were reached.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { S1, firstPageVars, items, page, probeOf, refusal, sourceRow } from './startDialogTestKit'
import { NRadioGroup } from 'naive-ui'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const labels = await import('@/utils/upstreamLabels')
const { answer, deferred, listCalls, byId, allById, rowsOf, mountList, click, chooseKind, chooseLookupType, chooseMode, lookUp } =
    makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
})

const TOKEN_PAGE = () => page({ items: items(30), next: 'TOKEN', nextPageToken: 'tok-1' })
const disabled = (w: any, id: string) => byId(w, id).attributes('disabled') !== undefined
const later = (w: any, extra: Record<string, any>) => Object.assign((w.vm as any).browse.later, extra)

describe('the buttons while a call is in flight', () => {
    it('freesTheProbeAgainButtonOnlyAfterTheProbeAnswered', async () => {
        const d = deferred()
        answer({ source: sourceRow(S1, probeOf('DONE', 'TIMED_OUT', 'No answer.')) })
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList()
        expect(disabled(w, 'upstream-start-probe')).toBe(false)
        expect(byId(w, 'upstream-start-probe').classes()).not.toContain('n-button--loading')
        await click(w, 'upstream-start-probe')
        expect(disabled(w, 'upstream-start-probe')).toBe(true)
        expect(byId(w, 'upstream-start-probe').classes()).toContain('n-button--loading')
        d.pending[0].reject(refusal('Refused.'))
        await flushPromises()
        expect(disabled(w, 'upstream-start-probe')).toBe(false)
        expect(byId(w, 'upstream-start-probe').classes()).not.toContain('n-button--loading')
        w.unmount()
    })

    it('holdsTheLookupButtonAndTheLookupWhileTheFirstPageIsInFlight', async () => {
        const d = deferred()
        answer({ list: d.fn })
        const w = await mountList()
        await chooseLookupType(w, 'TEI')
        await byId(w, 'upstream-start-lookup-value').find('input').setValue('tei://x')
        expect(disabled(w, 'upstream-start-lookup-submit')).toBe(true)
        ;(w.vm as any).lookUp()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        expect((w.vm as any).view).toBe('BROWSE')
        d.pending[0].resolve(page({ items: items(2) }))
        await flushPromises()
        expect(disabled(w, 'upstream-start-lookup-submit')).toBe(false)
        w.unmount()
    })

    it('holdsTryAgainOfAFailedFirstPageWhileALookupIsInFlight', async () => {
        const d = deferred()
        answer({ list: [refusal('The list could not be read.'), d.fn, page({ items: items(2) })] })
        const w = await mountList()
        expect(byId(w, 'upstream-start-list-failure').text()).toContain('The list could not be read.')
        expect(disabled(w, 'upstream-start-retry')).toBe(false)
        await chooseLookupType(w, 'TEI')
        await byId(w, 'upstream-start-lookup-value').find('input').setValue('tei://x')
        await click(w, 'upstream-start-lookup-submit')
        expect(listCalls()).toHaveLength(2)
        await click(w, 'upstream-start-lookup-back')
        expect(disabled(w, 'upstream-start-retry')).toBe(true)
        ;(w.vm as any).retry()
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(disabled(w, 'upstream-start-retry')).toBe(false)
        await click(w, 'upstream-start-retry')
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables).toEqual(firstPageVars())
        w.unmount()
    })

    it('holdsLoadMoreOfTheListWhileALookupIsInFlight', async () => {
        const d = deferred()
        answer({ list: [TOKEN_PAGE(), d.fn, page({ items: items(5, 31), next: 'NONE' })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        expect(listCalls()).toHaveLength(2)
        await click(w, 'upstream-start-lookup-back')
        expect(disabled(w, 'upstream-start-more')).toBe(true)
        ;(w.vm as any).loadMore()
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(disabled(w, 'upstream-start-more')).toBe(false)
        await click(w, 'upstream-start-more')
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables.pageToken).toBe('tok-1')
        w.unmount()
    })

    it.each([
        ['upstream-start-restart', 'restart', page({ items: null, failure: 'TOKEN_NOT_ACCEPTED', message: 'Token refused.' })],
        ['upstream-start-more', 'loadMore', page({ items: null, failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'Slow.' })],
    ])('holdsTheButton%sOfALaterFailureWhileALookupIsInFlight', async (id, method, failing) => {
        const d = deferred()
        answer({ list: [TOKEN_PAGE(), failing, d.fn, page({ items: items(2, 100) })] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-list-failure').exists()).toBe(true)
        expect(disabled(w, id)).toBe(false)
        await lookUp(w, 'TEI', 'tei://x')
        expect(listCalls()).toHaveLength(3)
        await click(w, 'upstream-start-lookup-back')
        expect(disabled(w, id)).toBe(true)
        ;(w.vm as any)[method]()
        await flushPromises()
        expect(listCalls()).toHaveLength(3)
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(disabled(w, id)).toBe(false)
        w.unmount()
    })
})

describe('the lookup head, the filter count and the statement', () => {
    it('showsTheHeadAndTheBackButtonOnlyForALookup', async () => {
        answer({ list: page({ items: items(3) }) })
        const w = await mountList()
        expect(w.find('.upstream-start-lookup-head').exists()).toBe(false)
        await lookUp(w, 'TEI', 'tei://x')
        const head = w.find('.upstream-start-lookup-head')
        expect(head.text()).toContain(labels.offerLookupHead)
        expect(byId(w, 'upstream-start-lookup-back').text()).toBe('Back to the list')
        await click(w, 'upstream-start-lookup-back')
        expect(w.find('.upstream-start-lookup-head').exists()).toBe(false)
        w.unmount()
    })

    it('showsTheFilterCountForATextAndNotForABlankOne', async () => {
        answer({ list: page({ items: items(3) }) })
        const w = await mountList()
        expect(byId(w, 'upstream-start-filter-sentence').text()).toBe(labels.offerFilterSentence(3))
        expect(byId(w, 'upstream-start-filter-count').exists()).toBe(false)
        await byId(w, 'upstream-start-filter').find('input').setValue('   ')
        expect(byId(w, 'upstream-start-filter-count').exists()).toBe(false)
        await byId(w, 'upstream-start-filter').find('input').setValue('Item 2')
        expect(byId(w, 'upstream-start-filter-count').text()).toBe(labels.offerFilterCount(1, 3))
        w.unmount()
    })

    it('showsNoTableAndTheStatementForALoadedSetWithNoRowThatIsNotTheCleanEmptyAnswer', async () => {
        answer({ list: page({ items: [], rowsDropped: 2 }) })
        const w = await mountList()
        expect(byId(w, 'upstream-start-list').exists()).toBe(false)
        expect(byId(w, 'upstream-start-empty').exists()).toBe(false)
        expect(byId(w, 'upstream-start-count').text()).toBe(labels.offerLoadedCount(0))
        expect(allById(w, 'upstream-start-warning')).toHaveLength(1)
        w.unmount()
    })

    it('showsTheHintOnlyWhenRowsAreMissing', async () => {
        answer({ list: [page({ items: items(2) }), page({ items: items(2), next: 'TOKEN', nextPageToken: null }), page({ items: items(2), rowsDropped: 1 })] })
        const clean = await mountList()
        expect(byId(clean, 'upstream-start-hint').exists()).toBe(false)
        clean.unmount()
        const unusable = await mountList()
        expect(byId(unusable, 'upstream-start-hint').text()).toBe(labels.offerMissingHint)
        unusable.unmount()
        const dropped = await mountList()
        expect(byId(dropped, 'upstream-start-hint').text()).toBe(labels.offerMissingHint)
        dropped.unmount()
    })

    it('showsTheLoadingTextAndNoStatementLineWhileLoadMoreIsInFlight', async () => {
        const d = deferred()
        answer({ list: [TOKEN_PAGE(), d.fn] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-loading').text()).toBe(labels.offerLoading)
        expect(byId(w, 'upstream-start-next').exists()).toBe(false)
        expect(byId(w, 'upstream-start-count').text()).toBe(labels.offerLoadedCount(30))
        expect(rowsOf(w)).toHaveLength(25)
        d.pending[0].resolve(page({ items: items(2, 31), next: 'NONE' }))
        await flushPromises()
        expect(byId(w, 'upstream-start-loading').exists()).toBe(false)
        expect(byId(w, 'upstream-start-next').text()).toContain(labels.offerEndSentence)
        w.unmount()
    })
})

describe('a later failure', () => {
    const sourceFailure = () => page({ items: null, failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'The server did not answer in time.' })
    const other = () => page({ items: null, failure: 'TOKEN_NOT_ACCEPTED', message: 'Token refused.' })

    it('showsASourceFailureWithTheTagOfTheProbeAndTheOthersInAnAlert', async () => {
        answer({ list: [TOKEN_PAGE(), sourceFailure()] })
        const a = await mountList()
        await click(a, 'upstream-start-more')
        const failure = byId(a, 'upstream-start-list-failure')
        expect(failure.find('[data-testid="upstream-probe-tag"]').exists()).toBe(true)
        expect(failure.find('.n-alert').exists()).toBe(false)
        expect(failure.text()).toContain('The server did not answer in time.')
        a.unmount()
        answer({ list: [TOKEN_PAGE(), other()] })
        const b = await mountList()
        await click(b, 'upstream-start-more')
        const alert = byId(b, 'upstream-start-list-failure')
        expect(alert.find('[data-testid="upstream-probe-tag"]').exists()).toBe(false)
        expect(alert.find('.n-alert').text()).toContain('Token refused.')
        b.unmount()
    })

    it('readsASourceWithNoCredentialWithoutAnException', async () => {
        answer({ source: sourceRow(S1, { credential: null }), list: [TOKEN_PAGE(), sourceFailure()] })
        const errors: any[] = []
        const w = await mountList({}, errors)
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-list-failure').find('[data-testid="upstream-probe-tag"]').exists()).toBe(true)
        expect(errors).toEqual([])
        w.unmount()
    })

    it('readsASourceWithNoCredentialWithoutAnExceptionForAFailedFirstPage', async () => {
        answer({ source: sourceRow(S1, { credential: null }), list: sourceFailure() })
        const errors: any[] = []
        const w = await mountList({}, errors)
        expect(byId(w, 'upstream-start-list-failure').find('[data-testid="upstream-probe-tag"]').exists()).toBe(true)
        expect(errors).toEqual([])
        w.unmount()
    })
})

describe('the states the template reads as they are, however they were reached', () => {
    it('showsTheWaitingTextForAWantedCallOfTheShownSetOnlyWhileItIsNotLoaded', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const vm: any = w.vm
        vm.wanted = { view: 'BROWSE', setId: vm.browse.id, epoch: vm.epoch, append: false, variables: {} }
        await flushPromises()
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(false)
        expect(byId(w, 'upstream-start-statement').exists()).toBe(true)
        vm.browse.phase = 'IDLE'
        await flushPromises()
        expect(byId(w, 'upstream-start-waiting').text()).toContain(labels.offerWaiting)
        vm.wanted = { ...vm.wanted, view: 'LOOKUP' }
        await flushPromises()
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(false)
        vm.wanted = { ...vm.wanted, view: 'BROWSE', setId: vm.browse.id + 1000 }
        await flushPromises()
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(false)
        w.unmount()
    })

    it('showsTheFailureOfASetOnlyWhileItsPhaseIsFailedAndNeverTheStatementOfASetNotLoaded', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const vm: any = w.vm
        vm.browse.failure = { page: null, text: 'A failure left over.' }
        await flushPromises()
        expect(byId(w, 'upstream-start-list-failure').exists()).toBe(false)
        expect(byId(w, 'upstream-start-statement').exists()).toBe(true)
        vm.browse.phase = 'IDLE'
        await flushPromises()
        expect(byId(w, 'upstream-start-statement').exists()).toBe(false)
        expect(byId(w, 'upstream-start-list-failure').exists()).toBe(false)
        vm.browse.phase = 'FAILED'
        vm.browse.failure = null
        await flushPromises()
        expect(byId(w, 'upstream-start-list-failure').exists()).toBe(false)
        vm.browse.failure = { page: null, text: 'Shown.' }
        await flushPromises()
        expect(byId(w, 'upstream-start-list-failure').text()).toContain('Shown.')
        w.unmount()
    })

    it('offersNoLoadMoreForALaterFailureOfASetThatHasNoTokenAndNoRestart', async () => {
        answer({ list: [TOKEN_PAGE(), page({ items: null, failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'Slow.' })] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-more').exists()).toBe(true)
        const vm: any = w.vm
        vm.browse.set = { ...vm.browse.set, token: null }
        await flushPromises()
        expect(byId(w, 'upstream-start-list-failure').exists()).toBe(true)
        expect(byId(w, 'upstream-start-more').exists()).toBe(false)
        expect(byId(w, 'upstream-start-restart').exists()).toBe(false)
        later(w, { restart: true })
        await flushPromises()
        expect(byId(w, 'upstream-start-restart').exists()).toBe(true)
        w.unmount()
    })
})

describe('Prepare, Cancel and the close of the dialog', () => {
    it('holdsPrepareAndCancelWhileTheUuidIsBeingPreparedAndFreesThemAfterARefusal', async () => {
        const d = deferred()
        mutate.mockImplementationOnce(d.fn)
        const w = await mountList({ mode: 'UUID' })
        await byId(w, 'upstream-start-uuid').find('input').setValue('3f2a6c1e-8b7d-4e5f-9a0b-1c2d3e4f5a6b')
        expect(disabled(w, 'upstream-start-submit')).toBe(false)
        expect(byId(w, 'upstream-start-submit').classes()).not.toContain('n-button--loading')
        expect(disabled(w, 'upstream-start-cancel')).toBe(false)
        await click(w, 'upstream-start-submit')
        expect(disabled(w, 'upstream-start-submit')).toBe(true)
        expect(byId(w, 'upstream-start-submit').classes()).toContain('n-button--loading')
        expect(disabled(w, 'upstream-start-cancel')).toBe(true)
        ;(w.vm as any).close()
        expect(w.emitted('update:show')).toBeUndefined()
        d.pending[0].reject(refusal('Refused.'))
        await flushPromises()
        expect(disabled(w, 'upstream-start-submit')).toBe(false)
        expect(byId(w, 'upstream-start-submit').classes()).not.toContain('n-button--loading')
        expect(disabled(w, 'upstream-start-cancel')).toBe(false)
        w.unmount()
    })

    it('closesOnCancelAndOnTheModalAskingToCloseAndNeverOnTheModalAskingToOpen', async () => {
        answer({ list: page() })
        const w = await mountList()
        await click(w, 'upstream-start-cancel')
        expect(w.emitted('update:show')).toEqual([[false]])
        const modal = w.findComponent('.modal')
        modal.vm.$emit('update:show', true)
        expect(w.emitted('update:show')).toEqual([[false]])
        modal.vm.$emit('update:show', false)
        expect(w.emitted('update:show')).toEqual([[false], [false]])
        w.unmount()
    })

    it('showsTheModeSwitchWithoutALabelAndTheListHasNoPrepareButton', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        const mode = byId(w, 'upstream-start-mode').element.closest('.n-form-item') as HTMLElement
        expect(mode.querySelector('.n-form-item-label')).toBeNull()
        expect(mode.classList.contains('n-form-item--no-label')).toBe(true)
        expect((byId(w, 'upstream-start-source').element.closest('.n-form-item') as HTMLElement).classList.contains('n-form-item--no-label')).toBe(false)
        await chooseMode(w, 'UUID')
        expect(byId(w, 'upstream-start-submit').exists()).toBe(true)
        w.unmount()
    })
})

describe('the choice of a kind and the states the gate has no sentence for', () => {
    it('doesNothingForTheKindThatIsChosenAlreadyAndForAValueThatIsNoKind', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const vm: any = w.vm
        const epoch = vm.epoch
        const group = w.findAllComponents(NRadioGroup).find((g: any) => g.attributes('data-testid') === 'upstream-start-kind')!
        group.vm.$emit('update:value', 'PRODUCT')
        group.vm.$emit('update:value', 'SERVICE')
        group.vm.$emit('update:value', '')
        await flushPromises()
        expect(vm.epoch).toBe(epoch)
        expect(vm.model.kind).toBe('PRODUCT')
        expect(listCalls()).toHaveLength(1)
        group.vm.$emit('update:value', 'COMPONENT')
        await flushPromises()
        expect(vm.model.kind).toBe('COMPONENT')
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })

    it.each([
        ['a probe that is pending', sourceRow(S1, probeOf('PENDING', null))],
        ['a probe that failed', sourceRow(S1, probeOf('DONE', 'TIMED_OUT', 'No answer.'))],
    ])('asksForNoPageForAKindChosenWhile%s', async (_name, row) => {
        answer({ source: row, list: page({ items: items(2) }) })
        const w = await mountList()
        await chooseKind(w, 'COMPONENT')
        expect(listCalls()).toHaveLength(0)
        expect((w.vm as any).model.kind).toBe('COMPONENT')
        w.unmount()
    })

    it('showsNothingInTheGateForAStateThatIsNoneOfTheNine', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        ;(w.vm as any).gate = 'SOMETHING_ELSE'
        await flushPromises()
        expect(byId(w, 'upstream-start-gate').text()).toBe('')
        expect(byId(w, 'upstream-start-gate').find('.upstream-start-gate-error').exists()).toBe(false)
        w.unmount()
    })

    it('clearsTheProblemOfALookupWhenTheKindChanges', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').exists()).toBe(true)
        await chooseKind(w, 'COMPONENT')
        expect(byId(w, 'upstream-start-lookup-problem').exists()).toBe(false)
        expect((w.vm as any).lookupProblem).toBe('')
        w.unmount()
    })
})

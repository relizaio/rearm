// @vitest-environment happy-dom
//
// T-1145, T-1146, T-1149, T-1150, T-1153 to T-1155 (TPF-243 design 3.3 and 3.8), mutation round: the
// list call from the inside. The guards of the one call in flight and of an answer that is no longer
// wanted, each layer on its own (the request number, the number of the set, the flag of the call in
// flight); the answers that are no page, no response, a refusal that is not about the token and the
// ones that are; the state a call leaves while it is in flight; and Try again, Load more and Start
// again in the browse view and in the lookup view, each with the arguments of its own set.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { S1, firstPageVars, items, page, refusal, sourceRow } from './startDialogTestKit'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const labels = await import('@/utils/upstreamLabels')
const { answer, deferred, listCalls, byId, mountList, click, lookUp, chooseKind } = makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

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
    expect(unhandled).toEqual([])
})

const vmOf = (w: any) => w.vm as any
const TOKEN_PAGE = (n = 30) => page({ items: items(n), next: 'TOKEN', nextPageToken: 'tok-1' })
const tokenRefused = () => refusal('The page token is not one this list returned.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'INVALID_PAGE_TOKEN' })
const alertOf = (w: any) => byId(w, 'upstream-start-list-failure').find('.n-alert')

describe('an answer that is no longer wanted, layer by layer', () => {
    it('dropsTheAnswerOfASetThatWasReplacedWhileTheRequestNumberStayedTheSame', async () => {
        const a = deferred()
        const b = deferred()
        answer({ list: [a.fn, b.fn] })
        const w = await mountList()
        const vm = vmOf(w)
        const old = vm.browse.id
        vm.wantFirstPage()
        expect(vm.browse.id).toBeGreaterThan(old)
        expect(vm.wanted.setId).toBe(vm.browse.id)
        a.pending[0].resolve(page({ items: items(3) }))
        await flushPromises()
        expect(vm.browse.set.rows).toEqual([])
        expect(listCalls()).toHaveLength(2)
        b.pending[0].resolve(page({ items: items(2, 10) }))
        await flushPromises()
        expect(vm.browse.set.rows).toHaveLength(2)
        w.unmount()
    })

    it('keepsTheLatestWantedCallAndNotTheFirstOne', async () => {
        const a = deferred()
        answer({ list: a.fn })
        const w = await mountList()
        const vm = vmOf(w)
        vm.wantFirstPage()
        const first = vm.wanted
        vm.wantFirstPage()
        expect(vm.wanted).not.toBe(first)
        expect(vm.wanted.setId).toBe(vm.browse.id)
        expect(vm.wanted.setId).toBeGreaterThan(first.setId)
        w.unmount()
    })

    it.each([
        ['the request number', (vm: any) => ({ epoch: vm.epoch - 1 })],
        ['the number of the set', (vm: any) => ({ setId: vm.browse.id - 1 })],
    ])('sendsNoWantedCallThatIsStaleInItsOwn%s', async (_name, stale) => {
        const a = deferred()
        answer({ list: a.fn })
        const w = await mountList()
        const vm = vmOf(w)
        vm.wanted = { ...vm.callFor(vm.browse, 'BROWSE', false), ...stale(vm) }
        a.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        expect(vm.wanted).toBeNull()
        expect(vm.listInFlight).toBe(false)
        w.unmount()
    })

    it('dropsTheAnswerOfALookupWhenTheDialogWasClosed', async () => {
        const d = deferred()
        answer({ list: [page({ items: items(2) }), d.fn] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        expect(vmOf(w).lookup.phase).toBe('LOADING')
        await w.setProps({ show: false })
        d.pending[0].resolve(page({ items: items(3) }))
        await flushPromises()
        expect(vmOf(w).lookup.set.rows).toEqual([])
        expect(vmOf(w).lookup.phase).toBe('LOADING')
        w.unmount()
    })

    it('sendsNothingForACallThatIsInFlightAlreadyOrStaleAndSendsAValidOne', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList()
        const vm = vmOf(w)
        const call = () => vm.callFor(vm.browse, 'BROWSE', false)
        vm.listInFlight = true
        vm.send(call())
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.listInFlight = false
        vm.send({ ...call(), epoch: vm.epoch - 1 })
        vm.send({ ...call(), setId: vm.browse.id - 1 })
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        expect(vm.listInFlight).toBe(false)
        vm.send(call())
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })

    it('holdsEveryOtherCallWhileLoadMoreIsInFlight', async () => {
        const d = deferred()
        answer({ list: [TOKEN_PAGE(), d.fn] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(vmOf(w).listInFlight).toBe(true)
        expect(byId(w, 'upstream-start-lookup-submit').attributes('disabled')).toBeDefined()
        vmOf(w).loadMore()
        vmOf(w).wantFirstPage()
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        d.pending[0].resolve(page({ items: items(2, 31) }))
        await flushPromises()
        expect(listCalls()).toHaveLength(3)
        w.unmount()
    })

    it('asksForTheSetsOwnSourceAndKindAndNotTheOnesOfTheChoicesAtTheTop', async () => {
        answer({ list: [TOKEN_PAGE(), page({ items: items(1, 31) }), page({ items: items(1) })] })
        const w = await mountList()
        const vm = vmOf(w)
        vm.model.kind = 'COMPONENT'
        vm.model.source = 'cccccccc-0000-4000-8000-00000000000c'
        vm.loadMore()
        await flushPromises()
        expect(listCalls()[1].variables).toEqual(firstPageVars({ pageToken: 'tok-1' }))
        vm.restart()
        await flushPromises()
        expect(listCalls()[2].variables).toEqual(firstPageVars())
        w.unmount()
    })

    it('asksAgainForTheFirstPageOfTheSetWithNoTokenWhateverTheSetHolds', async () => {
        answer({ list: [page({ items: null, failure: 'LIST_NOT_SUPPORTED', message: 'No list.' }), page({ items: items(1) })] })
        const w = await mountList()
        const vm = vmOf(w)
        vm.browse.set = { ...vm.browse.set, token: 'left-over' }
        await click(w, 'upstream-start-retry')
        expect(listCalls()[1].variables).toEqual(firstPageVars())
        w.unmount()
    })

    it('asksForTheFirstPageOfTheSourceOfTheModelAndNotOfALeftOverSet', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        const vm = vmOf(w)
        vm.browse.source = 'cccccccc-0000-4000-8000-00000000000c'
        vm.browse.kind = 'COMPONENT'
        vm.wantFirstPage()
        await flushPromises()
        expect(listCalls()[1].variables).toEqual(firstPageVars())
        vm.model.source = null
        vm.wantFirstPage()
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })
})

describe('an answer that is no page, no response or a refusal', () => {
    it('showsTheSentenceOfAFailedRequestForAFirstPageThatIsNoPage', async () => {
        answer({ list: null })
        const w = await mountList()
        expect(alertOf(w).text()).toBe(labels.offerSourceFailed)
        expect(byId(w, 'upstream-start-list-failure').find('[data-testid="upstream-probe-tag"]').exists()).toBe(false)
        expect(byId(w, 'upstream-start-retry').exists()).toBe(true)
        expect(vmOf(w).browse.phase).toBe('FAILED')
        w.unmount()
    })

    it('showsTheSameSentenceForAnAnswerThatIsNoResponse', async () => {
        query.mockImplementation(async (args: any) => args.query === GET_UPSTREAM_SOURCE ? { data: { upstreamSource: sourceRow(S1) } } : undefined)
        const w = await mountList()
        expect(alertOf(w).text()).toBe(labels.offerSourceFailed)
        w.unmount()
    })

    it('showsTheSameSentenceForALaterPageThatIsNoPageAndKeepsTheToken', async () => {
        answer({ list: [TOKEN_PAGE(), null] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(alertOf(w).text()).toBe(labels.offerSourceFailed)
        expect(byId(w, 'upstream-start-more').exists()).toBe(true)
        expect(byId(w, 'upstream-start-restart').exists()).toBe(false)
        expect(vmOf(w).browse.set.token).toBe('tok-1')
        expect(vmOf(w).browse.later).toEqual({ loading: false, failure: { page: null, text: labels.offerSourceFailed }, restart: false })
        w.unmount()
    })

    it('keepsTheTokenForARefusalThatIsNotAboutTheTokenAndDropsItForTheOnesThatAre', async () => {
        answer({ list: [TOKEN_PAGE(), refusal('A boom.'), tokenRefused(), TOKEN_PAGE()] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(alertOf(w).text()).toBe('A boom.')
        expect(byId(w, 'upstream-start-restart').exists()).toBe(false)
        expect(vmOf(w).browse.set.token).toBe('tok-1')
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-restart').exists()).toBe(true)
        expect(byId(w, 'upstream-start-more').exists()).toBe(false)
        expect(vmOf(w).browse.set.token).toBeNull()
        expect(vmOf(w).browse.later.restart).toBe(true)
        w.unmount()
    })

    it('dropsTheTokenForAPageServedWithATokenNotAccepted', async () => {
        answer({ list: [TOKEN_PAGE(), page({ items: null, failure: 'TOKEN_NOT_ACCEPTED', message: 'No.' })] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.set.token).toBeNull()
        expect(vmOf(w).browse.later.restart).toBe(true)
        w.unmount()
    })

    it('sendsNothingMoreForALaterFailureThatNeedsARestartAndOffersTheOneForTheLookupToo', async () => {
        answer({ list: [page({ items: items(2) }), TOKEN_PAGE(), tokenRefused()] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-restart').exists()).toBe(true)
        vmOf(w).loadMore()
        await flushPromises()
        expect(listCalls()).toHaveLength(3)
        w.unmount()
    })
})

describe('the state a call leaves while it is in flight', () => {
    it('leavesNoFailureOfAnEarlierLaterPageWhileLoadMoreIsInFlight', async () => {
        const d = deferred()
        answer({ list: [TOKEN_PAGE(), page({ items: null, failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'Slow.' }), d.fn] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.later.failure).not.toBeNull()
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.later).toEqual({ loading: true, failure: null, restart: false })
        d.pending[0].resolve(page({ items: items(2, 31) }))
        await flushPromises()
        expect(vmOf(w).browse.later).toEqual({ loading: false, failure: null, restart: false })
        w.unmount()
    })

    it('leavesNoFailureOfAnEarlierFirstPageWhileTheRetryIsInFlightAndAfterIt', async () => {
        const d = deferred()
        answer({ list: [refusal('Refused.'), d.fn] })
        const w = await mountList()
        expect(vmOf(w).browse.failure).not.toBeNull()
        await click(w, 'upstream-start-retry')
        expect(vmOf(w).browse.phase).toBe('LOADING')
        expect(vmOf(w).browse.failure).toBeNull()
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(vmOf(w).browse.phase).toBe('LOADED')
        expect(vmOf(w).browse.failure).toBeNull()
        w.unmount()
    })
})

describe('Try again, Load more and Start again, each for the set that is shown', () => {
    it('showsAFailedTryAgainAsAFailedFirstPageAndNotAsALaterOne', async () => {
        answer({ list: [refusal('First.'), refusal('Second.'), page({ items: items(2) })] })
        const w = await mountList()
        expect(byId(w, 'upstream-start-retry').text()).toBe('Try again')
        await click(w, 'upstream-start-retry')
        expect(alertOf(w).text()).toBe('Second.')
        expect(vmOf(w).browse.phase).toBe('FAILED')
        expect(vmOf(w).browse.later.failure).toBeNull()
        await click(w, 'upstream-start-retry')
        expect(vmOf(w).browse.phase).toBe('LOADED')
        expect(listCalls().map(c => c.variables)).toEqual([firstPageVars(), firstPageVars(), firstPageVars()])
        w.unmount()
    })

    it('showsAFailedLookupAsAFailedFirstPageAndTriesItAgainWithItsIdentifier', async () => {
        answer({ list: [page({ items: items(2) }), refusal('Lookup refused.'), page({ items: items(1, 7) })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        expect(vmOf(w).lookup.phase).toBe('FAILED')
        expect(alertOf(w).text()).toBe('Lookup refused.')
        await click(w, 'upstream-start-retry')
        const identifier = { idType: 'TEI', idValue: 'tei://x' }
        expect(listCalls()[1].variables).toEqual(firstPageVars({ identifier }))
        expect(listCalls()[2].variables).toEqual(firstPageVars({ identifier }))
        expect(vmOf(w).lookup.phase).toBe('LOADED')
        expect(vmOf(w).browse.phase).toBe('LOADED')
        w.unmount()
    })

    it('loadsMoreOfTheLookupWithTheTokenOfTheLookupAndAppendsToIt', async () => {
        answer({ list: [TOKEN_PAGE(5), page({ items: items(40, 100), next: 'TOKEN', nextPageToken: 'tok-L' }), page({ items: items(2, 200) })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        await click(w, 'upstream-start-more')
        const identifier = { idType: 'TEI', idValue: 'tei://x' }
        expect(listCalls()[2].variables).toEqual(firstPageVars({ identifier, pageToken: 'tok-L' }))
        expect(vmOf(w).lookup.set.rows).toHaveLength(42)
        expect(vmOf(w).browse.set.rows).toHaveLength(5)
        w.unmount()
    })

    it('startsTheLookupAgainFromItsFirstPageWithItsIdentifierAfterATokenRefusal', async () => {
        answer({ list: [page({ items: items(2) }), TOKEN_PAGE(), tokenRefused(), page({ items: items(2, 50) })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        await click(w, 'upstream-start-more')
        await click(w, 'upstream-start-restart')
        expect(listCalls()).toHaveLength(4)
        const identifier = { idType: 'TEI', idValue: 'tei://x' }
        expect(listCalls()[3].variables).toEqual(firstPageVars({ identifier }))
        expect(vmOf(w).lookup.identifier).toEqual(identifier)
        expect(vmOf(w).lookup.set.rows).toHaveLength(2)
        expect(vmOf(w).browse.set.rows).toHaveLength(2)
        w.unmount()
    })

    it('showsAFailedRestartAsAFailedFirstPageWithTryAgain', async () => {
        answer({ list: [TOKEN_PAGE(), tokenRefused(), refusal('Restart refused.')] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        await click(w, 'upstream-start-restart')
        expect(vmOf(w).browse.phase).toBe('FAILED')
        expect(alertOf(w).text()).toBe('Restart refused.')
        expect(byId(w, 'upstream-start-retry').exists()).toBe(true)
        expect(vmOf(w).browse.set.rows).toEqual([])
        w.unmount()
    })

    it('keepsTheOwnIdentifierAndSourceOfTheSetThatStartsAgain', async () => {
        answer({ list: [page({ items: items(2) }), page({ items: items(2, 9) }), page({ items: items(1, 20) })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        const vm = vmOf(w)
        vm.lookup.source = 'cccccccc-0000-4000-8000-00000000000c'
        vm.model.source = S1
        vm.restart()
        await flushPromises()
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables.source).toBe('cccccccc-0000-4000-8000-00000000000c')
        expect(listCalls()[2].variables.identifier).toEqual({ idType: 'TEI', idValue: 'tei://x' })
        w.unmount()
    })

    it.each([
        ['in flight', (vm: any) => { vm.listInFlight = true }],
        ['no more to load', (vm: any) => { vm.browse.set = { ...vm.browse.set, token: null } }],
        ['a later page to start again', (vm: any) => { vm.browse.later = { loading: false, failure: null, restart: true } }],
        ['a set that is not loaded', (vm: any) => { vm.browse.phase = 'FAILED' }],
    ])('sendsNoLoadMoreWhileThereIs%s', async (_name, spoil) => {
        answer({ list: [TOKEN_PAGE(), page({ items: items(1, 31) })] })
        const w = await mountList()
        spoil(vmOf(w))
        vmOf(w).loadMore()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('sendsNoTryAgainForASetThatIsNotFailedAndNoStartAgainWhileACallIsInFlight', async () => {
        answer({ list: [page({ items: items(1) }), page({ items: items(2) })] })
        const w = await mountList()
        const vm = vmOf(w)
        vm.retry()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        vm.listInFlight = true
        vm.restart()
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        expect(vm.browse.set.rows).toHaveLength(1)
        vm.listInFlight = false
        vm.restart()
        expect(vm.browse.set.rows).toEqual([])
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })

    it('usesTheShownSetForLoadMoreTryAgainAndStartAgain', async () => {
        answer({ list: [page({ items: items(2) }), TOKEN_PAGE(), page({ items: items(1, 31) })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        const vm = vmOf(w)
        vm.browse.phase = 'FAILED'
        vm.browse.failure = { page: null, text: 'x' }
        vm.retry()
        await flushPromises()
        expect(listCalls()).toHaveLength(2)
        vm.browse.phase = 'LOADED'
        vm.lookup.phase = 'FAILED'
        vm.lookup.failure = { page: null, text: 'y' }
        vm.retry()
        await flushPromises()
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables.identifier).toEqual({ idType: 'TEI', idValue: 'tei://x' })
        await chooseKind(w, 'COMPONENT')
        w.unmount()
    })
})

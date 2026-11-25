// @vitest-environment happy-dom
//
// T-524, T-525 and T-529 (TPF-185 design 3.8, threat model rows 5 to 8): the confirmation page.
// Outside READY and not expired there is no form and no Follow button; the untouched READY form
// has no starting point, on-drift Stop and a disabled button that sends nothing; the scope preview
// is asked 300 ms after the last change with exactly the values in the form, and the one confirm
// carries those very values; a stale or over-the-limit preview blocks; a refusal keeps the form.
// Supplier text is text, here and in the follows list. T-531 to T-533 (design 3.8, one block of
// controls per choice): after any change of choice the form holds the controls of the checked choice
// only, once, and the confirm carries the values of the checked choices only.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NDatePicker, NInput, NInputNumber, NRadio, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))
const route: any = { params: {}, query: {} }
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Page } = await import('./UpstreamFollowPreparationPage.vue')
const { default: FollowsList } = await import('./UpstreamFollowsList.vue')
const Q = await import('@/utils/upstreamFollowQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { forbiddenSentence, startingPointRequired } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const PREP = 'cccccccc-0000-4000-8000-000000000001'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const FOLLOW = 'ffffffff-0000-4000-8000-000000000001'
const CAND = 'dddddddd-0000-4000-8000-000000000001'
const HOSTILE = '<img src=x onerror=alert(1)>'
const ADMIN = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }] } }

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { RouterLink } }

function lines (name: string) {
    return [{ lineKey: '1', branchName: name, releases: 4, lowestVersion: '1.0.0', highestVersion: '1.3.0', newestDate: '2026-10-01T00:00:00Z' }]
}

function proposal (upstreamUuid: string, upstreamName: string, extra: Record<string, any> = {}) {
    return {
        kind: 'COMPONENT', upstreamUuid, upstreamName, rearmName: upstreamName, nameCollides: false, existingItem: null, adoptionCandidates: [],
        releases: 10, finalReleases: 9, preReleases: 1, releasesNotStatingPreRelease: 0, releasesWithDate: 10, releasesWithUnusableVersion: 0,
        preReleaseRecognition: 'STATED', proposedDepth: 1, depthBasis: 'HISTORY',
        depths: [0, 1, 2].map(depth => ({ depth, fit: 'FITS', lineCount: depth, unplaceable: 0, linesTruncated: false, lines: depth ? lines(`line-${depth}`) : [] })),
        failure: null, ...extra,
    }
}

function preparation (extra: Record<string, any> = {}) {
    return {
        uuid: PREP, org: ORG, source: SOURCE, state: 'READY', failure: null, sourceFailure: null, message: null,
        createdDate: '2026-11-25T09:00:00Z', finishedDate: '2026-11-25T09:01:00Z', expires: '2099-01-01T00:00:00Z',
        componentsTruncated: false, maxBackfillReleases: 500,
        item: proposal('eeeeeeee-0000-4000-8000-000000000000', 'Acme Suite', { kind: 'PRODUCT' }),
        components: [proposal('eeeeeeee-0000-4000-8000-000000000001', 'acme-core')],
        ...extra,
    }
}

function refusal (message: string, extensions?: Record<string, any>): any {
    const err: any = new Error(message)
    err.graphQLErrors = [{ message, ...(extensions ? { extensions } : {}) }]
    return err
}

let scopeAnswers: any[] = []
let releasePages: any[] = []

/** A promise the test settles itself, to answer requests in another order than they were sent. */
function deferred () {
    let resolve: (v: any) => void = () => {}
    let reject: (e: any) => void = () => {}
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
}

/** Answers by document: the preparation (or its refusal), the source's name, and the scope answers in turn. */
function answer (prep: any) {
    query.mockImplementation(async (args: any) => {
        if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION) {
            if (prep instanceof Error) throw prep
            return { data: { upstreamFollowPreparation: prep } }
        }
        if (args.query === GET_UPSTREAM_SOURCE) return { data: { upstreamSource: { uuid: SOURCE, name: 'Acme TEA' } } }
        if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE) {
            const next = scopeAnswers.shift()
            if (next instanceof Error) throw next
            return next
        }
        if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES) return releasePages.shift()
        throw new Error('unexpected query')
    })
}

const scope = (withinLimit: boolean, releases = 12) => ({
    data: { upstreamFollowPreparationScope: { releases, withinLimit, onBaseBranch: 0, skippedPreReleases: 1, linesTruncated: false, lines: lines('line-2') } },
})

async function mountPage (prep: any) {
    answer(prep)
    const w = mount(Page, { global, attachTo: document.body })
    await flushPromises()
    return w
}

const followButton = (w: any) => w.find('[data-testid="upstream-confirm-follow"]')
const confirmCalls = () => mutate.mock.calls.filter(c => c[0].mutation === Q.CONFIRM_UPSTREAM_FOLLOW)
const scopeCalls = () => query.mock.calls.filter(c => c[0].query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE)

async function chooseDepth (w: any, depth: number) {
    const radio = w.findAllComponents(NRadio).find((r: any) => r.attributes('data-testid') === `upstream-depth-${depth}`)
    await radio.find('input').setValue(true)
}

async function chooseNewestPerLine (w: any, count: number) {
    const radio = w.findAllComponents(NRadio).find((r: any) => r.props('value') === 'LATEST_PER_LINE')
    await radio.find('input').setValue(true)
    w.findComponent(NInputNumber).vm.$emit('update:value', count)
    await w.vm.$nextTick()
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
    scopeAnswers = []
    releasePages = []
    getters.myuser = ADMIN
    route.params = { orguuid: ORG, uuid: PREP }
    route.query = {}
})

afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
})

describe('T-524 no form outside a READY preparation that has not expired', () => {
    it.each([
        ['PENDING', preparation({ state: 'PENDING', item: null, components: [] }), 'Reading the item and its release history from the source.'],
        ['FAILED', preparation({ state: 'FAILED', failure: 'ITEM_NOT_FOUND', message: 'The source does not know this item.', item: null, components: [] }), 'The source does not know this item.'],
        ['CONSUMED', preparation({ state: 'CONSUMED' }), 'This preparation was confirmed. The item is followed.'],
        ['READY and expired', preparation({ expires: '2020-01-01T00:00:00Z' }), 'This preparation has expired. Prepare the item again.'],
        ['refused', refusal('Not authorized'), forbiddenSentence],
    ])('%s shows its text and no form', async (_name, prep, text) => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        const w = await mountPage(prep)
        expect(w.text()).toContain(text)
        expect(followButton(w).exists()).toBe(false)
        expect(w.find('[data-testid="upstream-backfill-kind"]').exists()).toBe(false)
        expect(confirmCalls()).toHaveLength(0)
        w.unmount()
    })

    it('showsTheProbeSentenceOfAFailedSourceAndALinkBackToTheFollows', async () => {
        const w = await mountPage(preparation({ state: 'FAILED', failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'Reading from the source failed.', item: null, components: [] }))
        expect(w.text()).toContain('The server did not answer in time.')
        expect(JSON.parse(w.find('[data-testid="upstream-back-to-follows"]').attributes('data-to')!)).toEqual({ name: 'UpstreamOfOrg', params: { orguuid: ORG }, query: { tab: 'follows' } })
        w.unmount()
    })

    it('readsAPendingPreparationAgainEveryTwoSecondsUntilItIsReady', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        const pending = preparation({ state: 'PENDING', item: null, components: [] })
        let reads = 0
        query.mockImplementation(async (args: any) => {
            if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION) return { data: { upstreamFollowPreparation: ++reads < 3 ? pending : preparation() } }
            return { data: { upstreamSource: { uuid: SOURCE, name: 'Acme TEA' } } }
        })
        const w = mount(Page, { global, attachTo: document.body })
        await flushPromises()
        expect(reads).toBe(1)
        await vi.advanceTimersByTimeAsync(2000)
        expect(reads).toBe(2)
        await vi.advanceTimersByTimeAsync(2000)
        expect(reads).toBe(3)
        expect(followButton(w).exists()).toBe(true)
        await vi.advanceTimersByTimeAsync(6000)
        expect(reads).toBe(3)
        w.unmount()
    })

    it('theUntouchedReadyFormCannotConfirm', async () => {
        const w = await mountPage(preparation())
        expect(w.text()).toContain('Acme Suite')
        expect(w.text()).toContain('Acme TEA')
        const kinds = w.findAllComponents(NRadio).filter((r: any) => ['LATEST_PER_LINE', 'FROM_DATE', 'FROM_RELEASE'].includes(r.props('value')))
        expect(kinds).toHaveLength(3)
        expect(kinds.every((r: any) => !(r.find('input').element as HTMLInputElement).checked)).toBe(true)
        const stop = w.findAllComponents(NRadio).find((r: any) => r.attributes('data-testid') === 'upstream-on-drift-STOP')!
        expect((stop.find('input').element as HTMLInputElement).checked).toBe(true)
        expect(followButton(w).attributes('disabled')).toBeDefined()
        expect(w.find('[data-testid="upstream-confirm-blockers"]').text()).toContain(startingPointRequired)
        await followButton(w).trigger('click')
        await (w.vm as any).follow()
        await flushPromises()
        expect(confirmCalls()).toHaveLength(0)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

describe('T-525 the preview and the one confirm', () => {
    it('asksThePreviewWithTheFormValuesAndConfirmsWithTheSameValuesOnce', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(true), scope(true, 7)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(299)
        expect(scopeCalls()).toHaveLength(0)
        expect(followButton(w).attributes('disabled')).toBeDefined()
        await vi.advanceTimersByTimeAsync(1)
        expect(scopeCalls()).toHaveLength(1)
        const sent = scopeCalls()[0][0].variables
        expect(sent).toEqual({ preparation: PREP, depth: 2, backfill: { kind: 'LATEST_PER_LINE', count: 3 } })
        await flushPromises()
        expect(w.text()).toContain('12 releases on these lines')
        expect(followButton(w).attributes('disabled')).toBeUndefined()

        let release: (v: any) => void = () => {}
        mutate.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
        await followButton(w).trigger('click')
        await (w.vm as any).follow()
        await w.vm.$nextTick()
        expect(followButton(w).attributes('disabled')).toBeDefined()
        await followButton(w).trigger('click')
        expect(confirmCalls()).toHaveLength(1)
        const follow = confirmCalls()[0][0].variables.follow
        expect(confirmCalls()[0][0].variables.preparation).toBe(PREP)
        expect(follow.depth).toEqual(sent.depth)
        expect(follow.backfill).toEqual(sent.backfill)
        release({ data: { confirmUpstreamFollow: { uuid: FOLLOW } } })
        await flushPromises()
        expect(router.replace).toHaveBeenCalledWith({ name: 'UpstreamFollow', params: { orguuid: ORG, uuid: FOLLOW } })
        expect(confirmCalls()).toHaveLength(1)
        w.unmount()
    })

    it('blocksAgainWhenTheDepthChangesUntilTheNewPreviewArrives', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(true), scope(true, 5)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        await chooseDepth(w, 1)
        expect(followButton(w).attributes('disabled')).toBeDefined()
        expect(w.find('[data-testid="upstream-confirm-blockers"]').text()).toContain('The preview is being computed.')
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        expect(scopeCalls()[1][0].variables.depth).toBe(1)
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        w.unmount()
    })

    it('staysBlockedOverTheLimitAndSaysBothNumbers', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(false, 812)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 50)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        expect(w.find('[data-testid="upstream-scope-over-limit"]').text())
            .toBe('This starting point takes in 812 releases. At most 500 can be followed at once: choose a later starting point.')
        expect(followButton(w).attributes('disabled')).toBeDefined()
        await (w.vm as any).follow()
        expect(confirmCalls()).toHaveLength(0)
        w.unmount()
    })

    it('showsAStaleRefusalWithPrepareAgainAndKeepsTheForm', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        mutate.mockRejectedValueOnce(refusal('The preparation is stale. Prepare again.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'PREPARATION_STALE' }))
        await followButton(w).trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="upstream-confirm-refusal"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-confirm-refusal"]').text()).toContain('The preparation is stale. Prepare again.')
        expect(w.find('[data-testid="upstream-prepare-again"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-follows-of-source"]').exists()).toBe(false)
        expect((w.vm as any).model.depth).toBe(2)
        expect((w.vm as any).model.backfill).toEqual({ kind: 'LATEST_PER_LINE', count: 3, day: null, release: null })
        expect(router.replace).not.toHaveBeenCalled()

        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: 'cccccccc-0000-4000-8000-000000000002', state: 'PENDING' } } })
        await w.find('[data-testid="upstream-prepare-again"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls.at(-1)![0].mutation).toBe(Q.PREPARE_UPSTREAM_FOLLOW)
        expect(mutate.mock.calls.at(-1)![0].variables).toEqual({ source: SOURCE, kind: 'PRODUCT', upstreamUuid: 'eeeeeeee-0000-4000-8000-000000000000' })
        expect(router.push).toHaveBeenCalledWith({ name: 'UpstreamFollowPreparation', params: { orguuid: ORG, uuid: 'cccccccc-0000-4000-8000-000000000002' } })
        w.unmount()
    })

    it('showsAnAlreadyFollowedRefusalWithALinkToTheFollowsOfTheSource', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        mutate.mockRejectedValueOnce(refusal('The item is already followed.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'ALREADY_FOLLOWED' }))
        await followButton(w).trigger('click')
        await flushPromises()
        const link = w.find('[data-testid="upstream-confirm-refusal"] [data-testid="upstream-follows-of-source"]')
        expect(JSON.parse(link.attributes('data-to')!)).toEqual({ name: 'UpstreamOfOrg', params: { orguuid: ORG }, query: { tab: 'follows', source: SOURCE } })
        expect(w.find('[data-testid="upstream-prepare-again"]').exists()).toBe(false)
        w.unmount()
    })

    it('offersPrepareAgainForAPreparationThatIsNotUsable', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        mutate.mockRejectedValueOnce(refusal('The preparation cannot be used.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'PREPARATION_NOT_USABLE' }))
        await followButton(w).trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="upstream-confirm-refusal"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-confirm-refusal"]').text()).toContain('The preparation cannot be used.')
        expect(w.find('[data-testid="upstream-prepare-again"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-follows-of-source"]').exists()).toBe(false)
        w.unmount()
    })

    it('restartsTheWaitForThePreviewOnEveryChange', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [scope(true), scope(true)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(200)
        w.findComponent(NInputNumber).vm.$emit('update:value', 4)
        await w.vm.$nextTick()
        await vi.advanceTimersByTimeAsync(200)
        expect(scopeCalls()).toHaveLength(0)
        await vi.advanceTimersByTimeAsync(100)
        expect(scopeCalls()).toHaveLength(1)
        expect(scopeCalls()[0][0].variables).toEqual({ preparation: PREP, depth: 2, backfill: { kind: 'LATEST_PER_LINE', count: 4 } })
        await vi.advanceTimersByTimeAsync(1000)
        expect(scopeCalls()).toHaveLength(1)
        w.unmount()
    })

    it.each([['an answer'], ['a refusal']])('dropsAnOlderPreviewThatArrivesAfterTheNewerOne, %s', async (kind) => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        const older = deferred()
        const newer = deferred()
        scopeAnswers = [older.promise, newer.promise]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await chooseDepth(w, 1)
        await vi.advanceTimersByTimeAsync(300)
        expect(scopeCalls().map(c => c[0].variables.depth)).toEqual([2, 1])
        newer.resolve(scope(true, 5))
        await flushPromises()
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        if (kind === 'an answer') older.resolve(scope(true, 99))
        else older.reject(refusal('The backfill point is not valid.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'INVALID_BACKFILL' }))
        await flushPromises()
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        expect(w.find('[data-testid="upstream-scope-answer"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-scope-answer"]').text()).toContain('5 releases on these lines')
        expect(w.text()).not.toContain('99 releases')
        expect(w.text()).not.toContain('The backfill point is not valid.')
        w.unmount()
    })

    it('keepsAnAnswerWithTheValuesItWasAskedForNotTheValuesInTheFormWhenItArrives', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        const pending = deferred()
        scopeAnswers = [pending.promise]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        expect(scopeCalls()).toHaveLength(1)
        await chooseDepth(w, 1)
        pending.resolve(scope(true, 77))
        await flushPromises()
        expect(followButton(w).attributes('disabled')).toBeDefined()
        expect(w.find('[data-testid="upstream-confirm-blockers"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-confirm-blockers"]').text()).toContain('The preview is being computed.')
        expect(w.text()).not.toContain('77 releases')
        await (w.vm as any).follow()
        expect(confirmCalls()).toHaveLength(0)
        w.unmount()
    })

    it('showsARefusedPreviewWithItsSentenceAndListsItAsTheBlocker', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
        scopeAnswers = [refusal('The backfill point is not valid.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'INVALID_BACKFILL' })]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        expect(w.find('[data-testid="upstream-scope-error"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-scope-error"]').text()).toBe('The backfill point is not valid.')
        expect(w.find('[data-testid="upstream-confirm-blockers"]').text()).toContain('The backfill point is not valid.')
        expect(w.find('[data-testid="upstream-confirm-blockers"]').text()).not.toContain('The preview is being computed.')
        expect(followButton(w).attributes('disabled')).toBeDefined()
        w.unmount()
    })

    it('readsTheClockAtTheMomentOfTheConfirm', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
        vi.setSystemTime(new Date('2026-11-25T10:00:00Z'))
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation({ expires: '2026-11-25T10:00:05Z' }))
        await chooseDepth(w, 2)
        await chooseNewestPerLine(w, 3)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        // Past the expiry, before the page's own clock ticks again.
        vi.setSystemTime(new Date('2026-11-25T10:00:06Z'))
        await (w.vm as any).follow()
        await flushPromises()
        expect(confirmCalls()).toHaveLength(0)
        w.unmount()
    })

    it('turnsAnOpenFormIntoTheExpiredTextWithinTenSeconds', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
        vi.setSystemTime(new Date('2026-11-25T10:00:00Z'))
        const w = await mountPage(preparation({ expires: '2026-11-25T10:00:05Z' }))
        expect(w.find('[data-testid="upstream-confirm-form"]').exists()).toBe(true)
        await vi.advanceTimersByTimeAsync(10000)
        expect(w.find('[data-testid="upstream-preparation-expired"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-confirm-form"]').exists()).toBe(false)
        expect(followButton(w).exists()).toBe(false)
        w.unmount()
    })

    it('picksAStartingReleaseFromThePagesOfThePreparationAndOnlyAFinalUsableOne', async () => {
        const R1 = 'bbbbbbbb-0000-4000-8000-000000000001'
        const R2 = 'bbbbbbbb-0000-4000-8000-000000000002'
        const R3 = 'bbbbbbbb-0000-4000-8000-000000000003'
        const page = (releases: any[], endCursor: string | null) => ({ data: { upstreamFollowPreparationReleases: { total: 3, endCursor, releases } } })
        releasePages = [
            page([
                { upstreamUuid: R1, version: '1.0.0', date: '2026-01-01T00:00:00Z', preRelease: 'NO', usable: true },
                { upstreamUuid: R2, version: '1.1.0-rc.1', date: '2026-02-01T00:00:00Z', preRelease: 'YES', usable: true },
            ], 'c1'),
            page([{ upstreamUuid: R3, version: null, date: '2026-03-01T00:00:00Z', preRelease: 'NO', usable: false }], null),
        ]
        const releaseCalls = () => query.mock.calls.filter(c => c[0].query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES).map(c => c[0].variables)
        const options = () => w.findAllComponents(NRadio).filter((r: any) => r.attributes('data-testid') === 'upstream-release-option')
        const w = await mountPage(preparation())
        expect(releaseCalls()).toHaveLength(0)
        const fromRelease = w.findAllComponents(NRadio).find((r: any) => r.props('value') === 'FROM_RELEASE')!
        await fromRelease.find('input').setValue(true)
        await flushPromises()
        expect(releaseCalls()).toEqual([{ preparation: PREP, after: null }])
        expect(options().map((r: any) => [r.props('value'), !!r.props('disabled')])).toEqual([[R1, false], [R2, true]])
        expect(w.find('[data-testid="upstream-releases-more"]').exists()).toBe(true)
        await w.find('[data-testid="upstream-releases-more"]').trigger('click')
        await flushPromises()
        expect(releaseCalls()).toEqual([{ preparation: PREP, after: null }, { preparation: PREP, after: 'c1' }])
        expect(options().map((r: any) => [r.props('value'), !!r.props('disabled')])).toEqual([[R1, false], [R2, true], [R3, true]])
        expect(w.find('[data-testid="upstream-releases-more"]').exists()).toBe(false)
        w.unmount()
    })

    it('showsTheStandingDepthOfAnItemAlreadyInReARMAndNoChoice', async () => {
        const prep = preparation()
        prep.item.existingItem = { uuid: 'i1', component: CAND, depth: 3, depthOrigin: 'CHOSEN', updateState: 'UPDATED' }
        const w = await mountPage(prep)
        expect(w.find('[data-testid="upstream-confirm-existing"]').text()).toContain('Its depth is 3 and cannot be chosen again.')
        expect(w.find('[data-testid="upstream-confirm-target"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-depth-assessment"]').exists()).toBe(false)
        expect(JSON.parse(w.find('[data-testid="upstream-existing-link"]').attributes('data-to')!)).toEqual({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: CAND } })
        w.unmount()
    })
})

describe('T-531 to T-533 one block of controls per choice', () => {
    const FAKE = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] } as any
    const KINDS = ['LATEST_PER_LINE', 'FROM_DATE', 'FROM_RELEASE']
    const BLOCKS: Record<string, string> = {
        LATEST_PER_LINE: 'upstream-backfill-count',
        FROM_DATE: 'upstream-backfill-day',
        FROM_RELEASE: 'upstream-release-picker',
    }
    const VERSIONS = ['1.0.0', '1.1.0', '1.2.0', '1.3.0', '1.4.0', '1.5.0', '1.6.0']
    const releaseUuid = (i: number) => `bbbbbbbb-0000-4000-8000-00000000000${i + 1}`
    const sevenReleases = () => ({
        data: {
            upstreamFollowPreparationReleases: {
                total: 7,
                endCursor: null,
                releases: VERSIONS.map((version, i) => ({ upstreamUuid: releaseUuid(i), version, date: `2026-0${i + 1}-01T00:00:00Z`, preRelease: 'NO', usable: true })),
            },
        },
    })
    const count = (testId: string) => document.querySelectorAll(`[data-testid="${testId}"]`).length
    const checkedKind = () => KINDS.filter(k => (document.querySelector(`[data-testid="upstream-backfill-${k}"] input`) as HTMLInputElement | null)?.checked)
    const VALUES: Record<string, any> = {
        LATEST_PER_LINE: { kind: 'LATEST_PER_LINE', count: 3 },
        FROM_DATE: { kind: 'FROM_DATE', date: '2026-03-01T00:00:00.000Z' },
        FROM_RELEASE: { kind: 'FROM_RELEASE', release: releaseUuid(2) },
    }

    function expectOneStartingPointBlock (kind: string | null) {
        expect(KINDS.map(k => count(`upstream-backfill-${k}`))).toEqual([1, 1, 1])
        expect(checkedKind()).toEqual(kind ? [kind] : [])
        for (const k of KINDS) expect(count(BLOCKS[k])).toBe(k === kind ? 1 : 0)
        if (kind === 'FROM_RELEASE') {
            const options = Array.from(document.querySelectorAll('[data-testid="upstream-release-option"]')).map(o => o.textContent!.trim())
            expect(options).toHaveLength(VERSIONS.length)
            expect(VERSIONS.map(v => options.filter(o => o.startsWith(v)).length)).toEqual(VERSIONS.map(() => 1))
        }
    }

    async function chooseKind (w: any, kind: string) {
        await w.find(`[data-testid="upstream-backfill-${kind}"] input`).setValue(true)
        await flushPromises()
    }

    /** Completes the value of the checked kind and lets the preview go out. */
    async function complete (w: any, kind: string) {
        if (kind === 'LATEST_PER_LINE') {
            w.findComponent(NInputNumber).vm.$emit('update:value', 3)
        } else if (kind === 'FROM_DATE') {
            const input = w.findComponent(NDatePicker).find('input')
            await input.setValue('2026-03-01')
            await input.trigger('blur')
        } else {
            await w.findAll('[data-testid="upstream-release-option"] input')[2].setValue(true)
        }
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
    }

    it.each([
        ['a', ['LATEST_PER_LINE', 'FROM_DATE']],
        ['b', ['LATEST_PER_LINE', 'FROM_RELEASE']],
        ['c', ['FROM_DATE', 'FROM_RELEASE']],
        ['d', ['FROM_RELEASE', 'LATEST_PER_LINE']],
        ['e', ['LATEST_PER_LINE', 'FROM_DATE', 'FROM_RELEASE', 'LATEST_PER_LINE']],
    ])('T-531 (%s) shows the controls of the checked starting point only, once', async (_name, sequence) => {
        vi.useFakeTimers(FAKE)
        scopeAnswers = sequence.map(() => scope(true))
        releasePages = [sevenReleases()]
        const w = await mountPage(preparation())
        expectOneStartingPointBlock(null)
        for (const kind of sequence) {
            await chooseKind(w, kind)
            expectOneStartingPointBlock(kind)
            await complete(w, kind)
            expectOneStartingPointBlock(kind)
            const calls = scopeCalls()
            expect(calls.length).toBeGreaterThan(0)
            expect(calls[calls.length - 1][0].variables.backfill).toEqual(VALUES[kind])
        }
        w.unmount()
    })

    it('T-532 shows the control of the checked target only, once, for the root and for a component row', async () => {
        const prep = preparation()
        prep.item.adoptionCandidates = [{ component: CAND, name: 'Old suite', releases: 3, releasesWithUpstreamVersion: 2 }]
        prep.components[0].adoptionCandidates = [{ component: CAND, name: 'Old core', releases: 3, releasesWithUpstreamVersion: 2 }]
        const w = await mountPage(prep)
        const expectRoot = (target: 'CREATE' | 'ADOPT') => {
            expect([count('upstream-target-create'), count('upstream-target-adopt')]).toEqual([1, 1])
            expect(count('upstream-confirm-name')).toBe(target === 'CREATE' ? 1 : 0)
            expect(count('upstream-confirm-adopt')).toBe(target === 'ADOPT' ? 1 : 0)
        }
        expectRoot('CREATE')
        for (const target of ['ADOPT', 'CREATE', 'ADOPT'] as const) {
            await w.find(`[data-testid="upstream-target-${target.toLowerCase()}"] input`).setValue(true)
            await flushPromises()
            expectRoot(target)
        }
        expect(count('upstream-component-candidate-0')).toBe(0)
        await w.find('[data-testid="upstream-component-adopt-0"] input').setValue(true)
        await flushPromises()
        expect(count('upstream-component-candidate-0')).toBe(1)
        expectRoot('ADOPT')
        await w.find('[data-testid="upstream-component-create-0"] input').setValue(true)
        await flushPromises()
        expect(count('upstream-component-candidate-0')).toBe(0)
        expectRoot('ADOPT')
        w.unmount()
    })

    it('T-533 confirms the values of the checked choices only', async () => {
        vi.useFakeTimers(FAKE)
        scopeAnswers = [scope(true), scope(true), scope(true)]
        const prep = preparation()
        prep.item.adoptionCandidates = [{ component: CAND, name: 'Old suite', releases: 3, releasesWithUpstreamVersion: 2 }]
        const w = await mountPage(prep)
        await chooseKind(w, 'LATEST_PER_LINE')
        w.findComponent(NInputNumber).vm.$emit('update:value', 3)
        await chooseKind(w, 'FROM_DATE')
        const day = w.findComponent(NDatePicker).find('input')
        await day.setValue('2026-03-01')
        await day.trigger('blur')
        w.findComponent(NInput).vm.$emit('update:value', 'Renamed suite')
        await w.find('[data-testid="upstream-target-adopt"] input').setValue(true)
        await flushPromises()
        const adopt = w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === 'upstream-confirm-adopt')!
        adopt.vm.$emit('update:value', CAND)
        await vi.advanceTimersByTimeAsync(300)
        await flushPromises()
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        await followButton(w).trigger('click')
        await flushPromises()
        expect(confirmCalls()).toHaveLength(1)
        const follow = confirmCalls()[0][0].variables.follow
        expect(follow.backfill).toStrictEqual({ kind: 'FROM_DATE', date: '2026-03-01T00:00:00.000Z' })
        const calls = scopeCalls()
        expect(follow.backfill).toStrictEqual(calls[calls.length - 1][0].variables.backfill)
        expect(follow.adopt).toBe(CAND)
        expect('name' in follow).toBe(false)
        w.unmount()
    })
})

describe('T-529 supplier text is text', () => {
    it('showsHostileNamesLiterallyOnTheConfirmation', async () => {
        const prep = preparation({
            item: proposal('eeeeeeee-0000-4000-8000-000000000000', HOSTILE, { kind: 'PRODUCT' }),
            components: [proposal('eeeeeeee-0000-4000-8000-000000000001', HOSTILE, {
                adoptionCandidates: [{ component: CAND, name: HOSTILE, releases: 3, releasesWithUpstreamVersion: 1 }],
            })],
        })
        prep.item.depths[1].lines = lines(HOSTILE)
        prep.item.adoptionCandidates = [{ component: CAND, name: HOSTILE, releases: 3, releasesWithUpstreamVersion: 1 }]
        const w = await mountPage(prep)
        expect(w.find('[data-testid="upstream-confirm-item-name"]').text()).toBe(HOSTILE)
        expect(w.find('[data-testid="upstream-component-name"]').text()).toBe(HOSTILE)
        expect(w.find('[data-testid="upstream-depth-preview"]').text()).toContain(HOSTILE)
        const adopt = w.findAllComponents(NRadio).find((r: any) => r.attributes('data-testid') === 'upstream-target-adopt')!
        await adopt.find('input').setValue(true)
        const options = (w.vm as any).candidateOptions(prep.item)
        expect(options[0].label.startsWith(HOSTILE)).toBe(true)
        expect(document.body.querySelector('img')).toBeNull()
        expect(document.body.querySelector('[onerror]')).toBeNull()
        w.unmount()
    })

    it('showsAHostileNameLiterallyInTheFollowsListAndLinksByRouteNameAndUuids', () => {
        const follow = {
            uuid: FOLLOW, source: SOURCE, status: 'ACTIVE', onDrift: 'STOP', createdDate: '2026-11-01T00:00:00Z', unfollowedDate: null, pendingCount: 2,
            item: { uuid: 'i1', kind: 'COMPONENT', upstreamUuid: 'eeeeeeee-0000-4000-8000-000000000001', upstreamName: HOSTILE, component: CAND, depth: 1, depthOrigin: 'PROPOSED', updateState: 'NO_LONGER_UPDATED' },
            lastRun: { uuid: 'r1', kind: 'SYNC', origin: 'SYNC', state: 'COMPLETED', stopReason: null, finishedDate: '2026-11-02T00:00:00Z' },
        }
        const w = mount(FollowsList, { props: { orgUuid: ORG, follows: [follow], sources: [{ uuid: SOURCE, name: 'Acme TEA' }] }, global, attachTo: document.body })
        expect(w.text()).toContain(HOSTILE)
        expect(w.text()).toContain('Acme TEA')
        expect(w.text()).toContain('No longer updated')
        expect(document.body.querySelector('img')).toBeNull()
        expect(document.body.querySelector('[onerror]')).toBeNull()
        const targets = w.findAll('.rl').map(a => JSON.parse(a.attributes('data-to')!))
        expect(targets).toEqual([
            { name: 'UpstreamFollow', params: { orguuid: ORG, uuid: FOLLOW } },
            { name: 'ComponentsOfOrg', params: { orguuid: ORG, compuuid: CAND } },
        ])
        const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
        for (const t of targets) for (const v of Object.values(t.params)) expect(v).toMatch(uuid)
        w.unmount()
    })

    it('givesNoLinkForAValueThatIsNotAUuid', () => {
        const follow = { uuid: '../../admin', source: SOURCE, status: 'ACTIVE', onDrift: 'STOP', pendingCount: 0, item: { kind: 'PRODUCT', upstreamName: 'x', component: 'javascript:alert(1)' }, lastRun: null }
        const w = mount(FollowsList, { props: { orgUuid: ORG, follows: [follow], sources: [] }, global, attachTo: document.body })
        expect(w.findAll('.rl')).toHaveLength(0)
        w.unmount()
    })
})

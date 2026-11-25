// @vitest-environment happy-dom
//
// T-523 and T-525 (TPF-185 design 3.8) through the page, not the pure model: every choice of the
// confirmation form lands in its own field of the model, so the one confirm carries the root's
// target, name or adoption, the root's depth and each component's own depth and adoption, and the
// day the user typed; and a change of the preparation uuid on the same page (Prepare again pushes
// the same route with the new uuid) reads the new preparation and keeps nothing of the earlier one.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NDatePicker, NInput, NInputNumber, NRadio, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))
const route: any = reactive({ params: {}, query: {} })
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Page } = await import('./UpstreamFollowPreparationPage.vue')
const Q = await import('@/utils/upstreamFollowQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { PREVIEW_PENDING } = await import('@/utils/upstreamFollowConfirm')

const ORG = '11111111-2222-3333-4444-555555555555'
const PREP = 'cccccccc-0000-4000-8000-000000000001'
const PREP2 = 'cccccccc-0000-4000-8000-000000000002'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const SOURCE2 = 'aaaaaaaa-0000-4000-8000-000000000002'
const FOLLOW = 'ffffffff-0000-4000-8000-000000000001'
const ITEM = 'eeeeeeee-0000-4000-8000-000000000000'
const C1 = 'eeeeeeee-0000-4000-8000-000000000001'
const C2 = 'eeeeeeee-0000-4000-8000-000000000002'
const CAND = 'dddddddd-0000-4000-8000-000000000001'
const CAND2 = 'dddddddd-0000-4000-8000-000000000002'
const R1 = 'bbbbbbbb-0000-4000-8000-000000000001'
const ADMIN = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }] } }
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] } as any

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

/** A product whose root and first component each have an item to adopt; the second component has none. */
function preparation (extra: Record<string, any> = {}) {
    return {
        uuid: PREP, org: ORG, source: SOURCE, state: 'READY', failure: null, sourceFailure: null, message: null,
        createdDate: '2026-11-25T09:00:00Z', finishedDate: '2026-11-25T09:01:00Z', expires: '2099-01-01T00:00:00Z',
        componentsTruncated: false, maxBackfillReleases: 500,
        item: proposal(ITEM, 'Acme Suite', { kind: 'PRODUCT', adoptionCandidates: [{ component: CAND, name: 'Old suite', releases: 3, releasesWithUpstreamVersion: 2 }] }),
        components: [
            proposal(C1, 'acme-core', { adoptionCandidates: [{ component: CAND2, name: 'Old core', releases: 3, releasesWithUpstreamVersion: 2 }] }),
            proposal(C2, 'acme-util'),
        ],
        ...extra,
    }
}

function refusal (message: string, extensions?: Record<string, any>): any {
    const err: any = new Error(message)
    err.graphQLErrors = [{ message, ...(extensions ? { extensions } : {}) }]
    return err
}

/** A promise the test settles itself, to look at the page while a request is in flight. */
function deferred () {
    let resolve: (v: any) => void = () => {}
    const promise = new Promise(res => { resolve = res })
    return { promise, resolve }
}

const scope = (withinLimit: boolean, releases = 12) => ({
    data: { upstreamFollowPreparationScope: { releases, withinLimit, onBaseBranch: 0, skippedPreReleases: 1, linesTruncated: false, lines: lines('line-2') } },
})
const releasePage = (version: string, endCursor: string | null, total: number) => ({
    data: { upstreamFollowPreparationReleases: { total, endCursor, releases: [{ upstreamUuid: R1, version, date: null, preRelease: 'NO', usable: true }] } },
})

/** Pages left mounted are unmounted after each test, so a failed one cannot answer the route of the next. */
let mounted: any[] = []
let preparations: Record<string, any> = {}
let scopeAnswers: any[] = []
let releasePages: any[] = []
const sourceNames: Record<string, string> = { [SOURCE]: 'Acme TEA', [SOURCE2]: 'Other TEA' }

/** Answers by document: the preparation of the uuid asked, its source's name, the scope answers and release pages in turn. */
function answer () {
    query.mockImplementation(async (args: any) => {
        if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION) return { data: { upstreamFollowPreparation: preparations[args.variables.uuid] } }
        if (args.query === GET_UPSTREAM_SOURCE) return { data: { upstreamSource: { uuid: args.variables.uuid, name: sourceNames[args.variables.uuid] } } }
        if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE) return scopeAnswers.shift()
        if (args.query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES) return releasePages.shift()
        throw new Error('unexpected query')
    })
}

async function mountPage (prep: any) {
    preparations[prep.uuid] = prep
    route.params = { orguuid: ORG, uuid: prep.uuid }
    answer()
    const w = mount(Page, { global, attachTo: document.body })
    mounted.push(w)
    await flushPromises()
    return w
}

const followButton = (w: any) => w.find('[data-testid="upstream-confirm-follow"]')
const confirmCalls = () => mutate.mock.calls.filter(c => c[0].mutation === Q.CONFIRM_UPSTREAM_FOLLOW)
const scopeCalls = () => query.mock.calls.filter(c => c[0].query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE)
const releaseCalls = () => query.mock.calls.filter(c => c[0].query === Q.GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES)
const preparationCalls = () => query.mock.calls.filter(c => c[0].query === Q.GET_UPSTREAM_FOLLOW_PREPARATION)
const byTestId = (w: any, component: any, id: string) => w.findAllComponents(component).find((c: any) => c.attributes('data-testid') === id)
const radioOf = (w: any, value: string) => w.findAllComponents(NRadio).find((r: any) => r.props('value') === value)

/** Picks the option of a select the way a user does: one of the values the select offers, never one the test makes up. */
async function pick (w: any, select: any, index: number) {
    select.vm.$emit('update:value', (select.props('options') as any[])[index].value)
    await w.vm.$nextTick()
}

async function chooseDepth (w: any, depth: number) {
    const radio = w.findAllComponents(NRadio).find((r: any) => r.attributes('data-testid') === `upstream-depth-${depth}`)
    await radio.find('input').setValue(true)
}

async function chooseNewestPerLine (w: any, count: number) {
    await radioOf(w, 'LATEST_PER_LINE').find('input').setValue(true)
    w.findComponent(NInputNumber).vm.$emit('update:value', count)
    await w.vm.$nextTick()
}

async function previewArrives () {
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
    preparations = {}
    scopeAnswers = []
    releasePages = []
    getters.myuser = ADMIN
    route.params = { orguuid: ORG, uuid: PREP }
    route.query = {}
})

afterEach(() => {
    for (const w of mounted) w.unmount()
    mounted = []
    vi.useRealTimers()
    document.body.innerHTML = ''
})

describe('T-523 what the form writes reaches the one confirm, field by field', () => {
    it('sendsTheRootNameAndDepthAndEachComponentsOwnDepthAndAdoption', async () => {
        vi.useFakeTimers(FAKE_TIMERS)
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation())
        w.findAllComponents(NInput).find((c: any) => c.attributes('data-testid') === 'upstream-confirm-name').vm.$emit('update:value', 'Acme Renamed')
        await chooseDepth(w, 2)
        await w.find('[data-testid="upstream-component-adopt-0"] input').setValue(true)
        const candidate = byTestId(w, NSelect, 'upstream-component-candidate-0')
        expect(candidate).toBeDefined()
        await pick(w, candidate, 0)
        await pick(w, byTestId(w, NSelect, 'upstream-component-depth-0'), 2)
        await pick(w, byTestId(w, NSelect, 'upstream-component-depth-1'), 0)
        await chooseNewestPerLine(w, 3)
        await previewArrives()

        expect(scopeCalls().map(c => c[0].variables)).toEqual([{ preparation: PREP, depth: 2, backfill: { kind: 'LATEST_PER_LINE', count: 3 } }])
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        mutate.mockResolvedValueOnce({ data: { confirmUpstreamFollow: { uuid: FOLLOW } } })
        await followButton(w).trigger('click')
        await flushPromises()
        expect(confirmCalls().map(c => c[0].variables)).toEqual([{
            preparation: PREP,
            follow: {
                depth: 2,
                backfill: { kind: 'LATEST_PER_LINE', count: 3 },
                onDrift: 'STOP',
                name: 'Acme Renamed',
                components: [{ upstreamUuid: C1, depth: 2, adopt: CAND2 }, { upstreamUuid: C2, depth: 0 }],
            },
        }])
    })

    it('sendsTheRootAdoptionAsTheUuidOfTheChosenItemAndLeavesTheComponentsAsProposed', async () => {
        vi.useFakeTimers(FAKE_TIMERS)
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation())
        await w.find('[data-testid="upstream-target-adopt"] input').setValue(true)
        const adopt = byTestId(w, NSelect, 'upstream-confirm-adopt')
        expect((adopt.props('options') as any[]).map(o => o.value)).toEqual([CAND])
        await pick(w, adopt, 0)
        await chooseNewestPerLine(w, 3)
        await previewArrives()

        expect(followButton(w).attributes('disabled')).toBeUndefined()
        mutate.mockResolvedValueOnce({ data: { confirmUpstreamFollow: { uuid: FOLLOW } } })
        await followButton(w).trigger('click')
        await flushPromises()
        expect(confirmCalls().map(c => c[0].variables)).toEqual([{
            preparation: PREP,
            follow: { depth: 1, backfill: { kind: 'LATEST_PER_LINE', count: 3 }, onDrift: 'STOP', adopt: CAND },
        }])
    })

    it('asksThePreviewForTheDayTypedIntoThePicker', async () => {
        vi.useFakeTimers(FAKE_TIMERS)
        scopeAnswers = [scope(true)]
        const w = await mountPage(preparation())
        await radioOf(w, 'FROM_DATE').find('input').setValue(true)
        const input = w.findComponent(NDatePicker).find('input')
        await input.setValue('2026-03-01')
        await input.trigger('blur')
        await previewArrives()
        expect(scopeCalls().map(c => c[0].variables)).toEqual([{ preparation: PREP, depth: 1, backfill: { kind: 'FROM_DATE', date: '2026-03-01T00:00:00.000Z' } }])
        expect((w.vm as any).model.backfill).toEqual({ kind: 'FROM_DATE', count: null, day: '2026-03-01', release: null })
    })
})

describe('T-525 a change of the preparation uuid keeps nothing of the earlier preparation', () => {
    it('readsTheNewPreparationWithItsOwnSourceAndClearsThePreviewTheRefusalTheErrorAndThePicker', async () => {
        vi.useFakeTimers(FAKE_TIMERS)
        scopeAnswers = [scope(true)]
        releasePages = [releasePage('7.7.7', 'c1', 40)]
        const w = await mountPage(preparation())
        await chooseDepth(w, 2)
        await radioOf(w, 'FROM_RELEASE').find('input').setValue(true)
        await flushPromises()
        expect(w.text()).toContain('7.7.7')
        expect(w.find('[data-testid="upstream-releases-more"]').exists()).toBe(true)
        await radioOf(w, R1).find('input').setValue(true)
        await previewArrives()
        expect(followButton(w).attributes('disabled')).toBeUndefined()
        mutate.mockRejectedValueOnce(refusal('Stale words.', { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'PREPARATION_STALE' }))
        await followButton(w).trigger('click')
        await flushPromises()
        mutate.mockRejectedValueOnce(refusal('Prepare refused words.'))
        await w.find('[data-testid="upstream-prepare-again"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="upstream-confirm-refusal"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-prepare-again-error"]').exists()).toBe(true)

        // The same item prepared again: same releases on the source, another preparation and source name.
        preparations[PREP2] = preparation({ uuid: PREP2, source: SOURCE2, item: proposal(ITEM, 'Beta Suite', { kind: 'PRODUCT' }) })
        route.params = { orguuid: ORG, uuid: PREP2 }
        await flushPromises()
        expect(preparationCalls().map(c => c[0].variables)).toEqual([{ uuid: PREP }, { uuid: PREP2 }])
        expect(w.find('[data-testid="upstream-confirm-item-name"]').text()).toBe('Beta Suite')
        expect(w.find('[data-testid="upstream-confirm-source"]').text()).toBe('Other TEA')
        expect(w.find('[data-testid="upstream-confirm-refusal"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-prepare-again-error"]').exists()).toBe(false)

        const secondPage = deferred()
        releasePages = [secondPage.promise]
        await chooseDepth(w, 2)
        await radioOf(w, 'FROM_RELEASE').find('input').setValue(true)
        expect(releaseCalls().map(c => c[0].variables)).toEqual([{ preparation: PREP, after: null }, { preparation: PREP2, after: null }])
        expect(w.text()).not.toContain('7.7.7')
        expect(w.find('[data-testid="upstream-releases-more"]').exists()).toBe(false)
        secondPage.resolve(releasePage('8.8.8', null, 1))
        await flushPromises()
        expect(w.text()).toContain('8.8.8')

        // The same depth and release as the earlier preview: that answer does not count for this preparation.
        scopeAnswers = [scope(true, 5)]
        await radioOf(w, R1).find('input').setValue(true)
        expect(followButton(w).attributes('disabled')).toBeDefined()
        expect(w.find('[data-testid="upstream-confirm-blockers"]').text()).toContain(PREVIEW_PENDING)
        await previewArrives()
        expect(scopeCalls().map(c => c[0].variables.preparation)).toEqual([PREP, PREP2])
        expect(w.find('[data-testid="upstream-scope-answer"]').text()).toContain('5 releases on these lines')
        expect(followButton(w).attributes('disabled')).toBeUndefined()
    })
})

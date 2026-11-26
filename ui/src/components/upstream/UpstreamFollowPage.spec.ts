// @vitest-environment happy-dom
//
// TPF-229 (TPF-185 design 3.9 sections 1 to 3, and T-530): the read-only part of the follow page. The header, the version
// lines with their branch links and their latest version as text, the components table and the last run are shown from the
// follow; an unfollowed follow, a no-longer-updated item, a stopped run, a refused source read
// and a refused first read each have their own words; every value is text; the page reads again
// every 5 seconds while the run is open and never sends a mutation.
// TPF-230 (design 3.9 sections 4 to 8, T-541): sync now, the run history, the releases, the pending
// releases, reconfigure and unfollow; the controls exist for an administrator on an active follow
// only, and the unfollow is sent from the positive button of its dialog only.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { print, visit } from 'graphql'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))

const route: any = { params: {}, query: {} }
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Page } = await import('./UpstreamFollowPage.vue')
const { default: ReconfigureModal } = await import('./UpstreamReconfigureModal.vue')
const {
    GET_UPSTREAM_FOLLOW, GET_UPSTREAM_INGEST_RUNS, GET_UPSTREAM_RELEASES, START_UPSTREAM_FOLLOW_RUN, UNFOLLOW_UPSTREAM_ITEM,
} = await import('@/utils/upstreamFollowQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { driftKindLabel, forbiddenSentence, unfollowStatement } = await import('@/utils/upstreamLabels')
const { NSelect } = await import('naive-ui')
const { RUN_COUNTERS } = await import('@/utils/upstreamHelp')

const ORG = '11111111-2222-3333-4444-555555555555'
const FOLLOW = 'cccccccc-0000-4000-8000-000000000001'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const UPSTREAM_ITEM = 'dddddddd-0000-4000-8000-000000000001'
const COMPONENT = 'eeeeeeee-0000-4000-8000-000000000001'
const BRANCH_1 = 'ffffffff-0000-4000-8000-000000000001'
const BRANCH_2 = 'ffffffff-0000-4000-8000-000000000002'
const SEALED = 'System is sealed. Please unseal the system first.'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { RouterLink } }

function counters (over: any = {}) {
    return {
        releasesInScope: 12, created: 10, adopted: 0, alreadyIngested: 2, skippedPreReleases: 0, failed: 0, failedComponentRefs: 0,
        documentsPending: 0, filesStored: 0, filesNotKept: 0, bytes: 0, checked: 0, newCollectionVersions: 0, gone: 0, repaired: 0, held: 1,
        ...over,
    }
}

function run (over: any = {}) {
    return {
        uuid: '77777777-0000-4000-8000-000000000001', kind: 'BACKFILL', origin: 'FOLLOW', state: 'COMPLETED', stopReason: null, sourceFailure: null,
        message: null, createdDate: '2026-11-01T10:00:00Z', startedDate: '2026-11-01T10:00:01Z', finishedDate: '2026-11-01T10:00:09Z', sync: null,
        counters: counters(), ...over,
    }
}

function follow (over: any = {}) {
    return {
        uuid: FOLLOW, org: ORG, source: SOURCE, status: 'ACTIVE', onDrift: 'STOP', createdDate: '2026-11-01T10:00:00Z', unfollowedDate: null, pendingCount: 0,
        backfill: { kind: 'LATEST_PER_LINE', count: 3, date: null, release: null, releaseVersion: null },
        item: {
            uuid: '88888888-0000-4000-8000-000000000001', kind: 'PRODUCT', upstreamUuid: UPSTREAM_ITEM, upstreamName: 'Widget Suite', component: COMPONENT,
            depth: 2, depthOrigin: 'PROPOSED', mapping: 'CREATED', updateState: 'UPDATED',
            lines: [
                { lineKey: '1.x', branch: BRANCH_1, latestVersion: '1.4.2' },
                { lineKey: '2.x', branch: BRANCH_2, latestVersion: '2.0.1' },
            ],
        },
        components: [{
            uuid: '88888888-0000-4000-8000-000000000002', kind: 'COMPONENT', upstreamUuid: 'dddddddd-0000-4000-8000-000000000002', upstreamName: 'Widget Core',
            component: 'eeeeeeee-0000-4000-8000-000000000002', depth: 1, depthOrigin: 'PROPOSED', mapping: 'CREATED', updateState: 'UPDATED',
            lines: [{ lineKey: '1.x', branch: BRANCH_1, latestVersion: '1.4.2' }],
        }],
        lastRun: run(),
        pending: [],
        ...over,
    }
}

function sourceReply () {
    return { data: { upstreamSource: { uuid: SOURCE, name: 'Acme TEA', lastSync: null, nextSync: null } } }
}

function refusal (...messages: string[]): any {
    const err: any = new Error(messages[0])
    err.errors = messages.map(message => ({ message }))
    return err
}

async function mountPage (first: any, source: any = sourceReply()) {
    if (first instanceof Error) query.mockRejectedValueOnce(first)
    else query.mockResolvedValueOnce({ data: { upstreamFollow: first } })
    if (source instanceof Error) query.mockRejectedValueOnce(source)
    else query.mockResolvedValueOnce(source)
    const w = mount(Page, { global, attachTo: document.body })
    await flushPromises()
    return w
}

function links (w: any): any[] {
    return w.findAll('a.rl').map((a: any) => JSON.parse(a.attributes('data-to')))
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    mutationsExpected = false
    getters.myuser = { installationType: 'SAAS', permissions: { permissions: [] } }
    route.params = { orguuid: ORG, uuid: FOLLOW }
    route.query = {}
})

// Sections 1 to 3 never send a mutation; the tests of sections 4 to 8 that send one say so.
let mutationsExpected = false

afterEach(() => {
    if (!mutationsExpected) expect(mutate).not.toHaveBeenCalled()
    mutationsExpected = false
    vi.useRealTimers()
})

describe('the follow page, sections 1 to 3', () => {
    it('shows the header, the lines, the components and the last run', async () => {
        const w = await mountPage(follow())
        expect(query.mock.calls[0][0].query).toBe(GET_UPSTREAM_FOLLOW)
        expect(query.mock.calls[0][0].variables).toEqual({ uuid: FOLLOW })
        expect(query.mock.calls[1][0].query).toBe(GET_UPSTREAM_SOURCE)
        expect(query.mock.calls[1][0].variables).toEqual({ uuid: SOURCE })
        const text = w.text()
        expect(w.get('[data-testid="upstream-follow-name"]').text()).toBe('Widget Suite')
        expect(text).toContain(UPSTREAM_ITEM)
        expect(text).toContain('Following since')
        expect(w.get('[data-testid="upstream-follow-source"]').text()).toBe('Acme TEA')
        expect(w.get('[data-testid="upstream-follow-backfill"]').text()).toContain('the newest 3 releases of each version line')
        expect(w.get('[data-testid="upstream-run-counters"]').text()).toBe('12 releases in scope, 10 created, 2 already ingested, 0 failed, 1 held')
        expect(w.find('[data-testid="upstream-no-longer-updated"]').exists()).toBe(false)

        const to = links(w)
        expect(to).toContainEqual({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: COMPONENT } })
        expect(to).toContainEqual({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: COMPONENT, branchuuid: BRANCH_1 } })
        expect(to).toContainEqual({ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: COMPONENT, branchuuid: BRANCH_2 } })

        const lines = w.findAll('[data-testid="upstream-follow-line"]')
        expect(lines.length).toBe(2)
        expect(lines[0].text()).toContain('1.x')
        expect(lines[0].text()).toContain('1.4.2')
        const comps = w.findAll('[data-testid="upstream-follow-component"]')
        expect(comps.length).toBe(1)
        expect(comps[0].text()).toContain('Widget Core')
        w.unmount()
    })

    it('shows an unfollowed follow with its date and the kept sentence', async () => {
        const w = await mountPage(follow({ status: 'UNFOLLOWED', unfollowedDate: '2026-11-10T10:00:00Z' }))
        const text = w.get('[data-testid="upstream-follow-unfollowed"]').text()
        expect(text).toContain('Unfollowed on')
        expect(text).toContain('What was ingested is kept and no longer updated.')
        expect(w.find('[data-testid="upstream-follow-since"]').exists()).toBe(false)
        w.unmount()
    })

    it('shows the tag No longer updated on an active follow whose item is no longer updated', async () => {
        const base = follow()
        const w = await mountPage(follow({ item: { ...base.item, updateState: 'NO_LONGER_UPDATED' } }))
        expect(w.get('[data-testid="upstream-no-longer-updated"]').text()).toBe('No longer updated')
        w.unmount()
    })

    it('shows no tag No longer updated on an unfollowed follow', async () => {
        const base = follow()
        const w = await mountPage(follow({ status: 'UNFOLLOWED', unfollowedDate: '2026-11-10T10:00:00Z', item: { ...base.item, updateState: 'NO_LONGER_UPDATED' } }))
        expect(w.find('[data-testid="upstream-follow-unfollowed"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-no-longer-updated"]').exists()).toBe(false)
        w.unmount()
    })

    it('links no branch whose uuid is not a uuid', async () => {
        const base = follow()
        const w = await mountPage(follow({
            item: { ...base.item, lines: [{ lineKey: '3.x', branch: '../x', latestVersion: '3.0.0' }] },
            components: [],
        }))
        const line = w.get('[data-testid="upstream-follow-line"]')
        expect(line.text()).toContain('3.0.0')
        expect(line.findAll('a.rl')).toHaveLength(0)
        w.unmount()
    })

    it('shows the message of the backend for a stopped run before the words of its stop reason', async () => {
        const w = await mountPage(follow({ lastRun: run({ state: 'STOPPED', message: 'The supplier answered 503 three times.', stopReason: 'ITEM_GONE' }) }))
        expect(w.find('[data-testid="upstream-run-message"]').exists()).toBe(true)
        expect(w.get('[data-testid="upstream-run-message"]').text()).toBe('The supplier answered 503 three times.')
        w.unmount()
    })

    it('shows that no release has been ingested when there are no lines', async () => {
        const base = follow()
        const w = await mountPage(follow({ item: { ...base.item, lines: [] } }))
        expect(w.get('[data-testid="upstream-follow-no-lines"]').text()).toBe('No release has been ingested yet.')
        expect(w.findAll('[data-testid="upstream-follow-line"]').length).toBe(0)
        w.unmount()
    })

    it('shows the words of the stop reason of a stopped run without a message', async () => {
        const w = await mountPage(follow({ lastRun: run({ state: 'STOPPED', message: null, stopReason: 'ITEM_GONE' }) }))
        expect(w.find('[data-testid="upstream-run-message"]').exists()).toBe(true)
        expect(w.get('[data-testid="upstream-run-message"]').text()).toBe('The source no longer knows this item.')
        w.unmount()
    })

    it('shows the page and the source uuid when the source read is refused', async () => {
        const w = await mountPage(follow(), refusal('Not authorized'))
        expect(w.get('[data-testid="upstream-follow-name"]').text()).toBe('Widget Suite')
        expect(w.get('[data-testid="upstream-follow-source"]').text()).toBe(SOURCE)
        w.unmount()
    })

    it('shows the forbidden sentence for a plain Not authorized first read', async () => {
        const w = await mountPage(refusal('Not authorized'))
        expect(w.find('[data-testid="upstream-follow-forbidden"]').exists()).toBe(true)
        expect(w.get('[data-testid="upstream-follow-forbidden"]').text()).toBe(forbiddenSentence)
        expect(w.find('[data-testid="upstream-follow-name"]').exists()).toBe(false)
        w.unmount()
    })

    it('shows the sealed sentence and not the forbidden one for any other refusal', async () => {
        const w = await mountPage(refusal(SEALED))
        expect(w.get('[data-testid="upstream-follow-error"]').text()).toBe(SEALED)
        expect(w.text()).not.toContain(forbiddenSentence)
        w.unmount()
    })

    it('shows a hostile upstream name as text and creates no element from it', async () => {
        const hostile = '<img src=x onerror=alert(1)>'
        const base = follow()
        const w = await mountPage(follow({ item: { ...base.item, upstreamName: hostile } }))
        expect(w.text()).toContain(hostile)
        expect(document.body.querySelector('img')).toBeNull()
        w.unmount()
    })

    it('reads again every 5 seconds while the run is open, and stops when it ends and on unmount', async () => {
        vi.useFakeTimers()
        const followQueries = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_FOLLOW).length
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_SOURCE) return sourceReply()
            const n = followQueries()
            return { data: { upstreamFollow: follow({ lastRun: run({ state: n <= 2 ? 'RUNNING' : 'COMPLETED' }) }) } }
        })
        const w = mount(Page, { global, attachTo: document.body })
        await flushPromises()
        expect(followQueries()).toBe(1)
        await vi.advanceTimersByTimeAsync(5000)
        expect(followQueries()).toBe(2)
        await vi.advanceTimersByTimeAsync(5000)
        expect(followQueries()).toBe(3)
        expect(w.get('[data-testid="upstream-run-state"]').text()).toBe('Completed')
        await vi.advanceTimersByTimeAsync(5000)
        expect(followQueries()).toBe(3)
        w.unmount()
    })

    it('reads again every 5 seconds while the run is queued', async () => {
        vi.useFakeTimers()
        const followQueries = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_FOLLOW).length
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_SOURCE) return sourceReply()
            return { data: { upstreamFollow: follow({ lastRun: run({ state: followQueries() <= 1 ? 'QUEUED' : 'COMPLETED' }) }) } }
        })
        const w = mount(Page, { global, attachTo: document.body })
        await flushPromises()
        await vi.advanceTimersByTimeAsync(5000)
        expect(followQueries()).toBe(2)
        w.unmount()
    })

    it('keeps the page when a later read is refused', async () => {
        vi.useFakeTimers()
        let reads = 0
        query.mockImplementation(async (args: any) => {
            if (args.query !== GET_UPSTREAM_FOLLOW) return sourceReply()
            if (++reads === 1) return { data: { upstreamFollow: follow({ lastRun: run({ state: 'RUNNING' }) }) } }
            throw refusal('Not authorized')
        })
        const w = mount(Page, { global, attachTo: document.body })
        await flushPromises()
        await vi.advanceTimersByTimeAsync(5000)
        expect(reads).toBe(2)
        expect(w.find('[data-testid="upstream-follow-name"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-follow-forbidden"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-follow-error"]').exists()).toBe(false)
        w.unmount()
    })

    it('sends no further read after unmount while the run is open', async () => {
        vi.useFakeTimers()
        const followQueries = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_FOLLOW).length
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_SOURCE) return sourceReply()
            return { data: { upstreamFollow: follow({ lastRun: run({ state: 'RUNNING' }) }) } }
        })
        const w = mount(Page, { global, attachTo: document.body })
        await flushPromises()
        await vi.advanceTimersByTimeAsync(5000)
        expect(followQueries()).toBe(2)
        w.unmount()
        await vi.advanceTimersByTimeAsync(20000)
        expect(followQueries()).toBe(2)
    })
})

describe('T-530 the latest version of a line is text', () => {
    const ROW = '55555555-0000-4000-8000-000000000001'
    const B1 = 'ffffffff-0000-4000-8000-000000000011'
    const B2 = 'ffffffff-0000-4000-8000-000000000012'
    const B3 = 'ffffffff-0000-4000-8000-000000000013'
    const CB = 'ffffffff-0000-4000-8000-000000000014'

    it('shows the latest version as text, links no release and selects no latestRelease', async () => {
        const base = follow()
        const w = await mountPage(follow({
            item: {
                ...base.item,
                lines: [
                    { lineKey: '1.x', branch: B1, latestRelease: ROW, latestVersion: '1.4.2' },
                    { lineKey: '2.x', branch: B2, latestVersion: '2.0.1' },
                    { lineKey: '3.x', branch: B3 },
                ],
            },
            components: [{ ...base.components[0], lines: [{ lineKey: '1.x', branch: CB, latestVersion: '0.9.0' }] }],
        }))
        const latest = w.findAll('[data-testid="upstream-follow-line-latest"]')
        expect(latest.map((c: any) => c.text())).toEqual(['1.4.2', '2.0.1', ''])
        expect(latest.every((c: any) => c.findAll('a').length === 0)).toBe(true)

        const to = links(w)
        expect(to.some(t => t.name === 'ReleaseView')).toBe(false)
        expect(to.some(t => typeof t.path === 'string' && t.path.startsWith('/release/show'))).toBe(false)
        expect(to.some(t => JSON.stringify(t).includes(ROW))).toBe(false)
        expect(document.body.innerHTML).not.toContain('/release/show')
        const lineRows = w.findAll('[data-testid="upstream-follow-line"]')
        expect(lineRows.map((r: any) => r.findAll('a.rl').map((a: any) => JSON.parse(a.attributes('data-to'))))).toEqual(
            [B1, B2, B3].map(branch => [{ name: 'ProductsOfOrg', params: { orguuid: ORG, compuuid: COMPONENT, branchuuid: branch } }]))

        expect(print(GET_UPSTREAM_FOLLOW)).not.toContain('latestRelease')
        w.unmount()
    })

    // Mutation round 3 (M-18): the cell shows latestVersion, so the follow document must still ask for it.
    it('asks for exactly lineKey, branch and latestVersion of the lines of the item and of each component', () => {
        const lineSelections: Record<string, string[]> = {}
        const parents: string[] = []
        visit(GET_UPSTREAM_FOLLOW, {
            Field: {
                enter (node) { parents.push(node.name.value) },
                leave (node) {
                    parents.pop()
                    if (node.name.value === 'lines') {
                        lineSelections[parents[parents.length - 1]] = (node.selectionSet?.selections ?? []).map((s: any) => s.name.value)
                    }
                },
            },
        })
        expect(lineSelections).toEqual({
            item: ['lineKey', 'branch', 'latestVersion'],
            components: ['lineKey', 'branch', 'latestVersion'],
        })
    })
})

// ---- TPF-230: sections 4 to 8 ----

const ADMIN = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }] } }
const READER = { installationType: 'SAAS', permissions: { permissions: [{ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_ONLY' }] } }
const ROOT_ITEM = '88888888-0000-4000-8000-000000000001'
const COMP_ITEM = '88888888-0000-4000-8000-000000000002'

function pendingFollow (over: any = {}) {
    return follow({
        pendingCount: 2,
        pending: [
            { uuid: '66666666-0000-4000-8000-000000000001', item: ROOT_ITEM, upstreamUuid: '44444444-0000-4000-8000-000000000001', upstreamVersion: '7', upstreamDate: '2026-11-02T10:00:00Z', drift: 'NUMBERING_RESTART' },
            { uuid: '66666666-0000-4000-8000-000000000002', item: COMP_ITEM, upstreamUuid: '44444444-0000-4000-8000-000000000002', upstreamVersion: '1.4.2-final', upstreamDate: '2026-11-03T10:00:00Z', drift: 'SHAPE_CHANGE' },
        ],
        ...over,
    })
}

function releaseRow (n: number, over: any = {}) {
    return {
        uuid: `55555555-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
        upstreamUuid: `44444444-0000-4000-8000-0000000001${String(n).padStart(2, '0')}`,
        upstreamVersion: `1.${n}.0`, upstreamDate: '2026-11-01T10:00:00Z', state: 'INGESTED', failure: null, message: null, integrity: 'CONSISTENT',
        presence: 'PRESENT', goneSince: null, drift: null, lineKey: '1.x',
        release: `33333333-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
        ...over,
    }
}

interface Served { follow?: () => any, runs?: any[], releases?: (variables: any) => any[] }

function serve (served: Served = {}) {
    query.mockImplementation(async (args: any) => {
        if (args.query === GET_UPSTREAM_FOLLOW) return { data: { upstreamFollow: (served.follow ?? (() => pendingFollow()))() } }
        if (args.query === GET_UPSTREAM_SOURCE) return sourceReply()
        if (args.query === GET_UPSTREAM_INGEST_RUNS) return { data: { upstreamIngestRuns: served.runs ?? [run()] } }
        if (args.query === GET_UPSTREAM_RELEASES) return { data: { upstreamReleases: served.releases ? served.releases(args.variables) : [releaseRow(1)] } }
        throw new Error('unexpected document')
    })
}

let current: any = null
// naive-ui's modal is stubbed (the UpstreamSourcesPanel.spec.ts way), so a dialog's content is part of the page while it is shown.
const withModal = { stubs: { ...global.stubs, Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

async function mountServed (user: any, served: Served = {}) {
    getters.myuser = user
    serve(served)
    const w = mount(Page, { global: withModal, attachTo: document.body })
    current = w
    await flushPromises()
    return w
}

/** The element of the mounted page (its dialogs included) with this data-testid, or null. */
const inBody = (id: string): HTMLElement | null => {
    const found = current?.find(`[data-testid="${id}"]`)
    return found?.exists() ? found.element as HTMLElement : null
}
const calls = (fn: any, doc: any, key: 'query' | 'mutation') => fn.mock.calls.filter((c: any) => c[0][key] === doc)
const byTestId = (w: any, component: any, id: string) => w.findAllComponents(component).find((c: any) => c.attributes('data-testid') === id)

async function click (el: HTMLElement | null) {
    expect(el).not.toBeNull()
    el!.click()
    await flushPromises()
}

const CONTROLS = ['upstream-sync-now', 'upstream-reconfigure', 'upstream-unfollow']
const SECTIONS = ['upstream-follow-header', 'upstream-follow-lines', 'upstream-follow-last-run', 'upstream-follow-runs', 'upstream-follow-releases', 'upstream-follow-pending']

describe('T-541 the follow page, sections 4 to 8, by role', () => {
    it('shows a reader every section and none of the three controls, and mounting sends no mutation', async () => {
        const w = await mountServed(READER)
        for (const id of SECTIONS) expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(true)
        for (const id of CONTROLS) expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        expect(w.findComponent(ReconfigureModal).exists()).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('shows an administrator the three controls, the pending sentence and the words of each drift kind', async () => {
        const w = await mountServed(ADMIN)
        for (const id of CONTROLS) expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(true)
        const alert = w.get('[data-testid="upstream-pending-alert"]').text()
        expect(alert).toContain('2 releases are held because they do not fit the version lines.')
        expect(alert).toContain('Nothing else is held: releases that fit and documents of releases already ingested keep coming in.')
        const rows = w.findAll('[data-testid="upstream-pending-row"]')
        expect(rows).toHaveLength(2)
        expect(rows[0].text()).toContain('Widget Suite')
        expect(rows[0].get('[data-testid="upstream-pending-drift"]').text()).toBe(driftKindLabel.NUMBERING_RESTART)
        expect(rows[1].text()).toContain('Widget Core')
        expect(rows[1].get('[data-testid="upstream-pending-drift"]').text()).toBe(driftKindLabel.SHAPE_CHANGE)
        expect(w.text()).not.toContain('NUMBERING_RESTART')
        expect(w.text()).not.toContain('SHAPE_CHANGE')
        expect(w.find('[data-testid="upstream-pending-truncated"]').exists()).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('shows an administrator none of the three controls on an unfollowed follow', async () => {
        const w = await mountServed(ADMIN, { follow: () => pendingFollow({ status: 'UNFOLLOWED', unfollowedDate: '2026-11-10T10:00:00Z' }) })
        for (const id of CONTROLS) expect(w.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
        expect(w.text()).toContain('Unfollowed on')
        expect(w.find('[data-testid="upstream-follow-pending"]').exists()).toBe(true)
        w.unmount()
    })

    it('has no pending section, and so no Reconfigure, when nothing is pending', async () => {
        const w = await mountServed(ADMIN, { follow: () => follow() })
        expect(w.find('[data-testid="upstream-follow-pending"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-reconfigure"]').exists()).toBe(false)
        expect(w.find('[data-testid="upstream-sync-now"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-unfollow"]').exists()).toBe(true)
        w.unmount()
    })

    it('opens the unfollow dialog with the statement and sends the unfollow only from its positive button', async () => {
        mutationsExpected = true
        const w = await mountServed(ADMIN)
        await click(inBody('upstream-unfollow'))
        expect(inBody('upstream-unfollow-statement')?.textContent).toBe(unfollowStatement('Widget Suite'))
        expect(mutate).not.toHaveBeenCalled()

        await click(inBody('upstream-unfollow-cancel'))
        expect(inBody('upstream-unfollow-dialog')).toBeNull()
        expect(mutate).not.toHaveBeenCalled()

        const followReads = calls(query, GET_UPSTREAM_FOLLOW, 'query').length
        mutate.mockResolvedValueOnce({ data: { unfollowUpstreamItem: { uuid: FOLLOW } } })
        await click(inBody('upstream-unfollow'))
        await click(inBody('upstream-unfollow-confirm'))
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(UNFOLLOW_UPSTREAM_ITEM)
        expect(mutate.mock.calls[0][0].variables).toEqual({ follow: FOLLOW })
        expect(inBody('upstream-unfollow-dialog')).toBeNull()
        expect(calls(query, GET_UPSTREAM_FOLLOW, 'query').length).toBe(followReads + 1)
        w.unmount()
    })

    it('keeps the unfollow dialog open with the refusal sentence', async () => {
        mutationsExpected = true
        const w = await mountServed(ADMIN)
        mutate.mockRejectedValueOnce(refusal('The follow is not active.'))
        await click(inBody('upstream-unfollow'))
        await click(inBody('upstream-unfollow-confirm'))
        expect(inBody('upstream-unfollow-dialog')).not.toBeNull()
        expect(inBody('upstream-unfollow-error')?.textContent).toBe('The follow is not active.')
        w.unmount()
    })

    it('shows the unfollow statement with a hostile name as text', async () => {
        const hostile = '<img src=x onerror=alert(1)>'
        const base = pendingFollow()
        const w = await mountServed(ADMIN, { follow: () => ({ ...base, item: { ...base.item, upstreamName: hostile } }) })
        await click(inBody('upstream-unfollow'))
        expect(inBody('upstream-unfollow-statement')?.textContent).toBe(unfollowStatement(hostile))
        expect(document.body.querySelector('img')).toBeNull()
        w.unmount()
    })
})

describe('TPF-230 sync now and the run history', () => {
    it('starts a run of the follow and reads the follow again', async () => {
        mutationsExpected = true
        const w = await mountServed(ADMIN)
        const followReads = calls(query, GET_UPSTREAM_FOLLOW, 'query').length
        mutate.mockResolvedValueOnce({ data: { startUpstreamFollowRun: { uuid: FOLLOW } } })
        await click(inBody('upstream-sync-now'))
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(START_UPSTREAM_FOLLOW_RUN)
        expect(mutate.mock.calls[0][0].variables).toEqual({ follow: FOLLOW })
        expect(calls(query, GET_UPSTREAM_FOLLOW, 'query').length).toBe(followReads + 1)
        w.unmount()
    })

    it('shows the sentence of a refused start', async () => {
        mutationsExpected = true
        const w = await mountServed(ADMIN)
        const err: any = new Error('x')
        err.errors = [{ message: 'The follow has a run that has not ended.', extensions: { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'RUN_IN_PROGRESS' } }]
        mutate.mockRejectedValueOnce(err)
        await click(inBody('upstream-sync-now'))
        expect(w.get('[data-testid="upstream-sync-now-error"]').text()).toBe('The follow has a run that has not ended.')
        w.unmount()
    })

    it('lists the newest runs with their state, their sentence and their counters on demand', async () => {
        const runs = [
            run({ uuid: '77777777-0000-4000-8000-000000000002', kind: 'SYNC', origin: 'MANUAL', state: 'STOPPED', message: null, stopReason: 'ITEM_GONE' }),
            run({ counters: counters({ filesStored: 17 }) }),
        ]
        const w = await mountServed(READER, { runs })
        expect(calls(query, GET_UPSTREAM_INGEST_RUNS, 'query')[0][0].variables).toEqual({ follow: FOLLOW })
        expect(w.text()).toContain('The newest 20 runs.')
        const rows = w.findAll('[data-testid="upstream-run-row"]')
        expect(rows).toHaveLength(2)
        expect(rows[0].get('[data-testid="upstream-run-row-state"]').text()).toBe('Stopped')
        expect(rows[0].get('[data-testid="upstream-run-row-message"]').text()).toBe('The source no longer knows this item.')
        expect(rows[1].get('[data-testid="upstream-run-row-state"]').text()).toBe('Completed')
        expect(rows[1].text()).toContain('12 releases in scope, 10 created, 2 already ingested, 0 failed, 1 held')
        expect(w.findAll('[data-testid="upstream-run-row-counters"]')).toHaveLength(0)
        await rows[1].get('[data-testid="upstream-run-row-toggle"]').trigger('click')
        const expanded = w.findAll('[data-testid="upstream-run-row-counters"]')
        expect(expanded).toHaveLength(1)
        expect(expanded[0].text()).toContain('Files stored: 17')
        w.unmount()
    })

    it('shows the refusal of the runs read in place of the table and keeps the page', async () => {
        getters.myuser = READER
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_FOLLOW) return { data: { upstreamFollow: follow() } }
            if (args.query === GET_UPSTREAM_INGEST_RUNS) throw refusal(SEALED)
            if (args.query === GET_UPSTREAM_RELEASES) return { data: { upstreamReleases: [] } }
            return sourceReply()
        })
        const w = mount(Page, { global, attachTo: document.body })
        await flushPromises()
        expect(w.get('[data-testid="upstream-runs-error"]').text()).toBe(SEALED)
        expect(w.find('[data-testid="upstream-follow-name"]').exists()).toBe(true)
        w.unmount()
    })

    it('reads the runs and the releases again when the last run changes, and not on an unchanged poll', async () => {
        vi.useFakeTimers()
        let reads = 0
        const states = ['RUNNING', 'RUNNING', 'COMPLETED']
        const w = await mountServed(READER, { follow: () => follow({ lastRun: run({ state: states[Math.min(reads++, 2)] }) }) })
        expect(calls(query, GET_UPSTREAM_INGEST_RUNS, 'query')).toHaveLength(1)
        expect(calls(query, GET_UPSTREAM_RELEASES, 'query')).toHaveLength(1)
        await vi.advanceTimersByTimeAsync(5000)
        expect(calls(query, GET_UPSTREAM_INGEST_RUNS, 'query')).toHaveLength(1)
        await vi.advanceTimersByTimeAsync(5000)
        expect(calls(query, GET_UPSTREAM_INGEST_RUNS, 'query')).toHaveLength(2)
        expect(calls(query, GET_UPSTREAM_RELEASES, 'query')).toHaveLength(2)
        w.unmount()
    })
})

describe('TPF-230 the releases of the item', () => {
    it('reads the root item first, links a ReARM release by its release field only, and words each row', async () => {
        const rows = [
            releaseRow(1),
            releaseRow(2, { state: 'FAILED', message: 'The supplier answered with an unreadable collection.', release: null }),
            releaseRow(3, { presence: 'GONE', goneSince: '2026-11-05T10:00:00Z' }),
            releaseRow(4, { upstreamVersion: null, state: 'FAILED', failure: 'VERSION_UNUSABLE', message: 'No usable version.', release: null }),
            releaseRow(5, { release: '../x' }),
        ]
        const w = await mountServed(READER, { releases: () => rows })
        const first = calls(query, GET_UPSTREAM_RELEASES, 'query')[0][0].variables
        expect(first).toEqual({ item: ROOT_ITEM, states: null, after: null })
        const shown = w.findAll('[data-testid="upstream-release-row"]')
        expect(shown).toHaveLength(5)
        expect(shown[0].get('[data-testid="upstream-release-state"]').text()).toBe('Ingested')
        expect(shown[1].get('[data-testid="upstream-release-message"]').text()).toBe('The supplier answered with an unreadable collection.')
        expect(shown[2].get('[data-testid="upstream-release-gone"]').text()).toContain('No longer on the source since')
        expect(shown[3].get('[data-testid="upstream-release-version"]').text()).toBe('no usable version')

        const releaseLinks = shown.map((r: any) => r.findAll('[data-testid="upstream-release-link"]').map((a: any) => JSON.parse(a.attributes('data-to'))))
        expect(releaseLinks).toEqual([
            [{ name: 'ReleaseView', params: { uuid: rows[0].release } }],
            [],
            [{ name: 'ReleaseView', params: { uuid: rows[2].release } }],
            [],
            [],
        ])
        const all = JSON.stringify(links(w))
        for (const r of rows) {
            expect(all).not.toContain(r.uuid)
            expect(all).not.toContain(r.upstreamUuid)
        }
        w.unmount()
    })

    it('loads more with the number of rows already loaded as the cursor, while a page is full', async () => {
        const page = (from: number, n: number) => Array.from({ length: n }, (_, i) => releaseRow(from + i))
        const w = await mountServed(READER, { releases: (v: any) => v.after === null ? page(1, 50) : page(51, 3) })
        expect(w.findAll('[data-testid="upstream-release-row"]')).toHaveLength(50)
        await w.get('[data-testid="upstream-releases-more"]').trigger('click')
        await flushPromises()
        const sent = calls(query, GET_UPSTREAM_RELEASES, 'query').map((c: any) => c[0].variables)
        expect(sent).toEqual([{ item: ROOT_ITEM, states: null, after: null }, { item: ROOT_ITEM, states: null, after: '50' }])
        expect(w.findAll('[data-testid="upstream-release-row"]')).toHaveLength(53)
        expect(w.find('[data-testid="upstream-releases-more"]').exists()).toBe(false)
        w.unmount()
    })

    it('reads again from the start for another state filter and for a component', async () => {
        const w = await mountServed(READER)
        const states = byTestId(w, NSelect, 'upstream-releases-states')
        states.vm.$emit('update:value', ['FAILED', 'HELD'])
        await flushPromises()
        const item = byTestId(w, NSelect, 'upstream-releases-item')
        expect((item.props('options') as any[]).map(o => o.value)).toEqual([ROOT_ITEM, COMP_ITEM])
        item.vm.$emit('update:value', COMP_ITEM)
        await flushPromises()
        const sent = calls(query, GET_UPSTREAM_RELEASES, 'query').map((c: any) => c[0].variables)
        expect(sent).toEqual([
            { item: ROOT_ITEM, states: null, after: null },
            { item: ROOT_ITEM, states: ['FAILED', 'HELD'], after: null },
            { item: COMP_ITEM, states: ['FAILED', 'HELD'], after: null },
        ])
        w.unmount()
    })

    it('shows a hostile version and a hostile pending item name as text', async () => {
        const hostile = '<img src=x onerror=alert(1)>'
        const base = pendingFollow()
        const w = await mountServed(READER, {
            follow: () => ({ ...base, components: [{ ...base.components[0], upstreamName: hostile }] }),
            releases: () => [releaseRow(1, { upstreamVersion: hostile, message: hostile, state: 'FAILED' })],
        })
        expect(w.findAll('[data-testid="upstream-pending-row"]')[1].text()).toContain(hostile)
        expect(w.get('[data-testid="upstream-release-version"]').text()).toBe(hostile)
        expect(document.body.querySelector('img')).toBeNull()
        w.unmount()
    })
})

describe('TPF-230 the pending releases and the reconfigure dialog', () => {
    it('says that the oldest 50 are listed when more are held than served', async () => {
        const w = await mountServed(READER, { follow: () => pendingFollow({ pendingCount: 60 }) })
        expect(w.get('[data-testid="upstream-pending-truncated"]').text()).toBe('The oldest 50 are listed.')
        expect(w.get('[data-testid="upstream-pending-alert"]').text()).toContain('60 releases are held')
        w.unmount()
    })

    it('opens the reconfigure dialog with the follow, and reads the follow again when it was saved', async () => {
        const w = await mountServed(ADMIN)
        const modal = w.findComponent(ReconfigureModal)
        expect(modal.props('show')).toBe(false)
        await w.get('[data-testid="upstream-reconfigure"]').trigger('click')
        expect(modal.props('show')).toBe(true)
        expect(modal.props('follow').uuid).toBe(FOLLOW)
        const followReads = calls(query, GET_UPSTREAM_FOLLOW, 'query').length
        modal.vm.$emit('saved', FOLLOW)
        await flushPromises()
        expect(calls(query, GET_UPSTREAM_FOLLOW, 'query').length).toBe(followReads + 1)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

describe('the follow page, way back and explanations', () => {
    it('links back to the Follows tab of the organization\'s Upstream page', async () => {
        const w = await mountPage(follow())
        const back = w.get('[data-testid="upstream-follow-back"]')
        expect(back.text()).toBe('\u2190 Follows')
        expect(JSON.parse(back.attributes('data-to') as string)).toEqual({ name: 'UpstreamOfOrg', params: { orguuid: ORG }, query: { tab: 'follows' } })
        w.unmount()
    })

    it('lists every counter of a run with its explanation, the bytes in megabytes with the exact count on hover', async () => {
        const runs = [run({ counters: counters({ bytes: 1294967296, filesStored: 17 }) })]
        const w = await mountServed(READER, { runs })
        await w.get('[data-testid="upstream-run-row-toggle"]').trigger('click')
        const rows = w.findAll('[data-testid^="upstream-run-row-counter-"]')
        expect(rows.map(r => r.attributes('data-testid'))).toEqual(RUN_COUNTERS.map(c => 'upstream-run-row-counter-' + c.key))
        for (const r of rows) expect(r.find('.n-icon').exists(), r.attributes('data-testid')).toBe(true)
        const bytes = w.get('[data-testid="upstream-run-row-counter-bytes"]')
        expect(bytes.text()).toBe('Bytes downloaded: 1,235.0 MB')
        expect(bytes.get('span[title]').attributes('title')).toBe('1,294,967,296 bytes')
        expect(w.get('[data-testid="upstream-run-row-counter-filesStored"]').text()).toBe('Files stored: 17')
        w.unmount()
    })

    it('puts an info icon on the Version lines title and on the Depth, Lines and Line headers', async () => {
        const w = await mountPage(follow())
        expect(w.get('[data-testid="upstream-follow-lines"] h5').find('.n-icon').exists()).toBe(true)
        const componentHeaders = w.findAll('th').filter(th => ['Depth', 'Lines'].includes(th.text()))
        expect(componentHeaders.map(th => th.text()).sort()).toEqual(['Depth', 'Lines'])
        for (const th of componentHeaders) expect(th.find('.n-icon').exists(), th.text()).toBe(true)
        w.unmount()
    })

    it('puts an info icon on the Line header of the releases', async () => {
        const w = await mountServed(READER)
        const line = w.get('[data-testid="upstream-follow-releases"]').findAll('th').find(th => th.text() === 'Line')
        expect(line, 'the releases table has a Line header').toBeTruthy()
        expect(line!.find('.n-icon').exists()).toBe(true)
        w.unmount()
    })
})

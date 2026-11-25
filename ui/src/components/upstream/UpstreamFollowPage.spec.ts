// @vitest-environment happy-dom
//
// TPF-229 (TPF-185 design 3.9 sections 1 to 3, and T-530): the read-only follow page. The header, the version
// lines with their branch links and their latest version as text, the components table and the last run are shown from the
// follow; an unfollowed follow, a no-longer-updated item, a stopped run, a refused source read
// and a refused first read each have their own words; every value is text; the page reads again
// every 5 seconds while the run is open and never sends a mutation.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { print } from 'graphql'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

const getters: any = { myuser: null }
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))

const route: any = { params: {}, query: {} }
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Page } = await import('./UpstreamFollowPage.vue')
const { GET_UPSTREAM_FOLLOW } = await import('@/utils/upstreamFollowQueries')
const { GET_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { forbiddenSentence } = await import('@/utils/upstreamLabels')

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
    getters.myuser = { installationType: 'SAAS', permissions: { permissions: [] } }
    route.params = { orguuid: ORG, uuid: FOLLOW }
    route.query = {}
})

afterEach(() => {
    expect(mutate).not.toHaveBeenCalled()
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
            if (args.query === GET_UPSTREAM_SOURCE) return sourceReply()
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
})

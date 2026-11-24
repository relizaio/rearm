// @vitest-environment happy-dom
//
// The Statistics page (task TPF-140): a backend that serves a counted_at key dates the four large
// totals (releases, artifacts, commits, deliverables) with one "as of" note and serves null while a
// count is pending; a backend without the key is rendered exactly as today. The GraphQL client is
// mocked, commonFunctions is real, and the source pins read the component file.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a) } }))
vi.mock('vuex', () => ({ useStore: () => ({ getters: { myorg: { uuid: 'O', name: 'Org' } } }) }))

const { default: AnalyticsOfOrg } = await import('./AnalyticsOfOrg.vue')

const byId = (w: any, id: string) => w.find(`[data-testid="${id}"]`)
const fmt = (iso: string) => new Date(iso).toLocaleString('en-CA')
const SENTENCE = 'Releases, artifacts, commits and deliverables are counted nightly; the counts shown are as of '

const LIVE = Object.freeze({
    components: 312, products: 48, vcs: 57, branches: 1021, feature_sets: 73,
    admin_and_write_users: 14, read_only_users: 3
})
const FULL = Object.freeze({
    ...LIVE, releases: 104233, artifacts: 512904, commits: 201118, deliverables: 49877,
    counted_at: '2026-11-02T00:00:03.512417Z'
})
const PENDING = Object.freeze({
    ...LIVE, releases: null, artifacts: null, commits: null, deliverables: null, counted_at: null
})
const MIXED = Object.freeze({
    ...LIVE, releases: null, artifacts: 8120, commits: 3301, deliverables: 97,
    counted_at: '2026-11-02T00:00:02.980Z'
})
const LEGACY = Object.freeze({
    ...LIVE, releases: 104233, artifacts: 512904, commits: 201118, deliverables: 49877
})
const LABELS = ['Admin & Write Users', 'Artifacts', 'Branches', 'Commits', 'Components',
    'Deliverables', 'Feature sets', 'Products', 'Read-Only Users', 'Releases', 'VCS Repositories']
const CACHED = ['releases', 'artifacts', 'commits', 'deliverables']

const mounted: any[] = []
async function mountWith (map: any) {
    query.mockResolvedValueOnce({ data: { totalsAnalytics: map } })
    const w = mount(AnalyticsOfOrg)
    mounted.push(w)
    await flushPromises()
    return w
}

const labels = (w: any) => w.findAll('tbody tr td:first-child').map((td: any) => td.text())

function expectLiveCells (w: any) {
    expect(byId(w, 'totals-value-components').text()).toBe('312')
    expect(byId(w, 'totals-value-products').text()).toBe('48')
    expect(byId(w, 'totals-value-vcs').text()).toBe('57')
    expect(byId(w, 'totals-value-branches').text()).toBe('1021')
    expect(byId(w, 'totals-value-feature_sets').text()).toBe('73')
    expect(byId(w, 'totals-value-admin_and_write_users').text()).toBe('14')
    expect(byId(w, 'totals-value-read_only_users').text()).toBe('3')
}

function expectFullCachedCells (w: any) {
    expect(byId(w, 'totals-value-releases').text()).toBe('104233')
    expect(byId(w, 'totals-value-artifacts').text()).toBe('512904')
    expect(byId(w, 'totals-value-commits').text()).toBe('201118')
    expect(byId(w, 'totals-value-deliverables').text()).toBe('49877')
}

describe('AnalyticsOfOrg cached totals', () => {
    let errorSpy: any

    beforeEach(() => {
        query.mockReset()
        errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    })

    afterEach(() => {
        while (mounted.length) mounted.pop().unmount()
        errorSpy.mockRestore()
    })

    it('row 1: full map renders the numbers, one note and the live cells unchanged', async () => {
        const w = await mountWith(FULL)
        // (a) the document and its arguments are unchanged
        expect(query).toHaveBeenCalledTimes(1)
        const arg = query.mock.calls[0][0]
        expect(arg.variables).toEqual({ orgUuid: 'O' })
        expect(arg.fetchPolicy).toBe('no-cache')
        expect(arg.query.loc.source.body).toContain('totalsAnalytics(orgUuid: $orgUuid)')
        expect(arg.query.loc.source.body).not.toContain('counted_at')
        // (b) eleven rows, no Counted at
        expect(labels(w)).toEqual(LABELS)
        // (c) the four cached numbers
        expectFullCachedCells(w)
        // (d) the live cells
        expectLiveCells(w)
        // (e) nothing pending
        expect(w.findAll('[data-testid^="totals-pending-"]')).toHaveLength(0)
        // (f) one note, nothing after the date
        const note = byId(w, 'totals-counted-at-note').text()
        expect(note).toBe(SENTENCE + fmt('2026-11-02T00:00:03.512417Z'))
        expect(note).not.toContain('..')
        expect(w.text()).not.toContain('2026-11-02T00:00:03')
        expect(w.text()).not.toContain('Invalid Date')
        expect(w.text()).not.toContain('Counted at')
        // (g) the table and no loading text
        expect(byId(w, 'totals-table').exists()).toBe(true)
        expect(byId(w, 'totals-loading').exists()).toBe(false)
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('row 2: pending map renders four pending cells and no note', async () => {
        const w = await mountWith(PENDING)
        expect(labels(w)).toEqual(LABELS)
        for (const key of CACHED) {
            expect(byId(w, 'totals-pending-' + key).text()).toBe('pending, counted tonight')
            expect(byId(w, 'totals-value-' + key).exists()).toBe(false)
        }
        expect(byId(w, 'totals-counted-at-note').exists()).toBe(false)
        expect(w.text()).not.toContain('as of')
        expectLiveCells(w)
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('row 3: mixed map renders one pending cell, three numbers and the note', async () => {
        const w = await mountWith(MIXED)
        expect(byId(w, 'totals-pending-releases').text()).toBe('pending, counted tonight')
        expect(byId(w, 'totals-value-releases').exists()).toBe(false)
        expect(byId(w, 'totals-value-artifacts').text()).toBe('8120')
        expect(byId(w, 'totals-value-commits').text()).toBe('3301')
        expect(byId(w, 'totals-value-deliverables').text()).toBe('97')
        expect(byId(w, 'totals-pending-artifacts').exists()).toBe(false)
        expect(byId(w, 'totals-pending-commits').exists()).toBe(false)
        expect(byId(w, 'totals-pending-deliverables').exists()).toBe(false)
        expect(w.findAll('[data-testid^="totals-pending-"]')).toHaveLength(1)
        const note = byId(w, 'totals-counted-at-note').text()
        expect(note).toBe(SENTENCE + fmt('2026-11-02T00:00:02.980Z'))
        expect(note).not.toContain('..')
        expectLiveCells(w)
        expect(labels(w)).toEqual(LABELS)
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('row 4: a map without counted_at renders as today, no note, no pending', async () => {
        expect(Object.keys(LEGACY)).toHaveLength(11)
        const w = await mountWith(LEGACY)
        expect(labels(w)).toEqual(LABELS)
        expect(w.findAll('tbody tr td:nth-child(2)').map((td: any) => td.text())).toEqual(
            ['14', '512904', '1021', '201118', '312', '49877', '73', '48', '3', '104233', '57'])
        expect(w.findAll('[data-testid^="totals-pending-"]')).toHaveLength(0)
        expect(byId(w, 'totals-counted-at-note').exists()).toBe(false)
        expect(w.text()).not.toContain('as of')
        expect(w.text()).not.toContain('pending')
        expect(byId(w, 'totals-value-releases').text()).toBe('104233')
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('row 5: a legacy map with a null renders an empty cell, never pending', async () => {
        const w = await mountWith({ ...LEGACY, commits: null })
        expect(byId(w, 'totals-value-commits').text()).toBe('')
        expect(byId(w, 'totals-pending-commits').exists()).toBe(false)
        expect(byId(w, 'totals-counted-at-note').exists()).toBe(false)
        expect(labels(w)).toHaveLength(11)
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('row 6: an unparseable counted_at is shown raw, an empty one hides the note', async () => {
        const w = await mountWith({ ...FULL, counted_at: 'last night' })
        expect(byId(w, 'totals-counted-at-note').text()).toBe(SENTENCE + 'last night')
        expectFullCachedCells(w)
        expect(w.text()).not.toContain('Invalid Date')

        const e = await mountWith({ ...FULL, counted_at: '' })
        expect(byId(e, 'totals-counted-at-note').exists()).toBe(false)
        expectFullCachedCells(e)
        expect(e.findAll('[data-testid^="totals-pending-"]')).toHaveLength(0)
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('row 7: a failed query logs once and renders no rows and no note', async () => {
        query.mockRejectedValueOnce(new Error('boom'))
        const w = mount(AnalyticsOfOrg)
        mounted.push(w)
        await flushPromises()
        expect(errorSpy).toHaveBeenCalledTimes(1)
        expect(errorSpy.mock.calls[0][0]).toBe('Error fetching totals analytics:')
        expect(w.findAll('tbody tr')).toHaveLength(0)
        expect(byId(w, 'totals-counted-at-note').exists()).toBe(false)
        expect(byId(w, 'totals-loading').exists()).toBe(false)
    })

    it('row 10: 0, 3 and 6 fractional digits render the same note', async () => {
        const isos = ['2026-11-01T00:15:04.810723Z', '2026-11-01T00:15:04.810Z', '2026-11-01T00:15:04Z']
        for (const iso of isos) {
            expect(Number.isNaN(Date.parse(iso))).toBe(false)
        }
        for (const iso of isos) {
            const w = await mountWith({ ...FULL, counted_at: iso })
            const note = byId(w, 'totals-counted-at-note').text()
            expect(note).toBe(SENTENCE + fmt(iso))
            expect(note).not.toContain('..')
            expect(fmt(iso)).toBe(fmt('2026-11-01T00:15:04.810Z'))
            expect(w.text()).not.toContain('Invalid Date')
            expect(w.text()).not.toContain(iso)
            expectFullCachedCells(w)
        }
        expect(errorSpy).not.toHaveBeenCalled()
    })
})

// Task TPF-166 D3: the third user figure, members whose effective permission in this organization is NONE.
const NO_ROLE = Object.freeze({ ...FULL, users_without_role: 2 })
const NO_ROLE_LABELS = ['Admin & Write Users', 'Artifacts', 'Branches', 'Commits', 'Components',
    'Deliverables', 'Feature sets', 'Products', 'Read-Only Users', 'Releases', 'Users Without a Role',
    'VCS Repositories']

describe('AnalyticsOfOrg users without a role', () => {
    let errorSpy: any

    beforeEach(() => {
        query.mockReset()
        errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    })

    afterEach(() => {
        while (mounted.length) mounted.pop().unmount()
        errorSpy.mockRestore()
    })

    it('U1: the key renders one labelled row in alphabetical order, everything else as before', async () => {
        const w = await mountWith(NO_ROLE)
        expect(labels(w)).toEqual(NO_ROLE_LABELS)
        expect(byId(w, 'totals-value-users_without_role').text()).toBe('2')
        expect(w.text()).not.toContain('Inactive')
        expect(w.text()).not.toContain('Users without role')
        expectLiveCells(w)
        expectFullCachedCells(w)
        expect(byId(w, 'totals-counted-at-note').text()).toBe(SENTENCE + fmt('2026-11-02T00:00:03.512417Z'))
        expect(errorSpy).not.toHaveBeenCalled()
    })

    it('U1: a zero renders as 0, not as an empty cell', async () => {
        const w = await mountWith({ ...NO_ROLE, users_without_role: 0 })
        expect(byId(w, 'totals-value-users_without_role').text()).toBe('0')
    })

    it('U2: a backend without the key (LEGACY, LIVE) renders no such row and no error', async () => {
        for (const map of [LEGACY, LIVE]) {
            const w = await mountWith(map)
            expect(labels(w)).not.toContain('Users Without a Role')
            expect(byId(w, 'totals-value-users_without_role').exists()).toBe(false)
            expectLiveCells(w)
        }
        const legacy = mounted[0]
        expect(labels(legacy)).toEqual(LABELS)
        expect(errorSpy).not.toHaveBeenCalled()
    })
})

describe('AnalyticsOfOrg source pins', () => {
    const source = readFileSync(resolve(__dirname, 'AnalyticsOfOrg.vue'), 'utf8')
    const count = (needle: string) => source.split(needle).length - 1

    it('row 8 (a): the formatter is the shared commonFunctions.dateDisplay', () => {
        expect(count("import commonFunctions from '@/utils/commonFunctions'")).toBe(1)
        expect(source).toContain('commonFunctions.dateDisplay(')
    })

    it('row 8 (b): no v-html', () => {
        expect(source).not.toContain('v-html')
    })

    it('row 8 (c): the document selects the scalar once, no selection set', () => {
        expect(count('totalsAnalytics(orgUuid: $orgUuid)')).toBe(1)
        expect(source).not.toContain('totalsAnalytics(orgUuid: $orgUuid) {')
    })

    it('row 8 (d): no recount or refresh control', () => {
        expect(source).not.toMatch(/recount|refresh/i)
    })

    it('row 8 (e): the pending text and the counted_at key are each spelled once', () => {
        expect(count("'pending, counted tonight'")).toBe(1)
        expect(count("'counted_at'")).toBe(1)
    })

    it('row 8 (f): the testids', () => {
        expect(source).toContain('data-testid="totals-table"')
        expect(source).toContain('data-testid="totals-counted-at-note"')
        expect(source).toContain("'totals-pending-'")
        expect(source).toContain("'totals-value-'")
    })

    it('row 8 (g): nothing follows the date in the note', () => {
        expect(count('the counts shown are as of {{ countedAtText }}\n')).toBe(1)
        expect(source).not.toContain('}}.')
    })
})

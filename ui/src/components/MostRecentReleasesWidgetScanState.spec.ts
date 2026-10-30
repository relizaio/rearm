// @vitest-environment happy-dom
//
// RD4-11: a board's document round shows no scan state in the most-recent-releases list -- no pending
// badge and no vulnerability or policy circles -- while a software release keeps both.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

// By kind, with the metrics a round stored before RD4-11 (the no-BOM anchor), as a server without the
// NOT_APPLICABLE answer serves them: the kind alone must hide them.
const DOC = { uuid: 'r-doc', version: '3', lifecycle: 'ASSEMBLED', createdDate: '2026-09-29T10:00:00Z',
    componentDetails: { uuid: 'c-doc', name: 'RD4 architecture', type: 'COMPONENT', kind: 'BOARD_DOCUMENT' },
    branchDetails: { uuid: 'b-doc', name: 'main' }, artifactDetails: [],
    metrics: { firstScanned: '2026-09-29T10:00:00Z', lastScanned: '2026-09-29T10:01:00Z', critical: 0 } }
// By the served answer: a legacy board's GENERIC document component, still a DRAFT round.
const LEGACY_DOC = { ...DOC, uuid: 'r-legacy', lifecycle: 'DRAFT',
    componentDetails: { ...DOC.componentDetails, uuid: 'c-legacy', kind: 'GENERIC' },
    metrics: { dtrackFetchStatus: 'NOT_APPLICABLE', firstScanned: null, lastScanned: null, critical: 0 } }
const PENDING = { uuid: 'r-sw', version: '1.0.0', lifecycle: 'ASSEMBLED', createdDate: '2026-09-29T10:00:00Z',
    componentDetails: { uuid: 'c-sw', name: 'lib', type: 'COMPONENT', kind: 'GENERIC' },
    branchDetails: { uuid: 'b-sw', name: 'main' }, artifactDetails: [], metrics: { firstScanned: null } }
const SCANNED = { ...PENDING, uuid: 'r-sw2', metrics: { firstScanned: '2026-09-29T10:05:00Z', lastScanned: '2026-09-29T10:05:00Z',
    critical: 2, high: 0, medium: 0, low: 0, unassigned: 0, policyViolationsLicenseTotal: 0,
    policyViolationsSecurityTotal: 0, policyViolationsOperationalTotal: 0 } }

const query = vi.fn(async (_: any) => ({ data: { releasesByDateRange: [DOC, LEGACY_DOC, PENDING, SCANNED] } }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q) } }))
const { state, commit } = vi.hoisted(() => ({ state: { artifactScanStateSupported: true }, commit: vi.fn() }))
vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {}, state, commit }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ error: vi.fn() }) }))
vi.mock('@/utils/releaseVulnerabilityService', () => ({ ReleaseVulnerabilityService: {} }))
vi.mock('./VulnerabilityModal.vue', () => ({ default: { name: 'VulnerabilityModal', render: () => null } }))
const { default: Widget } = await import('./MostRecentReleasesWidget.vue')

const stubs = { 'router-link': { template: '<a><slot/></a>' } }

async function rows () {
    const w = mount(Widget, { props: { orgUuid: 'o1', perspectiveUuid: 'default' }, global: { stubs } })
    await flushPromises()
    return w.findAll('li')
}

describe('MostRecentReleasesWidget scan state', () => {
    it('shows no badge and no circles on a document round, by kind or by the served NOT_APPLICABLE', async () => {
        const [doc, legacy] = await rows()
        for (const row of [doc, legacy]) {
            expect(row.text()).not.toContain('pending')
            expect(row.findAll('span[title]').filter(s => s.attributes('style')?.includes('border-radius'))).toHaveLength(0)
            expect(row.findAll('.circle')).toHaveLength(0)
        }
    })

    it('keeps the pending badge and the circles on software releases', async () => {
        const [, , pending, scanned] = await rows()
        expect(pending.text()).toContain('Scan pending')
        expect(pending.findAll('span[title]').filter(s => s.attributes('style')?.includes('border-radius'))).toHaveLength(1)
        expect(scanned.findAll('.circle')).toHaveLength(8)
    })

    it('the release reads select what the check reads: the component kind and the served fetch status', async () => {
        const { default: GqlQueries } = await import('@/utils/graphqlQueries')
        for (const doc of [GqlQueries.ReleasesByDateRangeGql, GqlQueries.ReleasesByDateRangeAndPerspectiveGql,
            GqlQueries.LatestReleasesOfComponentGql]) {
            const text = doc.loc.source.body
            expect(text).toMatch(/componentDetails \{[^}]*\bkind\b/)
            expect(text).toMatch(/metrics \{\s*dtrackFetchStatus/)
        }
        expect(GqlQueries.BranchReleaseListGqlData).toMatch(/metrics \{\s*dtrackFetchStatus/)
    })
})

// TPF-127 (architecture row 6): the widget sends its documents through the latched fallback and draws the
// grey note beside the circles for the own BOMs that carry the not-scannable marker.
const { default: Queries } = await import('@/utils/graphqlQueries')
const { reasonLabel } = await import('@/utils/artifactScanStatus')
describe('MostRecentReleasesWidget not-scannable state (TPF-127)', () => {
    const GqlQueries = Queries
    const M1 = { reason: 'ONLY_ROOT', at: '2026-10-29T08:00:00Z', components: 1, root: 1, terminal: 0, unmatchable: 0 }
    const M2 = { reason: 'NO_COMPONENTS', at: '2026-10-29T08:00:00Z', components: 0, root: 0, terminal: 0, unmatchable: 0 }
    const ZERO = { critical: 0, high: 0, medium: 0, low: 0, unassigned: 0, policyViolationsLicenseTotal: 0,
        policyViolationsSecurityTotal: 0, policyViolationsOperationalTotal: 0 }
    const ANCHORED = { firstScanned: '2026-10-29T08:00:00Z', lastScanned: '2026-10-29T08:00:00Z', ...ZERO }
    const markedBom = (m: any = M1) => ({ uuid: 'a-m', type: 'BOM', tags: [], enrichmentStatus: 'COMPLETED',
        metrics: { dependencyTrackFullUri: null, dtrackSubmissionFailed: false, firstScanned: null, notScannable: m } })
    const scannedBom = { uuid: 'a-s', type: 'BOM', tags: [], enrichmentStatus: 'COMPLETED',
        metrics: { dependencyTrackFullUri: null, dtrackSubmissionFailed: false, firstScanned: '2026-10-29T08:00:00Z', notScannable: null } }
    const unsubmittedBom = { uuid: 'a-u', type: 'BOM', tags: [], enrichmentStatus: 'COMPLETED',
        metrics: { dependencyTrackFullUri: null, dtrackSubmissionFailed: false, firstScanned: null, notScannable: null } }
    // The ReleasesByDateRangeGql shape: own artifacts with type, an SCE without artifacts, no variantDetails.
    const row = (uuid: string, artifactDetails: any[], metrics: any = ANCHORED, type = 'COMPONENT') => ({
        uuid, version: uuid, lifecycle: 'ASSEMBLED', createdDate: '2026-10-29T08:00:00Z',
        componentDetails: { uuid: `c-${uuid}`, name: uuid, type, kind: 'GENERIC' },
        branchDetails: { uuid: `b-${uuid}`, name: 'main' }, sourceCodeEntryDetails: null, artifactDetails, metrics })
    const driftError = () => {
        const err: any = new Error("Validation error of type FieldUndefined: Field 'notScannable' is undefined")
        err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
        return err
    }
    const opName = (opts: any) => opts?.query?.definitions?.[0]?.name?.value
    const releaseCalls = () => query.mock.calls.map((c: any[]) => c[0]).filter((o: any) => opName(o) !== 'configuredBaseIntegrations')
    function answering (rows: any[], dtrack = false) {
        query.mockImplementation(async (opts: any) => opName(opts) === 'configuredBaseIntegrations'
            ? { data: { configuredBaseIntegrations: dtrack ? ['DEPENDENCYTRACK'] : [] } }
            : { data: { releasesByDateRange: rows, releasesByDateRangeAndPerspective: rows } })
    }
    async function mountRows (props: Record<string, any> = {}) {
        const w = mount(Widget, { props: { orgUuid: 'o-tpf127', perspectiveUuid: 'default', ...props }, global: { stubs } })
        await flushPromises()
        return w.findAll('li')
    }
    const badges = (li: any) => li.findAll('span[title]').filter((s: any) => s.attributes('style')?.includes('border-radius'))
    const notes = (li: any) => li.findAll('[data-testid="release-not-scannable-note"]')
    const isGrey = (style: string | undefined) => !!style && (style.includes('#9e9e9e') || style.includes('rgb(158, 158, 158)'))

    beforeEach(() => {
        query.mockReset()
        commit.mockClear()
        state.artifactScanStateSupported = true
    })

    it('(a) sends the preferred documents first', async () => {
        answering([])
        await mountRows()
        expect(releaseCalls()[0].query).toBe(GqlQueries.ReleasesByDateRangeGql)
        query.mockClear()
        await mountRows({ perspectiveUuid: 'p1' })
        expect(releaseCalls()[0].query).toBe(GqlQueries.ReleasesByDateRangeAndPerspectiveGql)
    })

    it('(b) on drift sends the core twin once more and latches after it answered', async () => {
        const rows = [row('r1', []), row('r2', []), row('r3', []), row('r4', [])]
        let first = true
        query.mockImplementation(async (opts: any) => {
            if (opName(opts) === 'configuredBaseIntegrations') return { data: { configuredBaseIntegrations: [] } }
            if (first) { first = false; throw driftError() }
            expect(commit).not.toHaveBeenCalled()
            return { data: { releasesByDateRange: rows } }
        })
        const lis = await mountRows()
        const calls = releaseCalls()
        expect(calls).toHaveLength(2)
        expect(calls[0].query).toBe(GqlQueries.ReleasesByDateRangeGql)
        expect(calls[1].query).toBe(GqlQueries.ReleasesByDateRangeGqlCore)
        expect(commit).toHaveBeenCalledTimes(1)
        expect(commit).toHaveBeenCalledWith('SET_ARTIFACT_SCAN_STATE_SUPPORTED', false)
        expect(lis).toHaveLength(4)
    })

    it('(b2) a latched session sends the core twin alone', async () => {
        state.artifactScanStateSupported = false
        answering([row('r1', [])])
        await mountRows()
        expect(releaseCalls()).toHaveLength(1)
        expect(releaseCalls()[0].query).toBe(GqlQueries.ReleasesByDateRangeGqlCore)
        expect(commit).not.toHaveBeenCalled()
    })

    it('(c) an all-marked anchored release reads zeros with the note; before the anchor, Scan pending', async () => {
        answering([row('r1', [markedBom()]), row('r2', [markedBom()], { ...ZERO, firstScanned: null, lastScanned: null })])
        const [anchored, before] = await mountRows()
        const circles = anchored.findAll('.circle')
        expect(circles).toHaveLength(8)
        for (const c of circles) expect(c.text()).toBe('0')
        expect(badges(anchored)).toHaveLength(0)
        const note = notes(anchored)
        expect(note).toHaveLength(1)
        expect(note[0].text()).toBe('1 BOM not scannable')
        expect(note[0].attributes('title')).toContain(reasonLabel('ONLY_ROOT'))
        expect(note[0].attributes('style')).toContain('color')
        expect(isGrey(note[0].attributes('style'))).toBe(true)
        expect(before.text()).toContain('Scan pending')
        expect(before.text()).not.toContain('DTrack pending')
        expect(badges(before)).toHaveLength(1)
        expect(notes(before)).toHaveLength(0)
    })

    it('(c2) a marked own BOM beside a scanned one keeps the findings and adds the note', async () => {
        answering([row('r1', [markedBom(), scannedBom], { ...ANCHORED, critical: 1 })])
        const [li] = await mountRows()
        const circles = li.findAll('.circle')
        expect(circles).toHaveLength(8)
        expect(circles[0].text()).toBe('1')
        expect(badges(li)).toHaveLength(0)
        expect(notes(li)[0].text()).toBe('1 BOM not scannable')
    })

    it('(c2b) two marked own BOMs are counted together', async () => {
        answering([row('r1', [markedBom(M1), markedBom(M2)])])
        const [li] = await mountRows()
        expect(notes(li)[0].text()).toBe('2 BOMs not scannable')
        expect(notes(li)[0].attributes('title')?.startsWith('Nothing to scan in 2 BOMs: ')).toBe(true)
    })

    it('(c3) a product keeps its rollup circles and carries the note for its own BOM', async () => {
        answering([row('r1', [markedBom()], { ...ANCHORED, critical: 2 }, 'PRODUCT')])
        const [li] = await mountRows()
        expect(li.findAll('.circle')).toHaveLength(8)
        expect(badges(li)).toHaveLength(0)
        expect(notes(li)).toHaveLength(1)
    })

    it('(c4) an unmarked BOM awaiting submission keeps DTrack pending, with no note', async () => {
        answering([row('r1', [markedBom(), unsubmittedBom])], true)
        const [li] = await mountRows({ orgUuid: 'o-tpf127-dt' })
        expect(li.text()).toContain('DTrack pending')
        expect(badges(li)).toHaveLength(1)
        expect(notes(li)).toHaveLength(0)
    })

    it('(c5) a served ALL_NOT_SCANNABLE draws the grey badge and no circle', async () => {
        const bomScan = { state: 'ALL_NOT_SCANNABLE', scannableBoms: 0, notScannableBoms: 1, reasons: ['ONLY_ROOT'] }
        answering([row('r1', [], { ...ANCHORED, bomScan })])
        const [li] = await mountRows()
        const badge = badges(li)
        expect(badge).toHaveLength(1)
        expect(badge[0].text()).toBe('No scannable components')
        expect(isGrey(badge[0].attributes('style'))).toBe(true)
        expect(badge[0].attributes('title')).toContain(reasonLabel('ONLY_ROOT'))
        expect(li.findAll('.circle')).toHaveLength(0)
    })

    it('(c6) a served state never covers a product', async () => {
        const bomScan = { state: 'ALL_NOT_SCANNABLE', scannableBoms: 0, notScannableBoms: 1, reasons: ['ONLY_ROOT'] }
        answering([row('r1', [], { ...ANCHORED, bomScan }, 'PRODUCT')])
        const [li] = await mountRows()
        expect(li.findAll('.circle')).toHaveLength(8)
        expect(badges(li)).toHaveLength(0)
        expect(notes(li)).toHaveLength(0)
    })
})

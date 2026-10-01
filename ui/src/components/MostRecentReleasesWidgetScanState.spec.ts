// @vitest-environment happy-dom
//
// RD4-11: a board's document round shows no scan state in the most-recent-releases list -- no pending
// badge and no vulnerability or policy circles -- while a software release keeps both.
import { describe, expect, it, vi } from 'vitest'
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
vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))
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

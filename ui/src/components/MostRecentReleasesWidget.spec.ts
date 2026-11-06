// @vitest-environment happy-dom
//
// RD4-10, TPF-157: the most-recent-releases widget lists software only on the home page and on the full
// page. Both reads it makes (the org's, and a perspective's) send the software kinds; no caller can ask
// for every kind.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn(async (_: any) => ({ data: { releasesByDateRange: [], releasesByDateRangeAndPerspective: [] } }))
vi.mock('@/utils/graphql', () => ({ default: { query: (q: any) => query(q) } }))
const { state, commit } = vi.hoisted(() => ({ state: { artifactScanStateSupported: true, releaseBomScanSupported: true }, commit: vi.fn() }))
vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {}, state, commit }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ error: vi.fn() }) }))
vi.mock('@/utils/releaseScanStatus', () => ({
    isDtrackConfiguredForOrg: async () => false,
    getReleaseScanStatus: () => ({ kind: 'ready', label: '', title: '' }),
    showsScanBadge: () => false,
}))
vi.mock('@/utils/releaseVulnerabilityService', () => ({ ReleaseVulnerabilityService: {} }))
vi.mock('./VulnerabilityModal.vue', () => ({ default: { name: 'VulnerabilityModal', render: () => null } }))
const { default: Widget } = await import('./MostRecentReleasesWidget.vue')
const { SOFTWARE_KINDS } = await import('@/utils/agentDocumentsView')

const stubs = { 'router-link': { template: '<a><slot/></a>' } }

async function variablesOf (props: Record<string, any>) {
    mount(Widget, { props: { orgUuid: 'o1', ...props }, global: { stubs } })
    await flushPromises()
    expect(query).toHaveBeenCalledTimes(1)
    return query.mock.calls[0][0]
}

describe('MostRecentReleasesWidget', () => {
    beforeEach(() => query.mockClear())

    it('on the home page asks the org read for the software kinds', async () => {
        const call = await variablesOf({ perspectiveUuid: 'default' })
        expect(call.query.definitions[0].name.value).toBe('releasesByDateRange')
        expect(call.variables.org).toBe('o1')
        expect(call.variables.componentKinds).toBe(SOFTWARE_KINDS)
        expect(call.variables.componentKinds).not.toContain('BOARD_DOCUMENT')
    })

    it('asks a perspective read for the software kinds too', async () => {
        const call = await variablesOf({ perspectiveUuid: 'p1' })
        expect(call.query.definitions[0].name.value).toBe('releasesByDateRangeAndPerspective')
        expect(call.variables.perspectiveUuid).toBe('p1')
        expect(call.variables.componentKinds).toBe(SOFTWARE_KINDS)
    })

    it('a stray includeDocuments binding changes nothing', async () => {
        const home = await variablesOf({ perspectiveUuid: 'default', includeDocuments: true, showFullPageIcon: false })
        expect(home.variables.componentKinds).toBe(SOFTWARE_KINDS)
        expect(home.variables.componentKinds).not.toContain('BOARD_DOCUMENT')
        query.mockClear()
        const perspective = await variablesOf({ perspectiveUuid: 'p1', includeDocuments: true, showFullPageIcon: false })
        expect(perspective.variables.componentKinds).toBe(SOFTWARE_KINDS)
    })

    it('the queries carry the argument', async () => {
        const { default: GqlQueries } = await import('@/utils/graphqlQueries')
        for (const doc of [GqlQueries.ReleasesByDateRangeGql, GqlQueries.ReleasesByDateRangeAndPerspectiveGql]) {
            const text = doc.loc.source.body
            expect(text).toContain('$componentKinds: [ComponentKind]')
            expect(text).toContain('componentKinds: $componentKinds')
        }
    })

    it('neither the full page nor the home page passes include-documents', async () => {
        const { readFileSync } = await import('node:fs')
        const { resolve } = await import('node:path')
        const src = (f: string) => readFileSync(resolve(__dirname, f), 'utf8')
        const page = src('MostRecentReleasesPage.vue')
        expect(page).not.toContain('include-documents')
        const lines = page.split('\n')
        const at = lines.findIndex((l) => l.includes(':component-type="componentTypeValue"'))
        expect(at).toBeGreaterThanOrEqual(0)
        expect(lines.slice(at + 1).find((l) => l.trim() !== '')?.trim()).toBe('/>')
        expect(src('AppHome.vue')).not.toContain('include-documents')
    })
})

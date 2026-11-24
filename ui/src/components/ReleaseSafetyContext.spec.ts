// @vitest-environment happy-dom
//
// The release Safety Context tab: a row names the broader level it overrides, and a level
// carried from a previous package version is marked with that version.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query } }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: {} }), RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
const { default: ReleaseSafetyContext } = await import('./ReleaseSafetyContext.vue')

const tooltip = { template: '<span><slot name="trigger"/><slot/></span>' }

function row (over: any) {
    return {
        uuid: 'r1', kind: 'RECORD', org: 'org-1', recordStatus: 'ACTIVE', releaseUuid: 'rel-1', scope: 'RELEASE',
        componentPurl: 'pkg:generic/acme/pump@1.1.0', schemeKey: 'IEC-62304', levelRaw: 'Class B',
        levelNormalized: 'Class B', ...over
    }
}

async function mounted (rows: any[]) {
    query.mockResolvedValue({ data: { getSafetyContextRecordsByRelease: rows } })
    const w = mount(ReleaseSafetyContext, {
        props: { orgUuid: 'org-1', releaseUuid: 'rel-1' },
        global: { stubs: { NTooltip: tooltip, Tooltip: tooltip, SafetyContextProposals: true, SafetyContextSourceModal: true } }
    })
    await flushPromises()
    return w
}

describe('ReleaseSafetyContext', () => {
    beforeEach(() => { query.mockReset() })

    it('names the level a row overrides', async () => {
        const w = await mounted([row({ overridesScope: 'COMPONENT', overridesLevel: 'Class C' })])
        expect(w.text()).toContain('overrides Component: Class C')
    })

    it('marks a level carried from a previous package version', async () => {
        const w = await mounted([row({
            carried: true, componentPurl: 'pkg:generic/acme/pump@1.0.0', shownForPurl: 'pkg:generic/acme/pump@1.1.0'
        })])
        expect(w.text()).toContain('carried from 1.0.0')
        expect(w.text()).toContain('declared for pkg:generic/acme/pump@1.0.0; no level declared yet for pkg:generic/acme/pump@1.1.0')
    })

    it('shows neither for a plain row', async () => {
        const w = await mounted([row({})])
        expect(w.text()).not.toContain('overrides Component')
        expect(w.text()).not.toContain('carried from ')
    })
})

// @vitest-environment happy-dom
//
// The VEX proposals inbox: the status filter is a labelled radio group, and an org admin can run the
// import audit (accepted statements whose accepting credential cannot be shown to hold Finding
// Analysis write now) from a collapsed card that nobody else sees.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { print } from 'graphql'

const query = vi.fn()
const getters: any = { myuser: null }
vi.mock('@/utils/graphql', () => ({ default: { query } }))
vi.mock('vuex', () => ({ useStore: () => ({ getters }) }))
vi.mock('vue-router', () => ({
    useRoute: () => ({ params: { orguuid: 'org-1' } }),
    useRouter: () => ({ resolve: () => ({ href: '/x' }) }),
    RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' }
}))
const { default: Inbox } = await import('./VexProposalsInbox.vue')

// Collapsed content renders in place so the card's controls can be reached.
const collapseItem = { props: ['title'], template: '<div><div class="title">{{ title }}</div><slot/></div>' }

const ROW = {
    proposal: 'p-1', sourceArtifact: 'a-1', scope: 'ORG', scopeUuid: 'org-1', location: 'pkg:npm/lodash@4.17.20',
    findingId: 'CVE-2026-0001', analysisState: 'NOT_AFFECTED', targetVulnAnalysis: 'va-1', mitigationAttestation: null,
    acceptedAt: '2026-11-20T10:00:00Z', acceptedVia: 'IMPORT', acceptedBy: 'key-1', acceptedByKind: 'FREEFORM_KEY',
    actedBy: 'key-1', holdsFindingAnalysisWriteNow: 'NO', analysisStillAsAccepted: true
}

function respond (report?: any) {
    query.mockImplementation(async (opts: any) => {
        const name = (opts.query.definitions[0] as any).name?.value
        if (name === 'GetVexStatementProposals') return { data: { getVexStatementProposals: [] } }
        if (name === 'VexImportAuthorityReport') return { data: { vexImportAuthorityReport: report } }
        return { data: { users: [] } }
    })
}

function asOrg (type: string | null) {
    getters.myuser = type
        ? { permissions: { permissions: [{ scope: 'ORGANIZATION', org: 'org-1', object: 'org-1', type }] } }
        : { permissions: { permissions: [] } }
}

async function mounted () {
    const w = mount(Inbox, { global: { stubs: { NCollapseItem: collapseItem, CollapseItem: collapseItem } } })
    await flushPromises()
    return w
}

function callsOf (name: string) {
    return query.mock.calls.filter((c: any) => (c[0].query.definitions[0] as any).name?.value === name)
}

describe('VexProposalsInbox', () => {
    beforeEach(() => { query.mockReset(); asOrg(null); respond({ rows: [ROW], truncated: false }) })

    it('switches the status with labelled buttons', async () => {
        const w = await mounted()
        expect(w.find('[data-testid="vex-proposals-status-filter"]').text()).toContain('Rejected')
        expect(callsOf('GetVexStatementProposals')[0][0].variables).toMatchObject({ org: 'org-1', status: 'PENDING' })
        const rejected = w.findAll('input[type="radio"]').find((i: any) => i.attributes('value') === 'REJECTED')!
        await rejected.setValue(true)
        await flushPromises()
        expect(callsOf('GetVexStatementProposals').at(-1)![0].variables).toMatchObject({ status: 'REJECTED' })
    })

    it.each([[null], ['READ_WRITE'], ['READ_ONLY']])('hides the import audit from a user whose org permission is %s', async (type) => {
        asOrg(type)
        const w = await mounted()
        expect(w.find('[data-testid="vex-import-audit"]').exists()).toBe(false)
    })

    it('shows the import audit to an org admin and runs it', async () => {
        asOrg('ADMIN')
        const w = await mounted()
        const card = w.find('[data-testid="vex-import-audit"]')
        expect(card.exists()).toBe(true)
        expect(card.text()).toContain('Import audit')
        expect(callsOf('VexImportAuthorityReport')).toHaveLength(0)
        await card.findAll('button').find((b: any) => b.text() === 'Run')!.trigger('click')
        await flushPromises()
        const calls = callsOf('VexImportAuthorityReport')
        expect(calls).toHaveLength(1)
        expect(print(calls[0][0].query)).toContain('vexImportAuthorityReport(org: $org, includeHeld: $includeHeld)')
        expect(calls[0][0].variables).toEqual({ org: 'org-1', includeHeld: false })
        const rows = w.find('[data-testid="vex-import-audit-rows"]')
        expect(rows.text()).toContain('CVE-2026-0001')
        expect(rows.text()).toContain('pkg:npm/lodash@4.17.20')
        expect(rows.text()).toContain('Freeform key')
        expect(rows.text()).toContain('Import')
        expect(w.find('[data-testid="vex-import-audit-truncated"]').exists()).toBe(false)
    })

    it('asks for held rows too when the toggle is on', async () => {
        asOrg('ADMIN')
        const w = await mounted()
        const card = w.find('[data-testid="vex-import-audit"]')
        await card.find('.n-checkbox').trigger('click')
        await card.findAll('button').find((b: any) => b.text() === 'Run')!.trigger('click')
        await flushPromises()
        expect(callsOf('VexImportAuthorityReport')[0][0].variables).toEqual({ org: 'org-1', includeHeld: true })
    })

    it('says the result was truncated', async () => {
        asOrg('ADMIN')
        respond({ rows: [ROW], truncated: true })
        const w = await mounted()
        await w.find('[data-testid="vex-import-audit"]').findAll('button').find((b: any) => b.text() === 'Run')!.trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="vex-import-audit-truncated"]').text()).toContain('truncated at 5000')
    })
})

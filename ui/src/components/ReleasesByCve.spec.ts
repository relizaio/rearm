// @vitest-environment happy-dom
//
// TPF-135 architecture round 2, 4.2 test 11: the CVE results modal has an Include archived branches
// checkbox, unchecked on every opening; the search sends branchScope ACTIVE_BRANCHES, and checking
// the box re-runs it with ALL_BRANCHES. A failed search shows the existing toast and sends nothing else.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const { query, notifyError } = vi.hoisted(() => ({ query: vi.fn(), notifyError: vi.fn() }))
vi.mock('@/utils/graphql', () => ({ default: { query } }))
vi.mock('@/utils/commonFunctions', () => ({ default: { parseGraphQLError: (m: string) => m } }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ error: notifyError }) }))
vi.mock('./ComponentBranchesTable.vue', () => ({
    default: { name: 'ComponentBranchesTable', props: ['data'], render: () => null }
}))
vi.mock('./VulnerabilityDetailsModal.vue', () => ({ default: { name: 'VulnerabilityDetailsModal', render: () => null } }))

const { NCheckbox } = await import('naive-ui')
const { default: ReleasesByCve } = await import('./ReleasesByCve.vue')
const { default: ComponentBranchesTable } = await import('./ComponentBranchesTable.vue')

const LIVE = { uuid: 'c1', name: 'api', branches: [{ uuid: 'b-live', name: 'main', status: 'ACTIVE', releases: [] }] }
const ARCHIVED = { uuid: 'c1', name: 'api', branches: [
    { uuid: 'b-live', name: 'main', status: 'ACTIVE', releases: [] },
    { uuid: 'b-old', name: 'old', status: 'ARCHIVED', releases: [] }
] }

function answer (vars: any) {
    const all = vars.branchScope === 'ALL_BRANCHES'
    return { data: { searchReleasesByCveId: { totalReleases: all ? 2 : 1, shownReleases: all ? 2 : 1, truncated: false,
        components: [all ? ARCHIVED : LIVE] } } }
}

async function open () {
    const w = mount(ReleasesByCve, {
        props: { show: false, cveId: 'CVE-2026-0001', orgUuid: 'o1' },
        attachTo: document.body,
    })
    await w.setProps({ show: true })
    await flushPromises()
    return w
}

const box = (w: any) => w.findComponent(NCheckbox)
const sent = () => query.mock.calls.map(c => c[0])

describe('ReleasesByCve branch scope (TPF-135)', () => {
    beforeEach(() => {
        document.body.innerHTML = ''
        query.mockReset()
        query.mockImplementation(async (req: any) => answer(req.variables))
        notifyError.mockReset()
    })

    it('(a) opening renders the box unchecked and searches the active branches without cache', async () => {
        const w = await open()
        expect(box(w).exists()).toBe(true)
        expect(box(w).text()).toBe('Include archived branches')
        expect(box(w).attributes('data-testid')).toBe('include-archived-branches')
        expect(box(w).props('checked')).toBe(false)
        expect(sent()).toHaveLength(1)
        expect(sent()[0].variables).toEqual({ org: 'o1', cveId: 'CVE-2026-0001', perspectiveUuid: null, branchScope: 'ACTIVE_BRANCHES' })
        expect(sent()[0].fetchPolicy).toBe('no-cache')
        expect(w.findComponent(ComponentBranchesTable).props('data')).toEqual([LIVE])
    })

    it('(b) checking the box re-runs the search over every branch and shows the new rows', async () => {
        const w = await open()
        await box(w).find('.n-checkbox').trigger('click')
        await flushPromises()
        expect(sent()).toHaveLength(2)
        expect(sent()[1].variables.branchScope).toBe('ALL_BRANCHES')
        expect(sent()[1].fetchPolicy).toBe('no-cache')
        expect(box(w).props('checked')).toBe(true)
        expect(w.findComponent(ComponentBranchesTable).props('data')).toEqual([ARCHIVED])
    })

    it('(c) closing and opening again starts unchecked on the active branches', async () => {
        const w = await open()
        await box(w).find('.n-checkbox').trigger('click')
        await flushPromises()
        await w.setProps({ show: false })
        await flushPromises()
        await w.setProps({ show: true })
        await flushPromises()
        expect(sent()).toHaveLength(3)
        expect(sent()[2].variables.branchScope).toBe('ACTIVE_BRANCHES')
        expect(box(w).props('checked')).toBe(false)
    })

    it('(d) a failed search shows the toast, sends no other request and leaves the box usable', async () => {
        query.mockReset()
        query.mockRejectedValue(new Error('boom'))
        vi.spyOn(console, 'error').mockImplementation(() => {})
        const w = await open()
        expect(query).toHaveBeenCalledTimes(1)
        expect(notifyError).toHaveBeenCalledTimes(1)
        expect(notifyError.mock.calls[0][0].content).toContain('CVE-2026-0001')
        expect(box(w).props('disabled')).toBeFalsy()
    })
})

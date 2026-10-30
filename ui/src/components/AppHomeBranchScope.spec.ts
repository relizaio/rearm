// @vitest-environment happy-dom
//
// TPF-135 architecture round 2, 4.2 test 12: the SBOM-component results module of the home page has
// an Include archived branches checkbox. Mounted with the real store and the children mocked; the
// GraphQL client records every request and answers by operation name. Rows (a) to (e) assert the
// recorded variables: the purl click searches the active branches, a tick re-queries the SAME
// selection over every branch, a new search clears the selection and the box, a tick with no purl
// picked sends nothing, and the next purl click carries the new uuids with the box's value.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'

const { query, fire } = vi.hoisted(() => ({ query: vi.fn(), fire: vi.fn(async () => ({})) }))
vi.mock('@/utils/graphql', () => ({ default: { query, mutate: vi.fn(async () => ({ data: {} })) } }))
vi.mock('../utils/graphql', () => ({ default: { query, mutate: vi.fn(async () => ({ data: {} })) } }))
vi.mock('@/utils/keycloak', () => ({ default: {} }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }) }))
vi.mock('sweetalert2', () => ({ default: { fire } }))
vi.mock('vue-router', () => ({
    useRouter: () => ({ push: vi.fn() }),
    useRoute: () => ({ params: {}, query: {} }),
    RouterLink: { name: 'RouterLink', render: () => null },
}))
const stub = (name: string) => ({ default: { name, render: () => null } })
vi.mock('./InstanceHistory.vue', () => stub('InstanceHistory'))
vi.mock('./FindingsOverTimeChart.vue', () => stub('FindingsOverTimeChart'))
vi.mock('./ReleasesPerDayChart.vue', () => stub('ReleasesPerDayChart'))
vi.mock('./MostActiveChart.vue', () => stub('MostActiveChart'))
vi.mock('./ReleasesByCve.vue', () => stub('ReleasesByCve'))
vi.mock('./VulnerabilityModal.vue', () => stub('VulnerabilityModal'))
vi.mock('./MostRecentReleasesWidget.vue', () => stub('MostRecentReleasesWidget'))
vi.mock('./InstanceStatusWidget.vue', () => stub('InstanceStatusWidget'))
vi.mock('./AiAgentBoardsPanel.vue', () => stub('AiAgentBoardsPanel'))
vi.mock('./VcsReposOfOrg.vue', () => stub('VcsReposOfOrg'))
// The table renders the branch names of its data prop, so the rows it was handed can be asserted.
vi.mock('./ComponentBranchesTable.vue', () => ({
    default: {
        name: 'ComponentBranchesTable',
        props: ['data', 'orgUuid', 'loading', 'featureSetLabel', 'showIsLatestColumn'],
        setup (props: any) {
            return () => h('div', { class: 'branch-names' },
                (props.data || []).flatMap((c: any) => c.branches.map((b: any) => b.name)).join(','))
        }
    }
}))

const ORG = { uuid: 'o1', name: 'Org', terminology: {}, settings: {} }
const PURLS = {
    lodash: [{ purl: 'pkg:npm/lodash@1', sbomComponents: ['sc-lodash'] }],
    express: [{ purl: 'pkg:npm/express@1', sbomComponents: ['sc-express'] }],
}
const LIVE = { uuid: 'b-live', name: 'main', status: 'ACTIVE', releases: [] }
const OLD = { uuid: 'b-old', name: 'old-archived', status: 'ARCHIVED', releases: [] }

function answer (req: any) {
    const op = req.query?.definitions?.[0]?.name?.value
    if (op === 'sbomComponentSearchNative') {
        const name = req.variables.queries[0].name
        return { data: { sbomComponentSearchNative: name.includes('express') ? PURLS.express : PURLS.lodash } }
    }
    if (op === 'releasesBySbomComponents') {
        const branches = req.variables.branchScope === 'ALL_BRANCHES' ? [LIVE, OLD] : [LIVE]
        return { data: { releasesBySbomComponents: [{ uuid: 'c1', name: 'api', type: 'COMPONENT', branches }] } }
    }
    return { data: {} }
}

const { NCheckbox } = await import('naive-ui')
const { default: storeModule } = await import('@/store')
const { default: AppHome } = await import('./AppHome.vue')

const store = storeModule.store
store.commit('SET_MY_USER', { uuid: 'u1', name: 'User', email: 'u@x.io', installationType: 'SAAS',
    permissions: { permissions: [] }, organizations: ['o1'] })
store.commit('SET_ORGANIZATIONS', [ORG])
store.commit('UPDATE_MY_ORG', ORG.uuid)

const releaseCalls = () => query.mock.calls.map(c => c[0])
    .filter(r => r.query?.definitions?.[0]?.name?.value === 'releasesBySbomComponents')

function scopeBox (w: any) {
    const box = w.findAllComponents(NCheckbox).find((c: any) => c.attributes('data-testid') === 'include-archived-branches-sbom')
    expect(box, 'the SBOM results checkbox is rendered').toBeDefined()
    return box
}

async function search (w: any, term: string) {
    w.vm.sbomSearchQuery = term
    await w.vm.searchSbomComponent({ preventDefault () {} } as Event)
    await flushPromises()
}

async function pick (w: any, row: any) {
    w.vm.handlePurlSearchClick(row)
    await flushPromises()
}

const tableText = () => document.body.querySelector('.branch-names')?.textContent ?? ''

describe('AppHome SBOM-component search branch scope (TPF-135)', () => {
    beforeEach(() => {
        document.body.innerHTML = ''
        query.mockReset()
        query.mockImplementation(async (req: any) => answer(req))
        fire.mockClear()
    })

    it('(a) to (e): the selection, the box and the scope sent', async () => {
        const w = mount(AppHome, { global: { plugins: [store] }, attachTo: document.body })
        await flushPromises()

        await search(w, 'lodash')
        await pick(w, PURLS.lodash[0])
        expect(scopeBox(w).props('checked'), '(a)').toBe(false)
        expect(releaseCalls(), '(a)').toHaveLength(1)
        expect(releaseCalls()[0].variables, '(a)').toMatchObject({ orgUuid: 'o1', sbomComponentUuids: ['sc-lodash'], branchScope: 'ACTIVE_BRANCHES' })
        expect(releaseCalls()[0].fetchPolicy, '(a)').toBe('cache-first')
        expect(tableText(), '(a)').toBe('main')

        await scopeBox(w).trigger('click')
        await flushPromises()
        expect(releaseCalls(), '(b)').toHaveLength(2)
        expect(releaseCalls()[1].variables, '(b)').toMatchObject({ sbomComponentUuids: ['sc-lodash'], branchScope: 'ALL_BRANCHES' })
        expect(scopeBox(w).props('checked'), '(b)').toBe(true)
        expect(tableText(), '(b)').toBe('main,old-archived')

        await search(w, 'express')
        expect(releaseCalls(), '(c) a search sends no release query').toHaveLength(2)
        expect(w.vm.dtrackSearchReleases, '(c)').toEqual([])
        expect(w.vm.selectedPurl, '(c)').toBe('')
        expect(scopeBox(w).props('checked'), '(c)').toBe(false)

        await scopeBox(w).trigger('click')
        await flushPromises()
        expect(releaseCalls(), '(d) a tick with no purl picked sends nothing').toHaveLength(2)
        expect(scopeBox(w).props('checked'), '(d)').toBe(true)
        expect(tableText(), '(d) the table stays empty').toBe('')

        await pick(w, PURLS.express[0])
        expect(releaseCalls(), '(e)').toHaveLength(3)
        expect(releaseCalls()[2].variables, '(e)').toMatchObject({ sbomComponentUuids: ['sc-express'], branchScope: 'ALL_BRANCHES' })
        expect(fire, 'no error dialog').not.toHaveBeenCalled()
        w.unmount()
    })
})

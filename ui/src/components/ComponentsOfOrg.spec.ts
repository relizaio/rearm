// @vitest-environment happy-dom
//
// TPF-101 test 2.1 (TPF-74 child 2, ARCHITECTURE round 2 3.6): the organization's component list
// page renders from ref rows (the six componentRefs scalars, frozen as Apollo hands them out). The
// store, the router, the GraphQL client and commonFunctions are mocked; ComponentView and
// CreateComponent are replaced, so the page alone is under test.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const ROWS = Object.freeze([
    Object.freeze({ uuid: 'c-zeta', name: 'zeta', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: null }),
    Object.freeze({ uuid: 'c-alpha', name: 'Alpha', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: 'V' }),
    Object.freeze({ uuid: 'c-mid', name: 'mid', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: null })
])

const dispatch = vi.fn(async () => [])
const push = vi.fn()
vi.mock('vuex', () => ({
    useStore: () => ({
        dispatch,
        getters: {
            componentsOfOrg: () => ROWS,
            productsOfOrg: () => [],
            myorg: 'O',
            myperspective: 'default',
            myuser: { installationType: 'SAAS' },
            orgById: () => ({ name: 'Org' }),
        },
    }),
}))
vi.mock('vue-router', () => ({
    useRoute: () => ({ params: { orguuid: 'O' }, name: 'ComponentsOfOrg' }),
    useRouter: () => ({ push }),
}))
vi.mock('@/utils/graphql', () => ({ default: {} }))
vi.mock('@/utils/commonFunctions', () => ({ default: { isWritable: () => true } }))
vi.mock('./ComponentView.vue', () => ({ default: { name: 'ComponentView', render: () => null } }))
vi.mock('./CreateComponent.vue', () => ({ default: { name: 'CreateComponent', render: () => null } }))

const { default: ComponentsOfOrg } = await import('./ComponentsOfOrg.vue')

describe('ComponentsOfOrg renders from ref rows (TPF-101)', () => {
    beforeEach(() => {
        dispatch.mockClear()
        push.mockClear()
    })

    it('shows the rows in the getter order, navigates by uuid and loads the refs on mount', async () => {
        const warn = vi.spyOn(console, 'warn')
        const error = vi.spyOn(console, 'error')
        const w = mount(ComponentsOfOrg, { global: { stubs: { ComponentView: true, CreateComponent: true } } })
        await flushPromises()
        const cells = w.findAll('tbody tr td').map(td => td.text())
        expect(cells).toEqual(['zeta', 'Alpha', 'mid'])
        expect(dispatch).toHaveBeenCalledWith('fetchComponents', { orgid: 'O', forceRefresh: false })
        await w.findAll('tbody tr')[0].trigger('click')
        expect(push).toHaveBeenCalledTimes(1)
        expect(push.mock.calls[0][0]).toMatchObject({ name: 'ComponentsOfOrg', params: { orguuid: 'O', compuuid: 'c-zeta' } })
        expect(warn).not.toHaveBeenCalled()
        expect(error).not.toHaveBeenCalled()
        warn.mockRestore()
        error.mockRestore()
        w.unmount()
    })
})

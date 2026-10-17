// @vitest-environment happy-dom
//
// The Locks tab tells the component settings page when it wrote the component row (TPF-62): a
// COMPONENT lock raised or released moves the component's revision, a BRANCH lock does not.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))
// commonFunctions pulls in the GraphQL client, which waits for a Keycloak login that never comes here.
vi.mock('@/utils/commonFunctions', () => ({ default: { extractGraphQLErrorMessage: (e: any) => e?.message } }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const { default: ComponentLocks } = await import('./ComponentLocks.vue')

function answering (failOn?: string) {
    dispatch.mockReset()
    dispatch.mockImplementation(async (action: string) => {
        if (action === failOn) throw new Error('refused')
        return []
    })
}

async function mounted () {
    const w = mount(ComponentLocks, { props: { orgUuid: 'o1', componentUuid: 'c1', isWritable: true } })
    await flushPromises()
    return w
}

describe('the Locks tab reports its component writes', () => {
    beforeEach(() => answering())

    it('emits once for a COMPONENT raise, not for a BRANCH raise or a failed raise', async () => {
        let w = await mounted()
        const vm = w.vm as any
        vm.draft.scope = 'COMPONENT'
        await vm.raise()
        expect(dispatch).toHaveBeenCalledWith('lockComponent', expect.objectContaining({ componentUuid: 'c1' }))
        expect(w.emitted('component-written')).toHaveLength(1)

        vm.draft.scope = 'BRANCH'
        vm.draft.branch = 'b1'
        await vm.raise()
        expect(dispatch).toHaveBeenCalledWith('lockBranch', expect.objectContaining({ branchUuid: 'b1' }))
        expect(w.emitted('component-written')).toHaveLength(1)

        answering('lockComponent')
        w = await mounted()
        ;(w.vm as any).draft.scope = 'COMPONENT'
        await (w.vm as any).raise()
        expect(w.emitted('component-written')).toBeUndefined()
    })

    it('emits once for releasing a COMPONENT lock, not a BRANCH lock or a failed release', async () => {
        const w = await mounted()
        const vm = w.vm as any
        vm.releasing = { uuid: 'l1', scope: 'COMPONENT' }
        await vm.release()
        expect(dispatch).toHaveBeenCalledWith('releaseLock', expect.objectContaining({ lockUuid: 'l1' }))
        expect(w.emitted('component-written')).toHaveLength(1)

        vm.releasing = { uuid: 'l2', scope: 'BRANCH' }
        await vm.release()
        expect(w.emitted('component-written')).toHaveLength(1)

        answering('releaseLock')
        vm.releasing = { uuid: 'l3', scope: 'COMPONENT' }
        await vm.release()
        expect(w.emitted('component-written')).toHaveLength(1)
    })
})

// @vitest-environment happy-dom
//
// The Guards tab tells the component settings page when it wrote the component row (TPF-62): a
// component's guards are saved through updateComponent, which moves the component's revision.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))
// commonFunctions pulls in the GraphQL client, which waits for a Keycloak login that never comes here.
vi.mock('@/utils/commonFunctions', () => ({ default: { extractGraphQLErrorMessage: (e: any) => e?.message } }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const { default: ActionGuards } = await import('./ActionGuards.vue')

const guard = { name: 'g', action: 'RELEASE_LIFECYCLE', cel: 'true', mode: 'DENY' }

function answering (failOn?: string) {
    dispatch.mockReset()
    dispatch.mockImplementation(async (action: string) => {
        if (action === failOn) throw new Error('refused')
        return []
    })
}

async function mounted (scope: 'ORG' | 'COMPONENT' | 'PERSPECTIVE') {
    const w = mount(ActionGuards, { props: { scope, uuid: 'x1', isWritable: true, orgUuid: 'o1' } })
    await flushPromises()
    return w
}

describe('the Guards tab reports its component writes', () => {
    beforeEach(() => answering())

    it('emits once when a COMPONENT save succeeds', async () => {
        const w = await mounted('COMPONENT')
        expect(await (w.vm as any).persist([guard], 'ok')).toBe(true)
        expect(dispatch).toHaveBeenCalledWith('setComponentActionGuards', expect.objectContaining({ componentUuid: 'x1' }))
        expect(w.emitted('component-written')).toHaveLength(1)
    })

    it('emits nothing for an ORG or PERSPECTIVE save', async () => {
        for (const scope of ['ORG', 'PERSPECTIVE'] as const) {
            const w = await mounted(scope)
            expect(await (w.vm as any).persist([guard], 'ok')).toBe(true)
            expect(w.emitted('component-written')).toBeUndefined()
        }
    })

    it('emits nothing when the save is refused', async () => {
        answering('setComponentActionGuards')
        const w = await mounted('COMPONENT')
        expect(await (w.vm as any).persist([guard], 'ok')).toBe(false)
        expect(w.emitted('component-written')).toBeUndefined()
    })
})

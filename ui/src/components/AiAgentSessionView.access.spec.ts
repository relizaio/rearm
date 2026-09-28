// @vitest-environment happy-dom
//
// The session page when the read is refused or finds nothing (RD2-9): it says so instead of spinning.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: { myuser: null } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { uuid: 's1' } }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))
// fetchClient pulls in the Keycloak client, which waits for a login that never comes here.
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('vue-prism-editor', () => ({ PrismEditor: { template: '<div/>' } }))
vi.mock('vue-prism-editor/dist/prismeditor.min.css', () => ({}))
vi.mock('prismjs/themes/prism-tomorrow.css', () => ({}))

const { default: SessionView } = await import('./AiAgentSessionView.vue')

describe('the session page for a person the read refuses', () => {
    beforeEach(() => dispatch.mockReset())

    it('says Not authorized, not a spinner', async () => {
        dispatch.mockImplementation(async (a: string) => {
            if (a === 'fetchSession') throw new Error('GraphQL error: Not authorized')
            return []
        })
        const w = mount(SessionView)
        await flushPromises()
        expect(w.find('[data-testid="session-error"]').exists()).toBe(true)
        expect(w.text()).toContain('Not authorized')
        expect(w.find('.n-spin-body, .n-spin').exists()).toBe(false)
    })

    it('says Session not found when the read finds nothing', async () => {
        dispatch.mockImplementation(async () => null)
        const w = mount(SessionView)
        await flushPromises()
        expect(w.text()).toContain('Session not found')
    })
})

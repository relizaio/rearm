// @vitest-environment happy-dom
//
// Force-closing a session from its page (task 6fdc5a37): offered to an org admin on an OPEN
// session, confirmed, then the page reloads the session.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
const getters: any = {}
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { uuid: 's1' } }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))
// fetchClient pulls in the Keycloak client, which waits for a login that never comes here.
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('vue-prism-editor', () => ({ PrismEditor: { template: '<div/>' } }))
vi.mock('vue-prism-editor/dist/prismeditor.min.css', () => ({}))
vi.mock('prismjs/themes/prism-tomorrow.css', () => ({}))

const { default: SessionView } = await import('./AiAgentSessionView.vue')

// The confirm's popover is teleported; a stub shows its trigger, its body (the reason) and a button that confirms.
const confirm = { emits: ['positive-click'], template: '<div class="pc"><slot name="trigger"/><slot/><button class="pc__yes" @click="$emit(\'positive-click\')"/></div>' }
const stubs = { NPopconfirm: confirm, Popconfirm: confirm }

function session (status: string) {
    return { uuid: 's1', org: 'o1', status, title: 't', commits: [], artifacts: [], agent: null }
}

function asAdmin (admin: boolean) {
    getters.myuser = { permissions: { permissions: admin ? [{ org: 'o1', scope: 'ORGANIZATION', type: 'ADMIN' }] : [] } }
}

describe('session force close', () => {
    beforeEach(() => dispatch.mockReset())

    it('is offered to an admin on an open session only', async () => {
        for (const [status, admin, shown] of [['OPEN', true, true], ['OPEN', false, false], ['CLOSED', true, false]] as const) {
            asAdmin(admin)
            dispatch.mockImplementation(async (a: string) => a === 'fetchSession' ? session(status) : [])
            const w = mount(SessionView, { global: { stubs } })
            await flushPromises()
            expect(w.find('.forceclose').exists(), `${status} admin=${admin}`).toBe(shown)
        }
    })

    it('closes on confirm and reloads the session', async () => {
        asAdmin(true)
        let status = 'OPEN'
        dispatch.mockImplementation(async (a: string) => {
            if (a === 'fetchSession') return session(status)
            if (a === 'forceCloseAgentSession') { status = 'CLOSED'; return { uuid: 's1', status } }
            return []
        })
        const w = mount(SessionView, { global: { stubs } })
        await flushPromises()
        await w.find('.pc__yes').trigger('click')
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('forceCloseAgentSession', { sessionUuid: 's1', reason: null })
        expect(dispatch.mock.calls.filter(c => c[0] === 'fetchSession')).toHaveLength(2)
        expect(w.find('.forceclose').exists(), 'a closed session offers nothing').toBe(false)
    })

    it('sends the reason the admin typed and shows who closed it', async () => {
        asAdmin(true)
        let closed = false
        dispatch.mockImplementation(async (a: string) => {
            if (a === 'fetchSession') {
                return closed
                    ? { ...session('CLOSED'), closedBy: { kind: 'USER', uuid: 'u1', name: 'pm@example.com' }, closeReason: 'force-closed by pm@example.com: agent crashed' }
                    : session('OPEN')
            }
            if (a === 'forceCloseAgentSession') { closed = true; return { uuid: 's1', status: 'CLOSED' } }
            return []
        })
        const w = mount(SessionView, { global: { stubs } })
        await flushPromises()
        await w.find('.forceclose-reason input').setValue('  agent crashed ')
        await w.find('.pc__yes').trigger('click')
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('forceCloseAgentSession', { sessionUuid: 's1', reason: 'agent crashed' })
        expect(w.find('.close-attribution').text()).toBe('pm@example.com — force-closed by pm@example.com: agent crashed')
    })

    it('flags an open session the sweep has warned', async () => {
        asAdmin(false)
        dispatch.mockImplementation(async (a: string) => a === 'fetchSession'
            ? { ...session('OPEN'), idleWarnedAt: '2026-09-26T10:00:00Z' } : [])
        const w = mount(SessionView, { global: { stubs } })
        await flushPromises()
        expect(w.find('.idle-warned').exists()).toBe(true)
    })
})

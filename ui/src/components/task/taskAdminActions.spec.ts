// @vitest-environment happy-dom
//
// An admin's strength and operator-hold rows in Task actions (task 6fdc5a37): shown to an admin,
// the hold only where the server places one and never without a reason.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskActions from './TaskActions.vue'
import TaskHeader from './TaskHeader.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

function actions (task: any, admin = true) {
    return mount(TaskActions, { props: { task, roles: fixtureRoles, board: {}, canReopen: admin, admin } })
}

function button (w: any, text: string) {
    return w.findAll('button').find((b: any) => b.text() === text)
}

describe('admin task actions', () => {
    it('shows the strength row to an admin only', () => {
        const queued = richTask({ status: 'QUEUED', hold: null, requiredStrength: null })
        expect(actions(queued).find('.strow').exists()).toBe(true)
        expect(actions(queued, false).find('.strow').exists()).toBe(false)
    })

    it('sets a strength and clears one', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, requiredStrength: null }))
        expect(button(w, 'Set strength')!.attributes('disabled')).toBeDefined()
        expect(button(w, 'Clear')).toBeUndefined()
        await w.find('.strow input').setValue('4.5')
        await w.find('.strow input').trigger('blur')
        await button(w, 'Set strength')!.trigger('click')
        expect(w.emitted('set-strength')?.[0]?.[0]).toMatchObject({ requiredStrength: 4.5 })

        const set = actions(richTask({ status: 'QUEUED', hold: null, requiredStrength: 3,
            strengthSetBy: { kind: 'USER', uuid: 'u1', name: 'pavel' }, strengthSetAt: '2026-09-25T09:00:00Z' }))
        expect(set.find('.strow').text()).toContain('set by pavel')
        await button(set, 'Clear')!.trigger('click')
        expect(set.emitted('set-strength')?.[0]?.[0]).toMatchObject({ requiredStrength: null })
    })

    it('offers the operator hold where the server places one, and only with a reason', async () => {
        for (const status of ['ASSIGNED', 'ON_HOLD', 'DELIVERING']) {
            expect(actions(richTask({ status, hold: null })).find('.holdrow').exists(), status).toBe(false)
        }
        expect(actions(richTask({ status: 'QUEUED', hold: null }), false).find('.holdrow').exists()).toBe(false)

        const w = actions(richTask({ status: 'AWAITING_COORDINATOR', hold: null }))
        const hold = () => button(w, 'Put on hold (operator)')!
        expect(hold().attributes('disabled')).toBeDefined()
        await w.find('.holdrow input').setValue('   ')
        expect(hold().attributes('disabled')).toBeDefined()
        await w.find('.holdrow input').setValue(' waiting on legal ')
        expect(hold().attributes('disabled')).toBeUndefined()
        await hold().trigger('click')
        expect(w.emitted('operator-hold')?.[0]?.[0]).toMatchObject({ reason: 'waiting on legal' })
    })
})

// RD2-16: a paused board shows on the task page, and a disabled action says why -- as the server would.
describe('task page hints', () => {
    const paused = { pause: { level: 'OPERATOR', reason: 'release freeze', pausedBy: { kind: 'USER', name: 'pm@example.com' } } }
    const hintOf = (w: any, testid: string) => w.find(`[data-testid="${testid}"]`).element.closest('.dhint')?.getAttribute('data-hint') ?? null

    it('shows the board\'s pause above the actions, with its level, reason and holder', () => {
        const w = mount(TaskHeader, { props: { task: richTask({ status: 'QUEUED', hold: null }), tasks: [], roles: fixtureRoles,
            priorityLevels: [], board: paused } })
        const banner = w.find('[data-testid="task-board-pause"]')
        expect(banner.text()).toBe('Board paused (OPERATOR) — no new assignments and no reopening. Reason: release freeze. Paused by pm@example.com.')
        const free = mount(TaskHeader, { props: { task: richTask({ status: 'QUEUED', hold: null }), tasks: [], roles: fixtureRoles,
            priorityLevels: [], board: { pause: { level: 'NONE' } } } })
        expect(free.find('[data-testid="task-board-pause"]').exists()).toBe(false)
    })

    it('says why Complete, Authorize, Order and Hold are disabled, and nothing once they are not', async () => {
        const assigned = mount(TaskActions, { props: { task: richTask({ status: 'ASSIGNED', hold: null }), roles: fixtureRoles,
            board: {}, canReopen: true, admin: true } })
        expect(assigned.find('[data-testid="complete-open"]').attributes('disabled')).toBeDefined()
        expect(hintOf(assigned, 'complete-open')).toBe('assigned to a session; unassign or force-close it first')

        const w = mount(TaskActions, { props: { task: richTask({ status: 'AWAITING_COORDINATOR', hold: null, orderIndex: 3 }),
            roles: fixtureRoles, board: {}, canReopen: true, admin: true } })
        expect(hintOf(w, 'complete-open')).toBeNull()
        expect(hintOf(w, 'authorize')).toBeNull()
        ;(w.vm as any).authorizeRole = null  // the task's role is picked for you; cleared, Authorize says why it waits
        await w.vm.$nextTick()
        expect(hintOf(w, 'authorize')).toBe('pick the role to authorize it for')
        expect(hintOf(w, 'order-set')).toBe('that is its order already')
        expect(hintOf(w, 'hold-place')).toBe('say why it is held')
        ;(w.vm as any).authorizeRole = 'coder'
        ;(w.vm as any).orderDraft = 5
        ;(w.vm as any).holdReason = 'legal review'
        await w.vm.$nextTick()
        for (const id of ['authorize', 'order-set', 'hold-place']) expect(hintOf(w, id), id).toBeNull()
    })

    it('refuses Reopen on a paused board, saying so, as the server does', async () => {
        const done = richTask({ status: 'COMPLETED', hold: null })
        const w = mount(TaskActions, { props: { task: done, roles: fixtureRoles, board: paused, canReopen: true, admin: true } })
        ;(w.vm as any).reopenRole = 'coder'
        ;(w.vm as any).reopenReason = 'PR conflicts'
        await w.vm.$nextTick()
        expect(w.find('[data-testid="reopen"]').attributes('disabled')).toBeDefined()
        expect(hintOf(w, 'reopen')).toBe('board paused by the operator: release freeze; resume it before reopening')
        const open = mount(TaskActions, { props: { task: done, roles: fixtureRoles, board: {}, canReopen: true, admin: true } })
        expect(hintOf(open, 'reopen')).toBe('pick the role to reopen it to')
        ;(open.vm as any).reopenRole = 'coder'
        ;(open.vm as any).reopenReason = 'PR conflicts'
        await open.vm.$nextTick()
        expect(open.find('[data-testid="reopen"]').attributes('disabled')).toBeUndefined()
        expect(hintOf(open, 'reopen')).toBeNull()
    })
})


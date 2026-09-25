// @vitest-environment happy-dom
//
// An admin's strength and operator-hold rows in Task actions (task 6fdc5a37): shown to an admin,
// the hold only where the server places one and never without a reason.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskActions from './TaskActions.vue'
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

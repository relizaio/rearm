// @vitest-environment happy-dom
//
// A task's budget on the task page and the drawer preview (task 6f1b348d): the row in Task actions
// sets and clears it in micros, and Usage reads the spend against it.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskActions from './TaskActions.vue'
import TaskUsage from './TaskUsage.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

function button (w: any, text: string) {
    return w.findAll('button').find((b: any) => b.text() === text)
}

describe('task budget', () => {
    it('sets a budget in micros and says who set it', async () => {
        const w = mount(TaskActions, { props: { task: richTask({ status: 'QUEUED', hold: null, budgetMicros: null }),
            roles: fixtureRoles, board: {} } })
        expect(button(w, 'Set budget')!.attributes('disabled')).toBeDefined()
        await w.find('.budrow input').setValue('2.5')
        await w.find('.budrow input').trigger('blur')
        await button(w, 'Set budget')!.trigger('click')
        expect(w.emitted('set-budget')?.[0]?.[0]).toMatchObject({ budgetMicros: 2_500_000 })

        const set = mount(TaskActions, { props: { task: richTask({ status: 'QUEUED', hold: null, budgetMicros: 2_500_000,
            budgetSetBy: { kind: 'USER', uuid: 'u1', name: 'pavel' }, budgetSetAt: '2026-09-25T09:00:00Z' }),
            roles: fixtureRoles, board: {} } })
        expect(set.find('.budrow').text()).toContain('set by pavel')
    })

    it('clears the budget when the field is emptied', async () => {
        const w = mount(TaskActions, { props: { task: richTask({ status: 'QUEUED', hold: null, budgetMicros: 2_500_000 }),
            roles: fixtureRoles, board: {} } })
        await w.find('.budrow input').setValue('')
        await w.find('.budrow input').trigger('blur')
        await button(w, 'Clear budget')!.trigger('click')
        expect(w.emitted('set-budget')?.[0]?.[0]).toMatchObject({ budgetMicros: null })
    })

    it('reads the spend against the budget in Usage, and says nothing without one', () => {
        const w = mount(TaskUsage, { props: { task: richTask({ budgetMicros: 1_000_000 }) } })
        expect(w.find('.budtag').text()).toMatch(/of this task's budget$/)
        expect(w.find('.budtag').text()).toContain('$0.42')
        expect(mount(TaskUsage, { props: { task: richTask({ budgetMicros: null }) } }).find('.budtag').exists()).toBe(false)
    })
})

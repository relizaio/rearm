// @vitest-environment happy-dom
//
// Task level in the task header and in Task actions (RD2-1): the chip after the key, and the editor
// row that sets, clears and says who set it.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskTitle from './TaskTitle.vue'
import TaskActions from './TaskActions.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

function button (w: any, text: string) {
    return w.findAll('button').find((b: any) => b.text() === text)
}

describe('the level chip', () => {
    it('sits after the key; none without a level', () => {
        const set = mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: 2 }), board: {} } })
        const chip = set.find('[data-testid="level-chip"]')
        expect(chip.text()).toBe('L2')
        const title = set.find('.dhead__title').html()
        expect(title.indexOf('RD2-1')).toBeLessThan(title.indexOf('L2'))
        expect(mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: null }), board: { defaultTaskLevel: 1 } } })
            .find('[data-testid="level-chip"]').text()).toBe('L1')
        expect(mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: null }), board: {} } })
            .find('[data-testid="level-chip"]').exists()).toBe(false)
    })
})

describe('the level editor', () => {
    const actions = (task: any, board: any = { defaultTaskLevel: 1 }) =>
        mount(TaskActions, { props: { task, roles: fixtureRoles, board, canReopen: true, admin: true } })

    it('reads the board default as its placeholder and sends only a change', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, level: null }))
        expect(w.find('.lvlrow input').attributes('placeholder')).toBe('board default 1')
        expect(button(w, 'Set level')!.attributes('disabled')).toBeDefined()
        expect(button(w, 'Clear') === undefined || !w.find('[data-testid="level-clear"]').exists()).toBe(true)
        await w.find('.lvlrow input').setValue('3')
        await w.find('.lvlrow input').trigger('blur')
        await button(w, 'Set level')!.trigger('click')
        expect(w.emitted('set-level')?.[0]?.[0]).toMatchObject({ level: 3 })
    })

    it('clears a level and says who set it', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, level: 2,
            levelSetBy: { kind: 'USER', uuid: 'u1', name: 'pavel' }, levelSetAt: '2026-09-27T09:00:00Z' }))
        expect(w.find('.lvlrow').text()).toContain('set by pavel')
        expect(button(w, 'Set level')!.attributes('disabled')).toBeDefined()
        await w.find('[data-testid="level-clear"]').trigger('click')
        expect(w.emitted('set-level')?.[0]?.[0]).toMatchObject({ level: null })
    })
})

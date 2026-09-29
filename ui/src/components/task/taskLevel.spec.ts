// @vitest-environment happy-dom
//
// Task level in the task header and in Task actions (RD2-1): the chip after the key, and the editor
// row that sets, clears and says who set it. Only on a board with a ladder, its rungs by name (task RD3-6).
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import TaskTitle from './TaskTitle.vue'
import TaskActions from './TaskActions.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const LADDER = { levels: [{ number: 0, name: 'requirements' }, { number: 1, name: 'solution' },
    { number: 2, name: 'objects' }, { number: 3, name: 'components' }] }
const laddered = (extra: any = {}) => ({ ladder: LADDER, ...extra })

function button (w: any, text: string) {
    return w.findAll('button').find((b: any) => b.text() === text)
}

describe('the level chip', () => {
    it('sits after the key, named; the board default for a task without its own, 0 when unset', () => {
        const set = mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: 2 }), board: laddered() } })
        const chip = set.find('[data-testid="level-chip"]')
        expect(chip.text()).toBe('2 · objects')
        const title = set.find('.dhead__title').html()
        expect(title.indexOf('RD2-1')).toBeLessThan(title.indexOf('2 · objects'))
        expect(mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: null }), board: laddered({ defaultTaskLevel: 1 }) } })
            .find('[data-testid="level-chip"]').text()).toBe('1 · solution')
        expect(mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: null }), board: laddered() } })
            .find('[data-testid="level-chip"]').text()).toBe('0 · requirements')
    })

    it('is hidden on a board without a ladder, whatever level the task kept', () => {
        for (const board of [{}, { defaultTaskLevel: 1 }, { ladder: null }, { ladder: { levels: [] } }]) {
            const w = mount(TaskTitle, { props: { task: richTask({ key: 'RD2-1', level: 2, effectiveLevel: 2 }), board } })
            expect(w.find('[data-testid="level-chip"]').exists(), JSON.stringify(board)).toBe(false)
        }
    })
})

describe('the level editor', () => {
    const actions = (task: any, board: any = laddered({ defaultTaskLevel: 1 })) =>
        mount(TaskActions, { props: { task, roles: fixtureRoles, board, canReopen: true, admin: true } })

    it('offers the ladder\'s rungs by name, reads the board default as its placeholder and sends only a change', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, level: null }))
        expect(w.find('[data-testid="level-row"]').text()).toContain('board default 1 · solution')
        const select = w.findAllComponents({ name: 'Select' }).find((c: any) => c.attributes('data-testid') === 'level-select')!
        expect(select.props('options')).toEqual([{ label: '0 · requirements', value: 0 }, { label: '1 · solution', value: 1 },
            { label: '2 · objects', value: 2 }, { label: '3 · components', value: 3 }])
        expect(button(w, 'Set level')!.attributes('disabled')).toBeDefined()
        expect(w.find('[data-testid="level-clear"]').exists()).toBe(false)
        ;(w.vm as any).levelDraft = 3
        await nextTick()
        await button(w, 'Set level')!.trigger('click')
        expect(w.emitted('set-level')?.[0]?.[0]).toMatchObject({ level: 3 })
    })

    it('refuses a level off the ladder', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, level: null }))
        ;(w.vm as any).levelDraft = 4
        await nextTick()
        expect(button(w, 'Set level')!.attributes('disabled')).toBeDefined()
    })

    it('clears a level and says who set it', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, level: 2,
            levelSetBy: { kind: 'USER', uuid: 'u1', name: 'pavel' }, levelSetAt: '2026-09-27T09:00:00Z' }))
        expect(w.find('.lvlrow').text()).toContain('set by pavel')
        expect(button(w, 'Set level')!.attributes('disabled')).toBeDefined()
        await w.find('[data-testid="level-clear"]').trigger('click')
        expect(w.emitted('set-level')?.[0]?.[0]).toMatchObject({ level: null })
    })

    it('is hidden on a board without a ladder', () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, level: 2 }), { defaultTaskLevel: 1 })
        expect(w.find('[data-testid="level-row"]').exists()).toBe(false)
        expect(button(w, 'Set level')).toBeUndefined()
    })
})

// @vitest-environment happy-dom
//
// Task groups and tags in the task header and in Task actions (RD2-31): the group chip in its colour,
// the gated tag and the tag chips; the move control and the tags editor.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import TaskTitle from './TaskTitle.vue'
import TaskActions from './TaskActions.vue'
import { fixtureRoles, richTask } from './taskFixtures'
import { groupColour, NO_GROUP } from '@/utils/agentTaskGroups'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const board = {
    groups: [
        { key: 'core-work', name: 'Core services', order: 1, status: 'OPEN' },
        { key: 'ui-work', name: 'Front end', order: 2, status: 'OPEN', dependsOn: ['core-work'] },
        { key: 'old-work', order: 3, status: 'CLOSED' },
    ],
}

describe('the header', () => {
    it('shows the group after the key and level, in its colour', () => {
        const w = mount(TaskTitle, { props: { task: richTask({ key: 'RD2-9', level: 1, group: { key: 'ui-work', name: 'Front end' },
            tags: [] }), board } })
        const chip = w.find('[data-testid="group-chip"]')
        expect(chip.text()).toBe('ui-work')
        expect(chip.attributes('style')).toContain(`--n-color: ${groupColour('ui-work')}22`)
        const title = w.find('.dhead__title').html()
        expect(title.indexOf('L1')).toBeLessThan(title.indexOf('ui-work'))
        expect(mount(TaskTitle, { props: { task: richTask({ group: null }), board } }).find('[data-testid="group-chip"]').exists()).toBe(false)
    })

    it('shows the tags and the groups a gated task waits on', () => {
        const w = mount(TaskTitle, { props: { task: richTask({ group: { key: 'ui-work' }, waitingOnGroups: ['core-work'],
            tags: [{ key: 'client-req' }, { key: 'urgent' }] }), board } })
        expect(w.find('[data-testid="waiting-chip"]').text()).toBe('waiting on group core-work')
        expect(w.findAll('[data-testid="tag-chip"]').map(t => t.text())).toEqual(['#client-req', '#urgent'])
        const plain = mount(TaskTitle, { props: { task: richTask({ waitingOnGroups: [], tags: [] }), board } })
        expect(plain.find('[data-testid="waiting-chip"]').exists()).toBe(false)
        expect(plain.findAll('[data-testid="tag-chip"]')).toHaveLength(0)
    })
})

describe('Task actions: group and tags', () => {
    const actions = (task: any, b: any = board) =>
        mount(TaskActions, { props: { task, roles: fixtureRoles, board: b, canReopen: true, admin: true } })

    it('moves a task into an open group or out of every group, sending only a change', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, group: { key: 'ui-work' }, tags: [] }))
        const move = w.find('[data-testid="group-move"]')
        expect(move.attributes('disabled')).toBeDefined()
        ;(w.vm as any).groupDraft = NO_GROUP
        await nextTick()
        await move.trigger('click')
        expect(w.emitted('set-group')?.[0]?.[0]).toMatchObject({ group: null })
        ;(w.vm as any).groupDraft = 'core-work'
        await nextTick()
        await move.trigger('click')
        expect(w.emitted('set-group')?.[1]?.[0]).toMatchObject({ group: 'core-work' })
    })

    it('offers the open groups, none first; no move row on a board without groups', () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, group: null, tags: [] }))
        expect((w.vm as any).groupDraft).toBe(NO_GROUP)
        expect(w.find('[data-testid="group-move-row"]').exists()).toBe(true)
        const bare = actions(richTask({ status: 'QUEUED', hold: null, group: null, tags: [] }), {})
        expect(bare.find('[data-testid="group-move-row"]').exists()).toBe(false)
    })

    it('saves the tags whole, normalised, only when they changed', async () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, tags: [{ key: 'client-req', value: 'R-7' }] }))
        const save = w.find('[data-testid="tags-save"]')
        expect(save.attributes('disabled')).toBeDefined()
        ;(w.vm as any).tagsDraft = ['client-req', 'urgent']
        await nextTick()
        await save.trigger('click')
        expect(w.emitted('set-tags')?.[0]?.[0]).toMatchObject({ tags: [{ key: 'client-req', value: 'R-7' }, { key: 'urgent' }] })
    })

    it('names the add-tag trigger for a screen reader (run 1 observation 1)', () => {
        const w = actions(richTask({ status: 'QUEUED', hold: null, tags: [] }))
        const add = w.find('[data-testid="tags-add"]')
        expect(add.attributes('aria-label')).toBe('Add tag')
        expect(add.text()).toBe('+ tag')
    })
})

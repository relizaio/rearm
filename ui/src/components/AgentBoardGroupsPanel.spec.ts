// @vitest-environment happy-dom
//
// The board page's Groups tab (RD2-31): a row per group in the board's order with its progress, spend,
// what it waits on and whether it is closed; the add form's variables, and a refusal beside its field.
import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import Panel from './AgentBoardGroupsPanel.vue'

// A board with a level ladder (task RD3-6): the group default level is shown by name and edited.
const LADDER = { levels: [{ number: 0, name: 'requirements' }, { number: 1, name: 'solution' }, { number: 2, name: 'objects' }] }
const board = {
    ladder: LADDER,
    groups: [
        { uuid: 'g2', key: 'ui-work', name: 'Front end', order: 2, dependsOn: ['core-work'], status: 'OPEN', defaultWorkLevel: 2,
            progress: { total: 4, done: 1, open: 3, complete: false }, spentMicros: 1_250_000 },
        { uuid: 'g1', key: 'core-work', name: 'Core services', order: 1, dependsOn: [], status: 'OPEN',
            progress: { total: 5, done: 3, open: 2, complete: false }, spentMicros: 0 },
        { uuid: 'g3', key: 'old-work', order: 3, dependsOn: [], status: 'CLOSED', progress: { total: 2, done: 2, open: 0, complete: true } },
    ],
}

// The form sits in a modal, which teleports: render it in place.
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

describe('AgentBoardGroupsPanel', () => {
    it('lists the groups in order, with progress, spend, waiting and closed', () => {
        const w = mount(Panel, { props: { board, canConfigure: false, saveGroup: vi.fn() }, global })
        const rows = w.findAll('[data-testid="group-row"]')
        expect(rows.map(r => r.attributes('data-key'))).toEqual(['core-work', 'ui-work', 'old-work'])
        expect(rows[0].text()).toContain('3 of 5 done')
        expect(rows[1].text()).toContain('Front end')
        expect(rows[1].find('[data-testid="group-waiting"]').text()).toBe('waiting on core-work')
        expect(rows[1].find('[data-testid="group-default-level"]').text()).toBe('2 · objects default')
        expect(rows[1].text()).toContain('$1.25')
        expect(rows[0].find('[data-testid="group-waiting"]').exists()).toBe(false)
        expect(rows[2].find('[data-testid="group-closed"]').exists()).toBe(true)
        expect(w.find('[data-testid="group-edit"]').exists()).toBe(false, 'no edit without CONFIGURATION_WRITE')
        expect(w.find('[data-testid="group-add"]').exists()).toBe(false)
    })

    it('says so when the board has no groups', () => {
        const w = mount(Panel, { props: { board: {}, canConfigure: true, saveGroup: vi.fn() }, global })
        expect(w.text()).toContain('No groups yet')
    })

    it('adds a group with the variables agentBoardGroupSet takes', async () => {
        const saveGroup = vi.fn().mockResolvedValue({})
        const w = mount(Panel, { props: { board, canConfigure: true, saveGroup }, global })
        await w.find('[data-testid="group-add"]').trigger('click')
        await w.find('[data-testid="group-key"] input').setValue(' Docs-Work ')
        await w.find('[data-testid="group-name"] input').setValue('Docs')
        ;(w.vm as any).draft.dependsOn = ['ui-work']
        ;(w.vm as any).draft.defaultWorkLevel = 1
        await nextTick()
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(saveGroup).toHaveBeenCalledWith({ key: 'docs-work', name: 'Docs', description: null, dependsOn: ['ui-work'],
            defaultWorkLevel: 1, status: 'OPEN' })
        expect(w.find('[data-testid="group-form"]').exists()).toBe(false, 'the form closes on save')
    })

    it('shows a refusal beside the field it is about and keeps the form open', async () => {
        const refusal = 'A group key is 2 to 24 lower-case letters, digits and hyphens, starting with a letter or digit (got \'x\')'
        const saveGroup = vi.fn().mockRejectedValue(new Error(`GraphQL error: ${refusal}`))
        const w = mount(Panel, { props: { board, canConfigure: true, saveGroup }, global })
        await w.find('[data-testid="group-add"]').trigger('click')
        await w.find('[data-testid="group-key"] input').setValue('x')
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(saveGroup).toHaveBeenCalledOnce()
        expect(w.find('[data-testid="group-key-error"]').text()).toBe(refusal)
        expect(w.find('[data-testid="group-error"]').exists()).toBe(false)
        expect(w.find('[data-testid="group-form"]').exists()).toBe(true)

        saveGroup.mockRejectedValue(new Error('group cycle: docs-work → ui-work → docs-work'))
        await w.find('[data-testid="group-key"] input').setValue('docs-work')
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="group-depends-error"]').text()).toContain('group cycle')
    })

    it('edits a group by uuid and closes or reopens one from its row', async () => {
        const saveGroup = vi.fn().mockResolvedValue({})
        const w = mount(Panel, { props: { board, canConfigure: true, saveGroup }, global })
        const row = (k: string) => w.find(`[data-testid="group-row"][data-key="${k}"]`)
        await row('ui-work').find('[data-testid="group-edit"]').trigger('click')
        expect((w.vm as any).draft).toMatchObject({ uuid: 'g2', key: 'ui-work', dependsOn: ['core-work'], defaultWorkLevel: 2 })
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(saveGroup).toHaveBeenLastCalledWith(expect.objectContaining({ uuid: 'g2', key: 'ui-work', defaultWorkLevel: 2 }))
        await row('core-work').find('[data-testid="group-close"]').trigger('click')
        expect(saveGroup).toHaveBeenLastCalledWith({ key: 'core-work', status: 'CLOSED' })
        expect(row('old-work').find('[data-testid="group-close"]').text()).toBe('Reopen')
        await row('old-work').find('[data-testid="group-close"]').trigger('click')
        expect(saveGroup).toHaveBeenLastCalledWith({ key: 'old-work', status: 'OPEN' })
    })

    // Tester run 1 T-1: an Add with a key the board has used to overwrite that group without a word.
    it('refuses an Add with a key the board already has, beside the key, and sends nothing', async () => {
        const saveGroup = vi.fn().mockResolvedValue({})
        const w = mount(Panel, { props: { board, canConfigure: true, saveGroup }, global })
        await w.find('[data-testid="group-add"]').trigger('click')
        await w.find('[data-testid="group-key"] input').setValue('Core-Work')
        await w.find('[data-testid="group-name"] input').setValue('dup')
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(saveGroup).not.toHaveBeenCalled()
        expect(w.find('[data-testid="group-key-error"]').text()).toBe('group core-work exists on this board')
        expect(w.find('[data-testid="group-form"]').exists()).toBe(true)
    })

    it('saves an edit under its own key, and refuses one renaming onto another group\'s', async () => {
        const saveGroup = vi.fn().mockResolvedValue({})
        const w = mount(Panel, { props: { board, canConfigure: true, saveGroup }, global })
        await w.find('[data-testid="group-row"][data-key="ui-work"] [data-testid="group-edit"]').trigger('click')
        await w.find('[data-testid="group-key"] input').setValue('old-work')
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(saveGroup).not.toHaveBeenCalled()
        expect(w.find('[data-testid="group-key-error"]').text()).toBe('group old-work exists on this board')
        await w.find('[data-testid="group-key"] input').setValue('ui-work')
        await w.find('[data-testid="group-save"]').trigger('click')
        await flushPromises()
        expect(saveGroup).toHaveBeenCalledWith(expect.objectContaining({ uuid: 'g2', key: 'ui-work' }))
    })

    // Levels are opt-in (task RD3-6): without a ladder the group default is neither shown, edited nor sent.
    describe('on a board without a ladder', () => {
        const flat = { ...board, ladder: null }

        it('hides the default level in the row and the form, and keeps it on save', async () => {
            const saveGroup = vi.fn().mockResolvedValue({})
            const w = mount(Panel, { props: { board: flat, canConfigure: true, saveGroup }, global })
            const row = w.find('[data-testid="group-row"][data-key="ui-work"]')
            expect(row.find('[data-testid="group-default-level"]').exists()).toBe(false)
            expect(row.text()).not.toContain('default')
            await row.find('[data-testid="group-edit"]').trigger('click')
            expect(w.find('[data-testid="group-form"]').exists()).toBe(true)
            expect(w.find('[data-testid="group-level"]').exists()).toBe(false)
            await w.find('[data-testid="group-save"]').trigger('click')
            await flushPromises()
            expect(saveGroup).toHaveBeenCalledOnce()
            expect(saveGroup.mock.calls[0][0]).not.toHaveProperty('defaultWorkLevel')
        })

        it('shows the default level field with a ladder', async () => {
            const w = mount(Panel, { props: { board, canConfigure: true, saveGroup: vi.fn() }, global })
            await w.find('[data-testid="group-add"]').trigger('click')
            expect(w.find('[data-testid="group-level"]').exists()).toBe(true)
        })
    })
})

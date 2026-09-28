// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { fixtureRoles, fixtureTasks, richTask } from './task/taskFixtures'

const dispatch = vi.fn()
const push = vi.fn()
const params = { uuid: 't1' }
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: { orgById: () => ({ settings: { findingPriorityLevels: 3 } }), myuser: null } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params }), useRouter: () => ({ push, back: vi.fn() }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const { default: Page } = await import('./AiAgentTaskPage.vue')

function serve (task: any) {
    dispatch.mockImplementation(async (action: string, arg: any) => {
        switch (action) {
        case 'fetchAgentTask': return arg === task.uuid ? task : null
        case 'fetchAgentBoard': return { uuid: 'b1', name: 'Dogfood', completionPriority: null, myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }
        case 'fetchAgentTasksOfBoard': return fixtureTasks(task)
        case 'fetchAgentTaskRoleConfigsOfBoard': return fixtureRoles
        case 'fetchAgentsOfOrg': return [{ uuid: 'a1', name: 'Arch' }]
        default: return null
        }
    })
}

describe('AiAgentTaskPage', () => {
    beforeEach(() => { dispatch.mockReset(); push.mockReset(); params.uuid = 't1' })

    it('loads from the uuid alone: the task, then its board, tasks, roles and agents', async () => {
        serve(richTask())
        mount(Page)
        await flushPromises()
        expect(dispatch.mock.calls[0]).toEqual(['fetchAgentTask', 't1'])
        const rest = dispatch.mock.calls.slice(1).map(c => c[0]).sort()
        expect(rest).toEqual(['fetchAgentBoard', 'fetchAgentTaskRoleConfigsOfBoard', 'fetchAgentTasksOfBoard', 'fetchAgentsOfOrg'])
        expect(dispatch).toHaveBeenCalledWith('fetchAgentBoard', 'b1')
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b1' })
        expect(dispatch).toHaveBeenCalledWith('fetchAgentsOfOrg', 'o1')
    })

    it('renders every section for a task with three review rounds, questions and documents', async () => {
        serve(richTask())
        const w = mount(Page)
        await flushPromises()
        const headings = w.findAll('.dsec__h').map(h => h.text())
        for (const h of ['Human review', 'Task actions', 'Dependencies', 'Lineage', 'Usage', 'File a finding',
            'Documents', 'Hops', 'Pull requests', 'Waiting on', 'Answer', 'Status history', 'Provenance']) {
            expect(headings, h).toContain(h)
        }
        // One word, one list (RD2-23, sweep UI-15): the hops are "Hops", the revisions "Revisions".
        expect(headings).not.toContain('History')
        expect(headings.some(h => h.startsWith('Review findings') && h.includes('round 3'))).toBe(true)
        expect(headings.some(h => h.startsWith('Test findings'))).toBe(true)
        // the newest review round only: F-4 is new in round 3, F-1 is shown closed
        expect(w.text()).toContain('F-4')
        expect(w.findAll('.drow')).toHaveLength(5)
        // each document says whether it was handed over or reviewed (0192a587)
        expect(w.findAll('.drow').map(r => r.text()).every(t => /draft|handed over|reviewed/.test(t))).toBe(true)
        expect(w.text()).toContain('Dogfood')
        expect(w.find('.tpage__main').exists() && w.find('.tpage__side').exists()).toBe(true)
        // the controls sit in the side column, the record in the main one
        expect(w.find('.tpage__side').text()).toContain('Task actions')
        expect(w.find('.tpage__main').text()).toContain('Status history')
    })

    it('shows the open questions when the task is with the role meant to answer them', async () => {
        serve(richTask({ status: 'QUEUED', hold: null }))
        const w = mount(Page)
        await flushPromises()
        const headings = w.findAll('.dsec__h').map(h => h.text())
        expect(headings).toContain('Open questions')
        expect(headings).not.toContain('Answer')
    })

    it('says so when the task is not found', async () => {
        serve(richTask())
        params.uuid = 'missing'
        const w = mount(Page)
        await flushPromises()
        expect(w.text()).toContain('Task not found')
        expect(w.find('.tpage').exists()).toBe(false)
    })

    it('opens a dependency on its own page', async () => {
        serve(richTask())
        const w = mount(Page)
        await flushPromises()
        await w.find('.depclick').trigger('click')
        expect(push).toHaveBeenCalledWith('/aiAgentTask/t0')
    })

    it('reloads the task after an action and stays on it', async () => {
        serve(richTask())
        const w = mount(Page)
        await flushPromises()
        const before = dispatch.mock.calls.filter(c => c[0] === 'fetchAgentTask').length
        w.findComponent({ name: 'TaskHeader' }).vm.$emit('require-review', { task: richTask(), value: true })
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('agentTaskRequireHumanReview', { taskUuid: 't1', value: true })
        expect(dispatch.mock.calls.filter(c => c[0] === 'fetchAgentTask').length).toBe(before + 1)
        expect(push).not.toHaveBeenCalled()
    })

    it('shows the description whole under the title (fceb1e57)', async () => {
        const long = 'Why: titles carried whole briefs.\nWhat: a description beside a one-line title.'
        serve(richTask({ description: long }))
        const w = mount(Page)
        await flushPromises()
        const desc = w.find('.dhead__desc')
        expect(desc.element.textContent).toBe(long)
        expect(desc.classes()).not.toContain('dhead__desc--clamped')
        expect(desc.attributes('title')).toBeUndefined()
    })
})

// RD2-9: a refusal says what it needs; the page does not read it as a missing task.
describe('AiAgentTaskPage: a task the person cannot read', () => {
    beforeEach(() => { dispatch.mockReset(); params.uuid = 't1' })

    it('says the board needs Board read, and keeps any other error\'s words', async () => {
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentTask') throw new Error('GraphQL error: Not authorized: this needs BOARD_READ on board X')
            return null
        })
        const w = mount(Page)
        await flushPromises()
        expect(w.text()).toContain("You don't have access to this task's board (needs Board read)")
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentTask') throw new Error('GraphQL error: timeout')
            return null
        })
        const other = mount(Page)
        await flushPromises()
        expect(other.text()).toContain('Could not load the task: timeout')
    })
})

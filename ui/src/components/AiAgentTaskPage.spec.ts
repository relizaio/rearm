// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { fixtureRoles, fixtureTasks, richTask, staleHopMark, staleTask } from './task/taskFixtures'

const dispatch = vi.fn()
const push = vi.fn()
const params = { uuid: 't1' }
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: { orgById: () => ({ settings: { reviewItemPriorityLevels: 3 } }), myuser: null } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params }), useRouter: () => ({ push, back: vi.fn() }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const { default: Page } = await import('./AiAgentTaskPage.vue')

function serve (task: any, myPermissions: string[] = ['BOARD_READ', 'BOARD_WRITE']) {
    dispatch.mockImplementation(async (action: string, arg: any) => {
        switch (action) {
        case 'fetchAgentTask': return arg === task.uuid ? task : null
        case 'fetchAgentBoard': return { uuid: 'b1', name: 'Dogfood', completionPriority: null, myPermissions }
        case 'fetchAgentRevisions': return [{ revision: 1, at: '2026-09-25T09:00:00Z', task: { uuid: task.uuid, status: 'QUEUED' } }]
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
        // The page reads its board's other tasks at card level, as the board's views do: a large board's whole
        // read was most of the page's load, and the page reads only their key, title, status and links.
        expect(dispatch).toHaveBeenCalledWith('fetchAgentTasksOfBoard', { boardUuid: 'b1' })
        expect(dispatch).toHaveBeenCalledWith('fetchAgentsOfOrg', 'o1')
    })

    it('renders every section for a task with three review rounds, questions and documents', async () => {
        serve(richTask())
        const w = mount(Page)
        await flushPromises()
        const headings = w.findAll('.dsec__h').map(h => h.text())
        for (const h of ['Human review', 'Task actions', 'Dependencies', 'Lineage', 'Usage', 'File a review item',
            'Documents', 'Hops', 'Pull requests', 'Waiting on', 'Answer', 'Status history', 'Provenance']) {
            expect(headings, h).toContain(h)
        }
        // One word, one list (RD2-23, sweep UI-15): the hops are "Hops", the revisions "Revisions".
        expect(headings).not.toContain('History')
        expect(headings.some(h => h.startsWith('Review items') && h.includes('round 3'))).toBe(true)
        expect(headings.some(h => h.startsWith('Test report items'))).toBe(true)
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

    // A staleness mark (TPF-69) is its own block beside the assignment, on the marks alone.
    it('shows a stale hop beside the current assignment', async () => {
        serve(staleTask('assigned'))
        const w = mount(Page)
        await flushPromises()
        const line = w.find('[data-testid="task-stale"]')
        expect(line.exists()).toBe(true)
        expect(line.text()).toContain('stale: no progress 70 min')
        expect(line.find('.tstale').attributes('title')).toBe(`hopNoProgress: ${staleHopMark.message}`)
        expect(w.findAll('.dsec__h').map(h => h.text())).toContain('Current assignment')
    })

    it('shows a stuck delivery although the task has no assignment', async () => {
        serve(staleTask('delivering'))
        const w = mount(Page)
        await flushPromises()
        const line = w.find('[data-testid="task-stale"]')
        expect(line.exists()).toBe(true)
        expect(line.text()).toContain('stale: delivery stuck 3 h')
        expect(w.findAll('.dsec__h').map(h => h.text())).not.toContain('Current assignment')
    })

    it('shows no stale block on a task without a mark', async () => {
        serve(staleTask('assigned', { stale: [] }))
        const w = mount(Page)
        await flushPromises()
        expect(w.find('[data-testid="task-stale"]').exists()).toBe(false)
        expect(w.findAll('.dsec__h').map(h => h.text())).toContain('Current assignment')
    })

    it('keeps the task in its first tab and the revisions in a second, read only when that tab opens', async () => {
        serve(richTask())
        const w = mount(Page)
        await flushPromises()
        const tabs = w.findAll('.n-tabs-tab').map(t => t.text())
        expect(tabs).toEqual(['Task', 'Revisions'])
        expect(w.find('.tpage__main').exists()).toBe(true)
        expect(w.find('.revhist').exists()).toBe(false, 'the revisions pane is not mounted before its tab opens')
        expect(dispatch.mock.calls.some(c => c[0] === 'fetchAgentRevisions')).toBe(false)
        await w.findAll('.n-tabs-tab')[1].trigger('click')
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchAgentRevisions', { kind: 'task', uuid: 't1', limit: 20, offset: 0 })
        expect(w.find('.revhist .revhist__rev').exists()).toBe(true)
        expect(w.find('.revhist .n-collapse').exists()).toBe(false, 'no collapse inside the tab')
        await w.findAll('.n-tabs-tab')[0].trigger('click')
        await w.findAll('.n-tabs-tab')[1].trigger('click')
        await flushPromises()
        expect(dispatch.mock.calls.filter(c => c[0] === 'fetchAgentRevisions')).toHaveLength(1, 'kept, not re-read')
    })

    it('offers no Revisions tab without Board read on the board', async () => {
        serve(richTask(), [])
        const w = mount(Page)
        await flushPromises()
        expect(w.findAll('.n-tabs-tab').map(t => t.text())).toEqual(['Task'])
        expect(dispatch.mock.calls.some(c => c[0] === 'fetchAgentRevisions')).toBe(false)
    })

    it('exports through an icon button with its tooltip, loading while the export runs', async () => {
        serve(richTask())
        let finish: (v: any) => void = () => {}
        const base = dispatch.getMockImplementation()!
        dispatch.mockImplementation(async (action: string, arg: any) =>
            action === 'exportAgentTask' ? new Promise(r => { finish = r }) : base(action, arg))
        const w = mount(Page)
        await flushPromises()
        const btn = w.find('[data-testid="task-export"]')
        expect(btn.text()).toBe('')
        expect(btn.attributes('aria-label')).toBe('Export task as JSON')
        expect(btn.find('svg').exists()).toBe(true)
        await btn.trigger('click')
        expect(dispatch).toHaveBeenCalledWith('exportAgentTask', 't1')
        expect(w.find('[data-testid="task-export"]').attributes('disabled')).toBeDefined()
        finish(null)
        await flushPromises()
        expect(w.find('[data-testid="task-export"]').attributes('disabled')).toBeUndefined()
        expect(w.text()).toContain('Export failed: Task not found')
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

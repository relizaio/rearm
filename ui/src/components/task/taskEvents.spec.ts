// @vitest-environment happy-dom
//
// The events of the task sections are the handler maps of the two hosts: the board panel listens to
// the drawer by name, and the task page handles them itself. This pins which section emits which
// event, that the drawer forwards those of the sections it shows with their payloads intact, and
// that the page acts on every one of them.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TaskActions from './TaskActions.vue'
import TaskDependencies from './TaskDependencies.vue'
import TaskDocuments from './TaskDocuments.vue'
import TaskFindings from './TaskFindings.vue'
import TaskHeader from './TaskHeader.vue'
import TaskQuestions from './TaskQuestions.vue'
import TaskSummary from './TaskSummary.vue'
import { fixtureRoles, fixtureTasks, richTask } from './taskFixtures'

const dispatch = vi.fn()
const push = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))
vi.mock('vue-router', async (orig) => ({ ...(await orig() as any), useRoute: () => ({ params: { uuid: 't1' } }), useRouter: () => ({ push, back: vi.fn() }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const { default: Drawer } = await import('../AiAgentTaskDetailDrawer.vue')
const { default: Page } = await import('../AiAgentTaskPage.vue')

/** Which section emits which event. */
const EMITTERS: [any, string, string[]][] = [
    [TaskHeader, 'TaskHeader', ['human-review', 'human-signoff', 'operator-release', 'require-review']],
    [TaskActions, 'TaskActions', ['authorize', 'order', 'complete', 'cancel', 'reopen', 'decide', 'set-budget']],
    [TaskDependencies, 'TaskDependencies', ['open']],
    [TaskSummary, 'TaskSummary', ['open']],
    [TaskFindings, 'TaskFindings', ['decide', 'open-element']],
    [TaskQuestions, 'TaskQuestions', ['answer']],
]

/** What the board panel listens to on the drawer, bar close. */
const DRAWER_EVENTS = ['open', 'human-review', 'human-signoff', 'operator-release', 'require-review',
    'authorize', 'order', 'complete', 'cancel', 'reopen', 'decide', 'set-budget']

/** The store action the page runs for each event ('open' navigates instead). */
const PAGE_ACTIONS: Record<string, string> = {
    'human-review': 'agentTaskHumanReview', 'human-signoff': 'agentTaskHumanSignOff',
    'operator-release': 'agentTaskOperatorHold', 'require-review': 'agentTaskRequireHumanReview',
    authorize: 'agentTaskAuthorize', order: 'agentTaskOrder', complete: 'agentTaskComplete',
    cancel: 'agentTaskCancel', reopen: 'agentTaskReopen', decide: 'agentTaskDecideFindings',
    answer: 'agentTaskAnswer', 'set-budget': 'agentTaskSetBudget',
}

const stubs = {
    Drawer: { template: '<div><slot/></div>' },
    DrawerContent: { template: '<div><slot name="header"/><slot/></div>' },
    RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
}

describe('task section events', () => {
    beforeEach(() => { dispatch.mockReset(); push.mockReset() })

    it('each section declares the events the table gives it', () => {
        for (const [comp, name, events] of EMITTERS) {
            expect([...(comp.emits ?? [])].sort(), name).toEqual([...events].sort())
        }
    })

    it('the drawer forwards the events of the sections it shows, payload intact', () => {
        const task = richTask()
        const w = mount(Drawer, {
            props: { task, tasks: fixtureTasks(task), agentNames: {}, roles: fixtureRoles, board: {}, priorityLevels: 3, canReopen: true },
            global: { stubs },
        })
        for (const [comp, name, events] of EMITTERS) {
            const section = w.findComponent(comp)
            if (!section.exists()) continue
            for (const e of events) {
                const payload = { marker: `${name}/${e}` }
                section.vm.$emit(e, payload)
                const got = w.emitted(e) ?? []
                expect(got[got.length - 1], `${name} → ${e}`).toEqual([payload])
            }
        }
        expect(Object.keys(w.emitted()).filter(e => DRAWER_EVENTS.includes(e)).sort()).toEqual([...DRAWER_EVENTS].sort())
    })

    it('the page acts on every section event', async () => {
        const task = richTask()
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentTask') return task
            if (action === 'fetchAgentTasksOfBoard') return fixtureTasks(task)
            if (action === 'fetchAgentTaskRoleConfigsOfBoard') return fixtureRoles
            if (action === 'fetchAgentBoard') return { uuid: 'b1', name: 'b' }
            return []
        })
        const w = mount(Page, { global: { stubs } })
        await flushPromises()
        for (const [comp, name, events] of EMITTERS) {
            if (comp === TaskSummary) continue // the drawer's preview only
            const section = w.findComponent(comp)
            expect(section.exists(), name).toBe(true)
            for (const e of events) {
                if (e === 'open') {
                    section.vm.$emit('open', { uuid: 't0' })
                    expect(push).toHaveBeenLastCalledWith('/aiAgentTask/t0')
                    continue
                }
                if (e === 'open-element') {
                    // stays on the page: the documents section opens the element under its document
                    section.vm.$emit('open-element', 'REQ-1')
                    await flushPromises()
                    expect(w.findComponent(TaskDocuments).props('focus')?.id, `${name} → ${e}`).toBe('REQ-1')
                    continue
                }
                section.vm.$emit(e, { task, role: 'coder', value: true, orderIndex: 1, note: '', reason: 'r',
                    specification: 'REVIEW_FINDINGS', decisions: [], answers: [], approve: true, outcome: 'PASSED' })
                await flushPromises()
                expect(dispatch.mock.calls.map(c => c[0]), `${name} → ${e}`).toContain(PAGE_ACTIONS[e])
            }
        }
    })
})

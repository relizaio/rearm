// @vitest-environment happy-dom
//
// The drawer's events are the panel's handler map (AiAgentBoardsPanel listens for each by name).
// Its sections now live in components/task/*, so this pins that every event the drawer emitted
// before the extraction is still declared by exactly one section and forwarded, payload intact.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Drawer from '../AiAgentTaskDetailDrawer.vue'
import TaskActions from './TaskActions.vue'
import TaskDependencies from './TaskDependencies.vue'
import TaskFindings from './TaskFindings.vue'
import TaskHeader from './TaskHeader.vue'
import TaskQuestions from './TaskQuestions.vue'
import { fixtureRoles, fixtureTasks, richTask } from './taskFixtures'

/** Every event the drawer emitted before its sections were extracted, bar its own close. */
const DRAWER_EVENTS = ['open', 'human-review', 'human-signoff', 'operator-release', 'require-review',
    'answer', 'authorize', 'order', 'complete', 'cancel', 'reopen', 'decide']

/** Which section emits which event. */
const EMITTERS: [any, string, string[]][] = [
    [TaskHeader, 'TaskHeader', ['human-review', 'human-signoff', 'operator-release', 'require-review']],
    [TaskActions, 'TaskActions', ['authorize', 'order', 'complete', 'cancel', 'reopen', 'decide']],
    [TaskDependencies, 'TaskDependencies', ['open']],
    [TaskFindings, 'TaskFindings', ['decide']],
    [TaskQuestions, 'TaskQuestions', ['answer']],
]

const stubs = { Drawer: { template: '<div><slot/></div>' }, DrawerContent: { template: '<div><slot name="header"/><slot/></div>' } }

function mountDrawer (task: any) {
    return mount(Drawer, {
        props: { task, tasks: fixtureTasks(task), agentNames: {}, roles: fixtureRoles, board: {}, priorityLevels: 3, canReopen: true },
        global: { stubs },
    })
}

describe('task section events', () => {
    it('each section declares the events the table gives it', () => {
        for (const [comp, name, events] of EMITTERS) {
            expect([...(comp.emits ?? [])].sort(), name).toEqual([...events].sort())
        }
    })

    it('together the sections declare every event the drawer emitted', () => {
        const declared = new Set(EMITTERS.flatMap(([comp]) => [...(comp.emits ?? [])]))
        for (const e of DRAWER_EVENTS) expect(declared.has(e), e).toBe(true)
    })

    it('the drawer forwards each section event with its payload', () => {
        const w = mountDrawer(richTask())
        for (const [comp, name, events] of EMITTERS) {
            const section = w.findComponent(comp)
            expect(section.exists(), name).toBe(true)
            for (const e of events) {
                const payload = { marker: `${name}/${e}` }
                section.vm.$emit(e, payload)
                const got = w.emitted(e) ?? []
                expect(got[got.length - 1], `${name} → ${e}`).toEqual([payload])
            }
        }
        expect(Object.keys(w.emitted()).filter(e => DRAWER_EVENTS.includes(e)).sort()).toEqual([...DRAWER_EVENTS].sort())
    })
})

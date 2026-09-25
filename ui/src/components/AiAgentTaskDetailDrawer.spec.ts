// @vitest-environment happy-dom
//
// The drawer composed of components/task/*: the same sections, in the same order, as before the
// extraction (headings captured from the pre-extraction drawer on the same fixtures).
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Drawer from './AiAgentTaskDetailDrawer.vue'
import { fixtureRoles, fixtureTasks, fixtureVariants } from './task/taskFixtures'

const stubs = { Drawer: { template: '<div><slot/></div>' }, DrawerContent: { template: '<div><slot name="header"/><slot/></div>' } }

const EXPECTED: Record<string, string[]> = {
        heldWithQuestions: ["Human review", "Task actions", "Dependencies", "Lineage", "Usage", "Review findings  \u00b7 round 3REJECTED", "Test findings  \u00b7 round 1PASSED", "File a finding", "Documents", "History", "Pull requests", "Waiting on", "Answer", "Status history", "Provenance"],
        humanGate: ["Human review", "Task actions", "Dependencies", "Lineage", "Usage", "Review findings  \u00b7 round 3REJECTED", "Test findings  \u00b7 round 1PASSED", "File a finding", "Open questions", "Documents", "History", "Pull requests", "Status history", "Provenance"],
        assigned: ["Human review", "Task actions", "Dependencies", "Lineage", "Current assignment", "Usage", "Review findings  \u00b7 round 3REJECTED", "Test findings  \u00b7 round 1PASSED", "Open questions", "Documents", "History", "Pull requests", "Status history", "Provenance"],
        completed: ["Reopen", "Dependencies", "Lineage", "Usage", "Review findings  \u00b7 round 3REJECTED", "Test findings  \u00b7 round 1PASSED", "Open questions", "Documents", "History", "Pull requests", "Status history", "Provenance"],
}

describe('AiAgentTaskDetailDrawer', () => {
    for (const [name, headings] of Object.entries(EXPECTED)) {
        it(`shows the ${name} sections in order`, () => {
            const task = (fixtureVariants() as Record<string, any>)[name]
            const w = mount(Drawer, {
                props: { task, tasks: fixtureTasks(task), agentNames: {}, roles: fixtureRoles, board: {}, priorityLevels: 3, canReopen: true },
                global: { stubs },
            })
            expect(w.findAll('.dsec__h').map(h => h.text())).toEqual(headings)
            expect(w.text()).toContain(task.title)
        })
    }
})

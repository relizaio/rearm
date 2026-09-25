// @vitest-environment happy-dom
//
// The drawer as a preview (gaps §1.26): header, the verbs that must stay one click away, a summary,
// and the way to the page. The round tables, questions, documents and history are on the page.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Drawer from './AiAgentTaskDetailDrawer.vue'
import { fixtureRoles, fixtureTasks, fixtureVariants, richTask } from './task/taskFixtures'

const stubs = {
    Drawer: { template: '<div><slot/></div>' },
    DrawerContent: { template: '<div><slot name="header"/><slot/></div>' },
    RouterLink: { props: ['to'], template: '<a class="rl" :href="to"><slot/></a>' },
}

function mountDrawer (task: any) {
    return mount(Drawer, {
        props: { task, tasks: fixtureTasks(task), agentNames: { a1: 'Arch' }, roles: fixtureRoles, board: {}, priorityLevels: 3, canReopen: true },
        global: { stubs },
    })
}

const PAGE_ONLY = ['Review findings', 'Test findings', 'File a finding', 'Open questions', 'Documents', 'History',
    'Pull requests', 'Waiting on', 'Answer', 'Status history', 'Provenance', 'Dependencies', 'Lineage', 'Usage',
    'Current assignment']

describe('AiAgentTaskDetailDrawer', () => {
    it('renders the preview blocks and not the round tables', () => {
        for (const [name, task] of Object.entries(fixtureVariants())) {
            const w = mountDrawer(task)
            const headings = w.findAll('.dsec__h').map(h => h.text())
            expect(headings, name).toContain('Summary')
            for (const h of PAGE_ONLY) expect(headings.some(x => x.startsWith(h)), `${name}: ${h}`).toBe(false)
            expect(w.findAll('.frow'), name).toHaveLength(0)
            expect(w.find('.hist').exists(), name).toBe(false)
        }
    })

    it('keeps the controls a person acts from', () => {
        const held = mountDrawer(richTask())
        expect(held.text()).toContain('Operator release')
        expect(held.findAll('.dsec__h').map(h => h.text())).toEqual(['Human review', 'Task actions', 'Summary'])
        const gate = mountDrawer(fixtureVariants().humanGate)
        expect(gate.text()).toContain('Approve reviewer pass')
        const done = mountDrawer(fixtureVariants().completed)
        expect(done.findAll('.dsec__h').map(h => h.text())).toEqual(['Reopen', 'Summary'])
    })

    it('summarises what the page shows in full', () => {
        const w = mountDrawer(richTask({ assignment: { role: 'coder', agent: 'a1', assignedAt: '2026-09-24T09:00:00Z' } }))
        const text = w.find('.tsum').text()
        expect(text).toContain('1 open P2')
        expect(text).toContain('1 open P3')
        expect(text).toContain('1 open, asked by coder')
        expect(text).toContain('after 1 done')
        expect(text).toMatch(/after 1 done\s*· blocks 1/)
        expect(text).toContain('coder · Arch · since')
        expect(text).toContain('$0.42')
        expect(w.findAll('.tsum__doc')).toHaveLength(3)
    })

    it('links to the task page by uuid', () => {
        const w = mountDrawer(richTask())
        const link = w.find('a.rl')
        expect(link.attributes('href')).toBe('/aiAgentTask/t1')
        expect(link.text()).toContain('Open task page')
    })

    it('sends a question hold to the page to be answered', () => {
        const w = mountDrawer(richTask({ hold: { level: 'OPERATOR', kind: 'QUESTION', reason: 'nobody answers', heldBy: null, heldAt: null } }))
        expect(w.text()).toContain('Answer it on the task page')
        expect(w.text()).not.toContain('Answer it under')
    })
})

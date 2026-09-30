// @vitest-environment happy-dom
//
// Investigations on the task page (task RD4-12): the kind chip, the investigation block and its report, the reports
// returned to a commissioning task, and the "Commission investigation" action.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskCommission from './TaskCommission.vue'
import TaskInvestigation from './TaskInvestigation.vue'
import TaskTitle from './TaskTitle.vue'
import { fixtureRoles, fixtureTasks, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const roles = [...fixtureRoles,
    { uuid: 'rc-test', name: 'tester', active: true, kind: 'AGENTIC',
        producesOutputs: [{ specification: 'INVESTIGATION_REPORT', scope: 'TASK', required: true }] }]

function investigationTask (over: Record<string, any> = {}) {
    return richTask({ uuid: 'i1', key: 'RD-7', kind: 'INVESTIGATION', status: 'COMPLETED', role: 'tester', hold: null,
        questionStack: [], prUrls: [], pullRequests: [],
        investigation: { role: 'tester', review: null, deadline: null, returnTo: 'TASK', report: 'rep2',
            commissionedBy: { role: 'architect', session: 's1', task: 't1', by: { kind: 'SESSION', uuid: 's1', name: null } } },
        documents: [{ uuid: 'rep2', version: '2', lifecycle: 'ASSEMBLED', component: 'c', createdDate: '2026-09-29T10:00:00Z',
            document: { specification: 'INVESTIGATION_REPORT', path: 'investigations/RD-7/report-2.md', round: 2, task: 'i1',
                findings: null } }],
        ...over })
}

describe('the investigation block', () => {
    it('shows the kind chip on an investigation and not on a work task', () => {
        expect(mount(TaskTitle, { props: { task: investigationTask(), roles } }).find('[data-testid="kind-chip"]').text())
            .toBe('investigation')
        expect(mount(TaskTitle, { props: { task: richTask(), roles } }).find('[data-testid="kind-chip"]').exists()).toBe(false)
    })

    it('reads the block and names the delivered report', () => {
        const w = mount(TaskInvestigation, { props: { task: investigationTask(), tasks: fixtureTasks(richTask({ key: 'RD-3' })) } })
        const lines = w.findAll('[data-testid="investigation-line"]').map(l => l.text())
        expect(lines[0]).toBe('tester')
        expect(lines[1]).toBe('the architect role from RD-3')
        const rep = w.find('[data-testid="investigation-report"]').text()
        expect(rep).toContain('investigations/RD-7/report-2.md')
        expect(rep).toContain('delivered')
    })

    it('lists the reports returned to a commissioning task, and shows nothing on a plain work task', () => {
        const back = richTask({ reportsReturned: [{ investigation: 'i1', investigationKey: 'RD-7', report: 'rep2', session: 's1',
            role: 'architect', at: '2026-09-29T10:00:00Z', reoffered: true }] })
        const w = mount(TaskInvestigation, { props: { task: back } })
        expect(w.find('[data-testid="report-returned"]').text()).toContain('RD-7')
        expect(w.find('[data-testid="report-returned"]').text()).toContain('offered back to architect')
        expect(mount(TaskInvestigation, { props: { task: richTask() } }).find('[data-testid="task-investigation"]').exists())
            .toBe(false)
    })
})

describe('commissioning from the task page', () => {
    it('is offered only when some role produces the report', () => {
        expect(mount(TaskCommission, { props: { task: richTask(), roles: fixtureRoles } })
            .find('[data-testid="commission-open"]').exists()).toBe(false)
        expect(mount(TaskCommission, { props: { task: richTask(), roles } })
            .find('[data-testid="commission-open"]').exists()).toBe(true)
    })

    it('sends the form as the commission input, the report brought back to this task', async () => {
        const w = mount(TaskCommission, { props: { task: richTask({ uuid: 't1', key: 'RD-3' }), board: { uuid: 'b1' }, roles } })
        await w.find('[data-testid="commission-open"]').trigger('click')
        const vm = w.vm as any
        expect(vm.roleOptions.map((o: any) => o.value)).toEqual(['tester'])
        expect(vm.problem).toBe('Pick the role to investigate')
        vm.draft.role = 'tester'
        vm.draft.title = 'measure the UI'
        vm.draft.brief = 'how slow is the board page'
        await w.vm.$nextTick()
        expect(vm.problem).toBe('')
        vm.submit()
        expect(w.emitted('commission')?.[0]?.[0]).toMatchObject({ input: { boardUuid: 'b1', role: 'tester',
            title: 'measure the UI', brief: 'how slow is the board page', fromTask: 't1', returnTo: 'TASK' } })
        expect(vm.draft).toBeNull()
    })
})

import { describe, expect, it } from 'vitest'
import { commissionDraftOf, commissionInput, commissionProblem, commissionableRoleOptions, commissionsDraftOf,
    commissionsPatch, INTAKE_OPTIONS, investigatingRoleOptions, investigationLines, investigationRequiredRoles,
    isInvestigation, reportOf, reviewRoleOptions } from './agentInvestigation'
import { OUTPUT_TYPE_OPTIONS } from './agentTaskOptions'
import { missingRequiredRoles } from './agentTaskFormat'

// Investigation tasks in the UI (task RD4-12): the option lists, the commission form's input, the task's block, and a
// role's commissions as the role form edits them.
const report = [{ specification: 'INVESTIGATION_REPORT', scope: 'TASK', required: true }]
const roles = [
    { name: 'architect', active: true, kind: 'AGENTIC', producesOutputs: [{ specification: 'ARCHITECTURE', scope: 'TASK' }] },
    { name: 'tester', active: true, kind: 'AGENTIC', producesOutputs: [{ specification: 'TEST_REPORT', scope: 'TASK' }, ...report] },
    { name: 'researcher', active: true, kind: 'AGENTIC', producesOutputs: report },
    { name: 'retired', active: false, kind: 'AGENTIC', producesOutputs: report },
    { name: 'signoff', active: true, kind: 'HUMAN', producesOutputs: report },
]

const investigation = {
    uuid: 'i1', board: 'b1', key: 'RD-7', kind: 'INVESTIGATION', status: 'QUEUED', role: 'tester', signOffs: [],
    investigation: { role: 'tester', review: 'architect', deadline: '2030-01-02T03:04:00Z', returnTo: 'TASK', report: null,
        commissionedBy: { role: 'architect', session: 's1', task: 't1', by: { kind: 'SESSION', uuid: 's1', name: null } } },
}

describe('option lists', () => {
    it('offers every active agentic role that produces the report, as {label, value}', () => {
        expect(investigatingRoleOptions(roles)).toEqual([{ label: 'tester', value: 'tester' },
            { label: 'researcher', value: 'researcher' }])
        expect(investigatingRoleOptions(null)).toEqual([])
    })

    it('offers every other active role to review', () => {
        expect(reviewRoleOptions(roles, 'tester').map(o => o.value)).toEqual(['architect', 'researcher', 'signoff'])
    })

    it('lists the report among the output types, and intake as AUTO then COORDINATOR', () => {
        for (const o of [...OUTPUT_TYPE_OPTIONS, ...INTAKE_OPTIONS]) {
            expect(typeof o.label).toBe('string')
            expect(typeof o.value).toBe('string')
        }
        expect(OUTPUT_TYPE_OPTIONS.map(o => o.value)).toEqual(['REVIEW_FINDINGS', 'TEST_REPORT', 'INVESTIGATION_REPORT'])
        expect(INTAKE_OPTIONS.map(o => o.value)).toEqual(['AUTO', 'COORDINATOR'])
    })

    it('lets a role commission only the others that produce the report', () => {
        expect(commissionableRoleOptions(roles, 'tester').map(o => o.value)).toEqual(['researcher', 'retired', 'signoff'])
    })
})

describe('the commission form', () => {
    const now = Date.parse('2029-01-01T00:00:00Z')

    it('names what is missing, one thing at a time', () => {
        const d = commissionDraftOf()
        expect(commissionProblem(d, now)).toBe('Pick the role to investigate')
        d.role = 'tester'
        expect(commissionProblem(d, now)).toBe('A title is required')
        d.title = 'x'.repeat(121)
        expect(commissionProblem(d, now)).toContain('at most 120')
        d.title = 'measure the UI'
        d.deadline = now - 1
        expect(commissionProblem(d, now)).toContain('deadline has passed')
        d.deadline = null
        expect(commissionProblem(d, now)).toBe('')
    })

    it('sends the task it came from with returnTo TASK, and none with returnTo NONE', () => {
        const d = { ...commissionDraftOf(), role: 'tester', title: ' measure it ', brief: 'how slow', budgetDollars: 2.5,
            deadline: Date.parse('2030-01-02T03:04:00Z'), review: 'architect' }
        expect(commissionInput('b1', { uuid: 't1' }, d, now)).toEqual({ boardUuid: 'b1', role: 'tester', title: 'measure it',
            brief: 'how slow', fromTask: 't1', returnTo: 'TASK', budgetMicros: 2_500_000,
            deadline: '2030-01-02T03:04:00.000Z', review: 'architect' })
        const standalone = commissionInput('b1', { uuid: 't1' }, { ...d, returnToTask: false, brief: ' ' }, now)
        expect(standalone).toMatchObject({ fromTask: null, returnTo: 'NONE', brief: null })
        expect(commissionInput('b1', null, { ...d, role: null }, now)).toBeNull()
    })
})

describe('an investigation on the task page', () => {
    it('is told by its kind and block', () => {
        expect(isInvestigation(investigation)).toBe(true)
        expect(isInvestigation({ kind: 'WORK' })).toBe(false)
        expect(isInvestigation({ kind: 'INVESTIGATION' })).toBe(false)
    })

    it('reads its block in order, naming the commissioning task by key', () => {
        const lines = investigationLines(investigation, uuid => uuid === 't1' ? 'RD-3' : null, iso => `at ${iso}`)
        expect(lines.map(l => l.label)).toEqual(['investigating role', 'commissioned by', 'deliverable', 'review',
            'deadline', 'report goes'])
        expect(lines[1].value).toBe('the architect role from RD-3')
        expect(lines[4].value).toBe('at 2030-01-02T03:04:00Z')
        expect(lines[5].value).toBe('back to RD-3, pinned')
        expect(investigationLines({ kind: 'WORK' })).toEqual([])
    })

    it('shows the delivered report, else the newest round', () => {
        const doc = (uuid: string, round: number) => ({ uuid, lifecycle: 'ASSEMBLED',
            document: { specification: 'INVESTIGATION_REPORT', round, path: `investigations/RD-7/report-${round}.md` } })
        const t = { ...investigation, documents: [doc('r1', 1), doc('r2', 2)] }
        expect(reportOf(t)?.uuid).toBe('r2')
        expect(reportOf({ ...t, investigation: { ...t.investigation, report: 'r1' } })?.uuid).toBe('r1')
        expect(reportOf({ documents: [] })).toBeNull()
    })

    it('needs its investigating role and its reviewer, not the board\'s required roles', () => {
        expect(investigationRequiredRoles(investigation)).toEqual(['tester', 'architect'])
        const board = [{ name: 'coder', active: true, necessity: 'REQUIRED' }]
        expect(missingRequiredRoles(investigation, board)).toEqual(['tester', 'architect'])
        const passed = { ...investigation, signOffs: [{ role: 'tester', outcome: 'PASSED' }] }
        expect(missingRequiredRoles(passed, board)).toEqual(['architect'])
    })
})

describe('a role\'s commissions in the role form', () => {
    it('reads a role\'s block into a draft, AUTO when unset', () => {
        expect(commissionsDraftOf(null)).toEqual({ roles: [], intake: 'AUTO', defaultBudgetDollars: null, review: null })
        expect(commissionsDraftOf({ commissions: { roles: ['tester'], intake: 'COORDINATOR', defaultBudgetMicros: 3_000_000,
            review: 'lead' } })).toEqual({ roles: ['tester'], intake: 'COORDINATOR', defaultBudgetDollars: 3, review: 'lead' })
    })

    it('sends the whole block, or null when it names nobody', () => {
        expect(commissionsPatch({ roles: ['tester', ' '], intake: 'AUTO', defaultBudgetDollars: 1.5, review: null }))
            .toEqual({ roles: ['tester'], intake: 'AUTO', defaultBudgetMicros: 1_500_000, review: null })
        expect(commissionsPatch({ roles: [], intake: 'COORDINATOR', defaultBudgetDollars: 2, review: 'lead' })).toBeNull()
        expect(commissionsPatch(null)).toBeNull()
    })
})

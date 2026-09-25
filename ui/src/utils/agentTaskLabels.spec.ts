import { describe, it, expect } from 'vitest'
import { refLabel, roleTagFor, shortRef, subtaskProgress, subtaskTag } from './agentTaskLabels'

const tasks = [
    { uuid: 'c1', status: 'COMPLETED', title: 'one' },
    { uuid: 'c2', status: 'CANCELLED', title: 'two' },
    { uuid: 'c3', status: 'QUEUED', title: 'three' },
    { uuid: 'c4', status: 'ASSIGNED', title: 'four' }
]

describe('subtaskProgress', () => {
    it('counts COMPLETED and CANCELLED as done, and lists the rest', () => {
        const p = subtaskProgress({ childTasks: ['c1', 'c2', 'c3', 'c4'] }, tasks)
        expect([p.done, p.total]).toEqual([2, 4])
        expect(p.open.map((c: any) => c.uuid)).toEqual(['c3', 'c4'])
    })

    it('is empty for a task with no children, and never counts an unloaded child as done', () => {
        expect(subtaskProgress({}, tasks)).toEqual({ done: 0, total: 0, open: [] })
        const p = subtaskProgress({ childTasks: ['c1', 'missing'] }, tasks)
        expect([p.done, p.total, p.open[0].uuid]).toEqual([1, 2, 'missing'])
    })
})

describe('subtaskTag', () => {
    it('says the parent is waiting while any child is open', () => {
        expect(subtaskTag({ status: 'AWAITING_COORDINATOR', childTasks: ['c1', 'c3'] }, tasks))
            .toEqual({ text: 'waiting on subtasks · 1 of 2 done', type: 'warning' })
    })

    it('says so once they are all done, and goes back to a plain count on a finished parent', () => {
        expect(subtaskTag({ status: 'AWAITING_COORDINATOR', childTasks: ['c1', 'c2'] }, tasks))
            .toEqual({ text: 'subtasks done', type: 'success' })
        expect(subtaskTag({ status: 'COMPLETED', childTasks: ['c1', 'c2'] }, tasks))
            .toEqual({ text: '2 subtasks', type: 'info' })
        expect(subtaskTag({ status: 'QUEUED' }, tasks)).toBeNull()
    })
})

describe('roleTagFor', () => {
    it('is the current role on QUEUED (with the order) and ASSIGNED', () => {
        expect(roleTagFor({ status: 'QUEUED', role: 'coder', orderIndex: 12 })?.text).toBe('coder · #12')
        expect(roleTagFor({ status: 'ASSIGNED', role: 'coder', assignment: { role: 'coder' } }))
            .toEqual({ text: 'coder', kind: 'current', tooltip: null })
    })

    it('is history on every status where the board decides next', () => {
        for (const status of ['AWAITING_COORDINATOR', 'ON_HOLD', 'DELIVERING', 'COMPLETED', 'CANCELLED']) {
            const tag = roleTagFor({ status, role: 'coder', orderIndex: 3 })
            expect(tag?.text).toBe('last: coder')
            expect(tag?.kind).toBe('history')
            expect(tag?.tooltip).toContain('board decides')
        }
    })

    it('is absent before intake and without a role', () => {
        expect(roleTagFor({ status: 'PENDING_INTAKE', role: 'coder' })).toBeNull()
        expect(roleTagFor({ status: 'AWAITING_COORDINATOR' })).toBeNull()
    })
})

describe('refLabel and shortRef', () => {
    it('show the tracker ref whatever the board', () => {
        expect(refLabel({ externalRef: 'github:acme/app#42' }, true)).toBe('acme/app#42')
        expect(refLabel({ externalRef: 'github:acme/app#42' }, false)).toBe('acme/app#42')
        expect(shortRef({ externalRef: 'github:acme/app#42' }, false)).toBe('#42')
    })

    it('say "draft" only on a board with sources', () => {
        expect(refLabel({}, true)).toBe('draft (no tracker ref yet)')
        expect(refLabel({}, false)).toBeNull()
        expect(shortRef({}, true)).toBe('draft')
        expect(shortRef({}, false)).toBe('—')
    })
})

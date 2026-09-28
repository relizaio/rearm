import { describe, it, expect } from 'vitest'
import { compareTaskKeys, matchesTaskText, refLabel, roleTagFor, shortRef, subtaskProgress, subtaskTag, waitsOn, waitsOnLabel } from './agentTaskLabels'
import { prChips } from './agentDelivery'

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

// Task keys (task 3d1f9dd7): the table filter matches the key, and the key column sorts by number.
describe('task keys in the table', () => {
    const t = { key: 'RD-42', number: 42, title: 'Reopen a task', externalRef: 'github:acme/widget#7' }
    it('the filter matches the key, the title or the ref, case ignored', () => {
        expect(matchesTaskText(t, 'rd-42')).toBe(true)
        expect(matchesTaskText(t, 'reopen')).toBe(true)
        expect(matchesTaskText(t, 'widget#7')).toBe(true)
        expect(matchesTaskText(t, 'RD-43')).toBe(false)
        expect(matchesTaskText(t, '  ')).toBe(true)
        expect(matchesTaskText({ title: 'no key yet' }, 'no key')).toBe(true)
    })
    it('the key column sorts by number, unnumbered last', () => {
        const rows = [{ key: 'RD-10', number: 10 }, { title: 'old' }, { key: 'RD-9', number: 9 }]
        expect([...rows].sort(compareTaskKeys).map(r => r.key ?? r.title)).toEqual(['RD-9', 'RD-10', 'old'])
    })
})

// RD2-13 (sweep UI-42, UI-38): the card names what it waits on once, by key; and says when a linked PR moved past
// the head its passing test named.
describe('what a card waits on', () => {
    const tasks = [
        { uuid: 'a', key: 'RD-1', status: 'ASSIGNED' },
        { uuid: 'b', key: 'RD-2', status: 'COMPLETED' },
        { uuid: 'c', externalRef: 'github:acme/x#12', status: 'QUEUED' },
    ]

    it('names each open dependency once, by key, else the issue number, else the uuid', () => {
        expect(waitsOn({ status: 'QUEUED', dependsOn: ['a', 'b', 'c', 'zzzzzzzz-9'] }, tasks)).toEqual(['RD-1', '#12', 'zzzzzzzz'])
        expect(waitsOnLabel({ status: 'QUEUED', dependsOn: ['a'] }, tasks)).toBe('waits on RD-1')
    })

    it('says nothing when every dependency is done, or the task is', () => {
        expect(waitsOnLabel({ status: 'QUEUED', dependsOn: ['b'] }, tasks)).toBeNull()
        expect(waitsOnLabel({ status: 'COMPLETED', dependsOn: ['a'] }, tasks)).toBeNull()
        expect(waitsOnLabel({ status: 'QUEUED' }, tasks)).toBeNull()
    })
})

describe('a linked PR moved past the tested head', () => {
    const url = 'https://github.com/acme/x/pull/7'
    it('is flagged when the PR\'s head is not the one the passing test named', () => {
        const moved = prChips({ prUrls: [url], pullRequests: [{ url, state: 'OPEN', head: 'bbbbbbb2' }],
            testedHeads: [{ pr: url, head: 'aaaaaaa1' }] })
        expect(moved.some((c: any) => c.moved)).toBe(true)
        const same = prChips({ prUrls: [url], pullRequests: [{ url, state: 'OPEN', head: 'aaaaaaa1' }],
            testedHeads: [{ pr: url, head: 'aaaaaaa1' }] })
        expect(same.some((c: any) => c.moved)).toBe(false)
    })
})


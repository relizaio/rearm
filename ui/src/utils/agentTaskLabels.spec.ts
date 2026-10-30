import { describe, it, expect } from 'vitest'
import { checkpointPhase, checkpointTag, checkpointTooltip, compareTaskKeys, matchesTaskText, refLabel, roleTagFor, shortRef, subtaskProgress,
    subtaskTag, waitsOn, waitsOnLabel } from './agentTaskLabels'
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

// The design checkpoint's phase, chip and tooltip (task SPLIT-8, T-18 b): the record read beside the task's status,
// its current role and its hops, from the task alone.
const reachedAt = '2026-09-23T10:00:00Z'
function pending (over: Record<string, any> = {}) {
    return { reachedAt, afterRole: 'reviewer', beforeRole: 'coder', design: ['a1', 'r3'], state: 'PENDING',
        settledBy: null, settledAt: null, note: null, ...over }
}
function cpTask (over: Record<string, any> = {}, rec: any = pending()) {
    return { status: 'ASSIGNED', role: 'architect', assignment: { role: 'architect' }, signOffs: [], returns: [],
        childTasks: [], designCheckpoint: rec, ...over }
}

describe('checkpointPhase', () => {
    it('is null without a record', () => {
        expect(checkpointPhase(cpTask({}, null))).toBeNull()
        expect(checkpointPhase(null)).toBeNull()
    })

    it('is waiting only while a PENDING task is AWAITING_COORDINATOR', () => {
        expect(checkpointPhase(cpTask({ status: 'AWAITING_COORDINATOR', role: 'reviewer', assignment: null }))).toBe('waiting')
    })

    it('is ahead for a PENDING record on an open task still with a design role', () => {
        for (const status of ['ASSIGNED', 'QUEUED', 'ON_HOLD', 'DELIVERING', 'PENDING_INTAKE']) {
            expect(checkpointPhase(cpTask({ status }))).toBe('ahead')
        }
        expect(checkpointPhase(cpTask({ status: 'QUEUED', assignment: null, role: 'architect' }))).toBe('ahead')
    })

    it('is closed for a PENDING record on a COMPLETED or CANCELLED task', () => {
        expect(checkpointPhase(cpTask({ status: 'COMPLETED' }))).toBe('closed')
        expect(checkpointPhase(cpTask({ status: 'CANCELLED' }))).toBe('closed')
    })

    it('is lapsed once the task is with the role the checkpoint precedes', () => {
        expect(checkpointPhase(cpTask({ assignment: { role: 'coder' }, role: 'coder' }))).toBe('lapsed')
        // No assignment: the task's own role decides.
        expect(checkpointPhase(cpTask({ status: 'ON_HOLD', assignment: null, role: 'coder' }))).toBe('lapsed')
        // Role names compare lower-cased.
        expect(checkpointPhase(cpTask({ assignment: { role: 'Coder' } }))).toBe('lapsed')
        expect(checkpointPhase(cpTask({ assignment: null, role: 'architect' }, pending({ beforeRole: 'CODER' })))).toBe('ahead')
    })

    it('is lapsed once that role signed off or returned the task after the checkpoint was reached', () => {
        const later = '2026-09-24T10:00:00Z'
        expect(checkpointPhase(cpTask({ status: 'QUEUED', role: 'tester', assignment: null,
            signOffs: [{ role: 'coder', signedOffAt: later, outcome: 'PASSED' }] }))).toBe('lapsed')
        expect(checkpointPhase(cpTask({ status: 'QUEUED', role: 'tester', assignment: null,
            returns: [{ role: 'coder', returnedAt: later }] }))).toBe('lapsed')
        expect(checkpointPhase(cpTask({ status: 'QUEUED', role: 'tester', assignment: null,
            signOffs: [{ role: 'Coder', signedOffAt: later, outcome: 'PASSED' }] }))).toBe('lapsed')
    })

    it('does not count a hop by that role from before the checkpoint was reached', () => {
        // A coder question answered by a design round, then the checkpoint reached on the way back to the coder.
        expect(checkpointPhase(cpTask({ returns: [{ role: 'coder', returnedAt: '2026-09-22T10:00:00Z' }] }))).toBe('ahead')
        expect(checkpointPhase(cpTask({ signOffs: [{ role: 'coder', signedOffAt: '2026-09-22T10:00:00Z' }] }))).toBe('ahead')
        // Nor one at the same instant.
        expect(checkpointPhase(cpTask({ returns: [{ role: 'coder', returnedAt: reachedAt }] }))).toBe('ahead')
        // Nor another role's later hop.
        expect(checkpointPhase(cpTask({ signOffs: [{ role: 'architect', signedOffAt: '2026-09-24T10:00:00Z' }] }))).toBe('ahead')
    })

    it('is sent_on or split for a settled record, whatever the status', () => {
        for (const status of ['AWAITING_COORDINATOR', 'ASSIGNED', 'COMPLETED']) {
            expect(checkpointPhase(cpTask({ status, assignment: { role: 'coder' } }, pending({ state: 'SENT_ON' })))).toBe('sent_on')
            expect(checkpointPhase(cpTask({ status, assignment: { role: 'coder' } }, pending({ state: 'SPLIT' })))).toBe('split')
        }
    })
})

describe('checkpointTag and checkpointTooltip', () => {
    it('says at the design checkpoint, in warning, only while the task waits there', () => {
        const t = cpTask({ status: 'AWAITING_COORDINATOR', role: 'reviewer', assignment: null })
        expect(checkpointTag(t)).toEqual({ text: 'at the design checkpoint', type: 'warning' })
        expect(checkpointTooltip(t)).toBe(`design checkpoint before coder, reached after reviewer ${reachedAt}; waiting for the coordinator`)
    })

    it('says design checkpoint ahead for a task sent back to a design role', () => {
        for (const status of ['ASSIGNED', 'ON_HOLD', 'QUEUED']) {
            const t = cpTask({ status })
            expect(checkpointTag(t)).toEqual({ text: 'design checkpoint ahead', type: 'default' })
            expect(checkpointTooltip(t)).toContain('now with architect')
            expect(checkpointTooltip(t)).toContain('before coder')
            expect(checkpointTooltip(t).endsWith('the task stops at the checkpoint again before coder')).toBe(true)
        }
    })

    it('shows no chip for a closed record', () => {
        const t = cpTask({ status: 'COMPLETED' })
        expect(checkpointTag(t)).toBeNull()
        expect(checkpointTooltip(t).endsWith('; closed from the checkpoint, not settled')).toBe(true)
    })

    it('shows no chip for a lapsed record, and does not say it is ahead', () => {
        const t = cpTask({ assignment: { role: 'coder' }, role: 'coder' })
        expect(checkpointTag(t)).toBeNull()
        expect(checkpointTooltip(t).endsWith('not settled; the task went on to coder')).toBe(true)
        expect(checkpointTooltip(t)).not.toContain('design checkpoint ahead')
    })

    it('names who sent the task on, or just "sent on" without an actor', () => {
        const by = { kind: 'USER', uuid: 'u1', name: 'rhythm@example.org' }
        for (const status of ['ASSIGNED', 'COMPLETED']) {
            const t = cpTask({ status, assignment: { role: 'coder' } },
                pending({ state: 'SENT_ON', settledBy: by, settledAt: '2026-09-23T11:00:00Z', note: 'authorized for coder' }))
            expect(checkpointTag(t)).toEqual({ text: 'sent on by rhythm@example.org', type: 'default' })
            expect(checkpointTooltip(t).endsWith('; sent on by rhythm@example.org 2026-09-23T11:00:00Z: authorized for coder')).toBe(true)
        }
        const anon = cpTask({}, pending({ state: 'SENT_ON' }))
        expect(checkpointTag(anon)).toEqual({ text: 'sent on', type: 'default' })
        expect(checkpointTooltip(anon).endsWith('; sent on')).toBe(true)
    })

    it('counts the children of a split from the task, not the note', () => {
        const t = cpTask({ status: 'QUEUED', childTasks: ['a', 'b'] },
            pending({ state: 'SPLIT', settledBy: { kind: 'SESSION', uuid: 'abcdef0123', name: null }, settledAt: '2026-09-23T11:00:00Z',
                note: '3 children: X-1, X-2, X-3' }))
        expect(checkpointTag(t)).toEqual({ text: 'split into 2', type: 'default' })
        expect(checkpointTooltip(t)).toContain('; split by session abcdef01 2026-09-23T11:00:00Z: 3 children')
        expect(checkpointTag(cpTask({ childTasks: undefined }, pending({ state: 'SPLIT' })))?.text).toBe('split into 0')
    })

    it('says nothing without a record, and the design when afterRole is missing', () => {
        expect(checkpointTag(cpTask({}, null))).toBeNull()
        expect(checkpointTooltip(cpTask({}, null))).toBe('')
        expect(checkpointTooltip(cpTask({ status: 'AWAITING_COORDINATOR' }, pending({ afterRole: null }))))
            .toContain('reached after the design ')
    })
})

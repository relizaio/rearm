import { describe, expect, it } from 'vitest'
import { describeValue, diffSnapshots, historyEntries, revisionSummary, snapshotOf } from './agentRevisions'

describe('agent revisions', () => {
    it('picks the snapshot for the kind and keeps an unreadable one empty', () => {
        expect(snapshotOf('task', { revision: 1, task: { uuid: 't' } })).toEqual({ uuid: 't' })
        expect(snapshotOf('board', { revision: 1, board: { uuid: 'b' } })).toEqual({ uuid: 'b' })
        expect(snapshotOf('role', { revision: 1, roleConfig: { uuid: 'r' } })).toEqual({ uuid: 'r' })
        expect(snapshotOf('task', { revision: 1, task: null })).toBeNull()
    })

    it('lists the live object first so the last edit can be compared', () => {
        const entries = historyEntries('role', [{ revision: 2, at: 'a2', roleConfig: { prompt: 'v2' } },
            { revision: 1, at: 'a1', roleConfig: { prompt: 'v1' } }], { prompt: 'v3' })
        expect(entries.map(e => e.revision)).toEqual([null, 2, 1])
        expect(entries[0].snapshot.prompt).toBe('v3')
        expect(historyEntries('role', [{ revision: 0, roleConfig: {} }]).map(e => e.revision)).toEqual([0])
    })

    it('reports changed, added and removed top-level fields, in the newer order', () => {
        const before = { status: 'QUEUED', role: null, hold: { kind: 'MANUAL', reason: 'r' }, orderIndex: 3 }
        const after = { status: 'ASSIGNED', role: 'coder', hold: null, orderIndex: 3 }
        const d = diffSnapshots(before, after)
        expect(d.map(c => [c.key, c.change])).toEqual([['status', 'changed'], ['role', 'added'], ['hold', 'removed']])
        expect(d[0].before).toBe('QUEUED')
        expect(d[0].after).toBe('ASSIGNED')
        expect(d[2].before).toBe('manual: r') // a hold reads in words (T-1, run 1)
        expect(d[2].after).toBe('—')
    })

    it('does not read a field only one side selected as removed', () => {
        // The live object comes from the board's own query, the revision from the history query.
        expect(diffSnapshots({ prompt: 'a', uiOnly: 1 }, { prompt: 'a', historyOnly: 2 })).toEqual([])
        expect(diffSnapshots({ prompt: 'a' }, { prompt: 'b', extra: 1 }).map(c => c.key)).toEqual(['prompt'])
    })

    it('treats key order inside objects as no change, and names arrays that changed at the same length', () => {
        expect(diffSnapshots({ lock: { level: 'HARD', reason: 'x' } }, { lock: { reason: 'x', level: 'HARD' } })).toEqual([])
        const d = diffSnapshots({ signOffs: [{ role: 'a' }] }, { signOffs: [{ role: 'b' }] })
        expect(d[0].before).toBe('1 item')
        expect(d[0].after).toBe('1 item (changed)')
        const grown = diffSnapshots({ signOffs: [] }, { signOffs: [{ role: 'a' }, { role: 'b' }] })
        expect([grown[0].change, grown[0].before, grown[0].after]).toEqual(['changed', '0 items', '2 items'])
    })

    it('marks a long or multi-line text for the side-by-side view, with the raw texts', () => {
        const d = diffSnapshots({ prompt: 'design it' }, { prompt: 'design it\ncarefully' })
        expect(d[0].long).toBe(true)
        expect(d[0].beforeValue).toBe('design it')
        expect(d[0].afterValue).toBe('design it\ncarefully')
        expect(diffSnapshots({ cycleCap: 4 }, { cycleCap: 3 })[0].long).toBe(false)
    })

    it('compares nothing when a side is missing (an unreadable revision)', () => {
        expect(diffSnapshots(null, { a: 1 })).toEqual([])
        expect(diffSnapshots({ a: 1 }, null)).toEqual([])
    })

    it('describes values in one short line', () => {
        expect(describeValue(null)).toBe('—')
        expect(describeValue('')).toBe('—')
        expect(describeValue([1, 2])).toBe('2 items')
        expect(describeValue(false)).toBe('false')
        expect(describeValue('x'.repeat(200)).length).toBe(120)
    })

    it('reads null and an empty string as one value, either way (T-2, run 2)', () => {
        expect(diffSnapshots({ coordinatorPrompt: null }, { coordinatorPrompt: '' })).toEqual([])
        expect(diffSnapshots({ coordinatorPrompt: '' }, { coordinatorPrompt: null })).toEqual([])
        // an empty side against a real value is still a change
        expect(diffSnapshots({ coordinatorPrompt: '' }, { coordinatorPrompt: 'coordinate' })[0])
            .toMatchObject({ key: 'coordinatorPrompt', change: 'added', before: '—', after: 'coordinate' })
        expect(diffSnapshots({ coordinatorPrompt: 'coordinate' }, { coordinatorPrompt: null })[0])
            .toMatchObject({ change: 'removed', before: 'coordinate', after: '—' })
    })

    it('never shows or compares the client __typename, at any depth (T-1, run 1)', () => {
        const before = { __typename: 'AgentTask', meta: { __typename: 'Meta', a: 1 } }
        const after = { __typename: 'AgentTask', meta: { __typename: 'Other', a: 1 } }
        expect(diffSnapshots(before, after)).toEqual([])
        const changed = diffSnapshots({ meta: { __typename: 'Meta', a: 1 } }, { meta: { __typename: 'Meta', a: 2 } })
        expect(changed).toHaveLength(1)
        expect(changed[0].before).toBe('{"a":1}')
        expect(changed[0].after).toBe('{"a":2}')
        expect(describeValue({ __typename: 'X', deep: { __typename: 'Y', v: [{ __typename: 'Z', w: 1 }] } }))
            .toBe('{"deep":{"v":[{"w":1}]}}')
    })

    it('says the objects a snapshot carries in words (T-1, run 1)', () => {
        const assignment = { __typename: 'AgentTaskWorkAssignment', agent: '46f1e594-aaaa', role: 'coder',
            session: '83922fa1-307e-41c5', assignedAt: '2026-09-25T12:07:47Z', promptVersion: 'abc' }
        const handedOver = diffSnapshots({ assignment }, { assignment: null })
        expect(handedOver[0]).toMatchObject({ key: 'assignment', change: 'removed', before: 'coder · session 83922fa1', after: '—' })
        expect(describeValue({ __typename: 'AgentTaskHold', level: 'OPERATOR', kind: 'HUMAN_GATE', reason: 'review the pass' }, 'hold'))
            .toBe('operator human gate: review the pass')
        expect(describeValue({ level: 'COORDINATOR', reason: 'intake paused' }, 'lock')).toBe('coordinator: intake paused')
        expect(describeValue({ session: '83922fa1-307e-41c5', agent: 'x' }, 'coordinatorSeat')).toBe('session 83922fa1')
        expect(describeValue({ __typename: 'AgentActor', kind: 'USER', uuid: 'cdd0c98c-cd0b', name: 'pavel@reliza.io' }, 'orderSetBy'))
            .toBe('pavel@reliza.io')
        expect(describeValue({ kind: 'SESSION', uuid: 'ebb928c9-6202', name: null }, 'budgetSetBy')).toBe('session ebb928c9')
        // an object nobody summarises stays compact JSON, still without __typename
        expect(describeValue({ __typename: 'Q', b: 2, a: 1 }, 'something')).toBe('{"a":1,"b":2}')
    })

    it('summarises a snapshot per kind', () => {
        expect(revisionSummary('task', { status: 'ON_HOLD', role: 'coder', hold: { kind: 'MANUAL' }, orderIndex: 2,
            budgetMicros: 1_500_000, requiredStrength: 0.8 }))
            .toEqual(['ON HOLD', 'role coder', 'hold manual', 'order 2', 'budget $1.50', 'strength 0.8'])
        expect(revisionSummary('board', { status: 'ACTIVE', lock: { level: 'SOFT' }, cycleCap: 3 }))
            .toEqual(['ACTIVE', 'lock soft', 'cycle cap 3'])
        expect(revisionSummary('role', { active: false, orderIndex: 10, prompt: 'abc' }))
            .toEqual(['inactive', 'order 10', 'prompt 3 chars'])
        expect(revisionSummary('task', null)).toEqual(['unreadable snapshot'])
    })
})

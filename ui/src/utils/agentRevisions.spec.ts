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
        expect(d[2].before).toBe('{"kind":"MANUAL","reason":"r"}')
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

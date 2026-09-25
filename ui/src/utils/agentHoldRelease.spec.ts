import { describe, expect, it } from 'vitest'
import { holdReleaseNote, isLoopStopHold, personMayRelease, releaseLabel, releasePayload, releaseRoleOptions } from './agentHoldRelease'

const routing = { kind: 'SYSTEM', uuid: null, name: 'routing' }

describe('hold release', () => {
    it('knows a loop stop from other holds', () => {
        expect(isLoopStopHold({ heldBy: routing, reason: 'stopped by no progress: [q1] stayed OPEN' })).toBe(true)
        expect(isLoopStopHold({ heldBy: routing, reason: 'stopped by cycle cap: coder ↔ designer went 3 round(s)' })).toBe(true)
        expect(isLoopStopHold({ heldBy: routing, reason: 'stopped by budget: the next round does not fit' })).toBe(false)
        expect(isLoopStopHold({ heldBy: { kind: 'USER', name: 'pavel' }, reason: 'stopped by no progress, said a person' })).toBe(false)
        expect(isLoopStopHold(null)).toBe(false)
    })

    it('reads the recorded stop first, which an escalation keeps (task c0a2134c)', () => {
        const coordinator = { kind: 'SESSION', uuid: 's1', name: null }
        expect(isLoopStopHold({ stop: 'NO_PROGRESS', heldBy: coordinator, reason: 'stopped by no progress — escalated by the coordinator: decide' })).toBe(true)
        expect(isLoopStopHold({ stop: 'CYCLE_CAP', heldBy: routing, reason: 'stopped by cycle cap' })).toBe(true)
        expect(isLoopStopHold({ stop: 'BUDGET', heldBy: routing, reason: 'stopped by budget' })).toBe(false)
    })

    it('says who may release a hold, and gives a person the controls where they may (task c0a2134c)', () => {
        const first = { level: 'COORDINATOR', kind: 'MANUAL', stop: 'NO_PROGRESS', heldBy: routing, reason: 'stopped by no progress' }
        expect(holdReleaseNote(first)).toBe('coordinator may release once')
        expect(personMayRelease(first)).toBe(true)
        const second = { ...first, level: 'OPERATOR', reason: 'stopped by no progress; released once already' }
        expect(holdReleaseNote(second)).toBe('operator only')
        expect(personMayRelease(second)).toBe(true)
        const coordinatorsOwn = { level: 'COORDINATOR', kind: 'MANUAL', stop: null, heldBy: { kind: 'SESSION' }, reason: 'waiting on the tracker' }
        expect(holdReleaseNote(coordinatorsOwn)).toBeNull()
        expect(personMayRelease(coordinatorsOwn)).toBe(false)
        const gate = { level: 'OPERATOR', kind: 'HUMAN_GATE' }
        expect(holdReleaseNote(gate)).toBeNull()
        expect(personMayRelease(gate)).toBe(false)
        expect(personMayRelease({ level: 'COORDINATOR', kind: 'QUESTION' })).toBe(false)
        expect(holdReleaseNote(null)).toBeNull()
    })

    it('offers the active agent roles in board order', () => {
        const roles = [{ name: 'tester', orderIndex: 30 }, { name: 'coder', orderIndex: 20 },
            { name: 'signoff', orderIndex: 40, kind: 'HUMAN' }, { name: 'old', orderIndex: 10, active: false }]
        expect(releaseRoleOptions(roles).map(o => o.value)).toEqual(['coder', 'tester'])
        expect(releaseRoleOptions(null)).toEqual([])
    })

    it('sends the role only when one is picked', () => {
        const t = { uuid: 't1' }
        expect(releasePayload(t, 'go', null)).toEqual({ task: t, note: 'go' })
        expect(releasePayload(t, 'go', '  ')).toEqual({ task: t, note: 'go' })
        expect(releasePayload(t, '', 'coder')).toEqual({ task: t, note: '', role: 'coder' })
    })

    it('says where the release sends the task', () => {
        expect(releaseLabel(true, 'coder')).toBe('Release to coder')
        expect(releaseLabel(true, null)).toBe('Release past the stop')
        expect(releaseLabel(false, null)).toBe('Operator release')
    })
})

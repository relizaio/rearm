import { describe, expect, it } from 'vitest'
import { isLoopStopHold, releaseLabel, releasePayload, releaseRoleOptions } from './agentHoldRelease'

const routing = { kind: 'SYSTEM', uuid: null, name: 'routing' }

describe('hold release', () => {
    it('knows a loop stop from other holds', () => {
        expect(isLoopStopHold({ heldBy: routing, reason: 'stopped by no progress: [q1] stayed OPEN' })).toBe(true)
        expect(isLoopStopHold({ heldBy: routing, reason: 'stopped by cycle cap: coder ↔ designer went 3 round(s)' })).toBe(true)
        expect(isLoopStopHold({ heldBy: routing, reason: 'stopped by budget: the next round does not fit' })).toBe(false)
        expect(isLoopStopHold({ heldBy: { kind: 'USER', name: 'pavel' }, reason: 'stopped by no progress, said a person' })).toBe(false)
        expect(isLoopStopHold(null)).toBe(false)
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

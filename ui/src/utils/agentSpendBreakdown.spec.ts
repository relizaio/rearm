import { describe, expect, it } from 'vitest'
import { breakdownEmpty, hopsLabel, partsSum, roleRows } from './agentSpendBreakdown'

// The Usage tab's breakdown (RD2-8): rows that add up to the total, and "no usage" only when there is none.
const b = {
    totalMicros: 2100, costComplete: true, coordinatorEstimateMicros: 200, unattributedMicros: 100,
    byRole: [{ role: 'coder', costMicros: 1500, closedHops: 1, openHops: 0, tokens: { inputTokens: 1500 } },
        { role: 'reviewer', costMicros: 300, closedHops: 0, openHops: 1, tokens: { inputTokens: 300 } }],
    bySession: [{ session: 's1', costMicros: 1500 }],
}

describe('the by-role rows', () => {
    it('are the roles, then the coordinator seat and the unattributed rest, adding up to the total', () => {
        expect(roleRows(b).map(r => r.label)).toEqual(['coder', 'reviewer', 'coordinator estimate', 'unattributed'])
        expect(partsSum(b)).toBe(b.totalMicros)
        expect(roleRows({ ...b, coordinatorEstimateMicros: 0, unattributedMicros: 0 }).map(r => r.kind)).toEqual(['role', 'role'])
        expect(roleRows(null)).toEqual([])
    })

    it('say how many hops, open ones included', () => {
        const rows = roleRows(b)
        expect(hopsLabel(rows[0])).toBe('1 closed')
        expect(hopsLabel(rows[1])).toBe('1 open')
        expect(hopsLabel({ ...rows[0], closedHops: 2, openHops: 1 })).toBe('2 closed · 1 open')
        expect(hopsLabel(rows[2])).toBe('')
    })
})

describe('no usage', () => {
    it('is said only when the window has none', () => {
        expect(breakdownEmpty(b)).toBe(false)
        expect(breakdownEmpty({ byRole: [], bySession: [], coordinatorEstimateMicros: 800_000, unattributedMicros: 0, totalMicros: 800_000 }))
            .toBe(false, 'a coordinator-only board is not "no usage"')
        expect(breakdownEmpty({ byRole: [], bySession: [], coordinatorEstimateMicros: 0, unattributedMicros: 0, totalMicros: 0 })).toBe(true)
        expect(breakdownEmpty(null)).toBe(true)
    })
})

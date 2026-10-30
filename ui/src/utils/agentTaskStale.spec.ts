import { describe, expect, it } from 'vitest'
import { STALE_RULE_WORDS, isStale, passesStale, staleAgo, staleChip, staleFromQuery, withStaleQuery } from './agentTaskStale'

const hop = { rule: 'hopNoProgress', since: '2026-10-20T10:00:00Z', minutes: 70, message: 'RD3-5 stalled: assigned to coder (session 1a2b3c4d) for 70 min with no progress reported; release it with task unassign if the agent is gone' }
const delivery = { rule: 'deliveryStuck', since: '2026-10-20T08:00:00Z', minutes: 180, message: 'RD3-5 delivery stuck: https://github.com/acme/app/pull/7 open for 3 h' }
const overdue = { rule: 'investigationOverdue', since: '2026-10-20T08:00:00Z', minutes: 45, message: 'RD3-9 investigation overdue: due 2026-10-20T08:00Z, 45 min ago, and assigned (researcher)' }

// A task's staleness marks on the card, the drawer and the page (task TPF-69).
describe('agentTaskStale', () => {
    it('rounds a span as the ALERT text does', () => {
        expect(staleAgo(0)).toBe('0 min')
        expect(staleAgo(59)).toBe('59 min')
        expect(staleAgo(119)).toBe('119 min')
        expect(staleAgo(120)).toBe('2 h')
        expect(staleAgo(300)).toBe('5 h')
        expect(staleAgo(null)).toBe('0 min')
    })

    it('has words for the three task rules', () => {
        expect(STALE_RULE_WORDS).toEqual({ hopNoProgress: 'no progress', deliveryStuck: 'delivery stuck', investigationOverdue: 'overdue' })
    })

    it('has no chip without a mark', () => {
        expect(staleChip({ stale: [] })).toBeNull()
        expect(staleChip({ stale: null })).toBeNull()
        expect(staleChip({ uuid: 't' })).toBeNull()
        expect(staleChip(null)).toBeNull()
        expect(isStale({ stale: [] })).toBe(false)
    })

    it('labels one mark with its words and span, the ALERT text as the tooltip', () => {
        const c = staleChip({ stale: [hop] })
        expect(c?.label).toBe('stale: no progress 70 min')
        expect(c?.tooltip).toBe(`hopNoProgress: ${hop.message}`)
        expect(c?.tooltip.startsWith('hopNoProgress: RD3-5 stalled: assigned to coder')).toBe(true)
        expect(c?.rules).toEqual(['hopNoProgress'])
        expect(staleChip({ stale: [overdue] })?.label).toBe('stale: overdue 45 min')
    })

    it('counts the marks after the first and lists every one in the tooltip', () => {
        const c = staleChip({ stale: [delivery, hop] })
        expect(c?.label).toBe('stale: delivery stuck 3 h +1')
        expect(c?.tooltip.split('\n')).toEqual([`deliveryStuck: ${delivery.message}`, `hopNoProgress: ${hop.message}`])
        expect(c?.rules).toEqual(['deliveryStuck', 'hopNoProgress'])
    })

    it('prints an unknown rule by its name', () => {
        expect(staleChip({ stale: [{ rule: 'someNewRule', minutes: 5, message: 'x' }] })?.label).toBe('stale: someNewRule 5 min')
    })

    it('filters stale only when on', () => {
        const marked = { stale: [hop] }
        const healthy = { stale: [] }
        const older = {}
        expect([marked, healthy, older].map(t => passesStale(t, false))).toEqual([true, true, true])
        expect([marked, healthy, older].map(t => passesStale(t, true))).toEqual([true, false, false])
    })

    it('reads and writes ?stale=1', () => {
        expect(staleFromQuery({ stale: '1' })).toBe(true)
        expect(staleFromQuery({})).toBe(false)
        expect(staleFromQuery({ stale: '0' })).toBe(false)
        expect(staleFromQuery(null)).toBe(false)
        expect(withStaleQuery({ tab: 'kanban' }, true)).toEqual({ tab: 'kanban', stale: '1' })
        expect(withStaleQuery({ tab: 'kanban', stale: '1' }, false)).toEqual({ tab: 'kanban' })
        expect(withStaleQuery(null, false)).toEqual({})
    })
})

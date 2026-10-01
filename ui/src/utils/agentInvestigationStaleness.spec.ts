import { describe, expect, it } from 'vitest'
import { STALENESS_FIELDS, stalenessPatch, stalenessProblem } from './agentStaleness'

// The investigationOverdue rule (task RD4-12): minutes past an investigation's deadline, where 0 alerts at the
// deadline, so the form accepts 0 for it and only for it.
describe('the investigation overdue threshold', () => {
    it('is a field of the staleness form, with its words', () => {
        const f = STALENESS_FIELDS.find(x => x.key === 'investigationOverdueMinutes')
        expect(f?.label).toBe('investigation overdue, min')
        expect(f?.help).toContain('0 alerts as soon as the deadline passes')
    })

    it('takes 0, refuses a negative, and keeps the 1-minute floor of the others', () => {
        expect(stalenessProblem({ investigationOverdueMinutes: 0 })).toBe('')
        expect(stalenessProblem({ investigationOverdueMinutes: -1 })).toBe(
            'investigationOverdueMinutes cannot be negative; 0 alerts at the deadline, blank turns it off')
        expect(stalenessProblem({ roleUnstaffedMinutes: 0 })).not.toBe('')
    })

    it('is sent in the block like any threshold', () => {
        expect(stalenessPatch(null, { investigationOverdueMinutes: 0 })).toEqual({ investigationOverdueMinutes: 0 })
    })
})

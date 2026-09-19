import { describe, it, expect } from 'vitest'
import {
    modelDisplayName,
    totalTokens,
    formatTokens,
    formatCostMicros,
    costLabel,
    costConfidence,
    usageBadges,
    byModelRows,
    periodRange,
} from './agentUsage'

describe('totalTokens', () => {
    it('counts cache reads, which are most of a cached session', () => {
        // The realistic shape: uncached input is a rounding error next to cache
        // reads. Leaving cache out would report this session as ~2k tokens.
        const u = { inputTokens: 1_306, outputTokens: 421_807, cacheReadTokens: 82_107_509, cacheWriteTokens: 50_000 }
        expect(totalTokens(u)).toBe(82_580_622)
    })

    it('treats missing classes as zero rather than NaN', () => {
        expect(totalTokens({ inputTokens: 5 })).toBe(5)
        expect(totalTokens(null)).toBe(0)
        expect(totalTokens(undefined)).toBe(0)
    })
})

describe('formatTokens', () => {
    it('scales to the magnitude', () => {
        expect(formatTokens(999)).toBe('999')
        expect(formatTokens(1_500)).toBe('1.5k')
        expect(formatTokens(1_234_567)).toBe('1.23M')
        expect(formatTokens(1_811_063_732)).toBe('1.81B')
    })
    it('renders nothing as zero, not as blank', () => {
        expect(formatTokens(null)).toBe('0')
        expect(formatTokens(0)).toBe('0')
    })
})

describe('formatCostMicros', () => {
    it('converts micros to dollars', () => {
        expect(formatCostMicros(1_500_000)).toBe('$1.50')
        expect(formatCostMicros(123_456_789)).toBe('$123')
    })

    it('distinguishes no price from zero cost', () => {
        // The whole point. $0.00 is a priced session that cost nothing;
        // null is a session nobody could price. Reporting the second as the
        // first would show an unpriced fleet as a free one.
        expect(formatCostMicros(null)).toBeNull()
        expect(formatCostMicros(undefined)).toBeNull()
        expect(formatCostMicros(0)).toBe('$0.00')
    })

    it('does not round a real cost down to nothing', () => {
        // A fraction of a cent is still spend; "$0.00" would read as free.
        expect(formatCostMicros(500)).toBe('<$0.01')
    })
})

describe('costLabel', () => {
    it('marks a part-priced total as a floor', () => {
        expect(costLabel({ derivedCostMicros: 2_000_000, costComplete: false })).toBe('≥ $2.00')
        expect(costLabel({ derivedCostMicros: 2_000_000, costComplete: true })).toBe('$2.00')
    })
    it('says no price rather than showing a number', () => {
        expect(costLabel({ derivedCostMicros: null })).toBe('no price')
    })
})

describe('costConfidence', () => {
    it('separates unpriced, partial and priced', () => {
        expect(costConfidence({ derivedCostMicros: null })).toBe('UNPRICED')
        expect(costConfidence({ derivedCostMicros: 10, costComplete: false })).toBe('PARTIAL')
        expect(costConfidence({ derivedCostMicros: 10, costComplete: true })).toBe('PRICED')
        // Zero is a real, complete price.
        expect(costConfidence({ derivedCostMicros: 0, costComplete: true })).toBe('PRICED')
    })
})

describe('usageBadges', () => {
    it('gives a clean session no badges', () => {
        // A row that is fine should look fine; badges only mean something if
        // their absence does.
        expect(usageBadges('COMPLETE', false, { reports: 3, derivedCostMicros: 100, costComplete: true })).toEqual([])
    })

    it('flags a sweeper-closed session as partial', () => {
        const badges = usageBadges('INCOMPLETE', false, { reports: 3, derivedCostMicros: 100, costComplete: true })
        expect(badges.map(b => b.label)).toEqual(['partial usage'])
        expect(badges[0].type).toBe('warning')
    })

    it('flags a model mismatch', () => {
        const badges = usageBadges('COMPLETE', true, { reports: 3, derivedCostMicros: 100, costComplete: true })
        expect(badges.map(b => b.label)).toEqual(['model mismatch'])
    })

    it('does not cry unpriced over a session with no usage at all', () => {
        // NONE already says it; adding "unpriced" beside it is noise on every
        // hook-less session in the org.
        const badges = usageBadges('NONE', false, { reports: 0, derivedCostMicros: null })
        expect(badges.map(b => b.label)).toEqual(['no usage reported'])
    })

    it('stacks every qualification that applies', () => {
        const badges = usageBadges('INCOMPLETE', true, { reports: 5, derivedCostMicros: 10, costComplete: false })
        expect(badges.map(b => b.label)).toEqual(['partial usage', 'model mismatch', 'partly priced'])
    })
})

describe('byModelRows', () => {
    it('puts the biggest spender first', () => {
        const rows = byModelRows({ byModel: [
            { model: 'a', derivedCostMicros: 10 },
            { model: 'b', derivedCostMicros: 900 },
            { model: 'c', derivedCostMicros: 50 },
        ] })
        expect(rows.map(r => r.model)).toEqual(['b', 'c', 'a'])
    })

    it('falls back to tokens when nothing is priced', () => {
        // Sorting an unpriced fleet by a null cost would order it arbitrarily,
        // which reads as a bug to anyone who reloads the page.
        const rows = byModelRows({ byModel: [
            { model: 'small', inputTokens: 10, derivedCostMicros: null },
            { model: 'large', inputTokens: 5_000, derivedCostMicros: null },
        ] })
        expect(rows.map(r => r.model)).toEqual(['large', 'small'])
    })

    it('does not mutate the array it was given', () => {
        const byModel = [{ model: 'a', derivedCostMicros: 1 }, { model: 'b', derivedCostMicros: 2 }]
        byModelRows({ byModel })
        expect(byModel.map(r => r.model)).toEqual(['a', 'b'])
    })
})

describe('periodRange', () => {
    it('spans back from the supplied clock', () => {
        const now = Date.parse('2026-09-19T12:00:00.000Z')
        const { from, to } = periodRange(24, now)
        expect(to).toBe('2026-09-19T12:00:00.000Z')
        expect(from).toBe('2026-09-18T12:00:00.000Z')
    })
})

describe('modelDisplayName', () => {
    it('prefers the name the rollup carries', () => {
        expect(modelDisplayName({ model: 'e639534d-1111-2222-3333-444444444444', modelName: 'claude-opus-5' }))
            .toBe('claude-opus-5')
    })

    it('shortens a bare uuid rather than printing 36 characters', () => {
        // Falling back to the full uuid let the id take over the column; a row whose model was
        // deleted out from under its usage still has to render legibly.
        expect(modelDisplayName({ model: 'e639534d-1111-2222-3333-444444444444' })).toBe('e639534d…')
    })

    it('renders an empty row as a dash', () => {
        expect(modelDisplayName(null)).toBe('—')
        expect(modelDisplayName({})).toBe('—')
    })
})

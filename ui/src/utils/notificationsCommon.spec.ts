import { describe, it, expect, vi } from 'vitest'

// notificationsCommon imports commonFunctions, which pulls in the graphql
// client + keycloak init as an import side effect. Stub it so this pure-helper
// spec doesn't boot keycloak; relativeTime only needs dateDisplay for its
// past-30d absolute fallback.
vi.mock('@/utils/commonFunctions', () => ({
    default: { dateDisplay: (s: string) => `ABS:${s}` },
}))

import { relativeTime } from './notificationsCommon'

// relativeTime is a pure function with an injectable `now`, so these are
// deterministic (no dependence on the wall clock).
describe('relativeTime', () => {
    const now = Date.parse('2026-07-08T12:00:00Z')
    const ago = (ms: number) => new Date(now - ms).toISOString()
    const SEC = 1000, MIN = 60 * SEC, HR = 60 * MIN, DAY = 24 * HR

    it('returns empty string for null/blank/invalid input', () => {
        expect(relativeTime(null, now)).toBe('')
        expect(relativeTime('', now)).toBe('')
        expect(relativeTime('not-a-date', now)).toBe('')
    })

    it('reads sub-minute (and future/skew) as "just now"', () => {
        expect(relativeTime(ago(0), now)).toBe('just now')
        expect(relativeTime(ago(59 * SEC), now)).toBe('just now')
        expect(relativeTime(new Date(now + 5 * MIN).toISOString(), now)).toBe('just now')
    })

    it('formats minutes, hours, and days', () => {
        expect(relativeTime(ago(MIN), now)).toBe('1m ago')
        expect(relativeTime(ago(59 * MIN), now)).toBe('59m ago')
        expect(relativeTime(ago(HR), now)).toBe('1h ago')
        expect(relativeTime(ago(23 * HR), now)).toBe('23h ago')
        expect(relativeTime(ago(DAY), now)).toBe('1d ago')
        expect(relativeTime(ago(29 * DAY), now)).toBe('29d ago')
    })

    it('falls back to an absolute date past ~30 days', () => {
        const out = relativeTime(ago(40 * DAY), now)
        expect(out).not.toMatch(/ago$/)
        expect(out.length).toBeGreaterThan(0)
    })
})

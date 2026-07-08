import { describe, it, expect, vi } from 'vitest'

// notificationsCommon imports commonFunctions, which pulls in the graphql
// client + keycloak init as an import side effect. Stub it so this pure-helper
// spec doesn't boot keycloak; relativeTime only needs dateDisplay for its
// past-30d absolute fallback.
vi.mock('@/utils/commonFunctions', () => ({
    default: { dateDisplay: (s: string) => `ABS:${s}` },
}))

import { print } from 'graphql'
import {
    relativeTime,
    LIST_GROUPS_QUERY, LIST_GROUPS_CORE_QUERY,
    LIST_SUBSCRIPTIONS_QUERY, LIST_SUBSCRIPTIONS_CORE_QUERY,
} from './notificationsCommon'

// Pin the CORE vs ENRICHMENT split of the drift-fallback list queries: CORE
// must carry every always-present render field and NONE of the Pro-ahead
// enrichment fields (else a CE mirror would re-blank the surface), and FULL
// must select both sets. Word-boundary match so "org" doesn't spuriously hit
// the $orgUuid variable.
describe('notification list query CORE / FULL split', () => {
    const has = (doc: any, field: string) => new RegExp(`\\b${field}\\b`).test(print(doc))

    const GROUP_CORE = ['uuid', 'org', 'resourceGroup', 'name', 'channels', 'revision']
    const GROUP_ENRICHMENT = ['createdDate', 'lastUpdatedDate']
    it('groups CORE has the render essentials and no enrichment fields', () => {
        GROUP_CORE.forEach(f => expect(has(LIST_GROUPS_CORE_QUERY, f), `core should have ${f}`).toBe(true))
        GROUP_ENRICHMENT.forEach(f => expect(has(LIST_GROUPS_CORE_QUERY, f), `core should NOT have ${f}`).toBe(false))
    })
    it('groups FULL has both core and enrichment fields', () => {
        [...GROUP_CORE, ...GROUP_ENRICHMENT].forEach(f => expect(has(LIST_GROUPS_QUERY, f), `full should have ${f}`).toBe(true))
    })

    const SUB_CORE = ['uuid', 'org', 'resourceGroup', 'name', 'status', 'eventTypes', 'revision']
    const SUB_ENRICHMENT = ['filter', 'routes', 'dedupWindowMinutes', 'rateLimit']
    it('subscriptions CORE has the render essentials and no enrichment fields', () => {
        SUB_CORE.forEach(f => expect(has(LIST_SUBSCRIPTIONS_CORE_QUERY, f), `core should have ${f}`).toBe(true))
        SUB_ENRICHMENT.forEach(f => expect(has(LIST_SUBSCRIPTIONS_CORE_QUERY, f), `core should NOT have ${f}`).toBe(false))
    })
    it('subscriptions FULL has both core and enrichment fields', () => {
        [...SUB_CORE, ...SUB_ENRICHMENT].forEach(f => expect(has(LIST_SUBSCRIPTIONS_QUERY, f), `full should have ${f}`).toBe(true))
    })
})

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

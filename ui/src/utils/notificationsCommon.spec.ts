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
    buildNotificationFilterInput,
    routeHasTarget,
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

// Edit -> Save of a subscription 400'd because the as-loaded filter blob
// (output shape) carries a `presetConfig` object that was spread into
// NotificationFilterInput, which only accepts mode/presetConfigJson/celExpression.
describe('buildNotificationFilterInput', () => {
    it('never leaks the output-only presetConfig key into the input', () => {
        const out = buildNotificationFilterInput(
            { mode: 'PRESET', celExpression: null, presetConfig: { sev: 'CRITICAL' } },
            'PRESET', '')
        expect('presetConfig' in out).toBe(false)
        expect(Object.keys(out).sort()).toEqual(['celExpression', 'mode', 'presetConfigJson'])
    })
    it('maps an output presetConfig object into presetConfigJson (stringified)', () => {
        const out = buildNotificationFilterInput(
            { presetConfig: { sev: 'CRITICAL', envs: ['prod'] } }, 'PRESET', '')
        expect(out.presetConfigJson).toBe(JSON.stringify({ sev: 'CRITICAL', envs: ['prod'] }))
    })
    it('passes through an already-string presetConfigJson unchanged', () => {
        const out = buildNotificationFilterInput({ presetConfigJson: '{"sev":"HIGH"}' }, 'PRESET', '')
        expect(out.presetConfigJson).toBe('{"sev":"HIGH"}')
    })
    it('omits presetConfigJson when neither preset field is present', () => {
        const out = buildNotificationFilterInput({}, 'PRESET', '')
        expect('presetConfigJson' in out).toBe(false)
    })
    it('load -> resave-unchanged round-trip is a valid input (no invalid keys)', () => {
        // Simulate the loaded output blob then a save with no user edits.
        const loaded = { mode: 'PRESET', celExpression: null, presetConfig: { sev: 'CRITICAL' } }
        const out = buildNotificationFilterInput(loaded, 'PRESET', '')
        const allowed = new Set(['mode', 'presetConfigJson', 'celExpression'])
        Object.keys(out).forEach(k => expect(allowed.has(k), `unexpected input field ${k}`).toBe(true))
    })
    it('carries celExpression only in ADVANCED mode', () => {
        expect(buildNotificationFilterInput({}, 'ADVANCED', 'event.kevListed == true').celExpression)
            .toBe('event.kevListed == true')
        expect(buildNotificationFilterInput({}, 'PRESET', 'event.kevListed == true').celExpression)
            .toBe(null)
    })
    it('tolerates a null/undefined rawFilter (Create path)', () => {
        expect(buildNotificationFilterInput(null, 'PRESET', '')).toEqual({ mode: 'PRESET', celExpression: null })
    })
})

describe('routeHasTarget (client-side mirror of the backend route-emptiness gate)', () => {
    const empty = { channels: [], channelGroups: [], teams: [], notifyComponentOwner: false }

    it('accepts any of the fixed targets, on either edition', () => {
        for (const isPro of [true, false]) {
            expect(routeHasTarget({ ...empty, channels: ['ch-1'] }, isPro)).toBe(true)
            expect(routeHasTarget({ ...empty, channelGroups: ['g-1'] }, isPro)).toBe(true)
            expect(routeHasTarget({ ...empty, teams: ['t-1'] }, isPro)).toBe(true)
        }
    })

    it('accepts an owner-only route on Pro -- naming no target is the point of T4a', () => {
        expect(routeHasTarget({ ...empty, notifyComponentOwner: true }, true)).toBe(true)
    })

    it('REJECTS an owner-only route on CE, where the field would fail the whole save', () => {
        // The compounding half of the CE gating bug: the control is hidden on
        // CE, but if the flag is somehow set, counting it as a target lets the
        // operator author a route guaranteed to 400 the subscription mutation
        // -- including the edits that have nothing to do with owner routing.
        expect(routeHasTarget({ ...empty, notifyComponentOwner: true }, false)).toBe(false)
    })

    it('rejects a genuinely empty route on both editions', () => {
        expect(routeHasTarget(empty, true)).toBe(false)
        expect(routeHasTarget(empty, false)).toBe(false)
        expect(routeHasTarget({}, true)).toBe(false)
    })

    it('does not treat a false/absent owner flag as a target', () => {
        expect(routeHasTarget({ ...empty, notifyComponentOwner: undefined }, true)).toBe(false)
        expect(routeHasTarget({ ...empty, notifyComponentOwner: null }, true)).toBe(false)
    })

    it('still accepts a Pro route carrying teams even when the owner flag is off', () => {
        // teams is NOT edition-gated here: CE soft-fails the teams query to [],
        // so a CE route cannot carry them anyway, and gating would wrongly
        // reject a Pro route whose team list loaded fine.
        expect(routeHasTarget({ ...empty, teams: ['t-1'] }, true)).toBe(true)
    })
})

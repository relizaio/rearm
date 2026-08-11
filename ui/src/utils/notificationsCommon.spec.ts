import { describe, it, expect, vi } from 'vitest'

// notificationsCommon imports commonFunctions, which pulls in the graphql
// client + keycloak init as an import side effect. Stub it so this pure-helper
// spec doesn't boot keycloak; relativeTime only needs dateDisplay for its
// past-30d absolute fallback.
vi.mock('@/utils/commonFunctions', () => ({
    default: { dateDisplay: (s: string) => `ABS:${s}` },
}))

import { readFileSync, existsSync, readdirSync } from 'fs'
import { fileURLToPath } from 'url'
import { join } from 'path'

import { print } from 'graphql'
import {
    relativeTime,
    subscriptionStatusOptions, eventTypeOptions, deliveryStatusOptions,
    buildNotificationFilterInput,
    routeHasTarget,
    classifySubscriptionTest,
    TERMINAL_OUTBOX_STATUSES,
    isOwnerRouted,
    ownerRoutedSuccessCaveat,
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
// Guard the two options that are deliberately unselectable because the backend
// has no implementation behind them. Both were silent traps: the control was
// offered, the user picked it, and nothing ever fired. Re-enabling either
// without shipping its backend half re-opens that trap, so pin them here --
// this is the executable form of the comments next to each options list.
describe('unselectable options without a backend implementation', () => {
    const option = (opts: Array<{ value: string, disabled?: boolean }>, value: string) =>
        opts.find(o => o.value === value)

    it('keeps PREVIEW unselectable while fan-out matches ACTIVE only', () => {
        // NotificationSubscriptionRepository.findActiveByOrgString filters on
        // status='ACTIVE', so a PREVIEW subscription never reaches fan-out and
        // behaves exactly like DISABLED. Reproduced on the sandbox: ACTIVE -> 1
        // delivery row, PREVIEW -> 0, DISABLED -> 0.
        expect(option(subscriptionStatusOptions, 'PREVIEW')?.disabled).toBe(true)
    })

    it('leaves the implemented subscription statuses selectable', () => {
        expect(option(subscriptionStatusOptions, 'ACTIVE')?.disabled).toBeFalsy()
        expect(option(subscriptionStatusOptions, 'DISABLED')?.disabled).toBeFalsy()
    })

    it('keeps VEX_STATE_CHANGED unselectable while it has no event producer', () => {
        expect(option(eventTypeOptions, 'VEX_STATE_CHANGED')?.disabled).toBe(true)
    })

})

const CE_BACKEND_SRC_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/java/io/reliza', import.meta.url))
const PRO_BACKEND_SRC_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/java/io/reliza', import.meta.url))

// Comments are stripped before scanning: javadoc in this codebase quotes call
// forms (NotificationCelEvaluatorImpl's class doc names
// NotificationDeliveryStatus.EVAL_TIMEOUT while explaining that nothing writes
// it), and reading prose as code would demand we enable a filter for a status
// that has no writer.
function stripComments (src: string): string {
    return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

// Every delivery status the backend can WRITE. Two idioms exist and both are
// scanned -- an earlier cut only saw the first, which left the native-SQL
// writer in NotificationDeliveryRepository invisible and the guard's coverage
// incomplete by coincidence rather than design:
//   1. setStatus(NotificationDeliveryStatus.X)          -- service code
//   2. SET status = '" + NotificationDeliveryStatus.X_VALUE   -- native @Query
// Form 2 is matched only in the assigning position, so the WHERE-clause
// _VALUE references on the very next line are correctly ignored.
const WRITER_PATTERNS = [
    /setStatus\(\s*NotificationDeliveryStatus\.([A-Z_]+)\s*\)/g,
    /SET status = '"\s*\+\s*NotificationDeliveryStatus\.([A-Z_]+)_VALUE/g,
]

function assignedDeliveryStatuses (root: string): { statuses: Set<string>, filesScanned: number } {
    const statuses = new Set<string>()
    let filesScanned = 0
    const walk = (dir: string) => {
        for (const entry of readdirSync(dir, { withFileTypes: true })) {
            const full = join(dir, entry.name)
            if (entry.isDirectory()) walk(full)
            else if (entry.name.endsWith('.java')) {
                filesScanned++
                const src = stripComments(readFileSync(full, 'utf8'))
                for (const re of WRITER_PATTERNS) {
                    for (const m of src.matchAll(re)) statuses.add(m[1])
                }
            }
        }
    }
    walk(root)
    return { statuses, filesScanned }
}

const offeredStatuses = () => new Set(deliveryStatusOptions.map(o => o.value))
const enabledStatuses = () => new Set(deliveryStatusOptions.filter(o => !o.disabled).map(o => o.value))

// Deliberately NOT the same assertion against both trees. CE is a subset
// mirror of Pro -- core carries service/saas classes CE lacks, which is
// exactly where a new writer is most likely to land. Demanding
// "enabled == produced" of BOTH would become UNSATISFIABLE the moment a
// Pro-only writer appears: Pro would require the option enabled and CE would
// require it disabled, and the only way out is deleting a test. So Pro (the
// source of truth) gets the exact both-ways assertion, and CE gets
// containment only. Same shape as notificationInboxSchemaDrift.spec.ts, which
// asserts different propositions per tree for the same reason.
describe('delivery status filters vs the CE backend (in-repo, always runs)', () => {
    it('has the CE backend source available', () => {
        expect(existsSync(CE_BACKEND_SRC_PATH),
            `CE mirror not found at ${CE_BACKEND_SRC_PATH}`).toBe(true)
    })

    it('every status the CE backend writes is offered and selectable', () => {
        if (!existsSync(CE_BACKEND_SRC_PATH)) return
        const { statuses, filesScanned } = assignedDeliveryStatuses(CE_BACKEND_SRC_PATH)
        expect(filesScanned).toBeGreaterThan(0)  // a scan that saw no java is broken
        expect(statuses.size).toBeGreaterThan(0)
        for (const produced of statuses) {
            expect(offeredStatuses(), `${produced} is written but has no filter option`)
                .toContain(produced)
            expect(enabledStatuses(), `${produced} is written but its filter is disabled`)
                .toContain(produced)
        }
    })
})

describe('delivery status filters vs the Pro backend (skipped if rearm-core absent)', () => {
    it.runIf(existsSync(PRO_BACKEND_SRC_PATH))('selectable set equals what Pro writes', () => {
        const { statuses, filesScanned } = assignedDeliveryStatuses(PRO_BACKEND_SRC_PATH)
        expect(filesScanned).toBeGreaterThan(0)
        expect(statuses.size).toBeGreaterThan(0)
        // Both directions, against the source of truth: nothing selectable
        // without a writer, and nothing written without a selectable option.
        expect([...enabledStatuses()].sort()).toEqual([...statuses].sort())
    })
})

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

describe('subscription test classification', () => {
    const done = [{ status: 'SENT' }]
    const mixed = [{ status: 'SENT' }, { status: 'PENDING' }]

    it('reports DELIVERED once rows exist and none is still PENDING', () => {
        expect(classifySubscriptionTest('FANNED_OUT', done)).toBe('DELIVERED')
        // Fan-out status is irrelevant once settled rows exist.
        expect(classifySubscriptionTest('PENDING', done)).toBe('DELIVERED')
    })

    it('keeps polling while a row is still PENDING, even after fan-out finished', () => {
        // The channel worker dispatches AFTER fan-out, so terminal + pending row
        // is normal and must not be reported as a final result.
        expect(classifySubscriptionTest('FANNED_OUT', mixed)).toBe('IN_FLIGHT')
    })

    it('calls an empty set final ONLY once fan-out is terminal', () => {
        expect(classifySubscriptionTest('PENDING', [])).toBe('IN_FLIGHT')
        expect(classifySubscriptionTest(null, [])).toBe('IN_FLIGHT')
        expect(classifySubscriptionTest(undefined, [])).toBe('IN_FLIGHT')
        for (const s of ['FANNED_OUT', 'SUPPRESSED']) {
            expect(classifySubscriptionTest(s, [])).toBe('NO_DELIVERY')
        }
        // FAILED is terminal too, but gets its own outcome -- see below.
        expect(classifySubscriptionTest('FAILED', [])).toBe('FANOUT_FAILED')
    })

    it('an unknown outbox status is not treated as terminal', () => {
        // Fail safe: a status this UI has not heard of keeps us polling rather
        // than declaring a premature "produced nothing".
        expect(classifySubscriptionTest('SOME_NEW_STATUS', [])).toBe('IN_FLIGHT')
    })

    it('separates a fan-out FAILURE from a deliberate no-delivery', () => {
        // Both end with zero rows, but the no-delivery copy blames the
        // subscription's filter / severity gate. Saying that after a backend
        // fan-out error sends the operator to audit a filter that is fine.
        expect(classifySubscriptionTest('FAILED', [])).toBe('FANOUT_FAILED')
        expect(classifySubscriptionTest('FANNED_OUT', [])).toBe('NO_DELIVERY')
        expect(classifySubscriptionTest('SUPPRESSED', [])).toBe('NO_DELIVERY')
        // A failure that still produced rows is reported from the rows.
        expect(classifySubscriptionTest('FAILED', done)).toBe('DELIVERED')
    })
})

// Drift pin against the mirrored backend enum, so the UI's notion of "fan-out
// is finished" cannot drift silently -- an unrecognised status degrades the
// test dialog back to a 60s timeout with nothing to point at.
//
// SUPERSET, not equality, and deliberately so: this UI is shared between CE
// and Pro, and the enum mirrored into this repo is the CE one, which currently
// trails Pro (Pro has SUPPRESSED, added with the vuln-withholding work; CE does
// not). The UI must handle every status the backend it is talking to can emit,
// so covering MORE than CE declares is correct and covering less is the bug.
const OUTBOX_STATUS_SRC_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/java/io/reliza/model/NotificationOutboxStatus.java',
    import.meta.url))

describe('TERMINAL_OUTBOX_STATUSES tracks the backend enum', () => {
    it('has the backend source available', () => {
        expect(existsSync(OUTBOX_STATUS_SRC_PATH)).toBe(true)
    })

    it('covers at least every mirrored NotificationOutboxStatus except PENDING', () => {
        if (!existsSync(OUTBOX_STATUS_SRC_PATH)) return
        const src = readFileSync(OUTBOX_STATUS_SRC_PATH, 'utf8')
        const body = src.slice(src.indexOf('public enum NotificationOutboxStatus'))
        const declared = [...body.matchAll(/^\t([A-Z_]+)[,;]/gm)].map(m => m[1])
        expect(declared.length).toBeGreaterThan(1)
        expect(declared).toContain('PENDING')
        for (const terminal of declared.filter(v => v !== 'PENDING')) {
            expect(TERMINAL_OUTBOX_STATUSES).toContain(terminal)
        }
        expect(TERMINAL_OUTBOX_STATUSES).not.toContain('PENDING')
    })
})

describe('owner-routed test caveat', () => {
    const ownerRoute = JSON.stringify([{ channels: [], notifyComponentOwner: true }])
    const plainRoute = JSON.stringify([{ channels: ['abc'], notifyComponentOwner: null }])

    it('detects an owner-routed subscription', () => {
        expect(isOwnerRouted(ownerRoute)).toBe(true)
        expect(isOwnerRouted(plainRoute)).toBe(false)
        expect(isOwnerRouted(null)).toBe(false)
        expect(isOwnerRouted('not json')).toBe(false)
    })

    it('warns only for owner-routed subscriptions', () => {
        expect(ownerRoutedSuccessCaveat(ownerRoute)).toContain('deliberately stamped')
        expect(ownerRoutedSuccessCaveat(plainRoute)).toBeNull()
    })
})

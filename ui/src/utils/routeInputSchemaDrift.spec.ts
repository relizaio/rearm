import { describe, it, expect, vi } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, coerceInputValue, type GraphQLSchema, type GraphQLInputType } from 'graphql'

// notificationsCommon imports commonFunctions, which boots the graphql client
// + keycloak as an import side effect. Same stub as notificationsCommon.spec.ts
// -- buildNotificationRouteInput itself touches none of it.
vi.mock('@/utils/commonFunctions', () => ({
    default: { dateDisplay: (s: string) => `ABS:${s}` },
}))

const { buildNotificationRouteInput } = await import('./notificationsCommon')

// buildNotificationRouteInput vs the REAL schemas, not a hand-copied field list.
//
// GraphQL input coercion rejects unknown keys outright, so a route field the
// server does not declare fails the entire subscription mutation. `teams` is
// Pro-only; a CE UI that sends `teams: []` cannot save ANY subscription. That
// path became reachable when the CE UI stopped hiding the Subscriptions tab.
//
// Same convention as notificationInboxSchemaDrift.spec.ts: the CE mirror ships
// in this repo so its checks always run; the Pro schema lives in the sibling
// rearm-core checkout and is skipped (not failed) when absent.
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

function loadSchema (path: string): GraphQLSchema | null {
    return existsSync(path) ? buildSchema(readFileSync(path, 'utf8')) : null
}

const ceSchema = loadSchema(CE_SCHEMA_PATH)
const proSchema = loadSchema(PRO_SCHEMA_PATH)

/** Coercion errors for `value` against input type `typeName`, [] if it is valid. */
function coerceErrors (schema: GraphQLSchema, typeName: string, value: unknown): string[] {
    const type = schema.getType(typeName) as GraphQLInputType
    const errors: string[] = []
    coerceInputValue(value, type, (_path, _invalidValue, error) => {
        errors.push(error.message)
    })
    return errors
}

// A fully-populated route as the editor models it, including the `_raw`
// passthrough of an edit loaded from a Pro backend.
const ROUTE_WITH_TEAMS = {
    _raw: { andEnvIn: ['prod'], andLifecycleIn: ['GENERAL_AVAILABILITY'], teams: ['team-1'] },
    whenSeverityAtLeast: 'HIGH',
    channels: ['ch-1'],
    channelGroups: [],
    perspectives: [],
    teams: ['team-1'],
}

const ROUTE_NO_TEAMS = { ...ROUTE_WITH_TEAMS, teams: [], _raw: { andEnvIn: ['prod'] } }

describe('buildNotificationRouteInput vs the CE mirror schema (in-repo, always runs)', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema).not.toBeNull()
    })

    it('CE really does lack `teams` -- the premise of the omission', () => {
        if (!ceSchema) return
        // Guards against "fixed" meaning "CE caught up and we forgot". If CE
        // gains `teams`, this fails and the omission can be simplified away.
        const errs = coerceErrors(ceSchema, 'NotificationRouteInput', {
            channels: ['ch-1'], teams: ['team-1'],
        })
        expect(errs.join(' ')).toMatch(/teams/)
    })

    it('a route with NO teams coerces cleanly on CE', () => {
        if (!ceSchema) return
        expect(coerceErrors(ceSchema, 'NotificationRouteInput', buildNotificationRouteInput(ROUTE_NO_TEAMS)))
            .toEqual([])
    })

    it('never leaks `teams` in through the _raw spread when it is not modelled', () => {
        // Exercises the passthrough strip SPECIFICALLY: `_raw` carries teams
        // while the modelled field is empty, which is the only shape in which
        // the strip is load-bearing. A fixture whose `_raw` has no teams would
        // pass with the strip deleted and prove nothing.
        const unmodelled = { _raw: { andEnvIn: ['prod'], teams: ['team-1'] }, channels: ['ch-1'] }
        const built = buildNotificationRouteInput(unmodelled)
        expect(built).not.toHaveProperty('teams')
        if (ceSchema) {
            expect(coerceErrors(ceSchema, 'NotificationRouteInput', built)).toEqual([])
        }
    })

    it('drops nothing else while stripping teams', () => {
        const unmodelled = { _raw: { andEnvIn: ['prod'], teams: ['team-1'] }, channels: ['ch-1'] }
        expect(buildNotificationRouteInput(unmodelled).andEnvIn).toEqual(['prod'])
    })

    it('omits `teams` when the modelled list is empty', () => {
        expect(buildNotificationRouteInput(ROUTE_NO_TEAMS)).not.toHaveProperty('teams')
    })

    it('preserves the unmodelled passthrough fields', () => {
        const out = buildNotificationRouteInput(ROUTE_WITH_TEAMS)
        expect(out.andEnvIn).toEqual(['prod'])
        expect(out.andLifecycleIn).toEqual(['GENERAL_AVAILABILITY'])
    })
})

// runIf, not an early return: an early return REPORTS PASSED when rearm-core is
// absent, which makes the describe title a lie and hides that Pro went unchecked.
describe('buildNotificationRouteInput vs the Pro schema (skipped when rearm-core is absent)', () => {
    it.runIf(proSchema)('a route WITH teams coerces cleanly on Pro', () => {
        expect(coerceErrors(proSchema!, 'NotificationRouteInput', buildNotificationRouteInput(ROUTE_WITH_TEAMS)))
            .toEqual([])
    })

    it.runIf(proSchema)('a route with no teams coerces cleanly on Pro too', () => {
        expect(coerceErrors(proSchema!, 'NotificationRouteInput', buildNotificationRouteInput(ROUTE_NO_TEAMS)))
            .toEqual([])
    })

    it('carries the team targets through on Pro -- the omission is CE-only', () => {
        // Pure function, no schema needed -- always runs.
        expect(buildNotificationRouteInput(ROUTE_WITH_TEAMS).teams).toEqual(['team-1'])
    })
})

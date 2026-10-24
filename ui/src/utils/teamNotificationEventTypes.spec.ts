import { describe, it, expect } from 'vitest'

import { coerceInputValue, type GraphQLSchema, type GraphQLInputType } from 'graphql'
import { ceSchema, proSchema } from './schemaDriftSupport'

import {
    ownedComponentEventTypes,
    selectedFromExcluded,
    excludedFromSelected,
    buildOwnedComponentNotificationsInput,
} from './teamNotificationEventTypes'

const OPTIONS = [
    { label: 'New vuln affects releases', value: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Vulnerability record updated', value: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'VEX state changed (not yet available)', value: 'VEX_STATE_CHANGED', disabled: true },
    { label: 'Release created', value: 'RELEASE_CREATED' },
    { label: 'Approval requested', value: 'APPROVAL_REQUESTED' },
    { label: 'Instance deployment changed', value: 'INSTANCE_DEPLOYMENT_CHANGED' },
    { label: 'Instance deployment failed', value: 'INSTANCE_DEPLOYMENT_FAILED' },
]
const VALUES = ['NEW_VULN_AFFECTS_RELEASES', 'VULNERABILITY_RECORD_UPDATED',
    'RELEASE_CREATED', 'APPROVAL_REQUESTED']

describe('ownedComponentEventTypes', () => {
    it('drops event types with no affected component (VEX + instance-deployment)', () => {
        const values = ownedComponentEventTypes(OPTIONS).map(o => o.value)
        expect(values).not.toContain('VEX_STATE_CHANGED')
        expect(values).not.toContain('INSTANCE_DEPLOYMENT_CHANGED')
        expect(values).not.toContain('INSTANCE_DEPLOYMENT_FAILED')
        expect(values).toEqual(VALUES)
    })

    it('keeps the labels so the picker reads like the subscription editor', () => {
        expect(ownedComponentEventTypes(OPTIONS)[0].label).toBe('New vuln affects releases')
    })

    it('survives an empty or missing option list', () => {
        expect(ownedComponentEventTypes([])).toEqual([])
        expect(ownedComponentEventTypes(null as any)).toEqual([])
    })
})

describe('the picker shows the complement of what is stored', () => {
    it('shows EVERYTHING for a team that has excluded nothing', () => {
        // The point of storing exclusions. A team that never touched the setting
        // must open with the whole list ticked, not an empty picker.
        expect(selectedFromExcluded(VALUES, [])).toEqual(VALUES)
        expect(selectedFromExcluded(VALUES, null)).toEqual(VALUES)
        expect(selectedFromExcluded(VALUES, undefined)).toEqual(VALUES)
    })

    it('hides exactly what the team excluded', () => {
        expect(selectedFromExcluded(VALUES, ['RELEASE_CREATED']))
            .toEqual(['NEW_VULN_AFFECTS_RELEASES', 'VULNERABILITY_RECORD_UPDATED', 'APPROVAL_REQUESTED'])
    })

    it('shows a NEW event type as selected for a team saved before it existed', () => {
        // The behaviour the storage choice exists for: RELEASE_BOM_DIFF ships
        // after this team last saved, and the team keeps hearing about what it
        // owns without anyone re-editing it.
        const laterValues = [...VALUES, 'RELEASE_BOM_DIFF']
        expect(selectedFromExcluded(laterValues, ['RELEASE_CREATED'])).toContain('RELEASE_BOM_DIFF')
    })

    it('ignores a stored exclusion for an event type that no longer exists', () => {
        expect(selectedFromExcluded(VALUES, ['SOMETHING_REMOVED'])).toEqual(VALUES)
    })
})

describe('what gets stored', () => {
    it('stores nothing when everything is selected', () => {
        expect(excludedFromSelected(VALUES, VALUES)).toEqual([])
    })

    it('stores exactly what was deselected', () => {
        expect(excludedFromSelected(VALUES, ['NEW_VULN_AFFECTS_RELEASES']))
            .toEqual(['VULNERABILITY_RECORD_UPDATED', 'RELEASE_CREATED', 'APPROVAL_REQUESTED'])
    })

    it('stores everything when the picker is emptied', () => {
        // Which the backend rejects -- a team cannot exclude its way to a
        // subscription that matches nothing -- so this must round-trip honestly
        // rather than being silently softened here.
        expect(excludedFromSelected(VALUES, [])).toEqual(VALUES)
    })

    // Derived from the AVAILABLE list rather than by diffing the previous
    // exclusions, so a stale entry for an event type no longer offered is
    // dropped on the next save: the team cannot have meant to exclude something
    // it was never asked about.
    it('round-trips: store then re-read gives back the same selection', () => {
        const selection = ['NEW_VULN_AFFECTS_RELEASES', 'APPROVAL_REQUESTED']
        const stored = excludedFromSelected(VALUES, selection)
        expect(selectedFromExcluded(VALUES, stored)).toEqual(selection)
    })
})

// The payload vs the REAL schema, not a hand-copied field list.
//
// GraphQL input coercion rejects unknown keys outright and fails the WHOLE
// mutation, and validate-graphql.mjs checks documents, never variables -- so
// this is the only thing standing between a renamed input field and a team
// editor that cannot save. Same convention as routeInputSchemaDrift.spec.ts:
// both schemas declare the team inputs, so CE is checked unconditionally and Pro
// when the sibling rearm-core checkout is present -- its absence SKIPS rather
// than fails.
const SCHEMAS: Array<[string, GraphQLSchema | null]> = [['CE', ceSchema], ['Pro', proSchema]]

function coerceErrors (schema: GraphQLSchema, typeName: string, value: unknown): string[] {
    const type = schema.getType(typeName) as GraphQLInputType
    const errors: string[] = []
    coerceInputValue(value, type, (_path, _invalidValue, error) => { errors.push(error.message) })
    return errors
}

for (const [edition, schema] of SCHEMAS) {
    describe(`buildOwnedComponentNotificationsInput vs the ${edition} schema`, () => {
        const cases: Array<[string, ReturnType<typeof buildOwnedComponentNotificationsInput>]> = [
            ['enabled with everything selected',
                buildOwnedComponentNotificationsInput(true, VALUES, VALUES)],
            ['enabled with two deselected',
                buildOwnedComponentNotificationsInput(true, VALUES, ['RELEASE_CREATED'])],
            ['switched off',
                buildOwnedComponentNotificationsInput(false, VALUES, VALUES)],
        ]

        it.skipIf(!schema)('coerces cleanly as OwnedComponentNotificationsInput', () => {
            for (const [label, payload] of cases) {
                expect(coerceErrors(schema!, 'OwnedComponentNotificationsInput', payload), label)
                    .toEqual([])
            }
        })

        it.skipIf(!schema)('coerces cleanly nested inside UpdateTeamInput', () => {
            // The shape the mutation actually sends. A field renamed on the input
            // type shows up here rather than as a failed save on the sandbox.
            const payload = {
                teamId: '00000000-0000-0000-0000-000000000001',
                name: 'Payments',
                ownedComponentNotifications: buildOwnedComponentNotificationsInput(
                    true, VALUES, ['APPROVAL_REQUESTED']),
            }
            expect(coerceErrors(schema!, 'UpdateTeamInput', payload)).toEqual([])
        })

        it.skipIf(!schema)('rejects an unknown key, proving the check has teeth', () => {
            const payload = {
                ...buildOwnedComponentNotificationsInput(true, VALUES, VALUES),
                notAField: true,
            }
            expect(coerceErrors(schema!, 'OwnedComponentNotificationsInput', payload).length)
                .toBeGreaterThan(0)
        })
    })
}

describe('board events are not team events (82880ea6)', () => {
    it('never offers a board event type to an ownership-scoped subscription', async () => {
        const { ownedComponentEventTypes } = await import('./teamNotificationEventTypes')
        const offered = ownedComponentEventTypes([
            { label: 'Board alert', value: 'AGENT_BOARD_ALERT' },
            { label: 'Board task needs a person', value: 'AGENT_TASK_NEEDS_PERSON' },
            { label: 'Board task returned', value: 'AGENT_TASK_RETURNED' },
            { label: 'Board task waiting', value: 'AGENT_TASK_QUEUE_AGE' },
            { label: 'Release created', value: 'RELEASE_CREATED' },
        ]).map(o => o.value)
        expect(offered).toEqual(['RELEASE_CREATED'])
    })
})

describe('Pro-only team events (TEA-5)', () => {
    it('offers the TEA publication events on Pro and drops them on CE, whose schema has no such value', async () => {
        const { ownedComponentEventTypes } = await import('./teamNotificationEventTypes')
        // notificationsCommon.spec.ts pins that eventTypeOptions flags both proOnly; importing it here
        // would pull the GraphQL client into a DOM-less run.
        const eventTypeOptions = [
            { label: 'Release created', value: 'RELEASE_CREATED' },
            { label: 'Release published on TEA', value: 'RELEASE_TEA_PUBLISHED', proOnly: true },
            { label: 'Release hidden from TEA', value: 'RELEASE_TEA_HIDDEN', proOnly: true },
        ]
        const pro = ownedComponentEventTypes(eventTypeOptions).map(o => o.value)
        const ce = ownedComponentEventTypes(eventTypeOptions, false).map(o => o.value)
        for (const t of ['RELEASE_TEA_PUBLISHED', 'RELEASE_TEA_HIDDEN']) {
            expect(pro, t).toContain(t)
            expect(ce, t).not.toContain(t)
        }
        expect(ce).toContain('RELEASE_CREATED')
    })
})

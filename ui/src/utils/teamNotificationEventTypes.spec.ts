import { describe, it, expect } from 'vitest'

import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, coerceInputValue, type GraphQLSchema, type GraphQLInputType } from 'graphql'

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
]
const VALUES = ['NEW_VULN_AFFECTS_RELEASES', 'VULNERABILITY_RECORD_UPDATED',
    'RELEASE_CREATED', 'APPROVAL_REQUESTED']

describe('ownedComponentEventTypes', () => {
    it('drops VEX, which no ownership-scoped subscription could ever match', () => {
        const values = ownedComponentEventTypes(OPTIONS).map(o => o.value)
        expect(values).not.toContain('VEX_STATE_CHANGED')
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
// Teams are Pro-only, the schema lives in the sibling rearm-core checkout, and
// its absence SKIPS rather than fails.
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

function loadSchema (path: string): GraphQLSchema | null {
    return existsSync(path) ? buildSchema(readFileSync(path, 'utf8')) : null
}
const proSchema = loadSchema(PRO_SCHEMA_PATH)

function coerceErrors (schema: GraphQLSchema, typeName: string, value: unknown): string[] {
    const type = schema.getType(typeName) as GraphQLInputType
    const errors: string[] = []
    coerceInputValue(value, type, (_path, _invalidValue, error) => { errors.push(error.message) })
    return errors
}

describe('buildOwnedComponentNotificationsInput vs the Pro schema', () => {
    const cases: Array<[string, ReturnType<typeof buildOwnedComponentNotificationsInput>]> = [
        ['enabled with everything selected',
            buildOwnedComponentNotificationsInput(true, VALUES, VALUES)],
        ['enabled with two deselected',
            buildOwnedComponentNotificationsInput(true, VALUES, ['RELEASE_CREATED'])],
        ['switched off',
            buildOwnedComponentNotificationsInput(false, VALUES, VALUES)],
    ]

    it.skipIf(!proSchema)('coerces cleanly as OwnedComponentNotificationsInput', () => {
        for (const [label, payload] of cases) {
            expect(coerceErrors(proSchema!, 'OwnedComponentNotificationsInput', payload), label)
                .toEqual([])
        }
    })

    it.skipIf(!proSchema)('coerces cleanly nested inside UpdateTeamInput', () => {
        // The shape the mutation actually sends. A field renamed on the input
        // type shows up here rather than as a failed save on the sandbox.
        const payload = {
            teamId: '00000000-0000-0000-0000-000000000001',
            name: 'Payments',
            ownedComponentNotifications: buildOwnedComponentNotificationsInput(
                true, VALUES, ['APPROVAL_REQUESTED']),
        }
        expect(coerceErrors(proSchema!, 'UpdateTeamInput', payload)).toEqual([])
    })

    it.skipIf(!proSchema)('rejects an unknown key, proving the check has teeth', () => {
        const payload = {
            ...buildOwnedComponentNotificationsInput(true, VALUES, VALUES),
            notAField: true,
        }
        expect(coerceErrors(proSchema!, 'OwnedComponentNotificationsInput', payload).length)
            .toBeGreaterThan(0)
    })
})

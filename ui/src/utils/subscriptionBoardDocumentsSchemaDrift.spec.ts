import { describe, it, expect, vi } from 'vitest'
import {
    coerceInputValue, validate, type GraphQLSchema, type GraphQLInputType,
    type GraphQLInputObjectType, type GraphQLObjectType,
} from 'graphql'
import { ceSchema, proSchema } from './schemaDriftSupport'

// notificationsCommon imports commonFunctions, which boots the graphql client
// + keycloak as an import side effect. Same stub as routeInputSchemaDrift.spec.ts.
vi.mock('@/utils/commonFunctions', () => ({
    default: { dateDisplay: (s: string) => `ABS:${s}` },
}))

const { LIST_SUBSCRIPTIONS_QUERY, LIST_SUBSCRIPTIONS_CORE_QUERY, includeBoardDocumentsInput } =
    await import('./notificationsCommon')

// TPF-159: the "Include board document releases" opt-in against the real schemas. The
// subscriptions list documents are built from an interpolated field list, which
// validate:graphql cannot expand, so this file is the Pro-schema gate for the new selection
// and for the input shape the editor sends on every save.

/** Coercion errors for `value` against input type `typeName`, [] if it is valid. */
function coerceErrors (schema: GraphQLSchema, typeName: string, value: unknown): string[] {
    const type = schema.getType(typeName) as GraphQLInputType
    const errors: string[] = []
    coerceInputValue(value, type, (_path, _invalidValue, error) => {
        errors.push(error.message)
    })
    return errors
}

function fullInput (on: boolean) {
    return {
        org: '00000000-0000-0000-0000-000000000001',
        name: 'docs',
        status: 'ACTIVE',
        eventTypes: ['RELEASE_CREATED'],
        filter: { mode: 'PRESET', celExpression: null },
        routes: [{ channels: ['ch-1'], channelGroups: [], perspectives: [] }],
        dedupWindowMinutes: null,
        ...includeBoardDocumentsInput(on),
    }
}

describe('T-7: subscription board-document opt-in vs the Pro schema (skipped if absent)', () => {
    it.runIf(proSchema)('the FULL list selection is valid against Pro', () => {
        expect(validate(proSchema as GraphQLSchema, LIST_SUBSCRIPTIONS_QUERY).map(e => e.message)).toEqual([])
    })
    it.runIf(proSchema)('the save input coerces with the opt-in on and off', () => {
        expect(coerceErrors(proSchema as GraphQLSchema, 'NotificationSubscriptionInput', fullInput(true))).toEqual([])
        expect(coerceErrors(proSchema as GraphQLSchema, 'NotificationSubscriptionInput', fullInput(false))).toEqual([])
    })
    it.runIf(proSchema)('the field is a nullable Boolean on input and result, and not a filter field', () => {
        const pro = proSchema as GraphQLSchema
        expect((pro.getType('NotificationSubscriptionInput') as GraphQLInputObjectType)
            .getFields().includeBoardDocuments.type.toString()).toBe('Boolean')
        expect((pro.getType('NotificationSubscriptionResult') as GraphQLObjectType)
            .getFields().includeBoardDocuments.type.toString()).toBe('Boolean')
        expect((pro.getType('NotificationFilterInput') as GraphQLInputObjectType)
            .getFields().includeBoardDocuments).toBeUndefined()
    })
})

describe('T-8: subscription CORE selection vs the CE mirror schema (in-repo, always runs)', () => {
    it('the CORE list selection stays valid on CE', () => {
        expect(validate(ceSchema, LIST_SUBSCRIPTIONS_CORE_QUERY).map(e => e.message)).toEqual([])
    })
})

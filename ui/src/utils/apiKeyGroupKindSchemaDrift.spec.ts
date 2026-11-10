import { describe, it, expect } from 'vitest'
import { coerceInputValue, parse, validate, type DocumentNode, type GraphQLError, type GraphQLSchema } from 'graphql'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'
import { API_KEY_GROUPS_OF_ORG_QUERY } from './apiKeyGroupsQuery'
import {
    CREATE_CUSTOMER_GROUP, DEACTIVATE_CUSTOMER_GROUP, UPDATE_CUSTOMER_GROUP, customerCreateSteps, customerUpdateInput
} from './teaCustomers'
import { permissionsFromScopedModel } from './apiKeyGroupInput'

/**
 * FDAW-40 row 22i (FDAW-25 3.12): the key-group kind and the Customers documents against the CE schema
 * (in-repo, so after FDAW-37's mirror) and the Pro schema (when the sibling rearm-core checkout is there).
 *
 * Both creates send kind only in the variables, which document validation cannot see, so the input is
 * pinned by a literal create, the absence of kind on update by a literal update, and the built variables
 * by coerceInputValue against the real argument types (the attestationVariablesCoercion.spec.ts tool).
 */
const SCHEMAS: Array<[string, GraphQLSchema | null]> = [['CE', ceSchema], ['Pro', proSchema]]

const DOCUMENTS: Array<[string, DocumentNode]> = [
    ['API_KEY_GROUPS_OF_ORG_QUERY', API_KEY_GROUPS_OF_ORG_QUERY],
    ['CREATE_CUSTOMER_GROUP', CREATE_CUSTOMER_GROUP],
    ['UPDATE_CUSTOMER_GROUP', UPDATE_CUSTOMER_GROUP],
    ['DEACTIVATE_CUSTOMER_GROUP', DEACTIVATE_CUSTOMER_GROUP],
]

function argType (schema: GraphQLSchema, mutation: string) {
    const field = schema.getMutationType()!.getFields()[mutation]
    const arg = field.args.find(a => a.name === 'apiKeyGroup')
    if (!arg) throw new Error(`${mutation} has no argument apiKeyGroup`)
    return arg.type
}

function coerce (schema: GraphQLSchema, mutation: string, value: unknown): string[] {
    const errors: string[] = []
    coerceInputValue(value, argType(schema, mutation), (_p, _v, err: GraphQLError) => errors.push(err.message))
    return errors
}

const ORG_READ = permissionsFromScopedModel({ orgPermission: { type: 'READ_ONLY', functions: [], approvals: [] }, scopedPermissions: [] }, 'o1')

for (const [edition, maybeSchema] of SCHEMAS) {
    describe.runIf(maybeSchema)(`API key group kind vs the ${edition} schema (row 22i)`, () => {
        const schema = maybeSchema as GraphQLSchema

        it('(1) the list query and the three Customers documents validate', () => {
            for (const [name, doc] of DOCUMENTS) {
                expect(validate(schema, doc).map(e => e.message), name).toEqual([])
            }
        })

        it('(2) createApiKeyGroup takes kind on its input', () => {
            const literal = parse('mutation { createApiKeyGroup(apiKeyGroup: { name: "n", org: "o", kind: CUSTOMER }) { uuid kind } }')
            expect(validate(schema, literal).map(e => e.message)).toEqual([])
        })

        it('(3) updateApiKeyGroup has no kind', () => {
            const literal = parse('mutation { updateApiKeyGroup(apiKeyGroup: { groupId: "g", kind: CUSTOMER }) { uuid } }')
            const errors = validate(schema, literal).map(e => e.message)
            expect(errors).toHaveLength(1)
            expect(errors[0]).toContain('kind')
        })

        it('(4) the built create and update variables coerce against the real argument types', () => {
            const create = customerCreateSteps({ name: 'Acme', description: '', apiKeys: [] }, [], [], 'o1').create
            expect(coerce(schema, 'createApiKeyGroup', create)).toEqual([])
            expect(coerce(schema, 'createApiKeyGroup', { name: 'n', description: '', org: 'o1', kind: 'INTERNAL' })).toEqual([])
            const stored = { uuid: 'g', name: 'Old', description: 'old', apiKeys: [], permissions: { permissions: [] } }
            const update = customerUpdateInput(stored, { name: 'New', description: 'new', apiKeys: ['k1'] }, ORG_READ)
            expect(Object.keys(update).sort()).toEqual(['apiKeys', 'description', 'groupId', 'name', 'permissions'])
            expect(coerce(schema, 'updateApiKeyGroup', update)).toEqual([])
            expect(coerce(schema, 'updateApiKeyGroup', { groupId: 'g', status: 'ACTIVE' })).toEqual([])
        })

        it('(5) ApiKeyGroupKind is INTERNAL and CUSTOMER', () => {
            expect(enumValuesOf(schema, 'ApiKeyGroupKind')).toEqual(['INTERNAL', 'CUSTOMER'])
        })
    })
}

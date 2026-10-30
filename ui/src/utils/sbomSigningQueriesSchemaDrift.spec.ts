import { describe, it, expect } from 'vitest'
import { validate, type FieldNode, type GraphQLSchema, type OperationDefinitionNode } from 'graphql'
import { GENERATE_SBOM_SIGNING_KEY, IMPORT_SBOM_SIGNING_KEY, REVOKE_SBOM_SIGNING_KEY, SBOM_SIGNING_KEYS } from './sbomSigningQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

// T-27 (SCORE-35): the four SBOM signing documents against both schemas, the
// sbomScoreQueriesSchemaDrift.spec.ts shape. They are statically parseable, so
// scripts/validate-graphql checks them too; this spec pins the CE side, which that script only
// warns about.
//
// Pro (rearm-saas, SCORE-32) has the list and the three mutations. The CE mirror in this repo
// has them since the CE sync of SCORE-55, so every document is valid against the CE schema too.
const DOCUMENTS: Array<[string, any, string, 'query' | 'mutation']> = [
    ['SBOM_SIGNING_KEYS', SBOM_SIGNING_KEYS, 'sbomSigningKeys', 'query'],
    ['GENERATE_SBOM_SIGNING_KEY', GENERATE_SBOM_SIGNING_KEY, 'generateSbomSigningKey', 'mutation'],
    ['IMPORT_SBOM_SIGNING_KEY', IMPORT_SBOM_SIGNING_KEY, 'importSbomSigningKey', 'mutation'],
    ['REVOKE_SBOM_SIGNING_KEY', REVOKE_SBOM_SIGNING_KEY, 'revokeSbomSigningKey', 'mutation'],
]

// The SbomSigningKey type of design 3.1, as built by SCORE-32.
const KEY_FIELDS = ['uuid', 'org', 'keyId', 'algorithm', 'status', 'source', 'publicKeyJwk', 'publicKeyPem', 'createdDate', 'retiredAt', 'revokedAt']

const operation = (doc: any): OperationDefinitionNode => doc.definitions[0]
const rootField = (doc: any): FieldNode => operation(doc).selectionSet.selections[0] as FieldNode

describe('SBOM signing documents vs the schemas', () => {
    it.runIf(proSchema).each(DOCUMENTS)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as GraphQLSchema, doc).map(e => e.message)).toEqual([])
    })

    it.each(DOCUMENTS)('%s is valid against the CE schema', (_name, doc) => {
        expect(validate(ceSchema, doc).map(e => e.message)).toEqual([])
    })

    it.each(DOCUMENTS)('%s is a %s with one root field', (_name, doc, field, op) => {
        expect(operation(doc).operation).toBe(op)
        expect(operation(doc).selectionSet.selections).toHaveLength(1)
        expect(rootField(doc).name.value).toBe(field)
    })

    it.each(DOCUMENTS)('%s selects only fields of SbomSigningKey', (_name, doc) => {
        const selected = rootField(doc).selectionSet!.selections.map(s => (s as FieldNode).name.value)
        expect(selected.length).toBeGreaterThan(0)
        expect(selected.filter(f => !KEY_FIELDS.includes(f))).toEqual([])
    })
})

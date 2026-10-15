import { describe, it, expect } from 'vitest'
import { GraphQLObjectType, print, validate, type FieldNode, type GraphQLSchema, type OperationDefinitionNode } from 'graphql'
import {
    COMPONENT_DECLARED_LICENSE_QUERY, ORG_DECLARED_LICENSE_QUERY, RELEASE_DECLARED_LICENSE_QUERY,
    SUPPLIER_SENTENCE, UPDATE_COMPONENT_DECLARED_LICENSE, UPDATE_ORG_DECLARED_LICENSE, UPDATE_RELEASE_DECLARED_LICENSE,
} from './declaredLicense'
import { ORGANIZATIONS_CORE, ORGANIZATIONS_FULL } from './organizationsQuery'
import graphqlQueries from './graphqlQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

// T-24 (SCORE-42, design ADR-1): the six declared-licence documents against both schemas, the
// sbomScoreQueriesSchemaDrift.spec.ts shape. They are statically parseable, so
// scripts/validate-graphql checks them too; this spec pins the CE side, which that script only
// warns about, and that the SHARED documents never gain the fields.
//
// Pro (rearm-saas 2026-10-sbom-score, SCORE-41) declares the fields. The CE mirror in this repo
// lacks them until its sync: a CE backend refuses each read as schema drift and the three
// surfaces show the unsupported note. When the sync lands, the CE cases fail and are to be turned
// into "valid on CE" assertions.
const cannotQuery = (fields: string[], type: string): string[] =>
    fields.map(f => `Cannot query field "${f}" on type "${type}".`)
const ORG_FIELDS = cannotQuery(['defaultLicenseExpression', 'defaultLicenseUrl'], 'Settings')
const COMPONENT_FIELDS = cannotQuery(['licenseExpression', 'licenseUrl', 'supplierName'], 'Component')
const RELEASE_FIELDS = cannotQuery(['licenseExpression', 'effectiveLicense'], 'Release')

const DOCUMENTS: Array<[string, any, string[]]> = [
    ['ORG_DECLARED_LICENSE_QUERY', ORG_DECLARED_LICENSE_QUERY, ORG_FIELDS],
    ['UPDATE_ORG_DECLARED_LICENSE', UPDATE_ORG_DECLARED_LICENSE, ORG_FIELDS],
    ['COMPONENT_DECLARED_LICENSE_QUERY', COMPONENT_DECLARED_LICENSE_QUERY, COMPONENT_FIELDS],
    ['UPDATE_COMPONENT_DECLARED_LICENSE', UPDATE_COMPONENT_DECLARED_LICENSE, COMPONENT_FIELDS],
    ['RELEASE_DECLARED_LICENSE_QUERY', RELEASE_DECLARED_LICENSE_QUERY, RELEASE_FIELDS],
    ['UPDATE_RELEASE_DECLARED_LICENSE', UPDATE_RELEASE_DECLARED_LICENSE, RELEASE_FIELDS],
]

const NEW_FIELDS = ['defaultLicenseExpression', 'defaultLicenseUrl', 'licenseExpression', 'licenseUrl', 'supplierName', 'effectiveLicense']

/** A selection-set string with its `#` comment lines removed: the claim is about what is asked for. */
const selectionOnly = (s: string): string => s.split('\n').filter(l => !l.trim().startsWith('#')).join('\n')

const SHARED: Array<[string, string]> = [
    ['ORGANIZATIONS_FULL', print(ORGANIZATIONS_FULL)],
    ['ORGANIZATIONS_CORE', print(ORGANIZATIONS_CORE)],
    ['SingleReleaseGql', print(graphqlQueries.SingleReleaseGql)],
    ['SingleReleaseProductGql', print(graphqlQueries.SingleReleaseProductGql)],
    ['ComponentMutate', print(graphqlQueries.ComponentMutate)],
    ['ComponentFullData', selectionOnly(graphqlQueries.ComponentFullData)],
]

describe('declared licence documents vs the schemas', () => {
    it.runIf(proSchema).each(DOCUMENTS)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as GraphQLSchema, doc).map(e => e.message)).toEqual([])
    })

    it.each(DOCUMENTS)('%s is reported as drift against the CE schema until the CE sync', (_name, doc, expected) => {
        expect(validate(ceSchema, doc).map(e => e.message)).toEqual(expected)
    })

    it.each(SHARED)('the shared document %s selects none of the new fields', (_name, text) => {
        expect(text.length).toBeGreaterThan(0)
        for (const field of NEW_FIELDS) expect(text).not.toMatch(new RegExp(`\\b${field}\\b`))
    })

    // Round 3 (T-1): organization(orgUuid:) is declared but never resolved by rearm-saas, so it
    // validates and answers null; validation cannot catch it, this can.
    it('the organization read selects the organizations list root', () => {
        const op = ORG_DECLARED_LICENSE_QUERY.definitions[0] as OperationDefinitionNode
        expect(op.selectionSet.selections.map(f => (f as FieldNode).name.value)).toEqual(['organizations'])
    })

    it.each(DOCUMENTS)('%s never selects the unresolved single-organization root field', (_name, doc) => {
        expect(print(doc)).not.toMatch(/\borganization\s*\(/)
    })

    it.runIf(proSchema)('the UI supplier sentence is the served Component.supplierName description\'s', () => {
        const component = (proSchema as GraphQLSchema).getType('Component') as GraphQLObjectType
        const description = (component.getFields().supplierName.description || '').replace(/\s+/g, ' ')
        expect(description).toContain(SUPPLIER_SENTENCE)
    })
})

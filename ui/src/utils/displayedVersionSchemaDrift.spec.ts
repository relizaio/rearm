import { describe, it, expect } from 'vitest'
import { print, validate, type GraphQLSchema } from 'graphql'
import { RELEASE_DISPLAYED_VERSION_QUERY, UPDATE_RELEASE_DISPLAYED_VERSION } from './displayedVersion'
import { ADDENDUM_RELEASE_QUERY_CORE, ADDENDUM_RELEASE_QUERY_FULL } from './addendumData'
import graphqlQueries from './graphqlQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

// FDAW-74 row 21 (3.7, 3.9): the two displayed-version documents against both schemas, the
// declaredLicenseSchemaDrift.spec.ts shape. Pro declares Release.displayedVersion; the CE mirror
// lacks it until the CE catch-up, so the two CE cells are the canary: when the catch-up brings the
// field they are replaced by "valid". The shared release documents never select the field.
const DOCUMENTS: Array<[string, any]> = [
    ['RELEASE_DISPLAYED_VERSION_QUERY', RELEASE_DISPLAYED_VERSION_QUERY],
    ['UPDATE_RELEASE_DISPLAYED_VERSION', UPDATE_RELEASE_DISPLAYED_VERSION],
]

const SHARED: Array<[string, string]> = [
    ['SingleReleaseGql', print(graphqlQueries.SingleReleaseGql)],
    ['SingleReleaseProductGql', print(graphqlQueries.SingleReleaseProductGql)],
    ['ADDENDUM_RELEASE_QUERY_CORE', print(ADDENDUM_RELEASE_QUERY_CORE)],
    ['ADDENDUM_RELEASE_QUERY_FULL', print(ADDENDUM_RELEASE_QUERY_FULL)],
]

describe('displayed version documents vs the schemas', () => {
    it.runIf(proSchema).each(DOCUMENTS)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as GraphQLSchema, doc).map(e => e.message)).toEqual([])
    })

    it.each(DOCUMENTS)('%s is not yet valid against the CE schema (the canary of the CE catch-up)', (_name, doc) => {
        const messages = validate(ceSchema, doc).map(e => e.message)
        expect(messages.length).toBeGreaterThan(0)
        for (const m of messages) expect(m).toContain('displayedVersion')
    })

    it.each(SHARED)('the shared document %s does not select displayedVersion', (_name, text) => {
        expect(text.length).toBeGreaterThan(0)
        expect(text).not.toContain('displayedVersion')
    })
})

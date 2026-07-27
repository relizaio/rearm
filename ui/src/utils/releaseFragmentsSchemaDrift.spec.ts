import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse } from 'graphql'
import graphqlQueries from './graphqlQueries'

// These release selection sets are interpolated into `gql` templates at
// runtime (store.fetchReleasesByOrgUuids and friends). scripts/validate-graphql
// only parses statically-analysable documents, so it skips every template
// containing ${...} -- which is exactly where CHILD_RELEASE_GQL_DATA lived
// when it shipped asking for a DigestRecord.value field that has never
// existed. The whole query fails validation at runtime, so a single wrong
// field name blanks the view rather than degrading it.
//
// Checked against the CE schema only. It ships in this repo, so the path
// always resolves and there is nothing to skip; the Pro schema lives in a
// separate repository that is not available here. CE is expected to match
// Pro (it may lag on updates), so a field valid on CE is valid on Pro.
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))

// Read eagerly: a missing schema is a broken checkout, and should fail the
// suite rather than quietly skip every assertion below.
const ceSchema = buildSchema(readFileSync(CE_SCHEMA_PATH, 'utf8'))

// Every fragment here is a selection set on Release, so each one is checked in
// the operation shape the UI actually sends it in.
const RELEASE_FRAGMENTS: Array<[string, string]> = [
    ['ChildReleaseGqlData', graphqlQueries.ChildReleaseGqlData],
    ['MultiReleaseGqlData', graphqlQueries.MultiReleaseGqlData],
    ['BranchReleaseListGqlData', graphqlQueries.BranchReleaseListGqlData]
]

function asReleaseQuery (fragment: string) {
    return parse(`query FragmentCheck($orgId: ID!, $releaseIds: [ID]) {
        releases(orgFilter: $orgId, releaseFilter: $releaseIds) {
            ${fragment}
        }
    }`)
}

describe('release selection fragments vs the CE schema', () => {
    it.each(RELEASE_FRAGMENTS)('%s is valid against the CE schema', (_name, fragment) => {
        expect(validate(ceSchema, asReleaseQuery(fragment)).map(e => e.message)).toEqual([])
    })
})

import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
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
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

// Read eagerly: a missing schema is a broken checkout, and should fail the
// suite rather than quietly skip every assertion below.
const ceSchema = buildSchema(readFileSync(CE_SCHEMA_PATH, 'utf8'))
const proSchema = existsSync(PRO_SCHEMA_PATH)
    ? buildSchema(readFileSync(PRO_SCHEMA_PATH, 'utf8'))
    : null

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

// The two SINGLE-release documents, which are full DocumentNodes rather than fragments.
//
// They are exactly the hole this file's header describes, one level up: both interpolate
// ${...}, so scripts/validate-graphql skips them -- and it does not count skips of that
// kind in its "N skipped" line either, so the report UNDERSTATES what went unchecked.
//
// The pairing below is the load-bearing part. fetchRelease uses the product-simplified
// document until the Underlying Artifacts tab is visited and the FULL one afterwards, so a
// field selected by only one of them is present or absent depending on which tabs the
// operator happened to click. eos/eol shipped in the simplified query alone: the editor
// saved correctly, then the refetch read undefined, blanked both pickers and reset the
// baseline -- so the window looked permanently unsaveable while the value sat safely in the
// database. A comment used to be the only thing holding these two in step.
const SINGLE_RELEASE_DOCUMENTS: Array<[string, any]> = [
    ['SingleReleaseGql', graphqlQueries.SingleReleaseGql],
    ['SingleReleaseProductGql', graphqlQueries.SingleReleaseProductGql]
]

describe('single-release documents vs the CE schema', () => {
    /**
     * Pro, not CE. These two select fdaAssessmentNarrative, which CE gains at the deferred
     * sync -- so a CE assertion here would fail for a reason the ruling has already accepted
     * as informational. Checking Pro keeps the assertion meaningful instead of deleting it.
     */
    it.runIf(proSchema).each(SINGLE_RELEASE_DOCUMENTS)(
        '%s is valid against the Pro schema', (_name, doc) => {
            expect(validate(proSchema as any, doc).map(e => e.message)).toEqual([])
        })

    /**
     * The CE gap is EXPECTED and TEMPORARY, asserted so it cannot quietly become permanent:
     * when the sync lands this fails, and the documents move back to a CE assertion.
     */
    it.each(SINGLE_RELEASE_DOCUMENTS)('%s is still ahead of CE, pending the sync', (_name, doc) => {
        const errs = validate(ceSchema, doc).map(e => e.message)
        // EVERY error must be the expected one, not merely "the expected one appears".
        // Joining and asking toContain was near-worthless: an unrelated typo -- a misspelled
        // artifactDetails subfield, say -- adds its own message while the expected string is
        // still present, so the assertion passed on a broken document. Combined with the Pro
        // check being skippable on a checkout without a rearm-core sibling, these two
        // documents could have ended up with no real validity checking at all.
        expect(errs.length).toBeGreaterThan(0)
        expect(errs.filter(e => !e.includes('fdaAssessmentNarrative'))).toEqual([])
    })

    it.each(SINGLE_RELEASE_DOCUMENTS)('%s selects the device support window', (_name, doc) => {
        const printed = doc.loc?.source?.body ?? ''
        expect(printed).toMatch(/\beos\b/)
        expect(printed).toMatch(/\beol\b/)
    })

    /**
     * The narrative override rides the SAME pairing, and for the same reason: null means
     * INHERIT, so a query that omits it reads as "this release has no override" -- a
     * meaningful value rather than an obvious absence. Omitting it from one document would
     * make the editor blank itself after a save on exactly the tab sequence that broke
     * eos/eol.
     */
    it.each(SINGLE_RELEASE_DOCUMENTS)('%s selects the narrative override', (_name, doc) => {
        expect(doc.loc?.source?.body ?? '').toMatch(/\bfdaAssessmentNarrative\b/)
    })
})

describe('release selection fragments vs the CE schema', () => {
    it.each(RELEASE_FRAGMENTS)('%s is valid against the CE schema', (_name, fragment) => {
        expect(validate(ceSchema, asReleaseQuery(fragment)).map(e => e.message)).toEqual([])
    })
})

import { describe, it, expect } from 'vitest'
import { validate, parse } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

// These release selection sets are interpolated into `gql` templates at
// runtime (store.fetchReleasesByOrgUuids and friends). scripts/validate-graphql
// only parses statically-analysable documents, so it skips every template
// containing ${...} -- which is exactly where CHILD_RELEASE_GQL_DATA lived
// when it shipped asking for a DigestRecord.value field that has never
// existed. The whole query fails validation at runtime, so a single wrong
// field name blanks the view rather than degrading it.
//
// Checked against the CE schema, which ships in this repo, so the path always
// resolves and there is nothing to skip; and against Pro when a sibling
// rearm-core checkout is present. CE is expected to match Pro (it may lag on
// updates), so a field valid on CE is valid on Pro.

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
    it.each(SINGLE_RELEASE_DOCUMENTS)('%s is valid against the CE schema', (_name, doc) => {
        expect(validate(ceSchema, doc).map(e => e.message)).toEqual([])
    })

    /**
     * Pro too, when the sibling checkout is there: the documents run against both, and a
     * field Pro gains first shows up here before the mirror (the release header's
     * kevCount / riskSummary were such fields until the CE sync).
     */
    it.runIf(proSchema).each(SINGLE_RELEASE_DOCUMENTS)(
        '%s is valid against the Pro schema', (_name, doc) => {
            expect(validate(proSchema as any, doc).map(e => e.message)).toEqual([])
        })

    /**
     * The header's KEV circle and CVSS / EPSS pills read these, and fetchRelease switches
     * between the two documents by tab (see the pairing note above): selected by one only, the
     * pills would come and go as the operator clicks around.
     */
    it.each(SINGLE_RELEASE_DOCUMENTS)('%s selects the release risk summary', (_name, doc) => {
        const printed = doc.loc?.source?.body ?? ''
        expect(printed).toMatch(/\bkevCount\b/)
        expect(printed).toMatch(/\briskSummary\s*\{/)
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

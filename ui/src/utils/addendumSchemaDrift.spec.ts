import { describe, it, expect } from 'vitest'
import { validate, parse, type GraphQLSchema } from 'graphql'
import { ADDENDUM_PAGE_QUERY, ADDENDUM_RELEASE_QUERY, ADDENDUM_ORG_QUERY } from './addendumData'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * The only static validation ADDENDUM_PAGE_QUERY gets.
 *
 * Same hole, same feature, as sbomComponentsSchemaDrift.spec.ts one file over:
 * scripts/validate-graphql.mjs skips any body containing `${` -- and it skips it BEFORE
 * incrementing its counter, so the document does not even appear in the "N skipped" line.
 * ADDENDUM_PAGE_QUERY interpolates the component selection, so the script is blind to it.
 *
 * What that leaves uncovered: a typo or an unbalanced brace in ADDENDUM_COMPONENT_SELECTION.
 * The unit specs mock the client, `vite build` sees a template literal, and at runtime it
 * surfaces as a GraphQL validation error -- which isSchemaDriftError classifies as the
 * server being out of date. A typo of ours wearing somebody else's outdated backend as a
 * costume. That has now happened twice on this feature, which is why the sibling file exists
 * and why this one does.
 */

function errorsAgainst (schema: GraphQLSchema, doc: any): string[] {
    return validate(schema, parse(doc.loc.source.body)).map(e => e.message)
}

/**
 * Validated against BOTH schemas.
 *
 * NOTE: the release query used to be Pro-only here, with a canary asserting CE still lacked
 * fdaAssessmentNarrative. The 2026-09 CE sync (#368) brought the field over, so the canary
 * was retired and the release query joined the others.
 */
const DOCUMENTS: Array<[string, any]> = [
    ['page', ADDENDUM_PAGE_QUERY],
    ['org', ADDENDUM_ORG_QUERY],
    ['release', ADDENDUM_RELEASE_QUERY]
]

describe('the addendum documents', () => {
    it.each(DOCUMENTS)('%s: parses after interpolation', (_name, doc) => {
        expect(() => parse(doc.loc.source.body)).not.toThrow()
    })

    it.each(DOCUMENTS)('%s: validates against the CE schema', (_name, doc) => {
        expect(errorsAgainst(ceSchema, doc)).toEqual([])
    })

    /**
     * runIf, not an early return: an early return reports PASSED when the rearm-core
     * checkout is absent, which hides that Pro went unchecked.
     */
    it.runIf(proSchema).each(DOCUMENTS)('%s: validates against the Pro schema', (_name, doc) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
    })
})

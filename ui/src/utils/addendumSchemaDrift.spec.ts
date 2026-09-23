import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, type GraphQLSchema } from 'graphql'
import { ADDENDUM_PAGE_QUERY, ADDENDUM_RELEASE_QUERY, ADDENDUM_ORG_QUERY } from './addendumData'

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
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

function loadSchema (path: string): GraphQLSchema | null {
    return existsSync(path) ? buildSchema(readFileSync(path, 'utf8')) : null
}
const ceSchema = loadSchema(CE_SCHEMA_PATH)
const proSchema = loadSchema(PRO_SCHEMA_PATH)

function errorsAgainst (schema: GraphQLSchema, doc: any): string[] {
    return validate(schema, parse(doc.loc.source.body)).map(e => e.message)
}

/**
 * Validated against BOTH schemas: these select only fields CE already carries.
 *
 * ADDENDUM_RELEASE_QUERY is deliberately absent -- it selects fdaAssessmentNarrative, which
 * CE gains at the deferred sync. It is checked against Pro alone below, and the CE gap is
 * the one drift warning validate-graphql reports. Splitting the list this way keeps the CE
 * assertion meaningful for the documents that CAN hold it, instead of dropping the check for
 * all three because one of them is ahead.
 */
const CE_AND_PRO: Array<[string, any]> = [
    ['page', ADDENDUM_PAGE_QUERY],
    ['org', ADDENDUM_ORG_QUERY]
]
const PRO_ONLY: Array<[string, any]> = [
    ['release', ADDENDUM_RELEASE_QUERY]
]

describe('the addendum documents', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    it.each([...CE_AND_PRO, ...PRO_ONLY])('%s: parses after interpolation', (_name, doc) => {
        expect(() => parse(doc.loc.source.body)).not.toThrow()
    })

    it.each(CE_AND_PRO)('%s: validates against the CE schema', (_name, doc) => {
        expect(errorsAgainst(ceSchema as GraphQLSchema, doc)).toEqual([])
    })

    /**
     * runIf, not an early return: an early return reports PASSED when the rearm-core
     * checkout is absent, which hides that Pro went unchecked.
     */
    it.runIf(proSchema).each([...CE_AND_PRO, ...PRO_ONLY])(
        '%s: validates against the Pro schema', (_name, doc) => {
            expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
        })

    /**
     * The CE gap is an EXPECTED, TEMPORARY state, asserted so it cannot quietly become
     * permanent: when the deferred sync lands, this test fails and the release query moves
     * into CE_AND_PRO above.
     */
    it.runIf(ceSchema)('release: is still ahead of CE, pending the deferred sync', () => {
        const errs = errorsAgainst(ceSchema as GraphQLSchema, ADDENDUM_RELEASE_QUERY)
        expect(errs.join(' ')).toContain('fdaAssessmentNarrative')
    })
})

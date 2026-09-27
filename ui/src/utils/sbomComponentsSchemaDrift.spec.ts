import { describe, it, expect } from 'vitest'
import { validate, parse, type GraphQLSchema } from 'graphql'
import { SBOM_COMPONENTS_PAGE_QUERY, SBOM_COMPONENTS_QUERY } from './sbomComponentsQuery'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * The only static validation these two documents get.
 *
 * scripts/validate-graphql.mjs scans `gql` literals out of source and validates them, but it
 * skips any body containing `${` -- it cannot resolve an interpolation from source text. Both
 * documents here interpolate the shared field list, so the script reports them as "2
 * dynamic/non-operation skipped" and checks neither.
 *
 * An earlier version of this file also asserted the paged query was Pro-ONLY, and that
 * assertion correctly died with the CE schema sync. Deleting the whole file with it removed
 * the document validation too, which was never about the CE lag. This is that half, restored.
 *
 * What it catches: a typo or an unbalanced brace in releaseComponentFields() or
 * COMPONENT_FULL_SELECTION. Every other gate is blind to it -- the specs mock the client,
 * `vite build` sees a template literal, and validate-graphql skips it. At runtime it is a
 * GraphQL validation error, which isSchemaDriftError reports as the server being out of date:
 * a typo of ours wearing somebody else's outdated backend as a costume. That has happened
 * twice on this feature.
 */
const DOCUMENTS: Array<[string, any]> = [
    ['paged', SBOM_COMPONENTS_PAGE_QUERY],
    ['unpaged', SBOM_COMPONENTS_QUERY]
]

function errorsAgainst (schema: GraphQLSchema, doc: any): string[] {
    return validate(schema, parse(doc.loc.source.body)).map(e => e.message)
}

describe('the interpolated SBOM component documents', () => {
    it.each(DOCUMENTS)('%s: parses after interpolation', (_name, doc) => {
        expect(() => parse(doc.loc.source.body)).not.toThrow()
    })

    it.each(DOCUMENTS)('%s: validates against the CE schema', (_name, doc) => {
        expect(errorsAgainst(ceSchema, doc)).toEqual([])
    })

    /**
     * runIf, not an early return: an early return reports PASSED when the rearm-core checkout
     * is absent, which hides that Pro went unchecked.
     */
    it.runIf(proSchema).each(DOCUMENTS)('%s: validates against the Pro schema', (_name, doc) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
    })
})

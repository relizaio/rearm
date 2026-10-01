import { describe, it, expect } from 'vitest'
import { validate, print, type GraphQLSchema } from 'graphql'
import { ORGANIZATIONS_CORE, ORGANIZATIONS_FULL } from './organizationsQuery'
import { ceSchema, proSchema, enumValuesOf } from './schemaDriftSupport'

/**
 * The organizations query, validated as a DOCUMENT against both schemas.
 *
 * An earlier version of this file matched `supportInjection` with a regex over the schema
 * text and over store.ts. That proved nothing about whether the selection sits in a document
 * the server will accept -- and the failure it was supposed to guard is exactly what shipped:
 * adding the field to the shared organizations query made the whole document invalid on a
 * backend without it, so `myorg` stayed null and every page rendered blank. A text match
 * cannot see that. Validation can, and this is what the three sibling drift specs already do.
 */

const errorsAgainst = (schema: GraphQLSchema, doc: any) =>
    validate(schema, doc).map(e => e.message)

describe('the organizations query survives a backend without supportInjection', () => {
    /**
     * THE ONE THAT MATTERS. Every page derives myorg from this query, so a document CE cannot
     * answer takes the whole app down -- not just the setting. CORE is what the fallback
     * serves, and it must be answerable by the mirror as it stands today.
     */
    it('CORE is valid against the CE schema, so the app never blanks', () => {
        expect(errorsAgainst(ceSchema, ORGANIZATIONS_CORE)).toEqual([])
    })

    /**
     * NOTE: this used to assert FULL was still ahead of CE. The 2026-09 CE sync (#368) brought
     * supportInjection over, so that canary was retired. The fallback stays for a backend
     * without the field (in practice a Pro build older than it); with FULL valid on both
     * schemas, what keeps it honest is that CORE still leaves the field out.
     */
    it('FULL is valid against the CE schema too', () => {
        expect(errorsAgainst(ceSchema, ORGANIZATIONS_FULL)).toEqual([])
    })

    it('only FULL selects supportInjection', () => {
        expect(print(ORGANIZATIONS_CORE)).not.toMatch(/\bsupportInjection\b/)
        expect(print(ORGANIZATIONS_FULL)).toMatch(/\bsupportInjection\b/)
    })

    it.runIf(proSchema)('both documents are valid against the Pro schema', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, ORGANIZATIONS_CORE)).toEqual([])
        expect(errorsAgainst(proSchema as GraphQLSchema, ORGANIZATIONS_FULL)).toEqual([])
    })

    // The form maps a switch to exactly two members. A third means the switch is no longer
    // sufficient, and whoever adds it should find that out here.
    it('the setting enum has exactly the two members the switch maps to, on CE', () => {
        expect(enumValuesOf(ceSchema, 'SupportInjectionSetting')).toEqual(['ENABLED', 'DISABLED'])
    })

    it.runIf(proSchema)('and on Pro', () => {
        expect(enumValuesOf(proSchema as GraphQLSchema, 'SupportInjectionSetting'))
            .toEqual(['ENABLED', 'DISABLED'])
    })
})

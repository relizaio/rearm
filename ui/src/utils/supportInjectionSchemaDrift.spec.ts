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
     * Every page derives myorg from this query, so a document the backend cannot answer takes the
     * whole app down. Both documents select Settings.ssoUsersWithoutRole (TPF-168), which the CE
     * schema declares, so neither may carry any error against CE.
     */
    it('CORE is valid against the CE schema', () => {
        expect(errorsAgainst(ceSchema, ORGANIZATIONS_CORE)).toEqual([])
    })

    it('FULL is valid against the CE schema', () => {
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

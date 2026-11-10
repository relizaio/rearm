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
     * whole app down. Both documents select Settings.ssoUsersWithoutRole (TPF-168, license seats),
     * a Pro field the CE mirror does not declare yet: the operator rule of 2026-11-01 accepts that
     * drift until the CE sync (UI work targets the Pro schema, no fallback). What these two rows
     * still guard is that nothing ELSE in either document is unknown to CE, so the CE sync of that
     * one field is all the mirror needs.
     */
    const CE_ACCEPTED_DRIFT = ['Cannot query field "ssoUsersWithoutRole" on type "Settings".']

    it('CORE is valid against the CE schema except the accepted ssoUsersWithoutRole drift', () => {
        expect(errorsAgainst(ceSchema, ORGANIZATIONS_CORE)).toEqual(CE_ACCEPTED_DRIFT)
    })

    it('FULL is valid against the CE schema except the accepted ssoUsersWithoutRole drift', () => {
        expect(errorsAgainst(ceSchema, ORGANIZATIONS_FULL)).toEqual(CE_ACCEPTED_DRIFT)
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

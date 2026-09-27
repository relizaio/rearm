import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { validate, parse, print, type GraphQLSchema } from 'graphql'
import { ADDENDUM_RELEASE_QUERY_CORE, ADDENDUM_RELEASE_QUERY_FULL } from './addendumData'
import { COMPONENT_DEVICE_WINDOW_QUERY } from './componentDeviceWindow'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * Every document that gained a device-window selection, validated against BOTH schemas (D7).
 *
 * A backend whose `Component.medicalProfile` lacks `deviceSupportWindow` fails the subfield
 * as a WHOLE-DOCUMENT error rather than a null field: graphql rejects the query at validation
 * and the caller renders nothing. It is the #339 defect exactly -- a UI build meeting a
 * backend one field behind and blanking. Hence the CORE / FULL split, and the component
 * query's hide-on-drift branch.
 *
 * NOTE: this spec used to assert that CE was such a backend (FULL and the component query
 * did NOT validate on CE). The 2026-09 CE sync (#368) brought the field over, so those
 * canaries were retired: every document now validates on both schemas. The split and the
 * hide branch stay for a backend without the field (in practice a Pro build older than it),
 * and since validation can no longer tell CORE from FULL on either schema, the split's shape
 * is pinned structurally instead.
 */

const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)

describe('the addendum release query', () => {
    it('CORE and FULL validate against the CE mirror', () => {
        expect(errorsAgainst(ceSchema, ADDENDUM_RELEASE_QUERY_CORE)).toEqual([])
        expect(errorsAgainst(ceSchema, ADDENDUM_RELEASE_QUERY_FULL)).toEqual([])
    })

    /**
     * The split's reason to exist: CORE is what a backend without the device window can
     * answer, so the subfield must stay out of it. If someone moves it into CORE, the fallback
     * blanks on exactly the backend it exists for, and no schema here can show that.
     */
    it('only FULL selects the device window', () => {
        expect(print(ADDENDUM_RELEASE_QUERY_CORE)).not.toMatch(/\bdeviceSupportWindow\b/)
        expect(print(ADDENDUM_RELEASE_QUERY_FULL)).toMatch(/\bdeviceSupportWindow\b/)
    })

    // it.runIf, not an early return: a silent green when rearm-core is absent is how a drift
    // spec stops covering anything without anyone noticing. Skipped is reported; green is not.
    it.runIf(proSchema)('both validate against the Pro schema', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, ADDENDUM_RELEASE_QUERY_CORE)).toEqual([])
        expect(errorsAgainst(proSchema as GraphQLSchema, ADDENDUM_RELEASE_QUERY_FULL)).toEqual([])
    })
})

describe('the component device-window query (the panel and the release read-only view)', () => {
    /**
     * One document serves both surfaces: the editable panel on the component page and the
     * read-only inherited line on the release page. It is FULL-only by nature -- there is no
     * CORE shape of "read the window", because the whole document exists to read it. The panel
     * and the line handle a backend without the field by HIDING, via loadComponentDeviceWindow's
     * drift branch, not by falling back to a narrower query.
     */
    it('validates against the CE mirror', () => {
        expect(errorsAgainst(ceSchema, COMPONENT_DEVICE_WINDOW_QUERY)).toEqual([])
    })

    it.runIf(proSchema)('validates against the Pro schema', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, COMPONENT_DEVICE_WINDOW_QUERY)).toEqual([])
    })
})

/**
 * The shipments document is built by string interpolation, so `npm run validate:graphql` skips
 * it ("dynamic/non-operation skipped") and no other spec covers it. The repo's own convention
 * for that case -- validate-graphql.mjs says so in as many words -- is a vitest drift spec.
 *
 * What needs pinning is that the fields it asks for EXIST: nothing else would tell us if they
 * moved. Both schemas declare them, so CE is checked unconditionally and Pro when present.
 */
describe('the shipments query (interpolated, skipped by validate-graphql)', () => {
    function shipmentsDocument () {
        const source = readFileSync(
            fileURLToPath(new URL('../components/DistributionOfOrg.vue', import.meta.url)), 'utf8')
        const m = source.match(/const SHIP_FIELDS = '([^']+)'\s*\n\s*\+ '([^']+)'/)
        expect(m, 'SHIP_FIELDS changed shape -- update this spec rather than deleting it').toBeTruthy()
        return parse(`query shippedProductsOfSite($siteUuid: ID!) {
            shippedProductsOfSite(siteUuid: $siteUuid) { ${(m as RegExpMatchArray)[1]}${(m as RegExpMatchArray)[2]} } }`)
    }

    it('asks only for fields CE declares on ShippedProduct', () => {
        expect(errorsAgainst(ceSchema, shipmentsDocument())).toEqual([])
    })

    it.runIf(proSchema)('asks only for fields Pro declares on ShippedProduct', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, shipmentsDocument())).toEqual([])
    })
})

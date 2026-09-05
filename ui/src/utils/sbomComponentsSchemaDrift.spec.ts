import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, type GraphQLSchema } from 'graphql'
import {
    SBOM_COMPONENTS_PAGE_QUERY,
    SBOM_COMPONENTS_QUERY,
    SBOM_COMPONENTS_QUERY_CORE,
    SBOM_COMPONENT_CORE_FIELDS,
    SBOM_COMPONENT_FIELDS
} from './sbomComponentsQuery'

/**
 * These documents interpolate a shared selection set (`${SBOM_COMPONENT_FIELDS}`), and
 * scripts/validate-graphql.mjs deliberately SKIPS any gql body containing `${`. That is the
 * house split -- static documents are covered by the script, dynamic ones by a spec like
 * this -- but it means the script stopped covering BOTH SBOM documents the moment the
 * shared fields were extracted, including the unpaged one it had been checking. Nothing
 * announced that. This file is the replacement coverage.
 *
 * Same skip/fail convention as the sibling drift specs: the CE mirror ships in this repo so
 * its checks are unconditional; the Pro schema lives in a sibling checkout that CE-only CI
 * does not have, so those are skipped rather than failed when absent.
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

const errorsAgainst = (schema: GraphQLSchema, doc: any) =>
    validate(schema, parse(doc.loc.source.body)).map(e => e.message)

describe('the CORE/FULL split is a real split', () => {
    /**
     * If FULL ever collapses into CORE, deviceSupportRisk stops being requested by EVERY
     * query and the device-risk column goes quietly blank -- no error, no failing document,
     * just a disclosure that silently stops being made. FULL is built by appending so this
     * cannot happen by reformatting, and asserted here so it cannot happen by editing.
     */
    it('FULL is not CORE, and differs by exactly deviceSupportRisk', () => {
        expect(SBOM_COMPONENT_FIELDS).not.toBe(SBOM_COMPONENT_CORE_FIELDS)
        expect(SBOM_COMPONENT_FIELDS).toContain('deviceSupportRisk')
        expect(SBOM_COMPONENT_CORE_FIELDS).not.toContain('deviceSupportRisk')
        expect(SBOM_COMPONENT_FIELDS.replace(/\s*deviceSupportRisk\n/, '\n'))
            .toBe(SBOM_COMPONENT_CORE_FIELDS)
    })
})

describe('SBOM component documents parse at all', () => {
    // The interpolation is textual. A missing brace or a stray field would previously have
    // been caught by validate-graphql.mjs; now it is caught here.
    it.each([
        ['paged', SBOM_COMPONENTS_PAGE_QUERY],
        ['unpaged full', SBOM_COMPONENTS_QUERY],
        ['unpaged core', SBOM_COMPONENTS_QUERY_CORE]
    ])('%s document is syntactically valid after interpolation', (_name, doc) => {
        expect(() => parse((doc as any).loc.source.body)).not.toThrow()
    })
})

describe('against the Pro schema (skipped when the sibling checkout is absent)', () => {
    it.runIf(proSchema)('the paged query is valid on Pro', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, SBOM_COMPONENTS_PAGE_QUERY)).toEqual([])
    })

    it.runIf(proSchema)('the unpaged full query is valid on Pro', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, SBOM_COMPONENTS_QUERY)).toEqual([])
    })

    it.runIf(proSchema)('the core query is valid on Pro too, so the last fallback always lands', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, SBOM_COMPONENTS_QUERY_CORE)).toEqual([])
    })
})

describe('against the CE mirror schema (always runs)', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    /**
     * The unpaged query is the CE FALLBACK TARGET. If it ever stops validating on CE, the
     * fallback in loadSbomComponentsPage has nowhere to land and a CE install renders the
     * SBOM Components tab as a toolbar over nothing. This is the load-bearing assertion in
     * the file.
     */
    it('the CORE fallback query is valid on CE', () => {
        expect(errorsAgainst(ceSchema as GraphQLSchema, SBOM_COMPONENTS_QUERY_CORE)).toEqual([])
    })

    /**
     * The FULL unpaged query is Pro-only, and only by deviceSupportRisk. This is the
     * assertion that would have caught the real bug in the first cut of this change: the
     * drift fallback pointed at the full query, which a CE backend cannot serve either, so
     * the "fallback" would have failed exactly where it was needed. Naming the single
     * offending field keeps the CORE/FULL split honest -- if the list grows, the split has
     * to be re-examined rather than silently widened.
     */
    it('the full query is CE-invalid on deviceSupportRisk alone', () => {
        const errs = errorsAgainst(ceSchema as GraphQLSchema, SBOM_COMPONENTS_QUERY)
        expect(errs).toEqual(['Cannot query field "deviceSupportRisk" on type "SbomComponent".'])
    })

    /**
     * The paged query is Pro-only until the CE schema sync lands, and that is EXPECTED, not
     * a failure -- syncing early is the one-way door on this track. Asserted rather than
     * ignored so the day it becomes valid is visible: when this flips, the runtime fallback
     * is dead code and should go with it.
     */
    it('the paged query is Pro-only for now, which is why the fallback exists', () => {
        const errs = errorsAgainst(ceSchema as GraphQLSchema, SBOM_COMPONENTS_PAGE_QUERY)
        expect(errs.length,
            'the paged query now validates on CE -- the schema sync has landed, so remove the'
            + ' drift fallback in loadSbomComponentsPage and delete this test').toBeGreaterThan(0)
        expect(errs.join(' ')).toContain('getReleaseSbomComponentsPage')
    })
})

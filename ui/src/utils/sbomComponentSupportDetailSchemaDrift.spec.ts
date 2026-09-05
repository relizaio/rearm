import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, type GraphQLSchema } from 'graphql'
import { SBOM_COMPONENT_SUPPORT_DETAIL } from './sbomComponentSupportDetail'

/**
 * Validates the attestation-detail query against Pro, and this spec exists because its
 * absence cost a browser round trip.
 *
 * The query originally selected a root `sbomComponent(uuid:)` field that does not exist in
 * this schema at all -- I invented it. An unknown field is a GraphQL VALIDATION error, and
 * isSchemaDriftError treats validation errors as drift, so the form refused with "this
 * server cannot store a full support attestation" against a Pro backend that could store one
 * perfectly well. A bug in my own query, wearing the costume of somebody else's outdated
 * server, and no unit test could see it because they all mock the client.
 *
 * Any query written against a schema needs one of these. Validation is the only thing that
 * distinguishes "the server is old" from "I typed it wrong".
 */
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const loadSchema = (p: string): GraphQLSchema | null =>
    existsSync(p) ? buildSchema(readFileSync(p, 'utf8')) : null
const ceSchema = loadSchema(CE_SCHEMA_PATH)
const proSchema = loadSchema(PRO_SCHEMA_PATH)
const errors = (s: GraphQLSchema) =>
    validate(s, parse(SBOM_COMPONENT_SUPPORT_DETAIL.loc!.source.body)).map(e => e.message)

describe('attestation detail query vs the schemas', () => {
    // The assertion that would have caught the invented query name before the browser did.
    it.runIf(proSchema)('is valid on Pro -- every field and argument exists', () => {
        expect(errors(proSchema as GraphQLSchema)).toEqual([])
    })

    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    /**
     * Pro-only, which is correct and is why the form refuses on CE rather than opening a
     * narrower one: a server that cannot report the stored level or party also cannot store
     * them. Fails by design when the CE sync lands.
     */
    it.runIf(ceSchema)('is Pro-only, matching the write it precedes', () => {
        const errs = errors(ceSchema as GraphQLSchema)
        expect(errs.length,
            'the detail query now validates on CE -- re-check whether the form should still'
            + ' refuse there, since the write may also have become possible')
            .toBeGreaterThan(0)
        expect(errs.join(' ')).toContain('attestationState')
    })
})

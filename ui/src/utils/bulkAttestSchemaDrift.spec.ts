import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, type GraphQLSchema } from 'graphql'
import { BULK_SET_SUPPORT } from './useBulkAttest'

/**
 * Validates the bulk mutation against both schemas.
 *
 * Every query on this track gets one of these, and this is the reason: an invented field or
 * an enum value the schema does not declare is a GraphQL VALIDATION error, which
 * isSchemaDriftError reports as drift -- so a typo of mine surfaces to the operator as
 * "your server is out of date". This feature has produced that exact disguise twice.
 */
const CE = fileURLToPath(new URL('../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO = fileURLToPath(new URL('../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const load = (p: string): GraphQLSchema | null => existsSync(p) ? buildSchema(readFileSync(p, 'utf8')) : null
const ce = load(CE)
const pro = load(PRO)
const errs = (s: GraphQLSchema) => validate(s, parse(BULK_SET_SUPPORT.loc!.source.body)).map(e => e.message)

describe('bulk attestation mutation vs the schemas', () => {
    it.runIf(pro)('is valid on Pro', () => expect(errs(pro as GraphQLSchema)).toEqual([]))

    it('has the CE mirror schema available', () => {
        expect(ce, `CE mirror schema not found at ${CE}`).not.toBeNull()
    })

    /**
     * Pro-only, so bulk refuses on CE for the same reason the single write does: a narrower
     * server cannot store a level or a justification, and a write must never degrade. Fails
     * by design when the CE sync lands.
     */
    it.runIf(ce)('is Pro-only', () => {
        expect(errs(ce as GraphQLSchema).length,
            'bulk now validates on CE -- re-check the refusal path').toBeGreaterThan(0)
    })

    // No supportNotes argument: per-component evidence cannot be fanned out.
    it('does not send supportNotes', () => {
        expect(BULK_SET_SUPPORT.loc!.source.body).not.toContain('supportNotes')
    })
})

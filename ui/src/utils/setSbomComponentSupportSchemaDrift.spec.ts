import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, type GraphQLSchema } from 'graphql'
import { SET_SBOM_COMPONENT_SUPPORT, DRIFT_REFUSAL } from './setSbomComponentSupport'

/**
 * The attestation WRITE against both schemas.
 *
 * This matters more than the read drift specs. A read that a server cannot serve shows less
 * information; a write it cannot serve, if silently narrowed, stores less while reporting
 * success. The CE mirror's setSbomComponentSupport takes four arguments to Pro's twelve --
 * a copy-src.sh snapshot that the CE schema sync resolves -- so until then a CE backend
 * would accept a write with the level of support and the justification simply absent.
 *
 * Hence no fallback and an explicit refusal, and hence this spec asserting the CE gap by
 * name. When the sync lands this test FAILS BY DESIGN, and whoever lands it should delete
 * the refusal path along with it.
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
    validate(s, parse(SET_SBOM_COMPONENT_SUPPORT.loc!.source.body)).map(e => e.message)

describe('the attestation write vs the schemas', () => {
    it.runIf(proSchema)('is valid on Pro', () => {
        expect(errors(proSchema as GraphQLSchema)).toEqual([])
    })

    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    it.runIf(ceSchema)('is Pro-only, which is why the write refuses instead of degrading', () => {
        const errs = errors(ceSchema as GraphQLSchema)
        expect(errs.length,
            'the attestation mutation now validates on CE -- the schema sync has landed, so'
            + ' remove the refuse-on-drift path in setSbomComponentSupport.ts and delete'
            + ' this test').toBeGreaterThan(0)
    })

    /**
     * Names the specific arguments, not just "it fails". If CE gained levelOfSupport but not
     * justification, an all-or-nothing refusal would still be right but the REASON would
     * have changed, and a bare count would not notice.
     */
    it.runIf(ceSchema)('is missing exactly the fields the refusal names', () => {
        const joined = errors(ceSchema as GraphQLSchema).join(' ')
        expect(joined).toContain('levelOfSupport')
        expect(joined).toContain('justification')
    })

    // The message has to name what would be lost. "Unsupported server" tells an operator
    // nothing about whether to retry, work around it, or escalate.
    it('the refusal names the fields at stake and says nothing was written', () => {
        expect(DRIFT_REFUSAL).toContain('level of support')
        expect(DRIFT_REFUSAL).toContain('justification')
        expect(DRIFT_REFUSAL).toContain('Nothing was written')
    })
})

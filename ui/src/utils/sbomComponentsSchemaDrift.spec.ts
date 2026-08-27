import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, type GraphQLSchema } from 'graphql'
import { SBOM_COMPONENTS_CORE_QUERY, SBOM_COMPONENTS_FULL_QUERY } from './sbomComponentsQuery'

// Sibling of notificationInboxSchemaDrift.spec.ts, and load-bearing for the same
// reason: validate-graphql.mjs only WARNS when a selection is Pro-valid but CE-invalid,
// so nothing else in the build stops the CORE selection from quietly drifting off the
// CE mirror -- which is the one thing the whole FULL/CORE fallback depends on.
//
// The CE mirror schema ships IN this repo, so its checks are unconditional. The Pro
// schema lives in the sibling rearm-core checkout, absent from this repo's own CI, so
// those checks skip rather than fail there.
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

function loadSchema (path: string): GraphQLSchema | null {
    return existsSync(path) ? buildSchema(readFileSync(path, 'utf8')) : null
}

const ceSchema = loadSchema(CE_SCHEMA_PATH)
const proSchema = loadSchema(PRO_SCHEMA_PATH)

describe('SBOM components core selection vs the CE mirror schema (in-repo, always runs)', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    // The invariant the fallback rests on: everything the SBOM components table
    // HARD-REQUIRES exists on the CE mirror, so a CE install renders rows rather than
    // blanking the tab. If this fails, move the offending field into the FULL-only
    // selection -- do not "fix" it by deleting the assertion.
    it('the CORE selection is valid against the CE mirror', () => {
        if (!ceSchema) return
        expect(validate(ceSchema, SBOM_COMPONENTS_CORE_QUERY).map(e => e.message)).toEqual([])
    })

    // Canary: while the mirror lags, FULL must be the only thing that fails there. When
    // the CE mirror catches up this assertion fires, and retiring it is the correct
    // response -- the split then goes dormant (FULL succeeds everywhere) but stays in
    // place for the next Pro-first field. Do NOT retire it by widening CORE.
    it('the FULL selection is NOT yet valid against the CE mirror (split is load-bearing)', () => {
        if (!ceSchema) return
        const errors = validate(ceSchema, SBOM_COMPONENTS_FULL_QUERY).map(e => e.message)
        expect(errors.join(' ')).toMatch(/supportSuggestion/)
    })
})

describe('both selections vs the Pro schema (skipped when rearm-core is absent)', () => {
    it.runIf(proSchema)('the CORE selection is valid against Pro', () => {
        expect(validate(proSchema as GraphQLSchema, SBOM_COMPONENTS_CORE_QUERY).map(e => e.message)).toEqual([])
    })

    it.runIf(proSchema)('the FULL selection is valid against Pro', () => {
        expect(validate(proSchema as GraphQLSchema, SBOM_COMPONENTS_FULL_QUERY).map(e => e.message)).toEqual([])
    })
})

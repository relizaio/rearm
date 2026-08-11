import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, type GraphQLSchema } from 'graphql'
import { INBOX_QUERY_FULL, INBOX_QUERY_CORE, INBOX_ENRICHMENT_ITEM_FIELDS } from './notificationInboxQuery'

// The CE mirror schema ships IN this repo -- always present, so its checks are
// unconditional. The Pro schema lives in the sibling rearm-core checkout,
// which is NOT present in this repo's own CI; those checks are skipped (not
// failed) when it's absent, so the suite is green in a CE-only checkout and
// still meaningful on a dev box that has both.
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

function loadSchema (path: string): GraphQLSchema | null {
    return existsSync(path) ? buildSchema(readFileSync(path, 'utf8')) : null
}

const ceSchema = loadSchema(CE_SCHEMA_PATH)
const proSchema = loadSchema(PRO_SCHEMA_PATH)

describe('inbox core selection vs the CE mirror schema (in-repo, always runs)', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    // The load-bearing invariant: every field the inbox HARD-REQUIRES exists on
    // the CE mirror, so a CE install never blanks.
    it('the CORE inbox selection is valid against the CE mirror', () => {
        if (!ceSchema) return
        expect(validate(ceSchema, INBOX_QUERY_CORE).map(e => e.message)).toEqual([])
    })

    // NOTE: this suite used to also assert that the FULL selection was INVALID
    // against the CE mirror ("split is load-bearing"). The mirror caught up with
    // the enrichment fields (Pro sync), so that canary fired and was retired --
    // the FULL/CORE split is now dormant at runtime (FULL succeeds everywhere).
    // The split machinery stays: move future Pro-first fields into
    // INBOX_ENRICHMENT_ITEM_FIELDS to re-arm it; the CORE-vs-CE invariant above
    // keeps the fallback document valid either way.
})

describe('inbox full selection vs the Pro schema (skipped if rearm-core absent)', () => {
    it.runIf(proSchema)('the full inbox selection is valid against Pro (source of truth)', () => {
        expect(validate(proSchema as GraphQLSchema, INBOX_QUERY_FULL).map(e => e.message)).toEqual([])
    })
})

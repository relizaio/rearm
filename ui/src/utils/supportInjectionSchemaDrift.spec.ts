import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, type GraphQLSchema } from 'graphql'
import { ORGANIZATIONS_CORE, ORGANIZATIONS_FULL } from './organizationsQuery'

/**
 * The organizations query, validated as a DOCUMENT against both schemas.
 *
 * An earlier version of this file matched `supportInjection` with a regex over the schema
 * text and over store.ts. That proved nothing about whether the selection sits in a document
 * the server will accept -- and the failure it was supposed to guard is exactly what shipped:
 * adding the field to the shared organizations query made the whole document invalid on a
 * backend without it, so `myorg` stayed null and every page rendered blank. A text match
 * cannot see that. Validation can, and this is what the three sibling drift specs already do.
 *
 * CE is read EAGERLY, not under runIf: the CE schema ships in this repo, so its absence is a
 * broken checkout and should fail the suite rather than silently drop the assertion that
 * matters most here.
 */
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

const ceSchema = buildSchema(readFileSync(CE_SCHEMA_PATH, 'utf8'))
const proSchema: GraphQLSchema | null = existsSync(PRO_SCHEMA_PATH)
    ? buildSchema(readFileSync(PRO_SCHEMA_PATH, 'utf8'))
    : null

const errorsAgainst = (schema: GraphQLSchema, doc: any) =>
    validate(schema, doc).map(e => e.message)

describe('the organizations query survives a backend without supportInjection', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    /**
     * THE ONE THAT MATTERS. Every page derives myorg from this query, so a document CE cannot
     * answer takes the whole app down -- not just the setting. CORE is what the fallback
     * serves, and it must be answerable by the mirror as it stands today.
     */
    it('CORE is valid against the CE schema, so the app never blanks', () => {
        expect(errorsAgainst(ceSchema, ORGANIZATIONS_CORE)).toEqual([])
    })

    /**
     * And FULL is NOT, today -- which is why the fallback exists rather than being defensive
     * decoration. When the deferred sync lands this fails, and the fallback can be retired
     * along with it.
     */
    it('FULL is still ahead of CE, pending the sync', () => {
        const errs = errorsAgainst(ceSchema, ORGANIZATIONS_FULL)
        expect(errs.length).toBeGreaterThan(0)
        expect(errs.filter(e => !e.includes('supportInjection'))).toEqual([])
    })

    it.runIf(proSchema)('both documents are valid against the Pro schema', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, ORGANIZATIONS_CORE)).toEqual([])
        expect(errorsAgainst(proSchema as GraphQLSchema, ORGANIZATIONS_FULL)).toEqual([])
    })

    // The form maps a switch to exactly two members. A third means the switch is no longer
    // sufficient, and whoever adds it should find that out here.
    it.runIf(proSchema)('the setting enum has exactly the two members the switch maps to', () => {
        const schema = readFileSync(PRO_SCHEMA_PATH, 'utf8')
        const body = schema.slice(schema.indexOf('enum SupportInjectionSetting'))
        const members = body.slice(0, body.indexOf('}'))
            .split('\n').map(l => l.trim()).filter(l => /^[A-Z_]+$/.test(l))
        expect(members).toEqual(['ENABLED', 'DISABLED'])
    })
})

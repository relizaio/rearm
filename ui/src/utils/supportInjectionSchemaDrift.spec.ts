import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * The export-injection setting, checked against the schemas that declare it.
 *
 * The store's organizations query and the OrgSettings mutation both select
 * `settings { supportInjection }`. Neither is validated by scripts/validate-graphql -- the
 * store document interpolates nothing but lives outside the scanned utils, and the mutation
 * is inline in a .vue file -- so a typo here would surface at runtime as a GraphQL validation
 * error, which isSchemaDriftError reports as the server being out of date. A typo of ours
 * wearing somebody else's outdated backend as a costume; that has happened twice on this
 * feature already.
 */
const CE_SCHEMA = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

const ORG_SETTINGS = fileURLToPath(new URL('../components/OrgSettings.vue', import.meta.url))
const STORE = fileURLToPath(new URL('../store.ts', import.meta.url))

describe('the supportInjection setting matches the Pro schema', () => {
    it.runIf(existsSync(PRO_SCHEMA))('is declared on Settings and SettingsInput', () => {
        const schema = readFileSync(PRO_SCHEMA, 'utf8')
        expect(schema).toMatch(/^\s+supportInjection: SupportInjectionSetting$/m)
        // Both the read type and the input type: the UI reads it and writes it.
        expect((schema.match(/supportInjection: SupportInjectionSetting/g) || []).length)
            .toBeGreaterThanOrEqual(2)
    })

    // The form maps a switch to these two members. A third member appearing means the switch
    // is no longer sufficient, and whoever adds it should find that out here.
    it.runIf(existsSync(PRO_SCHEMA))('has exactly the two members the switch maps to', () => {
        const schema = readFileSync(PRO_SCHEMA, 'utf8')
        const body = schema.slice(schema.indexOf('enum SupportInjectionSetting'))
        const members = body.slice(0, body.indexOf('}'))
            .split('\n').map(l => l.trim())
            .filter(l => /^[A-Z_]+$/.test(l))
        expect(members).toEqual(['ENABLED', 'DISABLED'])
    })

    it('is selected wherever the UI reads it', () => {
        expect(readFileSync(STORE, 'utf8')).toMatch(/^\s+supportInjection$/m)
        expect(readFileSync(ORG_SETTINGS, 'utf8')).toMatch(/^\s+supportInjection$/m)
    })

    /**
     * The CE gap is EXPECTED and TEMPORARY, asserted so it cannot quietly become permanent:
     * CE gains the field at the deferred sync, and when it does this fails and the assertion
     * moves to the positive form above.
     */
    it.runIf(existsSync(CE_SCHEMA))('is still ahead of CE, pending the sync', () => {
        expect(readFileSync(CE_SCHEMA, 'utf8')).not.toContain('SupportInjectionSetting')
    })
})

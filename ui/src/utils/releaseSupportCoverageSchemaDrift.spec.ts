import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, type GraphQLSchema } from 'graphql'
import { RELEASE_SUPPORT_COVERAGE_QUERY } from './releaseSupportCoverage'

/**
 * The coverage query is Pro-only on TWO counts, and both matter to the runtime behaviour:
 * the releaseUuid argument and the supportExportState field. Pinned here so the day the CE
 * sync lands is visible, and because the query has no static coverage of its own.
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
const errors = (schema: GraphQLSchema) =>
    validate(schema, parse(RELEASE_SUPPORT_COVERAGE_QUERY.loc.source.body)).map(e => e.message)

describe('release support coverage query vs the schemas', () => {
    it.runIf(proSchema)('is valid on Pro', () => {
        expect(errors(proSchema as GraphQLSchema)).toEqual([])
    })

    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_PATH}`).not.toBeNull()
    })

    /**
     * Invalid on CE, and this is WHY there is no fallback query. CE's
     * sbomComponentSupportCoverage takes only orgUuid, so the only thing it could answer is
     * the org-wide number -- a different question, not a degraded answer to this one. The
     * loader returns null and the gauge says "not available on this server".
     *
     * When the sync lands this fails, and whoever lands it should check whether the
     * no-fallback reasoning still holds before deleting the null path.
     */
    it('is Pro-only, which is why the loader has no fallback', () => {
        const errs = errors(ceSchema as GraphQLSchema)
        expect(errs.length,
            'the coverage query now validates on CE -- re-examine the deliberate absence of a'
            + ' fallback in loadReleaseSupportCoverage before removing the null path')
            .toBeGreaterThan(0)
    })

    /**
     * Names both reasons rather than just counting them. If CE gained releaseUuid but not
     * supportExportState -- or the reverse -- the loader's all-or-nothing null becomes the
     * wrong shape, and a bare "it fails somehow" assertion would not notice.
     */
    it.runIf(ceSchema)('fails on the release argument AND the export field, not one of them', () => {
        const joined = errors(ceSchema as GraphQLSchema).join(' ')
        expect(joined).toContain('releaseUuid')
        expect(joined).toContain('supportExportState')
    })
})

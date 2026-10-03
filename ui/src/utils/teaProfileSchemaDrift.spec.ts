import { describe, it, expect } from 'vitest'
import { validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { teaProfilesAvailable } from './teaProfile'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * The TEA profile documents (task TEA-2) against both schemas, and the edition gate that keeps a
 * CE UI from sending them.
 *
 * The operator decided on 2026-10-03 that the 2026-10 TEA work ships no CE backend sync, so the
 * CE schema has none of the TEA profile fields and every one of these documents fails there as a
 * whole. teaProfilesAvailable hides the TEA surfaces on CE for that reason. The canary below fails
 * as soon as the CE schema gains the fields: that is the moment to lift the gate, or the CE UI
 * keeps hiding a feature its backend serves.
 */

const DOCS = {
    TeaProfileEditorViewGql: graphqlQueries.TeaProfileEditorViewGql,
    TeaProfilesOfOrgGql: graphqlQueries.TeaProfilesOfOrgGql,
    TeaOrgDiscoveryGql: graphqlQueries.TeaOrgDiscoveryGql,
    SaveTeaProfileGql: graphqlQueries.SaveTeaProfileGql,
    DeleteTeaProfileGql: graphqlQueries.DeleteTeaProfileGql,
    TeaProfileExposureChangeGql: graphqlQueries.TeaProfileExposureChangeGql,
    // The membership dry-run (TEA-9 round 2): what setPerspectivesOnComponent would do to the visibility.
    TeaMembershipExposureChangeGql: graphqlQueries.TeaMembershipExposureChangeGql,
}

// The org TEA table's name read of an archived component (TEA-9) selects only long-standing
// Component fields, so it validates on CE too; it is sent only from the TEA tab, which the gate hides.
const COMPONENT_NAME_READ = graphqlQueries.TeaComponentNameGql

const ceServesTea = () => Boolean(ceSchema.getQueryType()?.getFields().teaProfileEditorView)

describe('TEA profile documents and the edition gate', () => {
    it('shows the TEA surfaces on CE exactly when the CE schema serves TEA profiles', () => {
        expect(teaProfilesAvailable('OSS')).toBe(ceServesTea())
    })

    it('shows them on every licensed edition, and on an unloaded user as the rest of the UI does', () => {
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE', undefined, null]) {
            expect(teaProfilesAvailable(t)).toBe(true)
        }
    })

    it('the CE schema answers either every TEA document or none, so the one gate covers them all', () => {
        const valid = Object.values(DOCS).map(d => validate(ceSchema, d).length === 0)
        expect(valid.every(v => v === ceServesTea())).toBe(true)
    })

    it('lists the seven TEA-profile documents beside the component name read: eight TEA documents', () => {
        expect(Object.keys(DOCS)).toHaveLength(7)
        expect(DOCS.TeaMembershipExposureChangeGql).toBeDefined()
    })

    it('the membership dry-run is one of the documents the CE schema does not serve', () => {
        expect(validate(ceSchema, DOCS.TeaMembershipExposureChangeGql).length > 0).toBe(!ceServesTea())
    })

    it.runIf(proSchema)('every TEA document validates against the Pro schema', () => {
        for (const [name, doc] of Object.entries({ ...DOCS, TeaComponentNameGql: COMPONENT_NAME_READ })) {
            expect(validate(proSchema!, doc).map(e => e.message), name).toEqual([])
        }
    })

    it('the component name read of the org TEA table validates on CE as well', () => {
        expect(validate(ceSchema, COMPONENT_NAME_READ).map(e => e.message)).toEqual([])
    })
})

import { describe, it, expect } from 'vitest'
import { validate, visit, print, type DocumentNode, type GraphQLSchema } from 'graphql'
import { SBOM_COMPONENT_FINDINGS_QUERY, SBOM_COMPONENT_FINDINGS_QUERY_CORE, SBOM_COMPONENT_FINDINGS_QUERY_LATEST } from './sbomComponentFindingsQuery'
import { ceSchema, proSchema } from './schemaDriftSupport'

// The dependency-graph page's findings badge (ReleaseSbomComponent.findings,
// rearm-saas#705) against the backend schemas. CORE is what the page falls
// back to on a backend without the field, so it must validate on CE.

const errorsAgainst = (schema: GraphQLSchema, doc: DocumentNode) => validate(schema, doc).map(e => e.message)

// FULL with the findings (and the score fragment they use) taken out.
const withoutFindings = (doc: DocumentNode) => print(visit(doc, {
    Field: node => node.name.value === 'findings' ? null : undefined,
    FragmentDefinition: () => null
}))

// LATEST with the latest-version fields (rearm-saas#708) taken out.
const withoutLatest = (doc: DocumentNode) => print(visit(doc, {
    Field: node => ['component', 'latestFix'].includes(node.name.value) ? null : undefined
}))

describe('SBOM component findings documents', () => {
    it.runIf(proSchema)('LATEST is valid against Pro (skipped if rearm-core absent)', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, SBOM_COMPONENT_FINDINGS_QUERY_LATEST)).toEqual([])
    })

    it('LATEST is FULL plus the latest version and what it fixes', () => {
        expect(withoutLatest(SBOM_COMPONENT_FINDINGS_QUERY_LATEST)).toBe(print(SBOM_COMPONENT_FINDINGS_QUERY))
    })

    it.runIf(proSchema)('FULL is valid against Pro (skipped if rearm-core absent)', () => {
        expect(errorsAgainst(proSchema as GraphQLSchema, SBOM_COMPONENT_FINDINGS_QUERY)).toEqual([])
    })

    it('FULL is CORE plus the findings', () => {
        expect(withoutFindings(SBOM_COMPONENT_FINDINGS_QUERY)).toBe(print(SBOM_COMPONENT_FINDINGS_QUERY_CORE))
    })

    it('CORE is valid against CE', () => {
        expect(errorsAgainst(ceSchema, SBOM_COMPONENT_FINDINGS_QUERY_CORE)).toEqual([])
    })

    /**
     * TEMPORARY: CE has not mirrored the latest version (rearm-saas#708) yet, so
     * the page shows no latest line there. Flip to "LATEST is valid against CE"
     * once the CE sync brings it over.
     */
    it('LATEST is ahead of CE by the latest version', () => {
        expect(errorsAgainst(ceSchema, SBOM_COMPONENT_FINDINGS_QUERY_LATEST).some(e => /latestVersion/.test(e))).toBe(true)
    })

    /**
     * TEMPORARY: CE has not mirrored ReleaseSbomComponent.findings yet, so the
     * page shows no badge there. Flip to "FULL is valid against CE" once the CE
     * sync brings the field over.
     */
    it('FULL is ahead of CE by findings', () => {
        const errors = errorsAgainst(ceSchema, SBOM_COMPONENT_FINDINGS_QUERY)
        expect(errors.length).toBeGreaterThan(0)
        expect(errors.every(e => /findings/.test(e))).toBe(true)
    })
})

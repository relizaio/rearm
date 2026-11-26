import { describe, it, expect } from 'vitest'
import { parse, validate, type GraphQLSchema } from 'graphql'
import { vulnAnalysisFieldsFragment } from './vulnAnalysisQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * FDAW-17c (FDAW-73 row 16): the finding analysis selection the Finding Analysis page and the
 * Finding Analysis Records dialog interpolate into their queries. Interpolated, so
 * scripts/validate-graphql.mjs skips those documents; they are validated here, for every query
 * root the two views use. The CE fragment validates against the CE schema (always present),
 * the Pro fragment against Pro (SKIPPED when the rearm-core checkout is absent).
 */
const documents = (fields: string): string[] => [
    `query getVulnAnalysisByRelease($releaseUuid: ID!) { getVulnAnalysisByRelease(releaseUuid: $releaseUuid) { ${fields} } }`,
    `query getVulnAnalysisByBranch($branchUuid: ID!) { getVulnAnalysisByBranch(branchUuid: $branchUuid) { ${fields} } }`,
    `query getVulnAnalysisByComponent($componentUuid: ID!) { getVulnAnalysisByComponent(componentUuid: $componentUuid) { ${fields} } }`,
    `query getVulnAnalysisByPerspective($perspectiveUuid: ID!) { getVulnAnalysisByPerspective(perspectiveUuid: $perspectiveUuid) { ${fields} } }`,
    `query getVulnAnalysis($org: ID!) { getVulnAnalysis(org: $org) { ${fields} } }`,
    `query getVulnAnalysisByLocationAndFinding($org: ID!, $location: String!, $findingId: String!, $findingType: FindingType!) {
        getVulnAnalysisByLocationAndFinding(org: $org, location: $location, findingId: $findingId, findingType: $findingType) { ${fields} }
    }`
]

const count = (text: string, word: string) => (text.match(new RegExp(`\\b${word}\\b`, 'g')) || []).length
const historyOf = (fields: string) => fields.slice(fields.indexOf('analysisHistory'), fields.indexOf('releaseDetails'))
const recordOf = (fields: string) => fields.slice(0, fields.indexOf('analysisHistory'))

describe('CE fragment', () => {
    const ce = vulnAnalysisFieldsFragment(false)

    it.each(documents(ce).map((d, i) => [i, d]))('document %i validates against the CE schema', (_i, doc) => {
        expect(validate(ceSchema, parse(doc as string))).toEqual([])
    })

    it('selects responses, recommendation and workaround on the record and inside analysisHistory', () => {
        for (const part of [recordOf(ce), historyOf(ce)]) {
            for (const field of ['responses', 'recommendation', 'workaround']) {
                expect(count(part, field), field).toBe(1)
            }
        }
    })

    it('selects none of the typed assessment fields', () => {
        for (const field of ['discoveryMethod', 'riskLevel', 'controls']) {
            expect(ce).not.toContain(field)
        }
    })
})

describe.runIf(proSchema)('Pro fragment', () => {
    const pro = vulnAnalysisFieldsFragment(true)

    it.each(documents(pro).map((d, i) => [i, d]))('document %i validates against the Pro schema', (_i, doc) => {
        expect(validate(proSchema as GraphQLSchema, parse(doc as string))).toEqual([])
    })

    it('selects the three fields on the record and inside analysisHistory, controls { type description } twice', () => {
        for (const part of [recordOf(pro), historyOf(pro)]) {
            for (const field of ['discoveryMethod', 'riskLevel', 'controls', 'responses', 'recommendation', 'workaround']) {
                expect(count(part, field), field).toBe(1)
            }
        }
        expect(pro.replace(/\s+/g, ' ').match(/controls \{ type description \}/g)).toHaveLength(2)
    })
})

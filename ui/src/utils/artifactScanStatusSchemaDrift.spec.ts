import { describe, it, expect } from 'vitest'
import { print, validate, type GraphQLSchema } from 'graphql'
import { ARTIFACT_SCAN_STATUS_QUERY } from './artifactScanStatus'
import { ceSchema, proSchema } from './schemaDriftSupport'

// TPF-122 (architecture-3 section 5, row 23): the scan-status document against both schemas.

describe('artifactScanStatus document vs the CE mirror schema (in-repo, always runs)', () => {
    // Canary: the CE mirror lacks the query, which is what makes the loader's drift probe
    // meaningful on CE. When a backend sync carries the query to CE this fails; retire it by
    // deleting this one test, nothing else.
    it('(a) is rejected by the CE mirror on artifactScanStatus', () => {
        const messages = validate(ceSchema, ARTIFACT_SCAN_STATUS_QUERY).map(e => e.message)
        expect(messages.some(m => m.includes('artifactScanStatus'))).toBe(true)
    })
})

describe('artifactScanStatus document vs the Pro schema (skipped if rearm-core absent)', () => {
    it.runIf(proSchema)('(b) validates against Pro', () => {
        expect(validate(proSchema as GraphQLSchema, ARTIFACT_SCAN_STATUS_QUERY).map(e => e.message)).toEqual([])
    })
})

describe('artifactScanStatus document shape', () => {
    it('(c) is the query artifactScanStatus', () => {
        const def: any = ARTIFACT_SCAN_STATUS_QUERY.definitions[0]
        expect(def.operation).toBe('query')
        expect(def.name.value).toBe('artifactScanStatus')
    })

    it('(d) selects every merged field and nothing withdrawn', () => {
        const printed = print(ARTIFACT_SCAN_STATUS_QUERY)
        const fields = ['artifactUuid', 'type', 'state', 'mapped', 'canonicalArtifactUuid', 'mappedAt', 'firstScanned',
            'lastScanned', 'fanOutAt', 'fanOutRequeuedAt', 'newestIngestedBucketUpdate', 'ingestedBucketCount',
            'enrichmentGated', 'inCandidateSet', 'candidateRule', 'matchableComponentCount', 'rootComponentCount',
            'terminalComponentCount', 'unmatchableComponentCount', 'noMatchableComponentsReason', 'blockerTotal',
            'blockersTruncated', 'blockers']
        const blockerFields = ['purl', 'enrichment', 'terminalReason', 'bucketIndex', 'bucketIngestState',
            'bucketLastUpdatedDate', 'reason']
        expect(fields).toHaveLength(23)
        for (const f of [...fields, ...blockerFields]) {
            expect(printed, f).toMatch(new RegExp(`\\b${f}\\b`))
        }
        // Case U (architecture-3 D4): notScannableAt is not selected until the server serves it.
        expect(printed).not.toMatch(/dtrackProjectUuid/)
        expect(printed).not.toMatch(/blockersVisible/)
    })
})

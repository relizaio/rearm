import { describe, it, expect } from 'vitest'
import { print, validate, type GraphQLSchema } from 'graphql'
import { ARTIFACT_SCAN_STATUS_QUERY, REQUEUE_ARTIFACT_SCAN_FAN_OUT_MUTATION } from './artifactScanStatus'
import { ceSchema, proSchema } from './schemaDriftSupport'

// TPF-122 (architecture-3 section 5, row 23): the scan-status document against both schemas.

describe('artifactScanStatus document vs the CE mirror schema (in-repo, always runs)', () => {
    // The canary that pinned the query's absence from CE is retired: the shared backend sync (#652)
    // carried artifactScanStatus to CE, so the document now validates there. The loader's drift probe
    // stays for servers older than the sync.
    it('(a) validates against the CE mirror', () => {
        expect(validate(ceSchema, ARTIFACT_SCAN_STATUS_QUERY).map(e => e.message)).toEqual([])
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
        // Case M (TPF-122 architecture-3 D4, folded into TPF-127): the server serves notScannableAt, so it is
        // selected between noMatchableComponentsReason and blockerTotal (TPF-122 row 23 (d)).
        expect(printed).toContain('notScannableAt')
        expect(printed.indexOf('noMatchableComponentsReason')).toBeLessThan(printed.indexOf('notScannableAt'))
        expect(printed.indexOf('notScannableAt')).toBeLessThan(printed.indexOf('blockerTotal'))
        expect(printed).not.toMatch(/dtrackProjectUuid/)
        expect(printed).not.toMatch(/blockersVisible/)
    })
})

describe('requeueArtifactScanFanOut mutation document', () => {
    // Both schemas carry the mutation (relizaio/rearm-saas#1001, synced to CE), so the UI has no fallback for it.
    it('validates against the CE mirror', () => {
        expect(validate(ceSchema, REQUEUE_ARTIFACT_SCAN_FAN_OUT_MUTATION).map(e => e.message)).toEqual([])
    })

    it.runIf(proSchema)('validates against Pro', () => {
        expect(validate(proSchema as GraphQLSchema, REQUEUE_ARTIFACT_SCAN_FAN_OUT_MUTATION).map(e => e.message)).toEqual([])
    })

    it('is the mutation requeueArtifactScanFanOut selecting what the query selects', () => {
        const def: any = REQUEUE_ARTIFACT_SCAN_FAN_OUT_MUTATION.definitions[0]
        expect(def.operation).toBe('mutation')
        expect(def.name.value).toBe('requeueArtifactScanFanOut')
        const fieldsOf = (doc: any) => doc.definitions[0].selectionSet.selections[0].selectionSet.selections
            .map((f: any) => f.name.value)
        expect(fieldsOf(REQUEUE_ARTIFACT_SCAN_FAN_OUT_MUTATION)).toEqual(fieldsOf(ARTIFACT_SCAN_STATUS_QUERY))
    })
})

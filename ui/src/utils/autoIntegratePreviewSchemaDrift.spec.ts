import { describe, it, expect } from 'vitest'
import { validate, type GraphQLSchema } from 'graphql'
import { AUTO_INTEGRATE_EXPLAINED_MUTATION, AUTO_INTEGRATE_LEGACY_MUTATION, AUTO_INTEGRATE_PREVIEW_QUERY } from './autoIntegratePreview'
import { ceSchema, proSchema } from './schemaDriftSupport'

// TPF-120 (architecture round 2, test row 18): the auto-integrate documents against both schemas.
// The CE mirror ships in this repo, so its checks always run; the Pro schema lives in the sibling
// rearm-core checkout and its checks are skipped (not failed) when it is absent.

describe('auto-integrate documents vs the CE mirror schema (in-repo, always runs)', () => {
    // Canary: the CE mirror lacks the preview query and the explained mutation, which is what makes
    // the probe meaningful there. When a backend sync carries the two fields to CE this fires; the
    // retirement is to delete this one test (the probe and the legacy fallback stay for older servers).
    it('the preview query and the explained mutation are INVALID against the CE mirror', () => {
        expect(validate(ceSchema, AUTO_INTEGRATE_PREVIEW_QUERY).map(e => e.message).join('\n'))
            .toContain('autoIntegrateFeatureSetPreview')
        expect(validate(ceSchema, AUTO_INTEGRATE_EXPLAINED_MUTATION).map(e => e.message).join('\n'))
            .toContain('autoIntegrateFeatureSetExplained')
    })

    // The fallback document must validate everywhere.
    it('the legacy mutation is valid against the CE mirror', () => {
        expect(validate(ceSchema, AUTO_INTEGRATE_LEGACY_MUTATION).map(e => e.message)).toEqual([])
    })
})

describe('auto-integrate documents vs the Pro schema (skipped if rearm-core absent)', () => {
    it.runIf(proSchema)('all three documents are valid against Pro', () => {
        for (const doc of [AUTO_INTEGRATE_PREVIEW_QUERY, AUTO_INTEGRATE_EXPLAINED_MUTATION, AUTO_INTEGRATE_LEGACY_MUTATION]) {
            expect(validate(proSchema as GraphQLSchema, doc).map(e => e.message)).toEqual([])
        }
    })
})

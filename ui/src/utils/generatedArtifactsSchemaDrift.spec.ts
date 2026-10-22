import { describe, it, expect } from 'vitest'
import { validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { syntheticArtifactsAvailable } from './generatedArtifacts'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * The generated-artifacts document (task TEA-4) against both schemas, and the edition gate that
 * keeps a CE UI from sending it.
 *
 * The operator decided on 2026-10-03 that the 2026-10 TEA work ships no CE backend sync, so the
 * CE schema has no Release.syntheticArtifactDetails and the document fails there. The release
 * page therefore loads it through its own query, only where syntheticArtifactsAvailable says so,
 * rather than in the shared release fragments. The canary below fails as soon as the CE schema
 * gains the field: that is the moment to lift the gate, or the CE UI keeps hiding a section its
 * backend serves.
 */

const ceServesGeneratedArtifacts = () => {
    const release: any = ceSchema.getType('Release')
    return Boolean(release?.getFields?.().syntheticArtifactDetails)
}

describe('generated-artifacts document and the edition gate', () => {
    it('sends it on CE exactly when the CE schema serves the field', () => {
        expect(syntheticArtifactsAvailable('OSS')).toBe(ceServesGeneratedArtifacts())
    })

    it('the CE schema answers the document exactly when it serves the field', () => {
        expect(validate(ceSchema, graphqlQueries.ReleaseSyntheticArtifactsGql).length === 0)
            .toBe(ceServesGeneratedArtifacts())
    })

    it.runIf(proSchema)('the document validates against the Pro schema', () => {
        expect(validate(proSchema!, graphqlQueries.ReleaseSyntheticArtifactsGql).map(e => e.message)).toEqual([])
    })
})

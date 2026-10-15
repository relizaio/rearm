import { describe, it, expect } from 'vitest'
import { validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

// The two SBOM score queries (SCORE-6) against both schemas. They are statically parseable,
// so scripts/validate-graphql checks them too; this spec pins the CE side, which that script
// only warns about.
//
// Pro (SCORE-5) has both fields, and the CE backend in this repo has them since the CE sync of
// October 2026 (SCORE-2 design C-3.8): they must validate on both. A CE backend older than that
// rejects the documents as schema drift, which the panel shows as "SBOM scoring is not
// available on this server".
const SCORE_DOCUMENTS: Array<[string, any]> = [
    ['ReleaseSbomScoreGql', graphqlQueries.ReleaseSbomScoreGql],
    ['ArtifactSbomScoreGql', graphqlQueries.ArtifactSbomScoreGql],
]

describe('SBOM score queries vs the schemas', () => {
    it.runIf(proSchema).each(SCORE_DOCUMENTS)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as any, doc).map(e => e.message)).toEqual([])
    })

    it.each(SCORE_DOCUMENTS)('%s is valid against the CE schema', (_name, doc) => {
        expect(validate(ceSchema, doc).map(e => e.message)).toEqual([])
    })

    it.each(SCORE_DOCUMENTS)('%s is a query (scores are read-only and never cached)', (_name, doc) => {
        expect(doc.definitions[0].operation).toBe('query')
    })
})

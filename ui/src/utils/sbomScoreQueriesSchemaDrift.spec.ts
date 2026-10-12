import { describe, it, expect } from 'vitest'
import { validate } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { ceSchema, proSchema } from './schemaDriftSupport'

// The two SBOM score queries (SCORE-6) against both schemas. They are statically parseable,
// so scripts/validate-graphql checks them too; this spec pins the CE side, which that script
// only warns about.
//
// Pro (rearm-saas 2026-10-sbom-score, SCORE-5) has both fields; they must validate there. The
// CE mirror in this repo does not have them until its sync (SCORE-2 design C-3.8): until then
// a CE backend rejects the documents as schema drift, which the panel shows as "SBOM scoring
// is not available on this server". When the sync lands, the CE case fails and is to be
// turned into a "valid on CE" assertion.
const SCORE_DOCUMENTS: Array<[string, any, string]> = [
    ['ReleaseSbomScoreGql', graphqlQueries.ReleaseSbomScoreGql, 'releaseSbomScore'],
    ['ArtifactSbomScoreGql', graphqlQueries.ArtifactSbomScoreGql, 'artifactSbomScore'],
]

describe('SBOM score queries vs the schemas', () => {
    it.runIf(proSchema).each(SCORE_DOCUMENTS)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as any, doc).map(e => e.message)).toEqual([])
    })

    it.each(SCORE_DOCUMENTS)('%s is reported as drift against the CE schema until the CE sync', (_name, doc, field) => {
        const messages = validate(ceSchema, doc).map(e => e.message)
        expect(messages).toHaveLength(1)
        expect(messages[0]).toMatch(new RegExp(`^Cannot query field "${field}" on type "Query"\\.`))
    })

    it.each(SCORE_DOCUMENTS)('%s is a query (scores are read-only and never cached)', (_name, doc) => {
        expect(doc.definitions[0].operation).toBe('query')
    })
})

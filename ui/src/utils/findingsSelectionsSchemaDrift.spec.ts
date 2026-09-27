import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, visit, print, Kind, type DocumentNode, type GraphQLSchema } from 'graphql'
import {
    ARTIFACT_FINDINGS_QUERY,
    ARTIFACT_FINDINGS_QUERY_CORE,
    RELEASE_FINDINGS_QUERY,
    RELEASE_FINDINGS_QUERY_CORE
} from './findingsQuery'

// The findings modal's two loads (a release's findings, an artifact's
// findings) checked against the backend schemas. Before these documents moved
// out of the service and the component, nothing validated them: the release
// selection interpolated ${...} (skipped by scripts/validate-graphql) and the
// artifact query was inline in ReleaseView.vue.
//
// Each load is a FULL / CORE pair (loadWithSchemaDriftFallback): FULL adds the
// per-finding scores; CORE is everything else, the fallback for a backend
// without them (a CE install behind this UI), and must validate on CE or the
// modal blanks there. Both validate on CE since the mirror.
//
// A schema is the three SDL files together (root fields live in user.graphqls).
// CE ships in this repo; Pro is checked when a sibling rearm-core checkout is
// present, which is never in this repo's own CI.
const SCHEMA_FILES = ['schema.graphqls', 'user.graphqls', 'programmatic.graphqls']
const CE_SCHEMA_DIR = fileURLToPath(new URL('../../../backend/src/main/resources/schema/', import.meta.url))
const PRO_SCHEMA_DIR = fileURLToPath(new URL('../../../../rearm-core/backend/src/main/resources/schema/', import.meta.url))

function loadSchema (dir: string): GraphQLSchema | null {
    const files = SCHEMA_FILES.map(f => dir + f)
    if (!files.every(existsSync)) return null
    return buildSchema(files.map(f => readFileSync(f, 'utf8')).join('\n'))
}

const ceSchema = loadSchema(CE_SCHEMA_DIR)
const proSchema = loadSchema(PRO_SCHEMA_DIR)

const errorsAgainst = (schema: GraphQLSchema, doc: DocumentNode) => validate(schema, doc).map(e => e.message)

const SCORE_FIELDS = new Set(['scores', 'topScore', 'epss'])

// FULL with the score fields and their fragment taken out.
function withoutScores (doc: DocumentNode): string {
    return print(visit(doc, {
        Field: node => SCORE_FIELDS.has(node.name.value) ? null : undefined,
        FragmentDefinition: () => null
    }))
}

const PAIRS: Array<[string, DocumentNode, DocumentNode]> = [
    ['release findings', RELEASE_FINDINGS_QUERY, RELEASE_FINDINGS_QUERY_CORE],
    ['artifact findings', ARTIFACT_FINDINGS_QUERY, ARTIFACT_FINDINGS_QUERY_CORE]
]

describe('findings modal documents vs the Pro schema (skipped if rearm-core absent)', () => {
    it.runIf(proSchema).each(PAIRS)('%s FULL is valid against Pro', (_name, full) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, full)).toEqual([])
    })
    it.runIf(proSchema).each(PAIRS)('%s CORE is valid against Pro', (_name, _full, core) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, core)).toEqual([])
    })
})

describe('findings modal documents vs the CE mirror schema (in-repo, always runs)', () => {
    it('has the CE mirror schema available', () => {
        expect(ceSchema, `CE mirror schema not found at ${CE_SCHEMA_DIR}`).not.toBeNull()
    })

    it.each(PAIRS)('%s CORE is valid against CE', (_name, _full, core) => {
        expect(errorsAgainst(ceSchema as GraphQLSchema, core)).toEqual([])
    })

    it.each(PAIRS)('%s FULL is valid against CE', (_name, full) => {
        expect(errorsAgainst(ceSchema as GraphQLSchema, full)).toEqual([])
    })
})

describe('FULL and CORE stay one document', () => {
    // A field added to one and not the other would make the modal differ by backend in
    // more than the score columns.
    it.each(PAIRS)('%s FULL is CORE plus the score fields', (_name, full, core) => {
        expect(withoutScores(full)).toBe(print(core))
        expect(print(full)).not.toBe(print(core))
    })

    it('selects the scores on the release\'s own findings only, not on its parents\'', () => {
        let scoredDetailLists = 0
        visit(RELEASE_FINDINGS_QUERY, {
            Field: node => {
                if (node.name.value !== 'vulnerabilityDetails') return
                if (node.selectionSet?.selections.some(s => s.kind === Kind.FIELD && s.name.value === 'topScore')) scoredDetailLists++
            }
        })
        expect(scoredDetailLists).toBe(1)
    })
})

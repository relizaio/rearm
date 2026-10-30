import { describe, expect, it, vi } from 'vitest'
import { parse, print, validate, type DocumentNode, type GraphQLSchema } from 'graphql'
import { ceSchema, proSchema } from './schemaDriftSupport'

// TPF-127 test 3: the release documents that carry the artifact scan state (Artifact.metrics.notScannable,
// on an #additive-field line) and their core twins (the same documents without the tagged lines). The
// store module is loaded for its three hoisted mutation documents, so the GraphQL client and keycloak are
// mocked as in componentRefsStore.spec.ts.
vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(), mutate: vi.fn() } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))

const queries = await import('./graphqlQueries')
const graphqlQueries = queries.default
const { releaseScanStateDocumentsForTests: hoisted } = await import('@/store')

const PAIRS: Array<[string, DocumentNode, DocumentNode]> = [
    ['SingleReleaseGql', graphqlQueries.SingleReleaseGql, graphqlQueries.SingleReleaseGqlCore],
    ['SingleReleaseProductGql', graphqlQueries.SingleReleaseProductGql, graphqlQueries.SingleReleaseProductGqlCore],
    ['ReleasesByDateRangeGql', graphqlQueries.ReleasesByDateRangeGql, graphqlQueries.ReleasesByDateRangeGqlCore],
    ['ReleasesByDateRangeAndPerspectiveGql', graphqlQueries.ReleasesByDateRangeAndPerspectiveGql, graphqlQueries.ReleasesByDateRangeAndPerspectiveGqlCore],
    ['LatestReleasesOfComponentGql', graphqlQueries.LatestReleasesOfComponentGql, graphqlQueries.LatestReleasesOfComponentGqlCore],
    ['ReleaseGqlMutate', graphqlQueries.ReleaseGqlMutate, graphqlQueries.ReleaseGqlMutateCore],
    ['ReleaseTagsMetaGqlMutate', graphqlQueries.ReleaseTagsMetaGqlMutate, graphqlQueries.ReleaseTagsMetaGqlMutateCore],
    ['ApproveReleaseGqlMutate', graphqlQueries.ApproveReleaseGqlMutate, graphqlQueries.ApproveReleaseGqlMutateCore],
    ['ADD_RELEASE_GQL', hoisted.ADD_RELEASE_GQL, hoisted.ADD_RELEASE_GQL_CORE],
    ['UPDATE_RELEASE_LIFECYCLE_GQL', hoisted.UPDATE_RELEASE_LIFECYCLE_GQL, hoisted.UPDATE_RELEASE_LIFECYCLE_GQL_CORE],
    ['REEVALUATE_RELEASE_TRIGGERS_GQL', hoisted.REEVALUATE_RELEASE_TRIGGERS_GQL, hoisted.REEVALUATE_RELEASE_TRIGGERS_GQL_CORE]
]
const PREFERRED = PAIRS.map(([name, preferred]) => [name, preferred] as [string, DocumentNode])
const CORES = PAIRS.map(([name, , core]) => [name, core] as [string, DocumentNode])

const TOKENS = 'notScannable { reason at components root terminal unmatchable }'
const MARKER = '#additive-field'
const errorsAgainst = (schema: GraphQLSchema, doc: DocumentNode) => validate(schema, doc).map(e => e.message)
const count = (text: string, needle: string) => text.split(needle).length - 1
/** The printed text between two markers, the child rows of the product document. */
function between (text: string, from: string, to: string): string {
    const i = text.indexOf(from)
    const j = text.indexOf(to, i)
    expect(i).toBeGreaterThanOrEqual(0)
    expect(j).toBeGreaterThan(i)
    return text.slice(i, j)
}

describe('release documents with the artifact scan state, and their core twins (TPF-127)', () => {
    it('lists eleven distinct pairs', () => {
        expect(PAIRS).toHaveLength(11)
        for (const [, preferred, core] of PAIRS) {
            expect(preferred).toBeTruthy()
            expect(core).toBeTruthy()
            expect(core).not.toBe(preferred)
        }
    })

    it.each(CORES)('(a) the core twin of %s is valid against the CE schema', (_name, core) => {
        expect(errorsAgainst(ceSchema, core)).toEqual([])
    })

    it.runIf(proSchema).each(CORES)('(a) the core twin of %s is valid against the Pro schema', (_name, core) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, core)).toEqual([])
    })

    it.runIf(proSchema).each(PREFERRED)('(b) %s is valid against the Pro schema', (_name, preferred) => {
        expect(errorsAgainst(proSchema as GraphQLSchema, preferred)).toEqual([])
    })

    // The canary: retired by deleting this row only, when an operator sync carries the field to CE.
    it.each(PREFERRED)('(c) %s is refused by CE for notScannable and nothing else', (_name, preferred) => {
        const errors = errorsAgainst(ceSchema, preferred)
        expect(errors.length).toBeGreaterThan(0)
        for (const message of errors) expect(message).toContain('notScannable')
    })

    it.each(PAIRS)('(d) %s selects the field and its core twin does not', (_name, preferred, core) => {
        expect(print(preferred)).toContain('notScannable {')
        expect(print(core)).not.toContain('notScannable')
    })

    it('(e) both single-release documents select the field (the pairing rule)', () => {
        expect(print(graphqlQueries.SingleReleaseGql)).toContain('notScannable {')
        expect(print(graphqlQueries.SingleReleaseProductGql)).toContain('notScannable {')
    })

    it('(f) the two line constants select the same six fields on one tagged line', () => {
        for (const line of [queries.ARTIFACT_NOT_SCANNABLE_LINE, queries.ARTIFACT_NOT_SCANNABLE_LIST_LINE]) {
            expect(line).toContain(TOKENS)
            expect(line.endsWith(MARKER)).toBe(true)
            expect(line).not.toContain('\n')
        }
    })

    it('(g) the shared list fragment, the instance document and the generated-artifacts document never select the field', () => {
        expect(graphqlQueries.MultiReleaseGqlData).not.toContain('notScannable')
        expect(print(graphqlQueries.InstanceGql)).not.toContain('notScannable')
        const synthetic = print(graphqlQueries.ReleaseSyntheticArtifactsGql)
        expect(synthetic).not.toContain('notScannable')
        expect(synthetic).toContain('syntheticArtifactDetails {')
        expect(synthetic).toContain('firstScanned')
        expect(errorsAgainst(ceSchema, graphqlQueries.ReleaseSyntheticArtifactsGql)).toEqual([])
    })

    it('(h) the light single-release document does not select the field', () => {
        expect(print(graphqlQueries.SingleReleaseGqlLight)).not.toContain('notScannable')
    })

    it('(i) the product child rows carry the whole scan-input set on three tagged lines', () => {
        const lines = [queries.CHILD_RELEASE_SCAN_INPUTS_LINE, queries.CHILD_RELEASE_SCE_SCAN_INPUTS_LINE, queries.CHILD_RELEASE_DELIVERABLE_SCAN_INPUTS_LINE]
        for (const line of lines) {
            expect(line).toContain(TOKENS)
            expect(line.endsWith(MARKER)).toBe(true)
            expect(line).not.toContain('\n')
        }
        expect(queries.CHILD_RELEASE_SCAN_INPUTS_LINE.startsWith('artifactDetails { type componentUuid enrichmentStatus metrics { dependencyTrackFullUri dtrackSubmissionFailed firstScanned')).toBe(true)
        expect(queries.CHILD_RELEASE_SCE_SCAN_INPUTS_LINE.startsWith('sourceCodeEntryDetails { uuid artifactDetails {')).toBe(true)
        expect(queries.CHILD_RELEASE_DELIVERABLE_SCAN_INPUTS_LINE.startsWith('variantDetails { uuid outboundDeliverableDetails { uuid artifactDetails {')).toBe(true)

        const preferred = print(graphqlQueries.SingleReleaseProductGql)
        expect(count(preferred, 'notScannable {')).toBeGreaterThanOrEqual(7)
        const children = between(preferred, 'parentReleases {', 'previousRelease {')
        expect(count(children, 'artifactDetails {')).toBeGreaterThanOrEqual(6)
        expect(count(children, 'sourceCodeEntryDetails {')).toBeGreaterThanOrEqual(2)
        expect(count(children, 'variantDetails {')).toBeGreaterThanOrEqual(2)

        const core = print(graphqlQueries.SingleReleaseProductGqlCore)
        expect(core).not.toContain('notScannable')
        const coreChildren = between(core, 'parentReleases {', 'previousRelease {')
        expect(coreChildren).not.toContain('artifactDetails')
        expect(coreChildren).not.toContain('sourceCodeEntryDetails')
        expect(coreChildren).not.toContain('variantDetails')

        const errors = errorsAgainst(ceSchema, graphqlQueries.SingleReleaseProductGql)
        expect(errors.length).toBeGreaterThan(0)
        for (const message of errors) expect(message).toContain('notScannable')
    })

    it('(j) the branch list fragment is untouched: no marker, no field, no artifact type', () => {
        const fragment: string = graphqlQueries.BranchReleaseListGqlData
        expect(fragment).not.toContain('notScannable')
        expect(fragment).not.toContain(MARKER)
        const start = fragment.indexOf('artifactDetails {')
        expect(start).toBeGreaterThanOrEqual(0)
        let depth = 0
        let end = start
        for (let i = fragment.indexOf('{', start); i < fragment.length; i++) {
            if (fragment[i] === '{') depth++
            if (fragment[i] === '}') depth--
            if (depth === 0) { end = i; break }
        }
        expect(fragment.slice(start, end)).not.toMatch(/\btype\b/)
        const doc = parse(`query q($b: ID!) { releases(branchFilter: $b) { ${fragment} } }`)
        expect(errorsAgainst(ceSchema, doc)).toEqual([])
    })
})

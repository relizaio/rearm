import { describe, expect, it, vi } from 'vitest'
import { parse, print, validate, type DocumentNode, type GraphQLSchema } from 'graphql'
import { ceSchema, proSchema } from './schemaDriftSupport'
import { BOM_SCAN_FIELD_MARKER, stripAdditiveFields } from './graphqlDriftFallback'

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
/** The artifact marker field as a word (TPF-133: the tally's notScannableBoms contains the same letters). */
const NOT_SCANNABLE_FIELD = /\bnotScannable\b/
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

    // The canary: retired by deleting this row only, when an operator sync carries both fields to CE
    // (TPF-133: the list documents and the product document also carry bomScan).
    it.each(PREFERRED)('(c) %s is refused by CE for notScannable or bomScan and nothing else', (_name, preferred) => {
        const errors = errorsAgainst(ceSchema, preferred)
        expect(errors.length).toBeGreaterThan(0)
        for (const message of errors) expect(message).toMatch(/notScannable|bomScan/)
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
        for (const message of errors) expect(message).toMatch(/notScannable|bomScan/)
    })

    it('(j) the branch list fragment: no artifact marker, no artifact type, one bomScan line, componentDetails { type } (TPF-133)', () => {
        const fragment: string = graphqlQueries.BranchReleaseListGqlData
        // the artifact marker field, as a word: the tally's own notScannableBoms is selected here
        expect(fragment).not.toMatch(NOT_SCANNABLE_FIELD)
        const tagged = fragment.split('\n').filter((line) => line.includes(MARKER))
        expect(tagged).toHaveLength(1)
        expect(tagged[0].trim().endsWith(BOM_SCAN_FIELD_MARKER)).toBe(true)
        expect(blockOf(fragment, 'artifactDetails {')).not.toMatch(/\btype\b/)
        const metrics = blockOf(fragment, 'metrics {')
        expect(metrics).toMatch(/^metrics \{\s*dtrackFetchStatus/)
        expect(count(metrics, 'bomScan {')).toBe(1)
        const component = blockOf(fragment, 'componentDetails {')
        expect(component).toMatch(/\btype\b/)
        expect(component).not.toMatch(/\bkind\b/)
        const shape = (f: string) => parse(`query q($b: ID!) { releases(branchFilter: $b) { ${f} } }`)
        const errors = errorsAgainst(ceSchema, shape(fragment))
        expect(errors.length).toBeGreaterThan(0)
        for (const message of errors) {
            expect(message).toContain('bomScan')
            expect(message).not.toContain('notScannable')
        }
        expect(errorsAgainst(ceSchema, shape(stripAdditiveFields(fragment)))).toEqual([])
    })

    it.runIf(proSchema)('(j) the branch list fragment validates on Pro (TPF-133)', () => {
        const doc = parse(`query q($b: ID!) { releases(branchFilter: $b) { ${graphqlQueries.BranchReleaseListGqlData} } }`)
        expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
    })
})

/** The text of the first selection block opening at `opening` (e.g. 'metrics {'), by a brace walk. */
function blockOf (text: string, opening: string): string {
    const start = text.indexOf(opening)
    expect(start).toBeGreaterThanOrEqual(0)
    let depth = 0
    for (let i = text.indexOf('{', start); i < text.length; i++) {
        if (text[i] === '{') depth++
        if (text[i] === '}') depth--
        if (depth === 0) return text.slice(start, i + 1)
    }
    throw new Error(`unbalanced block at ${opening}`)
}

// TPF-133 test 2: the served BOM scan tally (DependencyTrackMetrics.bomScan) on its own marker tier.
const BOM_SCAN_TOKENS = 'bomScan { state scannableBoms notScannableBoms reasons }'
const TIERED: Array<[string, DocumentNode, DocumentNode, DocumentNode]> = [
    ['ReleasesByDateRangeGql', graphqlQueries.ReleasesByDateRangeGql, graphqlQueries.ReleasesByDateRangeGqlWithoutBomScan, graphqlQueries.ReleasesByDateRangeGqlCore],
    ['ReleasesByDateRangeAndPerspectiveGql', graphqlQueries.ReleasesByDateRangeAndPerspectiveGql, graphqlQueries.ReleasesByDateRangeAndPerspectiveGqlWithoutBomScan, graphqlQueries.ReleasesByDateRangeAndPerspectiveGqlCore],
    ['LatestReleasesOfComponentGql', graphqlQueries.LatestReleasesOfComponentGql, graphqlQueries.LatestReleasesOfComponentGqlWithoutBomScan, graphqlQueries.LatestReleasesOfComponentGqlCore],
    ['SingleReleaseProductGql', graphqlQueries.SingleReleaseProductGql, graphqlQueries.SingleReleaseProductGqlWithoutBomScan, graphqlQueries.SingleReleaseProductGqlCore]
]
const BRANCH_PAIR: [DocumentNode, DocumentNode] = [hoisted.FETCH_RELEASES_GQL, hoisted.FETCH_RELEASES_GQL_CORE]
const WITHOUT_BOM_SCAN_TIER: Array<[string, DocumentNode]> = [
    ['SingleReleaseGql', graphqlQueries.SingleReleaseGql],
    ['ReleaseGqlMutate', graphqlQueries.ReleaseGqlMutate],
    ['ReleaseTagsMetaGqlMutate', graphqlQueries.ReleaseTagsMetaGqlMutate],
    ['ApproveReleaseGqlMutate', graphqlQueries.ApproveReleaseGqlMutate],
    ['ADD_RELEASE_GQL', hoisted.ADD_RELEASE_GQL],
    ['UPDATE_RELEASE_LIFECYCLE_GQL', hoisted.UPDATE_RELEASE_LIFECYCLE_GQL],
    ['REEVALUATE_RELEASE_TRIGGERS_GQL', hoisted.REEVALUATE_RELEASE_TRIGGERS_GQL]
]

describe('documents with the release BOM scan tally, the second tier (TPF-133)', () => {
    it('(a) the two line constants select the four served fields on one tagged line', () => {
        for (const line of [queries.RELEASE_BOM_SCAN_LINE, queries.RELEASE_BOM_SCAN_LIST_LINE]) {
            expect(line).toContain(BOM_SCAN_TOKENS)
            expect(line).not.toContain('notScannableReasons')
            expect(line).not.toContain('\n')
            expect(line.endsWith(BOM_SCAN_FIELD_MARKER)).toBe(true)
        }
    })

    it.each(TIERED)('(b) %s: full, without bomScan and core are three distinct forms', (_name, full, withoutBomScan, core) => {
        expect(print(full)).toContain('bomScan {')
        expect(print(withoutBomScan)).toContain('notScannable {')
        expect(print(withoutBomScan)).not.toContain('bomScan')
        expect(print(core)).not.toContain('bomScan')
        expect(print(core)).not.toContain('notScannable')
        expect(new Set([full, withoutBomScan, core]).size).toBe(3)
    })

    it.runIf(proSchema).each(TIERED)('(b) all three forms of %s validate on Pro', (_name, full, withoutBomScan, core) => {
        for (const doc of [full, withoutBomScan, core]) expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
    })

    it.each(TIERED)('(b) %s on CE: the core validates, the full form is refused for bomScan or notScannable only, the form without bomScan for notScannable only', (_name, full, withoutBomScan, core) => {
        expect(errorsAgainst(ceSchema, core)).toEqual([])
        const fullErrors = errorsAgainst(ceSchema, full)
        expect(fullErrors.length).toBeGreaterThan(0)
        for (const message of fullErrors) expect(message).toMatch(/notScannable|bomScan/)
        expect(fullErrors.some((m) => m.includes('bomScan'))).toBe(true)
        const tier2Errors = errorsAgainst(ceSchema, withoutBomScan)
        expect(tier2Errors.length).toBeGreaterThan(0)
        for (const message of tier2Errors) {
            expect(message).toContain('notScannable')
            expect(message).not.toContain('bomScan')
        }
    })

    it('(b) the branch document: the full form selects bomScan and componentDetails, the core neither field; CE refuses the full form for bomScan only', () => {
        const [full, core] = BRANCH_PAIR
        expect(full).not.toBe(core)
        expect(print(full)).toContain('bomScan {')
        expect(print(full)).toContain('componentDetails {')
        expect(print(full)).not.toMatch(NOT_SCANNABLE_FIELD)
        expect(print(core)).not.toContain('bomScan')
        expect(print(core)).toContain('componentDetails {')
        expect(errorsAgainst(ceSchema, core)).toEqual([])
        const errors = errorsAgainst(ceSchema, full)
        expect(errors.length).toBeGreaterThan(0)
        for (const message of errors) {
            expect(message).toContain('bomScan')
            expect(message).not.toContain('notScannable')
        }
    })

    it.runIf(proSchema)('(b) both forms of the branch document validate on Pro', () => {
        for (const doc of BRANCH_PAIR) expect(errorsAgainst(proSchema as GraphQLSchema, doc)).toEqual([])
    })

    it.each(WITHOUT_BOM_SCAN_TIER)('(d) %s carries no bomScan tier', (_name, preferred) => {
        expect(print(preferred)).not.toContain('bomScan')
    })

    it('(f) the product document selects bomScan on the child rows only', () => {
        const preferred = print(graphqlQueries.SingleReleaseProductGql)
        const children = between(preferred, 'parentReleases {', 'previousRelease {')
        expect(count(children, 'bomScan {')).toBeGreaterThanOrEqual(2)
        expect(preferred.slice(0, preferred.indexOf('parentReleases {'))).not.toContain('bomScan')
        const withoutBomScan = print(graphqlQueries.SingleReleaseProductGqlWithoutBomScan)
        expect(withoutBomScan).not.toContain('bomScan')
        expect(count(withoutBomScan, 'notScannable {')).toBeGreaterThanOrEqual(7)
    })

    it('(g) the shared fragment, the instance, generated-artifacts and single-release documents never select bomScan', () => {
        expect(graphqlQueries.MultiReleaseGqlData).not.toContain('bomScan')
        expect(print(graphqlQueries.InstanceGql)).not.toContain('bomScan')
        expect(print(graphqlQueries.ReleaseSyntheticArtifactsGql)).not.toContain('bomScan')
        expect(print(graphqlQueries.SingleReleaseGqlLight)).not.toContain('bomScan')
        expect(print(graphqlQueries.SingleReleaseGql)).not.toContain('bomScan')
    })
})

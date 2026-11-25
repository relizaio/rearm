import { describe, it, expect } from 'vitest'
import { Kind, print, validate, visit, type DocumentNode, type FieldNode, type GraphQLSchema, type OperationDefinitionNode } from 'graphql'
import {
    ARCHIVE_UPSTREAM_SOURCE, CREATE_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, GET_UPSTREAM_SYNCS, PROBE_UPSTREAM_SOURCE,
    SYNC_UPSTREAM_SOURCE_NOW, UPDATE_UPSTREAM_SOURCE,
} from './upstreamSourceQueries'
import {
    CONFIRM_UPSTREAM_FOLLOW, GET_UPSTREAM_FOLLOW, GET_UPSTREAM_FOLLOWS, GET_UPSTREAM_FOLLOW_PREPARATION, GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES,
    GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE, GET_UPSTREAM_INGEST_RUNS, GET_UPSTREAM_RELEASES, PREPARE_UPSTREAM_FOLLOW, RECONFIGURE_UPSTREAM_FOLLOW,
    START_UPSTREAM_FOLLOW_RUN, UNFOLLOW_UPSTREAM_ITEM, confirmedFollowUuid,
} from './upstreamFollowQueries'
import { proSchema } from './schemaDriftSupport'

// T-502 (TPF-185 design 3.4): every document of the upstream sources feature against the Pro
// schema, the sbomSigningQueriesSchemaDrift.spec.ts shape. One DOCUMENTS list per query module;
// each later module of the feature adds its own list below and to ALL. There is no case against
// the CE schema: these documents are Pro only and are sent only behind upstreamSourcesAvailable.
type Row = [string, DocumentNode, string, 'query' | 'mutation']

const SOURCE_DOCUMENTS: Row[] = [
    ['GET_UPSTREAM_SOURCES', GET_UPSTREAM_SOURCES, 'upstreamSources', 'query'],
    ['GET_UPSTREAM_SOURCE', GET_UPSTREAM_SOURCE, 'upstreamSource', 'query'],
    ['GET_UPSTREAM_SYNCS', GET_UPSTREAM_SYNCS, 'upstreamSyncs', 'query'],
    ['CREATE_UPSTREAM_SOURCE', CREATE_UPSTREAM_SOURCE, 'createUpstreamSource', 'mutation'],
    ['UPDATE_UPSTREAM_SOURCE', UPDATE_UPSTREAM_SOURCE, 'updateUpstreamSource', 'mutation'],
    ['PROBE_UPSTREAM_SOURCE', PROBE_UPSTREAM_SOURCE, 'probeUpstreamSource', 'mutation'],
    ['SYNC_UPSTREAM_SOURCE_NOW', SYNC_UPSTREAM_SOURCE_NOW, 'syncUpstreamSourceNow', 'mutation'],
    ['ARCHIVE_UPSTREAM_SOURCE', ARCHIVE_UPSTREAM_SOURCE, 'archiveUpstreamSource', 'mutation'],
]

// TPF-229 (design 3.7 to 3.9): the follows, the start of a follow, the confirmation and the read of a follow.
const FOLLOW_DOCUMENTS: Row[] = [
    ['GET_UPSTREAM_FOLLOWS', GET_UPSTREAM_FOLLOWS, 'upstreamFollows', 'query'],
    ['PREPARE_UPSTREAM_FOLLOW', PREPARE_UPSTREAM_FOLLOW, 'prepareUpstreamFollow', 'mutation'],
    ['GET_UPSTREAM_FOLLOW_PREPARATION', GET_UPSTREAM_FOLLOW_PREPARATION, 'upstreamFollowPreparation', 'query'],
    ['GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES', GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES, 'upstreamFollowPreparationReleases', 'query'],
    ['GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE', GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE, 'upstreamFollowPreparationScope', 'query'],
    ['CONFIRM_UPSTREAM_FOLLOW', CONFIRM_UPSTREAM_FOLLOW, 'confirmUpstreamFollow', 'mutation'],
    ['GET_UPSTREAM_FOLLOW', GET_UPSTREAM_FOLLOW, 'upstreamFollow', 'query'],
]

// TPF-230 (design 3.9, sections 4 to 8): operating a follow, appended to the follow module.
const OPERATE_DOCUMENTS: Row[] = [
    ['GET_UPSTREAM_INGEST_RUNS', GET_UPSTREAM_INGEST_RUNS, 'upstreamIngestRuns', 'query'],
    ['GET_UPSTREAM_RELEASES', GET_UPSTREAM_RELEASES, 'upstreamReleases', 'query'],
    ['START_UPSTREAM_FOLLOW_RUN', START_UPSTREAM_FOLLOW_RUN, 'startUpstreamFollowRun', 'mutation'],
    ['RECONFIGURE_UPSTREAM_FOLLOW', RECONFIGURE_UPSTREAM_FOLLOW, 'reconfigureUpstreamFollow', 'mutation'],
    ['UNFOLLOW_UPSTREAM_ITEM', UNFOLLOW_UPSTREAM_ITEM, 'unfollowUpstreamItem', 'mutation'],
]

const ALL: Row[] = [...SOURCE_DOCUMENTS, ...FOLLOW_DOCUMENTS, ...OPERATE_DOCUMENTS]

const operations = (doc: DocumentNode): OperationDefinitionNode[] =>
    doc.definitions.filter((d): d is OperationDefinitionNode => d.kind === Kind.OPERATION_DEFINITION)
const rootField = (doc: DocumentNode): FieldNode => operations(doc)[0].selectionSet.selections[0] as FieldNode

/** Every argument value in the document that is not a variable, as `field.argument`. */
function literalArguments (doc: DocumentNode): string[] {
    const found: string[] = []
    visit(doc, {
        Field (node) {
            for (const arg of node.arguments ?? []) {
                if (arg.value.kind !== Kind.VARIABLE) found.push(`${node.name.value}.${arg.name.value}`)
            }
        },
    })
    return found
}

describe('upstream documents vs the Pro schema', () => {
    it.runIf(proSchema).each(ALL)('%s is valid against the Pro schema', (_name, doc) => {
        expect(validate(proSchema as GraphQLSchema, doc).map(e => e.message)).toEqual([])
    })

    it.each(ALL)('%s is one %s with the one root field %s', (_name, doc, field, op) => {
        expect(operations(doc)).toHaveLength(1)
        expect(operations(doc)[0].operation).toBe(op)
        expect(operations(doc)[0].selectionSet.selections).toHaveLength(1)
        expect(rootField(doc).name.value).toBe(field)
    })

    it.each(ALL.filter(r => r[3] === 'mutation'))('%s passes every argument as a variable', (_name, doc) => {
        expect(literalArguments(doc)).toEqual([])
    })

    it('selectsExactlyTheFieldsOfTheDesignOnASource', () => {
        // design 3.6: the one selection of a source, field for field
        const paths: string[] = []
        const walk = (sel: readonly any[], prefix: string) => {
            for (const s of sel) {
                const path = prefix + s.name.value
                paths.push(path)
                if (s.selectionSet) walk(s.selectionSet.selections, path + '.')
            }
        }
        walk(rootField(GET_UPSTREAM_SOURCE).selectionSet!.selections, '')
        const sync = ['uuid', 'origin', 'state', 'stopReason', 'sourceFailure', 'createdDate', 'finished', 'counters',
            ...['follows', 'runsCompleted', 'runsWithFailures', 'runsStopped', 'ingested', 'newCollectionVersions', 'held', 'failed'].map(c => 'counters.' + c)]
        expect(paths.sort()).toEqual([
            'uuid', 'org', 'kind', 'name', 'status', 'documentClasses', 'vexImportMode', 'autoIntegrate',
            'tea', 'tea.root', 'credential', 'credential.kind', 'credential.keyId', 'credential.setDate',
            'probe', 'probe.state', 'probe.result', 'probe.message', 'probe.requestedDate', 'probe.finishedDate',
            'createdDate', 'lastUpdatedDate', 'archivedDate', 'syncIntervalMinutes', 'nextSync', 'syncFailures',
            'lastSync', ...sync.map(f => 'lastSync.' + f), 'documentReServing',
        ].sort())
        for (const [name, doc] of SOURCE_DOCUMENTS.filter(r => r[2] !== 'upstreamSyncs')) {
            expect(print(rootField(doc).selectionSet!), name).toBe(print(rootField(GET_UPSTREAM_SOURCE).selectionSet!))
        }
    })

    it('readsTheTenMostRecentSyncs', () => {
        const limit = rootField(GET_UPSTREAM_SYNCS).arguments!.find(a => a.name.value === 'limit')
        expect(limit?.value).toEqual(expect.objectContaining({ kind: Kind.INT, value: '10' }))
    })

    it('selectsNoSecretOfACredential', () => {
        for (const [name, doc] of ALL) {
            const selected: string[] = []
            visit(doc, { Field (node) { selected.push(node.name.value) } })
            for (const secret of ['token', 'keySecret', 'secret', 'password']) {
                expect(selected, `${name} selects ${secret}`).not.toContain(secret)
            }
        }
    })
})

/** Every field path under the root field, as a.b.c, sorted. */
function selectedPaths (doc: DocumentNode): string[] {
    const paths: string[] = []
    const walk = (sel: readonly any[], prefix: string) => {
        for (const s of sel) {
            const path = prefix + s.name.value
            paths.push(path)
            if (s.selectionSet) walk(s.selectionSet.selections, path + '.')
        }
    }
    walk(rootField(doc).selectionSet!.selections, '')
    return paths.sort()
}

const argOf = (doc: DocumentNode, name: string) => rootField(doc).arguments!.find(a => a.name.value === name)

describe('the follow documents (TPF-229, design 3.7 to 3.9)', () => {
    it('selectsTheFieldsOfTheDesignOnAProposal', () => {
        const lines = ['lineKey', 'branchName', 'releases', 'lowestVersion', 'highestVersion', 'newestDate']
        const proposal = [
            'kind', 'upstreamUuid', 'upstreamName', 'rearmName', 'nameCollides',
            'existingItem', ...['uuid', 'component', 'depth', 'depthOrigin', 'updateState'].map(f => 'existingItem.' + f),
            'adoptionCandidates', ...['component', 'name', 'releases', 'releasesWithUpstreamVersion'].map(f => 'adoptionCandidates.' + f),
            'releases', 'finalReleases', 'preReleases', 'releasesNotStatingPreRelease', 'releasesWithDate', 'releasesWithUnusableVersion',
            'preReleaseRecognition', 'proposedDepth', 'depthBasis',
            'depths', ...['depth', 'fit', 'lineCount', 'unplaceable', 'linesTruncated', 'lines', ...lines.map(l => 'lines.' + l)].map(f => 'depths.' + f),
            'failure',
        ]
        expect(selectedPaths(GET_UPSTREAM_FOLLOW_PREPARATION)).toEqual([
            'uuid', 'org', 'source', 'state', 'failure', 'sourceFailure', 'message', 'createdDate', 'finishedDate', 'expires',
            'componentsTruncated', 'maxBackfillReleases',
            'item', ...proposal.map(f => 'item.' + f), 'components', ...proposal.map(f => 'components.' + f),
        ].sort())
    })

    it('selectsTheFieldsOfTheDesignOnTheScope', () => {
        const lines = ['lineKey', 'branchName', 'releases', 'lowestVersion', 'highestVersion', 'newestDate']
        expect(selectedPaths(GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE)).toEqual([
            'releases', 'withinLimit', 'onBaseBranch', 'skippedPreReleases', 'linesTruncated', 'lines', ...lines.map(l => 'lines.' + l),
        ].sort())
    })

    it('selectsNoPendingRowsInTheListOfFollows', () => {
        expect(selectedPaths(GET_UPSTREAM_FOLLOWS)).not.toContain('pending')
        expect(selectedPaths(GET_UPSTREAM_FOLLOWS)).toContain('pendingCount')
    })

    it('readsTheReleasesOfAPreparationFiftyAtATimeAndAFollowsOldestFiftyPending', () => {
        expect(argOf(GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES, 'first')?.value).toEqual(expect.objectContaining({ kind: Kind.INT, value: '50' }))
        expect(argOf(GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES, 'after')?.value.kind).toBe(Kind.VARIABLE)
        const pending = rootField(GET_UPSTREAM_FOLLOW).selectionSet!.selections.find((s: any) => s.name.value === 'pending') as FieldNode
        expect(pending.arguments![0].value).toEqual(expect.objectContaining({ kind: Kind.INT, value: '50' }))
    })

    it('scopesAndConfirmsWithTheDepthAndStartingPointAsVariables', () => {
        for (const doc of [GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE]) {
            expect(argOf(doc, 'depth')?.value.kind).toBe(Kind.VARIABLE)
            expect(argOf(doc, 'backfill')?.value.kind).toBe(Kind.VARIABLE)
        }
        expect(argOf(CONFIRM_UPSTREAM_FOLLOW, 'follow')?.value.kind).toBe(Kind.VARIABLE)
        expect(selectedPaths(CONFIRM_UPSTREAM_FOLLOW)).toEqual(['uuid'])
    })

    it('readsTheConfirmedFollowFromTheAnswer', () => {
        expect(confirmedFollowUuid({ confirmUpstreamFollow: { uuid: 'f1' } })).toBe('f1')
        expect(confirmedFollowUuid({})).toBeNull()
        expect(confirmedFollowUuid(null)).toBeNull()
    })
})

describe('the documents of operating a follow (TPF-230, design 3.9 sections 4 to 8)', () => {
    it('readsTheNewestTwentyRunsWithTheFieldsOfTheLastRun', () => {
        expect(argOf(GET_UPSTREAM_INGEST_RUNS, 'first')?.value).toEqual(expect.objectContaining({ kind: Kind.INT, value: '20' }))
        expect(argOf(GET_UPSTREAM_INGEST_RUNS, 'follow')?.value.kind).toBe(Kind.VARIABLE)
        const lastRun = rootField(GET_UPSTREAM_FOLLOW).selectionSet!.selections.find((x: any) => x.name.value === 'lastRun') as FieldNode
        expect(print(rootField(GET_UPSTREAM_INGEST_RUNS).selectionSet!)).toBe(print(lastRun.selectionSet!))
    })

    it('readsTheReleasesOfAnItemFiftyAtATimeWithTheFieldsOfTheDesign', () => {
        expect(argOf(GET_UPSTREAM_RELEASES, 'first')?.value).toEqual(expect.objectContaining({ kind: Kind.INT, value: '50' }))
        for (const name of ['item', 'states', 'after']) expect(argOf(GET_UPSTREAM_RELEASES, name)?.value.kind, name).toBe(Kind.VARIABLE)
        expect(selectedPaths(GET_UPSTREAM_RELEASES)).toEqual([
            'uuid', 'upstreamUuid', 'upstreamVersion', 'upstreamDate', 'state', 'failure', 'message', 'integrity', 'presence', 'goneSince',
            'drift', 'lineKey', 'release',
        ].sort())
    })

    it('answersEachOperationWithTheFollowUuidOnly', () => {
        for (const doc of [START_UPSTREAM_FOLLOW_RUN, RECONFIGURE_UPSTREAM_FOLLOW, UNFOLLOW_UPSTREAM_ITEM]) {
            expect(selectedPaths(doc)).toEqual(['uuid'])
            expect(argOf(doc, 'follow')?.value.kind).toBe(Kind.VARIABLE)
        }
        expect(argOf(RECONFIGURE_UPSTREAM_FOLLOW, 'input')?.value.kind).toBe(Kind.VARIABLE)
    })
})

import { describe, it, expect } from 'vitest'
import { Kind, validate, visit, type DocumentNode, type FieldNode, type GraphQLSchema, type OperationDefinitionNode } from 'graphql'
import {
    ARCHIVE_UPSTREAM_SOURCE, CREATE_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, GET_UPSTREAM_SYNCS, PROBE_UPSTREAM_SOURCE,
    SYNC_UPSTREAM_SOURCE_NOW, UPDATE_UPSTREAM_SOURCE,
} from './upstreamSourceQueries'
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

const ALL: Row[] = [...SOURCE_DOCUMENTS]

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

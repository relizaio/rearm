import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, parse, DocumentNode } from 'graphql'
import graphqlQueries from './graphqlQueries'

// The instance view is fetched in two halves: a core document without
// DeployedRelease.releaseDetails, plus one deferred document per release list
// that carries the details when its tab opens. All of them interpolate shared
// selection sets at module load, so scripts/validate-graphql skips them as
// dynamic. This spec is the guard that keeps each half valid on its own --
// a wrong field in the core fragment would blank the whole instance page,
// and a wrong field in a detail document would blank just that tab, which is
// the harder one to notice.
//
// Checked against the CE schema only, same reasoning as
// releaseFragmentsSchemaDrift.spec.ts: it ships in this repo, and the Pro
// schema is a superset of it for the Instance type.
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))

const ceSchema = buildSchema(readFileSync(CE_SCHEMA_PATH, 'utf8'))

function asInstanceQuery (fragment: string) {
    return parse(`query FragmentCheck($instanceUuid: ID!) {
        instance(instanceUuid: $instanceUuid, revision: -1) {
            ${fragment}
        }
    }`)
}

const INSTANCE_FRAGMENTS: Array<[string, string]> = [
    ['InstanceCoreGqlData', graphqlQueries.InstanceCoreGqlData],
    ['InstanceGqlData', graphqlQueries.InstanceGqlData]
]

const INSTANCE_DOCUMENTS: Array<[string, DocumentNode]> = [
    ['InstanceCoreGql', graphqlQueries.InstanceCoreGql],
    ['InstanceGql', graphqlQueries.InstanceGql],
    ['InstanceReleaseDetailsGql.releases', graphqlQueries.InstanceReleaseDetailsGql.releases],
    ['InstanceReleaseDetailsGql.targetReleases', graphqlQueries.InstanceReleaseDetailsGql.targetReleases]
]

describe('instance selection fragments vs the CE schema', () => {
    it.each(INSTANCE_FRAGMENTS)('%s is valid against the CE schema', (_name, fragment) => {
        expect(validate(ceSchema, asInstanceQuery(fragment)).map(e => e.message)).toEqual([])
    })

    it.each(INSTANCE_DOCUMENTS)('%s is valid against the CE schema', (_name, doc) => {
        expect(validate(ceSchema, doc).map(e => e.message)).toEqual([])
    })

    it('the core document does not ask for releaseDetails -- that is the deferred half', () => {
        const core = graphqlQueries.InstanceCoreGqlData as string
        expect(core).not.toMatch(/releaseDetails\s*\{[\s\S]*?\bartifacts\b/)
        // The product-plan target summary legitimately nests a small
        // releaseDetails under parentReleases; the deferred halves are the
        // DeployedRelease ones, which carry artifacts / sourceCodeEntryDetails.
        expect(core).not.toMatch(/sourceCodeEntryDetails/)
    })

    it('the core document embeds the shared shallow row fragments', () => {
        // updateInstance maps these row fields back into DeployedReleaseInput,
        // so the core document must carry them; the fragments are shared
        // constants, and this pins the core document to them.
        const core = graphqlQueries.InstanceCoreGqlData as string
        expect(core).toContain(graphqlQueries.DeployedReleaseShallowGqlData)
        expect(core).toContain(graphqlQueries.TargetReleaseShallowGqlData)
        for (const field of ['timeSent', 'release', 'namespace', 'properties']) {
            expect(graphqlQueries.DeployedReleaseShallowGqlData).toMatch(new RegExp(`\\b${field}\\b`))
            expect(graphqlQueries.TargetReleaseShallowGqlData).toMatch(new RegExp(`\\b${field}\\b`))
        }
    })

    it('each deferred document selects the row key its details are merged by', () => {
        // InstanceView caches details by the row's release uuid and merges
        // them onto the shallow rows, so `release` must be selected next to
        // `releaseDetails` on the list the document resolves. Walk the AST
        // rather than grepping the source: `release` also appears elsewhere.
        for (const [part, doc] of [['releases', graphqlQueries.InstanceReleaseDetailsGql.releases],
            ['targetReleases', graphqlQueries.InstanceReleaseDetailsGql.targetReleases]] as const) {
            const op = doc.definitions[0]
            if (op.kind !== 'OperationDefinition') throw new Error('expected an operation')
            const instanceField = op.selectionSet.selections.find((sel) => sel.kind === 'Field' && sel.name.value === 'instance')
            if (!instanceField || instanceField.kind !== 'Field' || !instanceField.selectionSet) throw new Error('expected instance field')
            const listField = instanceField.selectionSet.selections.find((sel) => sel.kind === 'Field' && sel.name.value === part)
            if (!listField || listField.kind !== 'Field' || !listField.selectionSet) throw new Error(`expected ${part} field`)
            const names = listField.selectionSet.selections.map((sel) => sel.kind === 'Field' ? sel.name.value : '')
            expect(names).toContain('release')
            expect(names).toContain('releaseDetails')
        }
    })
})

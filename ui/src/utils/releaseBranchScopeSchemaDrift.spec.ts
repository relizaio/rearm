import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { GraphQLObjectType, parse, validate, valueFromAST, type DocumentNode, type GraphQLSchema } from 'graphql'
import { RELEASE_BRANCH_SCOPE } from './releaseBranchScope'
import { enumValuesOf, proSchema } from './schemaDriftSupport'

/**
 * TPF-135 architecture round 2, 4.2 test 9: the two inline search documents (ReleasesByCve.vue and
 * AppHome.vue), read from the sources by their gql literal, validate against Pro, where both fields
 * take branchScope: ReleaseBranchScope = ACTIVE_BRANCHES, and the UI's scope names are the schema's.
 * No CE row: until the CE backend sync the documents are a validate:graphql WARN on CE (test 13).
 */
function inlineDocument (vueFile: string, operation: string): DocumentNode {
    const source = readFileSync(fileURLToPath(new URL(`../components/${vueFile}`, import.meta.url)), 'utf8')
    const literals = [...source.matchAll(/gql`([^`]*)`/g)].map(m => m[1])
        .filter(text => new RegExp(`query\\s+${operation}\\s*\\(`).test(text))
    expect(literals, `${vueFile} holds one ${operation} document`).toHaveLength(1)
    return parse(literals[0])
}

const DOCUMENTS: [string, string][] = [
    ['ReleasesByCve.vue', 'searchReleasesByCveId'],
    ['AppHome.vue', 'releasesBySbomComponents']
]

describe('the branch-scoped search documents against Pro (TPF-135)', () => {
    for (const [file, operation] of DOCUMENTS) {
        it.runIf(proSchema)(`${operation} in ${file} validates and passes branchScope`, () => {
            const pro = proSchema as GraphQLSchema
            const doc = inlineDocument(file, operation)
            expect(validate(pro, doc).map(e => e.message)).toEqual([])
            const op: any = doc.definitions[0]
            expect(op.variableDefinitions.map((v: any) => `${v.variable.name.value}:${v.type.kind === 'NamedType' ? v.type.name.value : ''}`))
                .toContain('branchScope:ReleaseBranchScope')
            const field = op.selectionSet.selections[0]
            expect(field.arguments.map((a: any) => a.name.value)).toContain('branchScope')
        })

        it.runIf(proSchema)(`Pro's ${operation} takes branchScope last, defaulting to ACTIVE_BRANCHES`, () => {
            const pro = proSchema as GraphQLSchema
            const field = (pro.getQueryType() as GraphQLObjectType).getFields()[operation]
            const arg = field.args[field.args.length - 1]
            expect(arg.name).toBe('branchScope')
            expect(String(arg.type)).toBe('ReleaseBranchScope')
            expect(arg.defaultValue ?? (arg.astNode?.defaultValue && valueFromAST(arg.astNode.defaultValue, arg.type)))
                .toBe('ACTIVE_BRANCHES')
        })
    }

    it.runIf(proSchema)('the UI scope names are the Pro enum ReleaseBranchScope', () => {
        expect(enumValuesOf(proSchema as GraphQLSchema, 'ReleaseBranchScope')).toEqual(Object.keys(RELEASE_BRANCH_SCOPE))
    })
})

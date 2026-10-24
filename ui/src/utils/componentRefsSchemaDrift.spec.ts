import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { GraphQLObjectType, print, validate, type GraphQLSchema } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { COMPONENT_FORM_QUERY, COMPONENT_FORM_QUERY_CORE } from './componentRevision'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * TPF-101 test 4 (TPF-74 child 2, ARCHITECTURE round 2 3.1 and 3.8): the org lists' componentRefs
 * document against Pro, and effectiveLifecycle on COMPONENT_FULL_DATA, so ComponentView's lifecycle
 * tag is fed by the page's own full read and by every mutation response. No assertion that the refs
 * document is invalid on CE: that is validate:graphql's [WARN], and a canary that fails the day CE
 * syncs would be one to retire. The fallback documents (inline in store.ts) are validated as sent in
 * componentRefsStore.spec.ts rows 7 and 8.
 */
const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)
const LIFECYCLE_LINE = /^\s*effectiveLifecycle\s*$/m

describe('the componentRefs document and the lifecycle on the full record (TPF-101)', () => {
    it.runIf(proSchema)('(a) the refs document validates against Pro, whose componentRefs has the four arguments', () => {
        const pro = proSchema as GraphQLSchema
        expect(errorsAgainst(pro, graphqlQueries.ComponentRefsGql)).toEqual([])
        const field = (pro.getQueryType() as GraphQLObjectType).getFields().componentRefs
        expect(field).toBeDefined()
        expect(field.args.map(a => a.name)).toEqual(['orgUuid', 'componentType', 'perspective', 'kinds'])
        expect(String(field.type)).toBe('[ComponentRef!]!')
    })

    it('(b) COMPONENT_FULL_DATA, the core form read and the mutation select effectiveLifecycle and stay valid on CE', () => {
        const queries = readFileSync(fileURLToPath(new URL('./graphqlQueries.ts', import.meta.url)), 'utf8')
        const fragment = queries.slice(queries.indexOf('const COMPONENT_FULL_DATA'), queries.indexOf('const COMPONENT_MUTATE'))
            .split('\n').filter(l => !l.trim().startsWith('#')).join('\n')
        expect(fragment.length).toBeGreaterThan(0)
        expect(fragment).toMatch(LIFECYCLE_LINE)
        expect(print(COMPONENT_FORM_QUERY_CORE)).toMatch(LIFECYCLE_LINE)
        expect(print(graphqlQueries.ComponentMutate)).toMatch(LIFECYCLE_LINE)
        expect(errorsAgainst(ceSchema, COMPONENT_FORM_QUERY_CORE)).toEqual([])
        expect(errorsAgainst(ceSchema, graphqlQueries.ComponentMutate)).toEqual([])
        expect(String((ceSchema.getType('Component') as GraphQLObjectType).getFields().effectiveLifecycle?.type)).toBe('ReleaseLifecycleEnum')
    })

    it.runIf(proSchema)('(b) on Pro the field has the same type and both form documents validate', () => {
        const pro = proSchema as GraphQLSchema
        expect(String((pro.getType('Component') as GraphQLObjectType).getFields().effectiveLifecycle?.type)).toBe('ReleaseLifecycleEnum')
        expect(errorsAgainst(pro, COMPONENT_FORM_QUERY)).toEqual([])
        expect(errorsAgainst(pro, COMPONENT_FORM_QUERY_CORE)).toEqual([])
        expect(errorsAgainst(pro, graphqlQueries.ComponentMutate)).toEqual([])
        expect(print(COMPONENT_FORM_QUERY)).toMatch(LIFECYCLE_LINE)
    })
})

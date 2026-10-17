import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { GraphQLObjectType, GraphQLInputObjectType, print, validate, type GraphQLSchema } from 'graphql'
import { COMPONENT_FORM_QUERY, COMPONENT_FORM_QUERY_CORE } from './componentRevision'
import { ceSchema, proSchema } from './schemaDriftSupport'

/**
 * The settings page's component read with the revision (TPF-62), against both schemas.
 *
 * <p>The revision is read through its own document, never through COMPONENT_FULL_DATA: that
 * fragment is the updateComponent and createComponent response selection, and a backend
 * without Component.revision (the CE mirror today, an older Pro build) would reject the whole
 * mutation and break every component save. No assertion that CE lacks the field: such a canary
 * breaks the build on the day CE syncs (deviceWindowSchemaDrift.spec.ts retired its own).
 */
const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)

describe('the component form read', () => {
    it('the core document validates against the CE mirror', () => {
        expect(errorsAgainst(ceSchema, COMPONENT_FORM_QUERY_CORE)).toEqual([])
    })

    it.runIf(proSchema)('both documents validate against Pro, which serves the revision and takes it back', () => {
        const pro = proSchema as GraphQLSchema
        expect(errorsAgainst(pro, COMPONENT_FORM_QUERY)).toEqual([])
        expect(errorsAgainst(pro, COMPONENT_FORM_QUERY_CORE)).toEqual([])
        const component = pro.getType('Component') as GraphQLObjectType
        expect(String(component.getFields().revision?.type)).toBe('Int')
        const input = pro.getType('UpdateComponentInput') as GraphQLInputObjectType
        expect(String(input.getFields().expectedRevision?.type)).toBe('Int')
    })

    it('only the full document selects the revision, and the mutation fragment does not', () => {
        expect(print(COMPONENT_FORM_QUERY_CORE)).not.toMatch(/\brevision\b/)
        expect(print(COMPONENT_FORM_QUERY)).toMatch(/^\s*revision\s*$/m)
        const queries = readFileSync(fileURLToPath(new URL('./graphqlQueries.ts', import.meta.url)), 'utf8')
        const fragment = queries.slice(queries.indexOf('const COMPONENT_FULL_DATA'), queries.indexOf('const COMPONENT_MUTATE'))
            .split('\n').filter(l => !l.trim().startsWith('#')).join('\n')
        expect(fragment.length).toBeGreaterThan(0)
        expect(fragment).not.toMatch(/^\s*revision\s*$/m)
    })
})

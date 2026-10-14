import { describe, expect, it } from 'vitest'
import { getNamedType, validate, type GraphQLSchema } from 'graphql'
import { ADD_PERSPECTIVE_TO_COMPONENT } from './perspectiveMembership'
import { proSchema } from './schemaDriftSupport'

/**
 * The members dialog's add (TPF-41) against the Pro schema, which serves it since TPF-40.
 *
 * <p>No assertion about the CE mirror: the dialog is Pro-only, and a "CE lacks it" canary breaks
 * the build on the day CE syncs (deviceWindowSchemaDrift.spec.ts retired its own).
 */
describe('the perspective members add', () => {
    it.runIf(proSchema)('validates against Pro, which declares the field with two ID! arguments answering a Component', () => {
        const pro = proSchema as GraphQLSchema
        expect(validate(pro, ADD_PERSPECTIVE_TO_COMPONENT).map(e => e.message)).toEqual([])
        const field = pro.getMutationType()?.getFields().addPerspectiveToComponent
        expect(field).toBeDefined()
        expect(field!.args.map(a => [a.name, String(a.type)]))
            .toEqual([['componentUuid', 'ID!'], ['perspectiveUuid', 'ID!']])
        expect(getNamedType(field!.type).name).toBe('Component')
    })
})

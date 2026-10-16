import { describe, it, expect } from 'vitest'
import { Kind, print, type OperationDefinitionNode, type FieldNode } from 'graphql'
import { ADD_PERSPECTIVE_TO_COMPONENT, addPerspectiveToComponent } from './perspectiveMembership'

/**
 * The Org Settings members dialog's write (TPF-41): one component and one perspective, never a
 * replacement list. The fake client records every mutate call, so the assertions are on the exact
 * document and variables the dialog sends.
 */
function client (answer: any, throws?: any) {
    const calls: any[] = []
    return {
        calls,
        query: async () => { throw new Error('the add sends no query') },
        mutate: async (opts: any) => { calls.push(opts); if (throws) throw throws; return answer }
    }
}

const collapsed = (s: string) => s.replace(/\s+/g, ' ').trim()

describe('addPerspectiveToComponent', () => {
    it('sends the add document with one component and one perspective and returns the answered component', async () => {
        const component = {
            uuid: 'c1',
            perspectiveDetails: [
                { uuid: 'pA', name: 'A', org: 'o', createdDate: 'd' },
                { uuid: 'p1', name: 'B', org: 'o', createdDate: 'd' }
            ]
        }
        const c = client({ data: { addPerspectiveToComponent: component } })
        const result = await addPerspectiveToComponent(c, 'c1', 'p1')
        expect(c.calls).toHaveLength(1)
        const call = c.calls[0]
        expect(print(call.mutation)).toBe(print(ADD_PERSPECTIVE_TO_COMPONENT))
        expect(call.variables).toStrictEqual({ componentUuid: 'c1', perspectiveUuid: 'p1' })
        expect(call).not.toHaveProperty('fetchPolicy')
        expect(result).toEqual(component)
    })

    it.each([
        ['data without the field', { data: {} }],
        ['the field answered null', { data: { addPerspectiveToComponent: null } }],
        ['no data at all', {}]
    ])('gives null on %s', async (_label, answer) => {
        expect(await addPerspectiveToComponent(client(answer), 'c1', 'p1')).toBeNull()
    })

    it('lets a refusal propagate unchanged', async () => {
        const c = client(undefined, new Error('Perspective not found: p1'))
        await expect(addPerspectiveToComponent(c, 'c1', 'p1')).rejects.toThrow('Perspective not found: p1')
    })
})

describe('the add document', () => {
    it('is one mutation, addPerspectiveToComponent, over two ID! variables', () => {
        const defs = ADD_PERSPECTIVE_TO_COMPONENT.definitions
        expect(defs).toHaveLength(1)
        const op = defs[0] as OperationDefinitionNode
        expect(op.kind).toBe(Kind.OPERATION_DEFINITION)
        expect(op.operation).toBe('mutation')
        expect(op.name?.value).toBe('addPerspectiveToComponent')
        expect((op.variableDefinitions ?? []).map(v => [v.variable.name.value, print(v.type)]))
            .toEqual([['componentUuid', 'ID!'], ['perspectiveUuid', 'ID!']])
        const fields = op.selectionSet.selections as FieldNode[]
        expect(fields).toHaveLength(1)
        expect(fields[0].name.value).toBe('addPerspectiveToComponent')
        expect((fields[0].arguments ?? []).map(a => `${a.name.value}: ${print(a.value)}`))
            .toEqual(['componentUuid: $componentUuid', 'perspectiveUuid: $perspectiveUuid'])
    })

    it('selects what the dialog selected before, and names no replacement list', () => {
        const printed = print(ADD_PERSPECTIVE_TO_COMPONENT)
        // print() wraps a long argument list over lines, so the arguments are pinned structurally above
        // and the selection is compared on the root field's own selection set.
        const root = (ADD_PERSPECTIVE_TO_COMPONENT.definitions[0] as OperationDefinitionNode).selectionSet.selections[0] as FieldNode
        expect(collapsed(print(root.selectionSet!))).toBe('{ uuid perspectiveDetails { uuid name org createdDate } }')
        expect(printed).not.toMatch(/setPerspectivesOnComponent|perspectiveUuids/)
    })
})

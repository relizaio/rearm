import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Kind, OperationDefinitionNode, FieldNode } from 'graphql'

// TPF-78 test 6 (TPF-73 part 2, ARCHITECTURE round 2 3.2): the store action archiveBranches runs
// the bulk mutation, awaits a forced branch refresh and returns the response. The GraphQL client
// and keycloak are mocked so the store module loads under node.
const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))

const store = (await import('@/store')).default.store
const graphqlQueries = (await import('@/utils/graphqlQueries')).default

const RESPONSE = {
    results: [{ branchUuid: 'u1', outcome: 'ARCHIVED', message: null }, { branchUuid: 'u2', outcome: 'FORBIDDEN', message: null }],
    archivedCount: 1, skippedCount: 0, failedCount: 1
}
const REFRESHED = [{ uuid: 'b0', name: 'main', type: 'BASE', component: 'c' }, { uuid: 'u2', name: 'locked', type: 'REGULAR', component: 'c' }]

/** The selection set of a field, as names with their nested selections. */
function shape (field: FieldNode): any {
    return (field.selectionSet?.selections || []).map((s: any) => s.selectionSet ? { [s.name.value]: shape(s) } : s.name.value)
}

describe('store action archiveBranches', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        store.state.branches = [
            { uuid: 'b0', name: 'main', type: 'BASE', component: 'c' },
            { uuid: 'u1', name: 'old', type: 'REGULAR', component: 'c' },
            { uuid: 'u2', name: 'locked', type: 'REGULAR', component: 'c' }
        ]
    })

    it('sends the mutation, awaits the forced refresh and returns the response', async () => {
        mutate.mockResolvedValue({ data: { archiveBranches: RESPONSE } })
        let release: (v: any) => void = () => {}
        query.mockReturnValue(new Promise((resolve) => { release = resolve }))

        let settled = false
        const done = store.dispatch('archiveBranches', { branchUuids: ['u1', 'u2'], componentUuid: 'c' }).then((r: any) => { settled = true; return r })
        await vi.waitFor(() => expect(query).toHaveBeenCalledTimes(1))

        expect(mutate).toHaveBeenCalledTimes(1)
        const call = mutate.mock.calls[0][0]
        expect(call.variables).toEqual({ branchUuids: ['u1', 'u2'] })
        const operations = call.mutation.definitions.filter((d: any) => d.kind === Kind.OPERATION_DEFINITION) as OperationDefinitionNode[]
        expect(operations).toHaveLength(1)
        expect(operations[0].operation).toBe('mutation')
        expect(operations[0].name?.value).toBe('archiveBranches')
        const fields = operations[0].selectionSet.selections as FieldNode[]
        expect(fields).toHaveLength(1)
        expect(fields[0].name.value).toBe('archiveBranches')
        expect(fields[0].arguments?.map((a: any) => [a.name.value, a.value.kind, a.value.name?.value])).toEqual([['branchUuids', Kind.VARIABLE, 'branchUuids']])
        expect(shape(fields[0])).toEqual([{ results: ['branchUuid', 'outcome', 'message'] }, 'archivedCount', 'skippedCount', 'failedCount'])

        const refresh = query.mock.calls[0][0]
        expect(refresh.query).toBe(graphqlQueries.BranchesGql)
        expect(refresh.variables.componentUuid).toBe('c')
        expect(refresh.fetchPolicy).toBe('network-only')

        await Promise.resolve()
        await Promise.resolve()
        expect(settled).toBe(false)
        release({ data: { branchesOfComponent: REFRESHED } })
        expect(await done).toEqual(RESPONSE)
        expect(store.getters.branchesOfComponent('c')).toEqual(REFRESHED)
    })

    it('rejects on a thrown mutation and does not refresh', async () => {
        mutate.mockRejectedValue(new Error('System is sealed'))
        await expect(store.dispatch('archiveBranches', { branchUuids: ['u1'], componentUuid: 'c' })).rejects.toThrow('System is sealed')
        expect(query).not.toHaveBeenCalled()
        expect(store.getters.branchesOfComponent('c').map((b: any) => b.uuid)).toEqual(['b0', 'u1', 'u2'])
    })
})

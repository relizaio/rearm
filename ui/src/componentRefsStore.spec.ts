import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Kind, validate, type DocumentNode, type FieldNode, type OperationDefinitionNode, type GraphQLSchema } from 'graphql'
import { toRaw } from 'vue'
import { ceSchema, proSchema } from '@/utils/schemaDriftSupport'

// TPF-101 test 1 (TPF-74 child 2, ARCHITECTURE round 2 3.2-3.5): the organization's component and
// product lists read the componentRefs document into the one component cache (refs as the floor,
// merged by uuid), fall back to the actions' own inline components documents on a schema-drift
// refusal only, and latch the session onto them only once the fallback was served. The GraphQL
// client and keycloak are mocked so the store module loads under node (the storeArchiveBranches rig).
const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))

const store = (await import('@/store')).default.store
const graphqlQueries = (await import('@/utils/graphqlQueries')).default

const REFS: any[] = Object.freeze([
    Object.freeze({ uuid: 'c1', name: 'beta', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: 'V' }),
    Object.freeze({ uuid: 'c2', name: 'Alpha', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: null })
]) as any
const FULL: any[] = Object.freeze([
    Object.freeze({ uuid: 'f1', name: 'full-one', org: 'O', resourceGroup: null, type: 'COMPONENT', versionSchema: 'semver',
        featureBranchVersioning: null, vcs: 'V', identifiers: [], effectiveLifecycle: 'GENERAL_AVAILABILITY' }),
    Object.freeze({ uuid: 'f2', name: 'full-two', org: 'O', resourceGroup: null, type: 'COMPONENT', versionSchema: 'semver',
        featureBranchVersioning: null, vcs: null, identifiers: [], effectiveLifecycle: null })
]) as any

const COMPONENT_SHORT_SHAPE = ['uuid', 'name', 'org', 'resourceGroup', 'type', 'versionSchema',
    'featureBranchVersioning', 'vcs', { identifiers: ['idType', 'idValue'] }, 'effectiveLifecycle']

// --- error fixtures, the graphqlDriftFallback.spec.ts shapes ---------------------------------
function drift (field = 'componentRefs'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}
function opaque400 (): any {
    const err: any = new Error('Response not successful: Received status code 400')
    err.statusCode = 400
    err.bodyText = '<html><body>Your request has returned an error.</body></html>'
    return err
}
function status (code: number): any {
    const err: any = new Error(`Response not successful: Received status code ${code}`)
    err.statusCode = code
    return err
}
function transport (): any {
    return new Error('Failed to fetch')
}

/** The selection set of a field, as names with their nested selections. */
function shape (field: FieldNode): any {
    return (field.selectionSet?.selections || []).map((s: any) => s.selectionSet ? { [s.name.value]: shape(s) } : s.name.value)
}
function operation (doc: DocumentNode): OperationDefinitionNode {
    const ops = doc.definitions.filter(d => d.kind === Kind.OPERATION_DEFINITION) as OperationDefinitionNode[]
    expect(ops).toHaveLength(1)
    return ops[0]
}
function rootField (doc: DocumentNode): FieldNode {
    const op = operation(doc)
    expect(op.selectionSet.selections).toHaveLength(1)
    return op.selectionSet.selections[0] as FieldNode
}
/** argument name -> the variable it is bound to */
function argBindings (field: FieldNode): Record<string, string> {
    return Object.fromEntries((field.arguments || []).map((a: any) => [a.name.value, a.value.kind === Kind.VARIABLE ? '$' + a.value.name.value : 'literal']))
}
function declaredVariables (doc: DocumentNode): string[] {
    return (operation(doc).variableDefinitions || []).map(v => v.variable.name.value)
}
const call = (i: number) => query.mock.calls[i][0]
const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)

describe('the org component and product lists read componentRefs into one cache (TPF-101)', () => {
    beforeEach(() => {
        query.mockReset()
        store.state.components = []
        store.state.componentRefsSupported = true
        store.state.iam.perspectiveUuid = 'default'
    })
    afterEach(() => {
        vi.restoreAllMocks()
    })

    it('row 1: fetchComponents sends ComponentRefs with the software kinds and fills the getters', async () => {
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        const rows = await store.dispatch('fetchComponents', 'O')
        expect(query).toHaveBeenCalledTimes(1)
        const c = call(0)
        expect(c.query).toBe(graphqlQueries.ComponentRefsGql)
        expect(c.variables).toEqual({ orgUuid: 'O', componentType: 'COMPONENT', kinds: ['GENERIC', 'HELM'] })
        expect('perspective' in c.variables).toBe(false)
        expect(c.fetchPolicy).toBe('cache-first')
        const op = operation(c.query)
        expect(op.operation).toBe('query')
        expect(op.name?.value).toBe('ComponentRefs')
        const field = rootField(c.query)
        expect(field.name.value).toBe('componentRefs')
        expect(argBindings(field)).toEqual({ orgUuid: '$orgUuid', componentType: '$componentType', perspective: '$perspective', kinds: '$kinds' })
        expect(shape(field)).toEqual(['uuid', 'name', 'type', 'status', 'org', 'vcs'])
        expect(rows).toBe(REFS)
        expect(store.getters.componentsOfOrg('O').map((r: any) => r.uuid)).toEqual(['c2', 'c1'])
        expect(store.getters.componentById('c1').vcs).toBe('V')
    })

    it('row 2: forceRefresh sends network-only, an object payload without it cache-first', async () => {
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        await store.dispatch('fetchComponents', { orgid: 'O', forceRefresh: true })
        expect(call(0).query).toBe(graphqlQueries.ComponentRefsGql)
        expect(call(0).fetchPolicy).toBe('network-only')
        await store.dispatch('fetchComponents', { orgid: 'O' })
        expect(call(1).query).toBe(graphqlQueries.ComponentRefsGql)
        expect(call(1).fetchPolicy).toBe('cache-first')
    })

    it('row 3: a selected perspective is sent, the default one is not', async () => {
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        store.state.iam.perspectiveUuid = 'P'
        await store.dispatch('fetchComponents', 'O')
        expect(call(0).variables.perspective).toBe('P')
        store.state.iam.perspectiveUuid = 'default'
        await store.dispatch('fetchComponents', 'O')
        expect('perspective' in call(1).variables).toBe(false)
    })

    it('row 4: fetchProducts sends ComponentRefs without kinds; the getters split by type and drop ARCHIVED', async () => {
        const p1 = Object.freeze({ uuid: 'p1', name: 'Shop', type: 'PRODUCT', status: 'ACTIVE', org: 'O', vcs: null })
        const pArchived = Object.freeze({ uuid: 'p2', name: 'Gone', type: 'PRODUCT', status: 'ARCHIVED', org: 'O', vcs: null })
        const cArchived = Object.freeze({ uuid: 'c3', name: 'Gone too', type: 'COMPONENT', status: 'ARCHIVED', org: 'O', vcs: null })
        query.mockResolvedValueOnce({ data: { componentRefs: [...REFS, cArchived] } })
        await store.dispatch('fetchComponents', 'O')
        query.mockResolvedValueOnce({ data: { componentRefs: [p1, pArchived] } })
        await store.dispatch('fetchProducts', 'O')
        const c = call(1)
        expect(c.query).toBe(graphqlQueries.ComponentRefsGql)
        expect(c.variables).toEqual({ orgUuid: 'O', componentType: 'PRODUCT' })
        expect('kinds' in c.variables).toBe(false)
        expect(c.fetchPolicy).toBe('cache-first')
        expect(store.getters.productsOfOrg('O').map((r: any) => r.uuid)).toEqual(['p1'])
        expect(store.getters.componentsOfOrg('O').map((r: any) => r.uuid)).toEqual(['c2', 'c1'])
        expect(store.getters.componentsOfOrg('O').some((r: any) => r.uuid === 'p1')).toBe(false)
    })

    it('row 5: the getters serve VcsReposOfOrg from refs (its vcs filter)', async () => {
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        await store.dispatch('fetchComponents', 'O')
        expect(store.getters.componentsOfOrg('O').filter((c: any) => c.vcs === 'V').map((c: any) => c.uuid)).toEqual(['c1'])
    })

    it('row 6: SET_COMPONENTS_OF_ORG merges by uuid: the row wins its fields, the entry keeps the rest', async () => {
        const q1 = { uuid: 'q1', name: 'q', org: 'Q', type: 'COMPONENT' }
        const p9 = { uuid: 'p9', name: 'p', org: 'O', type: 'PRODUCT' }
        store.state.components = [
            { uuid: 'c1', name: 'old', org: 'O', type: 'COMPONENT', versionSchema: 'semver', identifiers: [] },
            { uuid: 'x1', name: 'x', org: 'O', type: 'COMPONENT' },
            q1,
            p9
        ]
        // the stored (reactive) objects, so identity is checked as the store hands them out
        const q1Stored = store.state.components[2]
        const p9Stored = store.state.components[3]
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        await store.dispatch('fetchComponents', 'O')
        const c1s = store.state.components.filter((c: any) => c.uuid === 'c1')
        expect(c1s).toHaveLength(1)
        expect(c1s[0]).toMatchObject({ name: 'beta', status: 'ACTIVE', vcs: 'V', versionSchema: 'semver', identifiers: [] })
        expect(store.state.components.some((c: any) => c.uuid === 'x1')).toBe(false)
        expect(store.state.components.find((c: any) => c.uuid === 'q1')).toBe(q1Stored)
        expect(store.state.components.find((c: any) => c.uuid === 'p9')).toBe(p9Stored)
        expect(toRaw(q1Stored)).toBe(q1)
        expect(toRaw(p9Stored)).toBe(p9)
        expect(store.state.components.filter((c: any) => c.uuid === 'q1')).toHaveLength(1)
        expect(store.state.components.some((c: any) => c.uuid === 'c2')).toBe(true)
        expect(Object.isFrozen(REFS[0])).toBe(true)
        expect(REFS[0]).toEqual({ uuid: 'c1', name: 'beta', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: 'V' })

        store.commit('ADD_UPDATE_COMPONENT', { uuid: 'c1', name: 'beta', org: 'O', type: 'COMPONENT', versionSchema: 'calver' })
        await store.dispatch('fetchComponents', 'O')
        expect(store.getters.componentById('c1')).toMatchObject({ versionSchema: 'calver', status: 'ACTIVE' })
        expect(store.state.components.filter((c: any) => c.uuid === 'c1')).toHaveLength(1)
    })

    describe('the schema-drift fallback and the latch', () => {
        async function assertFallbackServed (firstRejection: any) {
            query.mockRejectedValueOnce(firstRejection).mockResolvedValueOnce({ data: { components: FULL } })
            const rows = await store.dispatch('fetchComponents', { orgid: 'O', forceRefresh: true })
            expect(rows).toBe(FULL)
            expect(query).toHaveBeenCalledTimes(2)
            expect(call(0).query).toBe(graphqlQueries.ComponentRefsGql)
            const sent = call(1).query
            const op = operation(sent)
            expect(op.operation).toBe('query')
            expect(op.name?.value).toBe('FetchComponents')
            const field = rootField(sent)
            expect(field.name.value).toBe('components')
            expect(argBindings(field)).toEqual({ orgUuid: '$orgUuid', componentType: '$componentType', perspective: '$perspective', kinds: '$kinds' })
            expect(shape(field)).toEqual(COMPONENT_SHORT_SHAPE)
            expect(errorsAgainst(ceSchema, sent)).toEqual([])
            expect(call(1).variables).toEqual(call(0).variables)
            expect(call(1).fetchPolicy).toBe('network-only')
            expect(store.state.componentRefsSupported).toBe(false)
            expect(store.getters.componentsOfOrg('O').map((r: any) => r.uuid)).toEqual(['f1', 'f2'])
        }

        it('row 7: a validation verdict falls back to the inline components document, then latches', async () => {
            const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
            await assertFallbackServed(drift())
            expect(spy).toHaveBeenCalledTimes(1)
            spy.mockRestore()
        })

        it.runIf(proSchema)('row 7: the fallback document as sent validates on Pro too', async () => {
            vi.spyOn(console, 'error').mockImplementation(() => {})
            query.mockRejectedValueOnce(drift()).mockResolvedValueOnce({ data: { components: FULL } })
            await store.dispatch('fetchComponents', 'O')
            expect(errorsAgainst(proSchema as GraphQLSchema, call(1).query)).toEqual([])
            expect(errorsAgainst(proSchema as GraphQLSchema, call(0).query)).toEqual([])
        })

        it('row 7: an opaque HTTP 400 (the nginx case) falls back the same way', async () => {
            vi.spyOn(console, 'error').mockImplementation(() => {})
            await assertFallbackServed(opaque400())
        })

        it('row 7: nothing is committed before the fallback answered', async () => {
            vi.spyOn(console, 'error').mockImplementation(() => {})
            let release: (v: any) => void = () => {}
            query.mockRejectedValueOnce(drift()).mockReturnValueOnce(new Promise((r) => { release = r }))
            const pending = store.dispatch('fetchComponents', 'O')
            await Promise.resolve()
            await Promise.resolve()
            expect(store.state.componentRefsSupported).toBe(true)
            release({ data: { components: FULL } })
            await pending
            expect(store.state.componentRefsSupported).toBe(false)
        })

        it('row 8: once latched, both actions send their inline documents only', async () => {
            vi.spyOn(console, 'error').mockImplementation(() => {})
            query.mockRejectedValueOnce(drift()).mockResolvedValueOnce({ data: { components: FULL } })
            await store.dispatch('fetchComponents', { orgid: 'O', forceRefresh: true })
            expect(store.state.componentRefsSupported).toBe(false)
            query.mockReset()
            query.mockResolvedValue({ data: { components: FULL } })

            await store.dispatch('fetchComponents', 'O')
            expect(query).toHaveBeenCalledTimes(1)
            const comp = call(0)
            expect(comp.query).not.toBe(graphqlQueries.ComponentRefsGql)
            expect(operation(comp.query).name?.value).toBe('FetchComponents')
            expect(rootField(comp.query).name.value).toBe('components')
            expect(declaredVariables(comp.query)).toContain('kinds')
            expect(comp.fetchPolicy).toBe('cache-first')

            await store.dispatch('fetchProducts', 'O')
            expect(query).toHaveBeenCalledTimes(2)
            const prod = call(1)
            expect(prod.query).not.toBe(graphqlQueries.ComponentRefsGql)
            expect(operation(prod.query).name?.value).toBe('FetchComponents')
            const field = rootField(prod.query)
            expect(field.name.value).toBe('components')
            expect(argBindings(field)).toEqual({ orgUuid: '$orgUuid', componentType: '$componentType', perspective: '$perspective' })
            expect(declaredVariables(prod.query)).not.toContain('kinds')
            expect(shape(field)).toEqual(COMPONENT_SHORT_SHAPE)
            expect(errorsAgainst(ceSchema, prod.query)).toEqual([])
            expect(prod.variables).toEqual({ orgUuid: 'O', componentType: 'PRODUCT' })
            expect(prod.fetchPolicy).toBe('cache-first')
            expect(query.mock.calls.some((a: any[]) => a[0].query === graphqlQueries.ComponentRefsGql)).toBe(false)
        })

        it('row 9: a refusal that is not drift is rethrown, with no fallback and the latch untouched', async () => {
            for (const err of [status(500), status(401), status(403), transport()]) {
                query.mockReset()
                store.state.components = [{ uuid: 'k', name: 'k', org: 'O', type: 'COMPONENT' }]
                query.mockRejectedValueOnce(err)
                await expect(store.dispatch('fetchComponents', 'O')).rejects.toBe(err)
                expect(query).toHaveBeenCalledTimes(1)
                expect(call(0).query).toBe(graphqlQueries.ComponentRefsGql)
                expect(store.state.componentRefsSupported).toBe(true)
                expect(store.state.components.map((c: any) => c.uuid)).toEqual(['k'])
            }
        })

        it('row 10: drift then a failed fallback rethrows the fallback error and remembers nothing', async () => {
            const cases: Array<[any, any]> = [[drift(), status(500)], [opaque400(), opaque400()]]
            for (const [first, second] of cases) {
                query.mockReset()
                store.state.componentRefsSupported = true
                store.state.components = [{ uuid: 'k', name: 'k', org: 'O', type: 'COMPONENT' }]
                query.mockRejectedValueOnce(first).mockRejectedValueOnce(second)
                await expect(store.dispatch('fetchComponents', 'O')).rejects.toBe(second)
                expect(query).toHaveBeenCalledTimes(2)
                expect(store.state.componentRefsSupported).toBe(true)
                expect(store.state.components.map((c: any) => c.uuid)).toEqual(['k'])

                query.mockResolvedValueOnce({ data: { componentRefs: REFS } })
                const rows = await store.dispatch('fetchComponents', 'O')
                expect(query).toHaveBeenCalledTimes(3)
                expect(call(2).query).toBe(graphqlQueries.ComponentRefsGql)
                expect(rows).toBe(REFS)
                expect(store.state.componentRefsSupported).toBe(true)
            }
        })

        it('row 10: already latched, a failed full document is rethrown and the latch stays false', async () => {
            store.state.componentRefsSupported = false
            const err = status(500)
            query.mockRejectedValueOnce(err)
            await expect(store.dispatch('fetchComponents', 'O')).rejects.toBe(err)
            expect(query).toHaveBeenCalledTimes(1)
            expect(call(0).query).not.toBe(graphqlQueries.ComponentRefsGql)
            expect(rootField(call(0).query).name.value).toBe('components')
            expect(store.state.componentRefsSupported).toBe(false)
        })
    })

    describe('the effective lifecycle on the cache entry (round 2, F-1)', () => {
        it('row 11: a full record keeps effectiveLifecycle across refs refreshes', async () => {
            store.commit('ADD_UPDATE_COMPONENT', { uuid: 'c1', name: 'beta', org: 'O', type: 'COMPONENT', versionType: 'REGULAR', effectiveLifecycle: 'GENERAL_AVAILABILITY' })
            query.mockResolvedValueOnce({ data: { componentRefs: REFS } })
            await store.dispatch('fetchComponents', 'O')
            expect(store.getters.componentById('c1')).toMatchObject({
                effectiveLifecycle: 'GENERAL_AVAILABILITY', versionType: 'REGULAR', name: 'beta', status: 'ACTIVE', vcs: 'V'
            })
            const renamed = [Object.freeze({ ...REFS[0], name: 'gamma' }), REFS[1]]
            query.mockResolvedValueOnce({ data: { componentRefs: renamed } })
            await store.dispatch('fetchComponents', { orgid: 'O', forceRefresh: true })
            expect(store.getters.componentById('c1').name).toBe('gamma')
            expect(store.getters.componentById('c1').effectiveLifecycle).toBe('GENERAL_AVAILABILITY')
        })

        it('row 12: the refs document never supplies effectiveLifecycle', async () => {
            store.commit('ADD_UPDATE_COMPONENT', { uuid: 'c1', name: 'beta', org: 'O', type: 'COMPONENT', versionType: 'REGULAR' })
            query.mockResolvedValueOnce({ data: { componentRefs: REFS } })
            await store.dispatch('fetchComponents', 'O')
            expect(store.getters.componentById('c1').effectiveLifecycle).toBeUndefined()
            expect(store.getters.componentById('c1').versionType).toBe('REGULAR')
        })
    })
})

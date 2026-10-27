import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Kind, print, type DocumentNode, type OperationDefinitionNode } from 'graphql'

// TPF-101, mutation round: the cases the first test round leaves open (TPF-74 child 2). A fresh session
// sends the refs document first; a list answered with null is an empty list; a latched call logs
// nothing; the cache merge also lays a row over an entry that lacks org or type, keeps the order of
// the rows it is given, and never doubles an entry; the product list returns its rows and honours
// forceRefresh; the refs document declares exactly the variables the backend field takes. The GraphQL
// client and keycloak are mocked as in componentRefsStore.spec.ts, which this file does not share
// state with (its own module instance, so the first test sees the store as a page load leaves it).
const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))

const store = (await import('@/store')).default.store
const graphqlQueries = (await import('@/utils/graphqlQueries')).default

const REFS: any[] = Object.freeze([
    Object.freeze({ uuid: 'c1', name: 'beta', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: 'V' }),
    Object.freeze({ uuid: 'c2', name: 'Alpha', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: null })
]) as any
const PRODUCTS: any[] = Object.freeze([
    Object.freeze({ uuid: 'p1', name: 'Shop', type: 'PRODUCT', status: 'ACTIVE', org: 'O', vcs: null })
]) as any

const call = (i: number) => query.mock.calls[i][0]

function operation (doc: DocumentNode): OperationDefinitionNode {
    return doc.definitions.filter(d => d.kind === Kind.OPERATION_DEFINITION)[0] as OperationDefinitionNode
}

describe('a fresh session (the store as a page load leaves it)', () => {
    it('sends the refs document first, with nothing latched before any call', async () => {
        expect(store.state.componentRefsSupported).toBe(true)
        query.mockReset()
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        await store.dispatch('fetchComponents', 'O')
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.ComponentRefsGql)
        expect(store.state.componentRefsSupported).toBe(true)
    })
})

describe('the org lists, the cases the first round leaves open (TPF-101)', () => {
    beforeEach(() => {
        query.mockReset()
        store.state.components = []
        store.state.componentRefsSupported = true
        store.state.iam.perspectiveUuid = 'default'
    })
    afterEach(() => {
        vi.restoreAllMocks()
    })

    it('a refs answer of null is an empty list, and so is a null components answer on the full document', async () => {
        query.mockResolvedValueOnce({ data: { componentRefs: null } })
        expect(await store.dispatch('fetchComponents', 'O')).toEqual([])
        expect(store.getters.componentsOfOrg('O')).toEqual([])

        query.mockResolvedValueOnce({ data: { componentRefs: null } })
        expect(await store.dispatch('fetchProducts', 'O')).toEqual([])

        store.state.componentRefsSupported = false
        query.mockResolvedValueOnce({ data: { components: null } })
        expect(await store.dispatch('fetchComponents', 'O')).toEqual([])
        query.mockResolvedValueOnce({ data: { components: null } })
        expect(await store.dispatch('fetchProducts', 'O')).toEqual([])
        expect(store.state.componentRefsSupported).toBe(false)
    })

    it('once latched, a served full document logs nothing and commits nothing about the latch', async () => {
        const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
        const commit = vi.spyOn(store, 'commit')
        store.state.componentRefsSupported = false
        query.mockResolvedValue({ data: { components: [{ uuid: 'f1', name: 'full', org: 'O', type: 'COMPONENT' }] } })
        await store.dispatch('fetchComponents', 'O')
        await store.dispatch('fetchProducts', 'O')
        expect(spy).not.toHaveBeenCalled()
        expect(commit.mock.calls.some((c: any[]) => c[0] === 'SET_COMPONENT_REFS_SUPPORTED')).toBe(false)
    })

    it('a served refs document logs nothing and commits nothing about the latch either', async () => {
        const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
        const commit = vi.spyOn(store, 'commit')
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        await store.dispatch('fetchComponents', 'O')
        expect(spy).not.toHaveBeenCalled()
        expect(commit.mock.calls.some((c: any[]) => c[0] === 'SET_COMPONENT_REFS_SUPPORTED')).toBe(false)
        expect(store.state.componentRefsSupported).toBe(true)
    })

    it('the cache keeps the order it is given: the entries of other orgs and types first, then the rows as sent', async () => {
        const q1 = { uuid: 'q1', name: 'q', org: 'Q', type: 'COMPONENT' }
        const p9 = { uuid: 'p9', name: 'p', org: 'O', type: 'PRODUCT' }
        store.state.components = [
            { uuid: 'c1', name: 'old', org: 'O', type: 'COMPONENT' },
            q1,
            { uuid: 'x1', name: 'x', org: 'O', type: 'COMPONENT' },
            p9
        ]
        query.mockResolvedValue({ data: { componentRefs: REFS } })
        await store.dispatch('fetchComponents', 'O')
        expect(store.state.components.map((c: any) => c.uuid)).toEqual(['q1', 'p9', 'c1', 'c2'])
    })

    it('a row is laid over a cached entry of its uuid that lacks org or type, and the entry is not doubled', async () => {
        store.state.components = [
            { uuid: 'c1', name: 'old', versionSchema: 'semver' },                     // neither org nor type
            { uuid: 'c2', name: 'old2', org: 'O', identifiers: [{ idType: 'x', idValue: 'y' }] },  // no type
            { uuid: 'c3', name: 'old3', type: 'COMPONENT', versionType: 'REGULAR' }                // no org
        ]
        const rows = [...REFS, Object.freeze({ uuid: 'c3', name: 'gamma', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: null })]
        query.mockResolvedValue({ data: { componentRefs: rows } })
        await store.dispatch('fetchComponents', 'O')

        expect(store.state.components.map((c: any) => c.uuid)).toEqual(['c1', 'c2', 'c3'])
        expect(store.getters.componentById('c1')).toMatchObject({ name: 'beta', status: 'ACTIVE', vcs: 'V', versionSchema: 'semver', org: 'O', type: 'COMPONENT' })
        expect(store.getters.componentById('c2')).toMatchObject({ name: 'Alpha', org: 'O', type: 'COMPONENT', identifiers: [{ idType: 'x', idValue: 'y' }] })
        expect(store.getters.componentById('c3')).toMatchObject({ name: 'gamma', versionType: 'REGULAR', org: 'O' })
    })

    it('the product list returns its rows and honours forceRefresh, the component list likewise', async () => {
        query.mockResolvedValue({ data: { componentRefs: PRODUCTS } })
        const rows = await store.dispatch('fetchProducts', { orgid: 'O', forceRefresh: true })
        expect(rows).toBe(PRODUCTS)
        expect(call(0).fetchPolicy).toBe('network-only')
        await store.dispatch('fetchProducts', { orgid: 'O', forceRefresh: false })
        expect(call(1).fetchPolicy).toBe('cache-first')
        await store.dispatch('fetchProducts', { orgid: 'O' })
        expect(call(2).fetchPolicy).toBe('cache-first')

        query.mockReset()
        store.state.componentRefsSupported = false
        query.mockResolvedValue({ data: { components: PRODUCTS } })
        expect(await store.dispatch('fetchProducts', { orgid: 'O', forceRefresh: true })).toBe(PRODUCTS)
        expect(call(0).fetchPolicy).toBe('network-only')
        await store.dispatch('fetchProducts', { orgid: 'O' })
        expect(call(1).fetchPolicy).toBe('cache-first')
        expect(store.getters.productsOfOrg('O').map((r: any) => r.uuid)).toEqual(['p1'])
    })

    it('the product list sends a selected perspective and not the default one, on the refs and on the full document', async () => {
        query.mockResolvedValue({ data: { componentRefs: PRODUCTS } })
        store.state.iam.perspectiveUuid = 'P'
        await store.dispatch('fetchProducts', 'O')
        expect(call(0).query).toBe(graphqlQueries.ComponentRefsGql)
        expect(call(0).variables.perspective).toBe('P')
        expect(call(0).variables).toEqual({ orgUuid: 'O', componentType: 'PRODUCT', perspective: 'P' })
        store.state.iam.perspectiveUuid = 'default'
        await store.dispatch('fetchProducts', 'O')
        expect('perspective' in call(1).variables).toBe(false)
        expect(call(1).variables).toEqual({ orgUuid: 'O', componentType: 'PRODUCT' })

        query.mockReset()
        query.mockResolvedValue({ data: { components: PRODUCTS } })
        store.state.componentRefsSupported = false
        store.state.iam.perspectiveUuid = 'P'
        await store.dispatch('fetchProducts', 'O')
        expect(call(0).query).not.toBe(graphqlQueries.ComponentRefsGql)
        expect(call(0).variables).toEqual({ orgUuid: 'O', componentType: 'PRODUCT', perspective: 'P' })
        store.state.iam.perspectiveUuid = 'default'
        await store.dispatch('fetchProducts', 'O')
        expect(call(1).variables).toEqual({ orgUuid: 'O', componentType: 'PRODUCT' })
    })

    it('a row with a null field lays over a full record: the null overwrites, the fields the row does not carry stay', async () => {
        store.commit('SET_COMPONENTS_OF_ORG', { orgUuid: 'O', componentType: 'COMPONENT', components: [
            { uuid: 'c1', name: 'a', type: 'COMPONENT', org: 'O', vcs: 'V', status: 'ACTIVE', effectiveLifecycle: 'X', versionSchema: 'semver' }] })
        query.mockResolvedValue({ data: { componentRefs: [Object.freeze({ uuid: 'c1', name: 'b', type: 'COMPONENT', status: 'ACTIVE', org: 'O', vcs: null })] } })
        await store.dispatch('fetchComponents', 'O')
        const c = store.state.components.find((x: any) => x.uuid === 'c1')
        expect(c.vcs).toBe(null)
        expect(c.name).toBe('b')
        expect(c.effectiveLifecycle).toBe('X')
        expect(c.versionSchema).toBe('semver')
        expect(store.state.components.filter((x: any) => x.uuid === 'c1')).toHaveLength(1)
    })

    it('every field a row carries wins over the cached entry: name, type, org, vcs and a changed status', async () => {
        store.commit('SET_COMPONENTS_OF_ORG', { orgUuid: 'O', componentType: 'COMPONENT', components: [
            { uuid: 'c1', name: 'old', type: 'COMPONENT', org: 'O', vcs: 'V1', status: 'ACTIVE', effectiveLifecycle: 'X' }] })
        query.mockResolvedValue({ data: { componentRefs: [Object.freeze({ uuid: 'c1', name: 'new', type: 'COMPONENT', status: 'ARCHIVED', org: 'O', vcs: 'V2' })] } })
        await store.dispatch('fetchComponents', 'O')
        expect(store.state.components.find((x: any) => x.uuid === 'c1')).toEqual({
            uuid: 'c1', name: 'new', type: 'COMPONENT', status: 'ARCHIVED', org: 'O', vcs: 'V2', effectiveLifecycle: 'X' })
        expect(store.getters.componentsOfOrg('O').some((r: any) => r.uuid === 'c1')).toBe(false)
    })

    it('the refs document declares the four variables of the backend field with their types', () => {
        const op = operation(graphqlQueries.ComponentRefsGql)
        expect(op.name?.value).toBe('ComponentRefs')
        expect((op.variableDefinitions || []).map(v => `$${v.variable.name.value}: ${print(v.type)}`)).toEqual([
            '$orgUuid: ID!', '$componentType: ComponentType!', '$perspective: ID', '$kinds: [ComponentKind]'])
        expect((op.variableDefinitions || []).every(v => !v.defaultValue)).toBe(true)
    })
})

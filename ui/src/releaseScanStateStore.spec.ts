import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Kind, print, validate, type DocumentNode, type FieldNode, type OperationDefinitionNode, type GraphQLSchema } from 'graphql'
import { ceSchema } from '@/utils/schemaDriftSupport'

// TPF-127 test 5: the store's release loaders and mutations send the documents that carry the artifact
// scan state (Artifact.metrics.notScannable) while the session latch holds, fall back to the core twin
// on a schema-drift refusal only, and latch the session only once the core twin was served. The GraphQL
// client and keycloak are mocked so the store module loads under node (the componentRefsStore.spec.ts rig).
const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn() } }))

const storeModule = await import('@/store')
const store = storeModule.default.store
const hoisted = storeModule.releaseScanStateDocumentsForTests
const graphqlQueries = (await import('@/utils/graphqlQueries')).default

const R = {
    uuid: 'r1',
    artifactDetails: [{ uuid: 'a1', type: 'BOM', metrics: { firstScanned: null, notScannable: { reason: 'ONLY_ROOT',
        at: '2026-10-29T08:00:00Z', components: 1, root: 1, terminal: 0, unmatchable: 0 } } }]
}

// --- error fixtures, the graphqlDriftFallback.spec.ts shapes ---------------------------------
function drift (field = 'notScannable'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
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

function operation (doc: DocumentNode): OperationDefinitionNode {
    const ops = doc.definitions.filter(d => d.kind === Kind.OPERATION_DEFINITION) as OperationDefinitionNode[]
    expect(ops).toHaveLength(1)
    return ops[0]
}
function rootField (doc: DocumentNode): FieldNode {
    return operation(doc).selectionSet.selections[0] as FieldNode
}
const call = (i: number) => query.mock.calls[i][0]
const mcall = (i: number) => mutate.mock.calls[i][0]
const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)

let errorSpy: any
beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    store.state.releases = []
    store.state.artifactScanStateSupported = true
    store.state.releaseBomScanSupported = true
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => errorSpy.mockRestore())

describe('fetchReleaseById sends the release document with the artifact scan state (TPF-127)', () => {
    it('(a) sends SingleReleaseGql, the product document with product, and the light document as before', async () => {
        query.mockResolvedValue({ data: { release: R } })
        const answer = await store.dispatch('fetchReleaseById', { release: 'r1', org: 'O' })
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseGql)
        expect(call(0).variables).toEqual({ releaseID: 'r1', orgID: 'O' })
        expect(call(0).fetchPolicy).toBe('no-cache')
        expect(store.state.releases[0]).toEqual(R)
        expect(answer).toEqual(R)

        query.mockClear()
        await store.dispatch('fetchReleaseById', { release: 'r1', org: 'O', product: true })
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseProductGql)

        for (const flag of [true, false]) {
            store.state.artifactScanStateSupported = flag
            query.mockClear()
            await store.dispatch('fetchReleaseById', { release: 'r1', org: 'O', light: true })
            expect(query).toHaveBeenCalledTimes(1)
            expect(call(0).query).toBe(graphqlQueries.SingleReleaseGqlLight)
        }
    })

    it('(b) a drift refusal sends the core twin with the same variables, and latches only after it was served', async () => {
        let resolveCore: (v: any) => void = () => {}
        query.mockRejectedValueOnce(drift())
        query.mockImplementationOnce(() => new Promise((resolve) => { resolveCore = resolve }))
        const pending = store.dispatch('fetchReleaseById', { release: 'r1', org: 'O' })
        await vi.waitFor(() => expect(query).toHaveBeenCalledTimes(2))
        expect(call(1).query).toBe(graphqlQueries.SingleReleaseGqlCore)
        expect(call(1).variables).toEqual({ releaseID: 'r1', orgID: 'O' })
        expect(call(1).fetchPolicy).toBe('no-cache')
        expect(errorsAgainst(ceSchema, call(1).query)).toEqual([])
        expect(store.state.artifactScanStateSupported).toBe(true)
        const core = { uuid: 'r1', artifactDetails: [] }
        resolveCore({ data: { release: core } })
        await expect(pending).resolves.toEqual(core)
        expect(store.state.artifactScanStateSupported).toBe(false)
        expect(errorSpy).toHaveBeenCalledTimes(1)
    })

    it('(c) a latched session sends the core twin at once, for loaders and mutations', async () => {
        store.state.artifactScanStateSupported = false
        query.mockResolvedValue({ data: { release: R } })
        await store.dispatch('fetchReleaseById', { release: 'r1', org: 'O' })
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseGqlCore)
        expect(errorsAgainst(ceSchema, call(0).query)).toEqual([])
        mutate.mockResolvedValue({ data: { updateReleaseLifecycle: R } })
        await store.dispatch('updateReleaseLifecycle', { uuid: 'r1', lifecycle: 'ASSEMBLED' })
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mcall(0).mutation).toBe(hoisted.UPDATE_RELEASE_LIFECYCLE_GQL_CORE)
        expect(errorsAgainst(ceSchema, mcall(0).mutation)).toEqual([])
    })

    it.each([['500', () => status(500)], ['401', () => status(401)], ['transport', transport]])(
        '(d) %s on the preferred document is rethrown with one call and the flag kept', async (_n, make) => {
            const err = make()
            query.mockRejectedValueOnce(err)
            await expect(store.dispatch('fetchReleaseById', { release: 'r1', org: 'O' })).rejects.toBe(err)
            expect(query).toHaveBeenCalledTimes(1)
            expect(store.state.artifactScanStateSupported).toBe(true)
        })

    it('(e) drift then a failing core twin rejects with that error, keeps the flag, and the next call tries again', async () => {
        const err = status(500)
        query.mockRejectedValueOnce(drift())
        query.mockRejectedValueOnce(err)
        await expect(store.dispatch('fetchReleaseById', { release: 'r1', org: 'O' })).rejects.toBe(err)
        expect(query).toHaveBeenCalledTimes(2)
        expect(store.state.artifactScanStateSupported).toBe(true)
        query.mockResolvedValueOnce({ data: { release: R } })
        await store.dispatch('fetchReleaseById', { release: 'r1', org: 'O' })
        expect(call(2).query).toBe(graphqlQueries.SingleReleaseGql)
    })
})

describe('fetchReleases (the branch list) sends the BOM scan tally on its own tier (TPF-133)', () => {
    function expectBranchShape (c: any) {
        const doc = c.query
        expect(operation(doc).name?.value).toBe('FetchReleases')
        const root = rootField(doc)
        expect(root.name.value).toBe('releases')
        const arg: any = (root.arguments || []).find((a: any) => a.name.value === 'branchFilter')
        expect(arg?.value?.name?.value).toBe('branchID')
        expect(c.variables).toEqual({ branchID: 'b1' })
        expect(c.fetchPolicy).toBe('no-cache')
    }

    it('(a) both flags true: one call, the full document with bomScan and componentDetails, no artifact marker', async () => {
        query.mockResolvedValue({ data: { releases: [R] } })
        await store.dispatch('fetchReleases', { branch: 'b1' })
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(hoisted.FETCH_RELEASES_GQL)
        expectBranchShape(call(0))
        const printed = print(call(0).query)
        expect(printed).toContain('bomScan {')
        expect(printed).toContain('componentDetails')
        expect(printed).not.toMatch(/\bnotScannable\b/)
        expect(store.state.releases).toEqual([R])
    })

    it('(b) drift for bomScan then a served core: two calls, B false only after the core resolved, A kept', async () => {
        let resolveCore: (v: any) => void = () => {}
        query.mockRejectedValueOnce(drift('bomScan'))
        query.mockImplementationOnce(() => new Promise((resolve) => { resolveCore = resolve }))
        const pending = store.dispatch('fetchReleases', { branch: 'b1' })
        await vi.waitFor(() => expect(query).toHaveBeenCalledTimes(2))
        expect(call(1).query).toBe(hoisted.FETCH_RELEASES_GQL_CORE)
        expectBranchShape(call(1))
        expect(errorsAgainst(ceSchema, call(1).query)).toEqual([])
        expect(store.state.releaseBomScanSupported).toBe(true)
        resolveCore({ data: { releases: [R] } })
        await expect(pending).resolves.toEqual([R])
        expect(store.state.releaseBomScanSupported).toBe(false)
        expect(store.state.artifactScanStateSupported).toBe(true)
        expect(errorSpy).toHaveBeenCalledTimes(1)
    })

    it('(c) B false: one call, the core; A false alone: one call, the full document (A does not gate it)', async () => {
        query.mockResolvedValue({ data: { releases: [R] } })
        store.state.releaseBomScanSupported = false
        await store.dispatch('fetchReleases', { branch: 'b1' })
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(hoisted.FETCH_RELEASES_GQL_CORE)
        query.mockClear()
        store.state.releaseBomScanSupported = true
        store.state.artifactScanStateSupported = false
        await store.dispatch('fetchReleases', { branch: 'b1' })
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(hoisted.FETCH_RELEASES_GQL)
    })

    it.each([['500', () => status(500)], ['transport', transport]])(
        '(d) %s on the full document is rethrown with one call and both flags kept', async (_n, make) => {
            const err = make()
            query.mockRejectedValueOnce(err)
            await expect(store.dispatch('fetchReleases', { branch: 'b1' })).rejects.toBe(err)
            expect(query).toHaveBeenCalledTimes(1)
            expect(store.state.releaseBomScanSupported).toBe(true)
            expect(store.state.artifactScanStateSupported).toBe(true)
        })

    it('(d) drift then a failing core rejects with the core error and keeps both flags', async () => {
        const err = status(500)
        query.mockRejectedValueOnce(drift('bomScan'))
        query.mockRejectedValueOnce(err)
        await expect(store.dispatch('fetchReleases', { branch: 'b1' })).rejects.toBe(err)
        expect(query).toHaveBeenCalledTimes(2)
        expect(store.state.releaseBomScanSupported).toBe(true)
        expect(store.state.artifactScanStateSupported).toBe(true)
    })
})

describe('fetchReleaseById for a product sends the two-tier product document (TPF-133)', () => {
    const product = { release: 'r1', org: 'O', product: true }

    it('(e) both flags true: one call, SingleReleaseProductGql', async () => {
        query.mockResolvedValue({ data: { release: R } })
        await store.dispatch('fetchReleaseById', product)
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseProductGql)
    })

    it('(f) drift for bomScan then served: two calls, the second without bomScan; B false after, A true', async () => {
        query.mockRejectedValueOnce(drift('bomScan'))
        query.mockResolvedValueOnce({ data: { release: R } })
        await store.dispatch('fetchReleaseById', product)
        expect(query).toHaveBeenCalledTimes(2)
        expect(call(1).query).toBe(graphqlQueries.SingleReleaseProductGqlWithoutBomScan)
        expect(call(1).variables).toEqual({ releaseID: 'r1', orgID: 'O' })
        expect(call(1).fetchPolicy).toBe('no-cache')
        expect(store.state.releaseBomScanSupported).toBe(false)
        expect(store.state.artifactScanStateSupported).toBe(true)
        expect(errorSpy).toHaveBeenCalledTimes(1)
    })

    it('(g) drift, drift, served: three calls ending in the core, both flags false, two console.error', async () => {
        query.mockRejectedValueOnce(drift('bomScan'))
        query.mockRejectedValueOnce(drift('notScannable'))
        query.mockResolvedValueOnce({ data: { release: R } })
        await store.dispatch('fetchReleaseById', product)
        expect(query).toHaveBeenCalledTimes(3)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseProductGql)
        expect(call(1).query).toBe(graphqlQueries.SingleReleaseProductGqlWithoutBomScan)
        expect(call(2).query).toBe(graphqlQueries.SingleReleaseProductGqlCore)
        expect(store.state.releaseBomScanSupported).toBe(false)
        expect(store.state.artifactScanStateSupported).toBe(false)
        expect(errorSpy).toHaveBeenCalledTimes(2)
    })

    it('(h) A false, B true: one call, the core (no request for a field on a backend proven to lack the earlier one)', async () => {
        store.state.artifactScanStateSupported = false
        query.mockResolvedValue({ data: { release: R } })
        await store.dispatch('fetchReleaseById', product)
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseProductGqlCore)
    })

    it('(i) B false, A true: one call, the document without bomScan', async () => {
        store.state.releaseBomScanSupported = false
        query.mockResolvedValue({ data: { release: R } })
        await store.dispatch('fetchReleaseById', product)
        expect(query).toHaveBeenCalledTimes(1)
        expect(call(0).query).toBe(graphqlQueries.SingleReleaseProductGqlWithoutBomScan)
    })
})

// Each mutation: the action, its payload, the preferred and core documents, and the response field.
const MUTATIONS: Array<[string, any, DocumentNode, DocumentNode, string]> = [
    ['updateRelease', { uuid: 'r1', org: 'O', version: '1' }, graphqlQueries.ReleaseGqlMutate, graphqlQueries.ReleaseGqlMutateCore, 'updateRelease'],
    ['updateReleaseTagsMeta', { uuid: 'r1', org: 'O' }, graphqlQueries.ReleaseTagsMetaGqlMutate, graphqlQueries.ReleaseTagsMetaGqlMutateCore, 'updateReleaseTagsMeta'],
    ['approveRelease', { release: 'r1', approvals: [] }, graphqlQueries.ApproveReleaseGqlMutate, graphqlQueries.ApproveReleaseGqlMutateCore, 'approveReleaseManual'],
    ['approveReleaseLegacy', { uuid: 'r1', approvals: { a: true }, productsForDisapproval: [] }, graphqlQueries.ApproveReleaseGqlMutate, graphqlQueries.ApproveReleaseGqlMutateCore, 'approveReleaseManual'],
    ['createRelease', { uuid: 'r1', branch: 'b1' }, hoisted.ADD_RELEASE_GQL, hoisted.ADD_RELEASE_GQL_CORE, 'addReleaseManual'],
    ['updateReleaseLifecycle', { uuid: 'r1', lifecycle: 'ASSEMBLED' }, hoisted.UPDATE_RELEASE_LIFECYCLE_GQL, hoisted.UPDATE_RELEASE_LIFECYCLE_GQL_CORE, 'updateReleaseLifecycle'],
    ['reevaluateReleaseTriggers', 'r1', hoisted.REEVALUATE_RELEASE_TRIGGERS_GQL, hoisted.REEVALUATE_RELEASE_TRIGGERS_GQL_CORE, 'reevaluateReleaseTriggers']
]
const payloadOf = (p: any) => (typeof p === 'string' ? p : { ...p })

describe('the release mutations carry the field too (TPF-127, 5.7)', () => {
    it.each(MUTATIONS)('(g) %s sends the preferred document while the flag holds', async (action, payload, preferred, _core, field) => {
        mutate.mockResolvedValue({ data: { [field]: R } })
        await store.dispatch(action, payloadOf(payload))
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mcall(0).mutation).toBe(preferred)
        expect(store.state.releases[0]).toEqual(R)
    })

    it.each(MUTATIONS)('(g) %s on drift sends the core twin once more with the same variables', async (action, payload, preferred, core, field) => {
        mutate.mockRejectedValueOnce(drift())
        const served = { uuid: 'r1', artifactDetails: [] }
        mutate.mockResolvedValueOnce({ data: { [field]: served } })
        await store.dispatch(action, payloadOf(payload))
        expect(mutate).toHaveBeenCalledTimes(2)
        expect(mcall(0).mutation).toBe(preferred)
        expect(mcall(1).mutation).toBe(core)
        expect(mcall(1).variables).toEqual(mcall(0).variables)
        expect(store.state.releases[0]).toEqual(served)
        expect(store.state.artifactScanStateSupported).toBe(false)
        expect(errorsAgainst(ceSchema, core)).toEqual([])
        expect(print(core)).not.toContain('notScannable')
    })

    it.each(MUTATIONS)('(g) %s on a 500 rethrows and is never retried', async (action, payload) => {
        const err = status(500)
        mutate.mockRejectedValueOnce(err)
        await expect(store.dispatch(action, payloadOf(payload))).rejects.toBe(err)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(store.state.artifactScanStateSupported).toBe(true)
    })

    it.each(MUTATIONS)('(g) %s with the flag false sends the core twin once', async (action, payload, _preferred, core, field) => {
        store.state.artifactScanStateSupported = false
        mutate.mockResolvedValue({ data: { [field]: R } })
        await store.dispatch(action, payloadOf(payload))
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mcall(0).mutation).toBe(core)
    })

    it('(g) updateReleaseLifecycle still returns data.errors', async () => {
        const errors = [{ message: 'x' }]
        mutate.mockResolvedValue({ data: { updateReleaseLifecycle: R }, errors })
        await expect(store.dispatch('updateReleaseLifecycle', { uuid: 'r1', lifecycle: 'ASSEMBLED' })).resolves.toBe(errors)
    })
})

describe('the documents without a scan-state reader do not carry the field (TPF-127)', () => {
    it.each([true, false])('(h) searchReleasesByTags and fetchReleasesByOrgUuids send one document each without it, flag %s', async (flag) => {
        store.state.artifactScanStateSupported = flag
        query.mockResolvedValue({ data: { releasesByTags: [R], releases: [R] } })
        await store.dispatch('searchReleasesByTags', { org: 'O', tagKey: 'k' })
        expect(query).toHaveBeenCalledTimes(1)
        expect(print(call(0).query)).not.toContain('notScannable')
        await store.dispatch('fetchReleasesByOrgUuids', { org: 'O', releases: ['r1'] })
        expect(query).toHaveBeenCalledTimes(2)
        expect(print(call(1).query)).not.toContain('notScannable')
    })
})

// The settings page's revision handling (TPF-62): reading the refusal, never sending a null
// revision, naming who changed the component, the drift-guarded read and the page's own count.
import { describe, expect, it, vi } from 'vitest'
import {
    describeLastUpdater, revisionConflictOf, useComponentRevision, withExpectedRevision, type RevisionConflict
} from './componentRevision'

function conflictError (revision: number) {
    return { errors: [{ message: 'm', extensions: { code: 'COMPONENT_REVISION_CONFLICT', revision,
        lastUpdatedDate: '2026-10-13T10:00:00Z', lastUpdatedBy: 'u1', lastUpdatedType: 'MANUAL',
        classification: 'BAD_REQUEST' } }] }
}

describe('revisionConflictOf', () => {
    const expected = { revision: 5, lastUpdatedBy: 'u1', lastUpdatedType: 'MANUAL', lastUpdatedActor: null,
        lastUpdatedDate: '2026-10-13T10:00:00Z' }

    it('reads the refusal by its code, under errors and under graphQLErrors', () => {
        expect(revisionConflictOf(conflictError(5))).toEqual(expected)
        expect(revisionConflictOf({ graphQLErrors: conflictError(5).errors })).toEqual(expected)
    })

    it('gives null for anything else', () => {
        const other = { errors: [{ message: 'm', extensions: { code: 'SBOM_SCORE_ERROR', revision: 5 } }] }
        const noExtensions = { errors: [{ message: 'Component was changed since it was read' }] }
        const stringRevision = { errors: [{ message: 'm', extensions: { code: 'COMPONENT_REVISION_CONFLICT', revision: '5' } }] }
        for (const err of [other, noExtensions, stringRevision, new Error('Failed to fetch'), null]) {
            expect(revisionConflictOf(err)).toBeNull()
        }
    })
})

describe('withExpectedRevision', () => {
    it('adds a number, 0 included, and never a null', () => {
        expect(withExpectedRevision({ a: 1 }, 4)).toEqual({ a: 1, expectedRevision: 4 })
        expect(withExpectedRevision({ a: 1 }, 0)).toEqual({ a: 1, expectedRevision: 0 })
        const none = withExpectedRevision({ a: 1 }, null)
        expect(none).toEqual({ a: 1 })
        expect('expectedRevision' in none).toBe(false)
    })
})

describe('describeLastUpdater', () => {
    const users = [{ label: 'Alice', value: 'u-alice' }, { label: 'bob@example.io', value: 'u-bob' }]
    const c = (over: Partial<RevisionConflict>): RevisionConflict => ({ revision: 1, lastUpdatedBy: null,
        lastUpdatedType: null, lastUpdatedActor: null, lastUpdatedDate: null, ...over })

    it('names who changed it from the user list, else a short id, else someone else', () => {
        expect(describeLastUpdater(c({ lastUpdatedType: 'AUTO' }), users)).toBe('System')
        expect(describeLastUpdater(c({ lastUpdatedBy: 'u-alice', lastUpdatedType: 'MANUAL' }), users)).toBe('Alice')
        const key = '3f9a1c2e-0000-4000-8000-000000000001'
        expect(describeLastUpdater(c({ lastUpdatedType: 'API', lastUpdatedBy: key, lastUpdatedActor: 'u-bob' }), users))
            .toBe('bob@example.io (through an API key)')
        expect(describeLastUpdater(c({ lastUpdatedType: 'API', lastUpdatedBy: key }), users)).toBe('an API key (3f9a1c2e)')
        expect(describeLastUpdater(c({ lastUpdatedType: 'MANUAL', lastUpdatedBy: '7d4e2b1a-0000-4000-8000-000000000002' }), users))
            .toBe('a user (7d4e2b1a)')
        expect(describeLastUpdater(c({}), users)).toBe('someone else')
    })
})

describe('loadComponentForForm', () => {
    // Both modules fresh per case: the drift-rejection memo is module-level.
    async function fresh () {
        vi.resetModules()
        return await import('./componentRevision')
    }
    function fakeClient (mod: any, answer: (doc: 'full' | 'core') => any) {
        const asked: string[] = []
        const policies: (string | undefined)[] = []
        const client = {
            query: vi.fn(async (opts: any) => {
                const doc = opts.query === mod.COMPONENT_FORM_QUERY ? 'full'
                    : opts.query === mod.COMPONENT_FORM_QUERY_CORE ? 'core' : 'unknown'
                asked.push(doc)
                policies.push(opts.fetchPolicy)
                const a = answer(doc as any)
                if (a instanceof Error || a?.reject) throw a?.reject ?? a
                return a
            })
        }
        return { client, asked, policies }
    }

    it('returns the component without its revision key, and the revision, from one uncached read', async () => {
        const mod = await fresh()
        const { client, asked, policies } = fakeClient(mod, () => ({ data: { component: { uuid: 'c1', name: 'n', revision: 7 } } }))
        const r = await mod.loadComponentForForm(client, 'c1')
        expect(r).toEqual({ component: { uuid: 'c1', name: 'n' }, revision: 7 })
        expect('revision' in r.component).toBe(false)
        expect(asked).toEqual(['full'])
        expect(policies.every(p => p === 'no-cache')).toBe(true)
    })

    it('falls back to the core read on drift, and then asks only the core read', async () => {
        const mod = await fresh()
        const drift = { errors: [{ message: "Validation error (FieldUndefined@[component/revision]) : Field 'revision' in type 'Component' is undefined",
            extensions: { classification: 'ValidationError' } }] }
        const { client, asked, policies } = fakeClient(mod, (doc) => doc === 'full'
            ? { reject: drift } : { data: { component: { uuid: 'c1', name: 'n' } } })
        expect(await mod.loadComponentForForm(client, 'c1')).toEqual({ component: { uuid: 'c1', name: 'n' }, revision: null })
        expect(asked).toEqual(['full', 'core'])
        await mod.loadComponentForForm(client, 'c1')
        expect(asked).toEqual(['full', 'core', 'core'])
        expect(policies.every(p => p === 'no-cache')).toBe(true)
    })

    it('rethrows a failure that is not drift and never asks the core read', async () => {
        const mod = await fresh()
        const denied = { errors: [{ message: 'Not authorized' }] }
        const { client, asked } = fakeClient(mod, () => ({ reject: denied }))
        await expect(mod.loadComponentForForm(client, 'c1')).rejects.toBe(denied)
        expect(asked).toEqual(['full'])
    })

    it('rethrows the full read error when both fail, and remembers nothing', async () => {
        const mod = await fresh()
        const fullErr = { message: 'Response not successful', networkError: { statusCode: 400 } }
        const coreErr = { message: 'Response not successful', networkError: { statusCode: 400 } }
        const { client, asked } = fakeClient(mod, (doc) => ({ reject: doc === 'full' ? fullErr : coreErr }))
        await expect(mod.loadComponentForForm(client, 'c1')).rejects.toBe(fullErr)
        expect(asked).toEqual(['full', 'core'])
        await expect(mod.loadComponentForForm(client, 'c1')).rejects.toBe(fullErr)
        expect(asked).toEqual(['full', 'core', 'full', 'core'])
    })
})

describe('useComponentRevision', () => {
    it('counts the page own writes and keeps a refusal until a fresh read or an accepted save', async () => {
        const r = useComponentRevision()
        r.baseline(4)
        expect(r.expected.value).toBe(4)
        expect(r.conflict.value).toBeNull()
        r.ownWrite()
        r.ownWrite()
        expect(r.expected.value).toBe(6)

        r.baseline(null)
        r.ownWrite()
        expect(r.expected.value).toBeNull()

        r.baseline(6)
        const retry = vi.fn(async (_rev: number) => {})
        expect(r.refused(conflictError(9), retry)).toBe(true)
        expect(r.conflict.value?.info.revision).toBe(9)
        expect(r.expected.value).toBe(6)
        expect(r.refused(new Error('x'), vi.fn())).toBe(false)
        expect(r.conflict.value?.retry).toBe(retry)

        r.ownWrite()
        expect(r.expected.value).toBe(7)
        expect(r.conflict.value?.info.revision).toBe(10)
        await r.force()
        expect(retry).toHaveBeenCalledTimes(1)
        expect(retry).toHaveBeenCalledWith(10)

        r.saved(10)
        expect(r.expected.value).toBe(11)
        expect(r.conflict.value).toBeNull()
        r.saved(null)
        expect(r.expected.value).toBeNull()

        r.refused(conflictError(12), retry)
        r.baseline(12)
        expect(r.conflict.value).toBeNull()
        expect(r.expected.value).toBe(12)
    })
})

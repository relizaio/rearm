import { describe, it, expect } from 'vitest'
import graphqlQueries from './graphqlQueries'
import { loadTeaDocument, loadTeaPublishChildren, teaCoreDocument } from './teaDocuments'

// FDAW-39 (design 3.2, row 52 (a) to (f)): the TEA reads with additive fields fall back to the core
// document on a schema-drift rejection only, with the same variables and fetch policy.

const DRIFT = () => new Error("Validation error (FieldUndefined@[teaProfileEditorView/publishedVersions]) : Field 'publishedVersions' in type 'TeaProfileEditorView' is undefined")
const NOT_AUTHORIZED = () => Object.assign(new Error('Not authorized'), {
    errors: [{ message: 'Not authorized' }, { message: 'The field at path ...', extensions: { classification: 'NullValueInNonNullableField' } }],
})
const STATUS_500 = () => Object.assign(new Error('Response not successful: Received status code 500'), { statusCode: 500 })

/** A client that answers (or throws) per call, in order, and records every call. */
function client (...answers: any[]) {
    const calls: any[] = []
    return {
        calls,
        async query (options: any) {
            calls.push(options)
            const a = answers[calls.length - 1]
            if (a instanceof Error) throw a
            return a
        },
    }
}

const doc = graphqlQueries.TeaProfileEditorViewGql
const ADDITIVE_DOCS = {
    TeaProfileEditorViewGql: [graphqlQueries.TeaProfileEditorViewGql, ['autoPublish', 'autoPublishState', 'publishedVersions']],
    SaveTeaProfileGql: [graphqlQueries.SaveTeaProfileGql, ['autoPublish', 'autoPublishState']],
    TeaProfileExposureChangeGql: [graphqlQueries.TeaProfileExposureChangeGql, ['publishedVersions', 'autoPublishStarts', 'autoPublishStartsFor']],
    TeaMembershipExposureChangeGql: [graphqlQueries.TeaMembershipExposureChangeGql, ['publishedVersions', 'autoPublishStarts', 'autoPublishStartsFor']],
} as const
const ADDED = ['autoPublish', 'autoPublishState', 'publishedVersions', 'autoPublishStarts', 'autoPublishStartsFor']

describe('loadTeaDocument (row 52)', () => {
    it('(a) a backend that answers gets one call with the options as given, and the full form is reported', async () => {
        const c = client({ data: { x: 1 } })
        const result = await loadTeaDocument(c, { query: doc, variables: { a: 1 }, fetchPolicy: 'no-cache' })
        expect(c.calls).toEqual([{ query: doc, variables: { a: 1 }, fetchPolicy: 'no-cache' }])
        expect(result).toEqual({ data: { x: 1 }, additive: true })
    })

    it('(a) a string stub passes through untouched when the backend answers (the core form is not computed first)', async () => {
        const c = client({ data: {} })
        await expect(loadTeaDocument(c, { query: 'VIEW', variables: {}, fetchPolicy: 'no-cache' })).resolves.toBeDefined()
        expect(c.calls).toEqual([{ query: 'VIEW', variables: {}, fetchPolicy: 'no-cache' }])
    })

    it('(b) a drift rejection retries once with the core document, same variables and policy', async () => {
        const c = client(DRIFT(), { data: { y: 2 } })
        const result = await loadTeaDocument(c, { query: doc, variables: { a: 1 }, fetchPolicy: 'no-cache' })
        expect(c.calls).toHaveLength(2)
        expect(c.calls[1]).toEqual({ query: teaCoreDocument(doc), variables: { a: 1 }, fetchPolicy: 'no-cache' })
        expect(result.additive).toBe(false)
        expect(result.data).toEqual({ y: 2 })
    })

    it('(c) Not authorized and a status 500 are thrown after one call', async () => {
        for (const err of [NOT_AUTHORIZED(), STATUS_500()]) {
            const c = client(err)
            await expect(loadTeaDocument(c, { query: doc, variables: { a: 1 }, fetchPolicy: 'no-cache' })).rejects.toBe(err)
            expect(c.calls).toHaveLength(1)
        }
    })

    it('(d) when the core call fails too, the first error is thrown', async () => {
        const first = DRIFT()
        const c = client(first, new Error('boom'))
        await expect(loadTeaDocument(c, { query: doc, variables: { a: 1 }, fetchPolicy: 'no-cache' })).rejects.toBe(first)
        expect(c.calls).toHaveLength(2)
    })
})

describe('teaCoreDocument (row 52 (e))', () => {
    it('answers the same object on a second call', () => {
        expect(teaCoreDocument(doc)).toBe(teaCoreDocument(doc))
    })

    it('the core form of each additive document has none of the new fields; the full form has its own (control)', () => {
        for (const [name, [full, own]] of Object.entries(ADDITIVE_DOCS)) {
            const core = teaCoreDocument(full).loc!.source.body
            for (const f of ADDED) expect(core, name + ' ' + f).not.toMatch(new RegExp('\\b' + f + '\\b'))
            for (const f of own) expect(full.loc!.source.body, name + ' ' + f).toMatch(new RegExp('\\b' + f + '\\b'))
        }
    })
})

describe('loadTeaPublishChildren (row 52 (f))', () => {
    const kids = [{ release: 'c' }, { release: 'a' }, { release: 'b' }]

    it('one no-cache call for the release, the children in the answered order', async () => {
        const c = client({ data: { releaseTeaPublicationView: { release: 'r1', children: kids } } })
        const result = await loadTeaPublishChildren(c, 'r1')
        expect(c.calls).toEqual([{ query: graphqlQueries.ReleaseTeaPublishChildrenGql, variables: { release: 'r1' }, fetchPolicy: 'no-cache' }])
        expect(result.children).toEqual(kids)
    })

    it('a drift rejection answers children null; Not authorized is thrown', async () => {
        expect(await loadTeaPublishChildren(client(DRIFT()), 'r1')).toEqual({ children: null })
        const err = NOT_AUTHORIZED()
        await expect(loadTeaPublishChildren(client(err), 'r1')).rejects.toBe(err)
    })
})

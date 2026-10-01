import { describe, it, expect, vi, afterEach } from 'vitest'
import gql from 'graphql-tag'
import {
    SCHEMA_DRIFT_RETRY_MS,
    classifyGraphqlError,
    httpStatusOf,
    isSchemaDriftError,
    loadRichestServed,
    loadWithSchemaDriftFallback,
    type DriftFallbackClient,
} from './graphqlDriftFallback'
import { parse, type DocumentNode } from 'graphql'

const FULL = gql`query q { thing { a b enrichment } }`
const CORE = gql`query q { thing { a b } }`

// --- Apollo v4 error-shape fixtures -------------------------------------

// 200 + errors[] (CombinedGraphQLErrors): a classifiable validation verdict.
function validationError (field = 'channelName'): any {
    const err: any = new Error(`Validation error of type FieldUndefined: Field '${field}' is undefined`)
    err.errors = [{
        message: `Validation error of type FieldUndefined: Field '${field}' is undefined`,
        extensions: { classification: 'ValidationError' },
    }]
    return err
}
// The live-sandbox shape: HTTP 400 whose GraphQL body a WAF replaced with HTML.
function wafStripped400 (): any {
    const err: any = new Error('Response not successful: Received status code 400')
    err.statusCode = 400
    err.bodyText = '<html><body>Your request has returned an error.</body></html>'
    return err
}
function authError (): any {
    const err: any = new Error('Response not successful: Received status code 401')
    err.statusCode = 401
    return err
}
function serverError (): any {
    const err: any = new Error('Response not successful: Received status code 500')
    err.statusCode = 500
    return err
}
function transportError (): any {
    return new Error('Failed to fetch') // no status anywhere
}
function abortError (): any {
    const err: any = new Error('The operation was aborted')
    err.name = 'AbortError'
    return err
}

describe('classifyGraphqlError / httpStatusOf', () => {
    it('validation: classified extension', () => expect(classifyGraphqlError(validationError())).toBe('validation'))
    it('validation: graphql-js phrasing', () =>
        expect(classifyGraphqlError(new Error('Cannot query field "x" on type "Y".'))).toBe('validation'))
    it('network: plain fetch failure', () => expect(classifyGraphqlError(transportError())).toBe('network'))
    it('network: abort/timeout', () => expect(classifyGraphqlError(abortError())).toBe('network'))
    it('other: opaque WAF 400', () => expect(classifyGraphqlError(wafStripped400())).toBe('other'))
    it('httpStatusOf reads nested + top-level status', () => {
        expect(httpStatusOf(wafStripped400())).toBe(400)
        expect(httpStatusOf({ networkError: { statusCode: 503 } })).toBe(503)
        expect(httpStatusOf(transportError())).toBeUndefined()
    })
})

describe('isSchemaDriftError (retry gate)', () => {
    it('true for a classifiable validation error', () => expect(isSchemaDriftError(validationError())).toBe(true))
    it('true for an opaque 400 (WAF-stripped validation)', () => expect(isSchemaDriftError(wafStripped400())).toBe(true))
    it('FALSE for 401 auth', () => expect(isSchemaDriftError(authError())).toBe(false))
    it('FALSE for 500 server error', () => expect(isSchemaDriftError(serverError())).toBe(false))
    it('FALSE for a transport failure', () => expect(isSchemaDriftError(transportError())).toBe(false))
})

// --- loadWithSchemaDriftFallback ----------------------------------------

function clientThatRejectsFullWith (err: any, coreData: any): DriftFallbackClient {
    return {
        async query ({ query }: { query: DocumentNode }) {
            if (query === CORE) return { data: { thing: coreData } }
            throw err
        },
    }
}
const opts = (overrides = {}) => ({
    fullQuery: FULL, coreQuery: CORE, variables: {}, extractPath: (d: any) => d?.thing, ...overrides,
})

describe('loadWithSchemaDriftFallback', () => {
    it('returns full data, not degraded, when full succeeds', async () => {
        const client: DriftFallbackClient = { async query () { return { data: { thing: { a: 1, enrichment: 'e' } } } } }
        const r = await loadWithSchemaDriftFallback(client, opts())
        expect(r.degraded).toBe(false)
        expect(r.data.enrichment).toBe('e')
    })
    it('degrades to core on a validation drift', async () => {
        const r = await loadWithSchemaDriftFallback(clientThatRejectsFullWith(validationError(), { a: 1 }), opts())
        expect(r.degraded).toBe(true)
        expect(r.data.a).toBe(1)
    })
    it('degrades to core on an opaque WAF 400', async () => {
        const r = await loadWithSchemaDriftFallback(clientThatRejectsFullWith(wafStripped400(), { a: 1 }), opts())
        expect(r.degraded).toBe(true)
    })
    it('does NOT degrade on 401 -- rethrows so auth surfaces', async () => {
        await expect(loadWithSchemaDriftFallback(clientThatRejectsFullWith(authError(), { a: 1 }), opts()))
            .rejects.toThrow(/401/)
    })
    it('does NOT degrade on 500 -- rethrows', async () => {
        await expect(loadWithSchemaDriftFallback(clientThatRejectsFullWith(serverError(), { a: 1 }), opts()))
            .rejects.toThrow(/500/)
    })
    it('does NOT degrade on a transport failure -- rethrows without a second request', async () => {
        let calls = 0
        const client: DriftFallbackClient = { async query () { calls++; throw transportError() } }
        await expect(loadWithSchemaDriftFallback(client, opts())).rejects.toThrow(/failed to fetch/i)
        expect(calls).toBe(1) // no pointless core retry
    })
    it('rethrows the ORIGINAL error when the core retry also fails', async () => {
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                if (query === CORE) throw new Error('core also 400')
                throw wafStripped400()
            },
        }
        await expect(loadWithSchemaDriftFallback(client, opts())).rejects.toThrow(/status code 400/)
    })
    it('skipFull goes straight to core and is degraded, issuing one request', async () => {
        let calls = 0
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                calls++
                expect(query).toBe(CORE)
                return { data: { thing: { a: 1 } } }
            },
        }
        const r = await loadWithSchemaDriftFallback(client, opts({ skipFull: true }))
        expect(r.degraded).toBe(true)
        expect(calls).toBe(1)
    })
})

describe('loadRichestServed', () => {
    afterEach(() => vi.useRealTimers())

    // Fresh documents per test: the drift marks are keyed by document, and gql
    // caches documents by their source text, so parse instead.
    const tiers = () => [parse('query q { thing { a b c } }'), parse('query q { thing { a b } }'), parse('query q { thing { a } }')]
    // A client answering each document in turn from `answers` (a value or an error to throw).
    function clientFor (docs: DocumentNode[], answers: any[]): DriftFallbackClient & { asked: number[] } {
        const asked: number[] = []
        return {
            asked,
            async query ({ query, fetchPolicy }: { query: DocumentNode, fetchPolicy?: string }) {
                expect(fetchPolicy).toBe('no-cache')
                const i = docs.indexOf(query)
                asked.push(i)
                if (answers[i] instanceof Error) throw answers[i]
                return { data: { thing: answers[i] } }
            },
        }
    }
    const load = (client: DriftFallbackClient, documents: DocumentNode[]) =>
        loadRichestServed(client, { documents, variables: {}, extractPath: (d: any) => d?.thing })

    it('serves the richest document the backend accepts', async () => {
        const docs = tiers()
        const client = clientFor(docs, [validationError(), { a: 1, b: 2 }, { a: 1 }])
        expect(await load(client, docs)).toEqual({ data: { a: 1, b: 2 }, served: 1 })
        expect(client.asked).toEqual([0, 1])
    })

    it('skips a rejected document for a while once a narrower one was served', async () => {
        vi.useFakeTimers()
        const docs = tiers()
        const client = clientFor(docs, [validationError(), { a: 1, b: 2 }, { a: 1 }])
        await load(client, docs)
        await load(client, docs)
        vi.advanceTimersByTime(SCHEMA_DRIFT_RETRY_MS + 1)
        await load(client, docs)
        expect(client.asked).toEqual([0, 1, 1, 0, 1])
    })

    it('surfaces the last document\'s own error when only drift came before it', async () => {
        // A CE backend without the optional fields, briefly down: a Retry, not "not available"
        const docs = tiers().slice(1)
        const down = serverError()
        await expect(load(clientFor(docs, [validationError(), down]), docs)).rejects.toBe(down)
    })

    it('surfaces the first error that was not drift, and skips the tiers in between', async () => {
        const docs = tiers()
        const first = serverError()
        const client = clientFor(docs, [first, { a: 1, b: 2 }, transportError()])
        await expect(load(client, docs)).rejects.toBe(first)
        expect(client.asked).toEqual([0, 2])
    })

    it('steps down one document when a field failed to resolve, without remembering it', async () => {
        // 200 with errors, no HTTP error status: only the richest document's own fields may be at fault
        const docs = tiers()
        const fieldFailed = Object.assign(new Error('Exception while fetching data (/thing/c)'),
            { errors: [{ message: 'Exception while fetching data (/thing/c)', extensions: { classification: 'DataFetchingException' } }] })
        const client = clientFor(docs, [fieldFailed, { a: 1, b: 2 }, { a: 1 }])
        expect((await load(client, docs)).served).toBe(1)
        await load(client, docs)
        expect(client.asked).toEqual([0, 1, 0, 1])
    })

    it('marks the drift-rejected document even when a later one failed otherwise before the last was served', async () => {
        const docs = tiers()
        const client = clientFor(docs, [validationError(), serverError(), { a: 1 }])
        expect((await load(client, docs)).served).toBe(2)
        await load(client, docs)
        // the drift-rejected FULL is skipped; SCORED, which failed otherwise, is asked again
        expect(client.asked).toEqual([0, 1, 2, 1, 2])
    })

    it('surfaces the last document\'s drift error when every document is rejected', async () => {
        const docs = tiers().slice(1)
        const last = validationError('thing')
        await expect(load(clientFor(docs, [validationError(), last]), docs)).rejects.toBe(last)
    })

    it('remembers no rejection when nothing is served', async () => {
        const docs = tiers()
        await expect(load(clientFor(docs, [wafStripped400(), wafStripped400(), serverError()]), docs)).rejects.toBeTruthy()
        const client = clientFor(docs, [{ a: 1, b: 2, c: 3 }, { a: 1, b: 2 }, { a: 1 }])
        expect((await load(client, docs)).served).toBe(0)
    })
})

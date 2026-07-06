import { describe, it, expect } from 'vitest'
import gql from 'graphql-tag'
import {
    classifyGraphqlError,
    httpStatusOf,
    isSchemaDriftError,
    loadWithSchemaDriftFallback,
    type DriftFallbackClient,
} from './graphqlDriftFallback'
import type { DocumentNode } from 'graphql'

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

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
    stripAdditiveFields,
    withoutAdditiveFields,
    sendWithLatchedFallback,
    artifactScanStateLatch,
    ADDITIVE_FIELD_MARKER,
    BOM_SCAN_FIELD_MARKER,
    sendWithLatchedTiers,
    releaseBomScanLatch,
} from './graphqlDriftFallback'
import { parse, print, type DocumentNode } from 'graphql'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

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

// --- TPF-127: the additive-field primitive, the latched fallback and the artifact scan state latch ---

describe('stripAdditiveFields / withoutAdditiveFields (TPF-127)', () => {
    it('drops every line carrying #additive-field and keeps the others byte for byte', () => {
        const text = 'query q {\n  a\n  b #additive-field\n\t c  \n  d { e } #additive-field trailing\n}'
        expect(stripAdditiveFields(text)).toBe('query q {\n  a\n\t c  \n}')
        expect(stripAdditiveFields('query q { a }')).toBe('query q { a }')
    })

    it('derives the document without its tagged lines from the parsed source', () => {
        // The tagged line holds field b alone (a tagged selection is self-contained on its line).
        const doc = gql`query q { thing {
 a
 b #additive-field
 c } }`
        expect(print(withoutAdditiveFields(doc))).toBe(print(parse('query q { thing { a c } }')))
        const plain = gql`query q { thing { a b c } }`
        expect(print(withoutAdditiveFields(plain))).toBe(print(plain))
    })

    it('refuses a document without a parsed source', () => {
        const noLoc: any = { kind: 'Document', definitions: [] }
        expect(() => withoutAdditiveFields(noLoc)).toThrow()
    })
})

describe('sendWithLatchedFallback (TPF-127, the TPF-101 state machine)', () => {
    const PREFERRED = gql`query p { thing { a b } }`
    const FALLBACK = gql`query f { thing { a } }`
    const latchOf = (supported: boolean) => ({ supported: vi.fn(() => supported), markUnsupported: vi.fn() })

    it('row 1: the preferred document is served; nothing else is sent and the latch stays', async () => {
        const send = vi.fn(async (_doc: DocumentNode) => 'P')
        const latch = latchOf(true)
        await expect(sendWithLatchedFallback({ preferred: PREFERRED, fallback: FALLBACK, send, latch }))
            .resolves.toEqual({ data: 'P', served: 'preferred' })
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(PREFERRED)
        expect(latch.markUnsupported).not.toHaveBeenCalled()
    })

    it.each([['a validation verdict', () => validationError('notScannable')], ['a WAF-stripped 400', () => wafStripped400()]])(
        'row 2: on %s the fallback is sent and the latch is set only after it was served', async (_n, make) => {
            const driftErr = make()
            let resolveFallback: (v: string) => void = () => {}
            const send = vi.fn((doc: DocumentNode) => doc === PREFERRED
                ? Promise.reject(driftErr)
                : new Promise<string>((resolve) => { resolveFallback = resolve }))
            const latch = latchOf(true)
            const pending = sendWithLatchedFallback({ preferred: PREFERRED, fallback: FALLBACK, send, latch })
            await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
            expect(send.mock.calls[0][0]).toBe(PREFERRED)
            expect(send.mock.calls[1][0]).toBe(FALLBACK)
            expect(latch.markUnsupported).not.toHaveBeenCalled()
            resolveFallback('F')
            await expect(pending).resolves.toEqual({ data: 'F', served: 'fallback' })
            expect(latch.markUnsupported).toHaveBeenCalledTimes(1)
            expect(latch.markUnsupported).toHaveBeenCalledWith(driftErr)
        })

    it('row 3: drift then a failing fallback rejects with the fallback error and leaves the latch', async () => {
        const fallbackErr = serverError()
        const send = vi.fn((doc: DocumentNode) => Promise.reject(doc === PREFERRED ? validationError('notScannable') : fallbackErr))
        const latch = latchOf(true)
        await expect(sendWithLatchedFallback({ preferred: PREFERRED, fallback: FALLBACK, send, latch })).rejects.toBe(fallbackErr)
        expect(send).toHaveBeenCalledTimes(2)
        expect(latch.markUnsupported).not.toHaveBeenCalled()
    })

    function dataFetchingError (): any {
        const err: any = new Error('Exception while fetching data (/release/metrics) : boom')
        err.errors = [{ message: 'Exception while fetching data (/release/metrics) : boom', extensions: { classification: 'DataFetchingException' } }]
        return err
    }

    it.each([['401', authError], ['500', serverError], ['transport', transportError], ['DataFetchingException', dataFetchingError]])(
        'row 4: %s on the preferred document is rethrown unchanged with nothing else sent', async (_n, make) => {
            const err = make()
            const send = vi.fn(async (_doc: DocumentNode) => { throw err })
            const latch = latchOf(true)
            await expect(sendWithLatchedFallback({ preferred: PREFERRED, fallback: FALLBACK, send, latch })).rejects.toBe(err)
            expect(send).toHaveBeenCalledTimes(1)
            expect(latch.markUnsupported).not.toHaveBeenCalled()
        })

    it('row 5: a latched session sends the fallback once and leaves the latch', async () => {
        const send = vi.fn(async (_doc: DocumentNode) => 'F')
        const latch = latchOf(false)
        await expect(sendWithLatchedFallback({ preferred: PREFERRED, fallback: FALLBACK, send, latch }))
            .resolves.toEqual({ data: 'F', served: 'fallback' })
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(FALLBACK)
        expect(latch.markUnsupported).not.toHaveBeenCalled()
    })

    it('row 6: a latched session whose fallback fails rejects unchanged', async () => {
        const err = serverError()
        const send = vi.fn(async (_doc: DocumentNode) => { throw err })
        const latch = latchOf(false)
        await expect(sendWithLatchedFallback({ preferred: PREFERRED, fallback: FALLBACK, send, latch })).rejects.toBe(err)
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(FALLBACK)
        expect(latch.markUnsupported).not.toHaveBeenCalled()
    })
})

describe('artifactScanStateLatch (TPF-127)', () => {
    afterEach(() => vi.restoreAllMocks())

    it('reads the store flag and commits the mutation once with one console.error', () => {
        const stub = { state: { artifactScanStateSupported: true }, commit: vi.fn() }
        const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
        const latch = artifactScanStateLatch(stub)
        expect(latch.supported()).toBe(true)
        const err = validationError('notScannable')
        latch.markUnsupported(err)
        expect(stub.commit).toHaveBeenCalledTimes(1)
        expect(stub.commit).toHaveBeenCalledWith('SET_ARTIFACT_SCAN_STATE_SUPPORTED', false)
        expect(errSpy).toHaveBeenCalledTimes(1)
        expect(String(errSpy.mock.calls[0][0])).toContain('notScannable')
        expect(errSpy.mock.calls[0][1]).toBe(err)
        expect(artifactScanStateLatch({ state: { artifactScanStateSupported: false }, commit: vi.fn() }).supported()).toBe(false)
    })

    it('lives in a module that imports no store', () => {
        const text = readFileSync(fileURLToPath(new URL('./graphqlDriftFallback.ts', import.meta.url)), 'utf8')
        expect(text).not.toContain("from '@/store'")
        expect(text).not.toContain("from 'vuex'")
        expect(text).not.toContain("from './store'")
    })
})

// --- TPF-133: the second marker tier, the N-tier sender and the release BOM scan latch ---

describe('the bomScan marker tier (TPF-133)', () => {
    it('the tier marker starts with the base marker', () => {
        expect(BOM_SCAN_FIELD_MARKER).toBe('#additive-field:bomScan')
        expect(BOM_SCAN_FIELD_MARKER.startsWith(ADDITIVE_FIELD_MARKER)).toBe(true)
    })

    it('the default strip drops both tiers; the tier strip drops the bomScan tier alone, byte for byte otherwise', () => {
        const text = 'query q {\n  a\n  b #additive-field\n\t c  \n  d { e } #additive-field:bomScan\n}'
        expect(stripAdditiveFields(text)).toBe('query q {\n  a\n\t c  \n}')
        expect(stripAdditiveFields(text, BOM_SCAN_FIELD_MARKER)).toBe('query q {\n  a\n  b #additive-field\n\t c  \n}')
    })

    it('withoutAdditiveFields by the tier marker keeps the base-tier line and drops the bomScan line', () => {
        const doc = gql`query q { thing {
 a
 b #additive-field
 d { e } #additive-field:bomScan
 c } }`
        expect(print(withoutAdditiveFields(doc, BOM_SCAN_FIELD_MARKER))).toBe(print(parse('query q { thing { a b c } }')))
        expect(print(withoutAdditiveFields(doc))).toBe(print(parse('query q { thing { a c } }')))
    })
})

describe('sendWithLatchedTiers (TPF-133, the TPF-101 state machine over two tiers)', () => {
    const T1 = gql`query t1 { thing { a b c } }`
    const T2 = gql`query t2 { thing { a b } }`
    const CORE_DOC = gql`query core { thing { a } }`
    const latchOf = (supported: boolean) => ({ supported: vi.fn(() => supported), markUnsupported: vi.fn() })
    const run = (send: (doc: DocumentNode) => Promise<string>, b: any, a: any) =>
        sendWithLatchedTiers<string>({ tiers: [{ doc: T1, latch: b }, { doc: T2, latch: a }], core: CORE_DOC, send })
    function dataFetchingError (): any {
        const err: any = new Error('Exception while fetching data (/release/metrics) : boom')
        err.errors = [{ message: 'Exception while fetching data (/release/metrics) : boom', extensions: { classification: 'DataFetchingException' } }]
        return err
    }

    it('row 1: tier 1 served; one send, served 0, no latch moves', async () => {
        const send = vi.fn(async (_doc: DocumentNode) => 'T1')
        const b = latchOf(true); const a = latchOf(true)
        await expect(run(send, b, a)).resolves.toEqual({ data: 'T1', served: 0 })
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(T1)
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).not.toHaveBeenCalled()
    })

    it.each([['a validation verdict', () => validationError('bomScan')], ['a WAF-stripped 400', () => wafStripped400()]])(
        'row 2: on %s of tier 1, tier 2 is sent and B is marked only after tier 2 was served', async (_n, make) => {
            const driftErr = make()
            let resolveT2: (v: string) => void = () => {}
            const send = vi.fn((doc: DocumentNode) => doc === T1
                ? Promise.reject(driftErr)
                : new Promise<string>((resolve) => { resolveT2 = resolve }))
            const b = latchOf(true); const a = latchOf(true)
            const pending = run(send, b, a)
            await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
            expect(send.mock.calls[0][0]).toBe(T1)
            expect(send.mock.calls[1][0]).toBe(T2)
            expect(b.markUnsupported).not.toHaveBeenCalled()
            resolveT2('T2')
            await expect(pending).resolves.toEqual({ data: 'T2', served: 1 })
            expect(b.markUnsupported).toHaveBeenCalledTimes(1)
            expect(b.markUnsupported).toHaveBeenCalledWith(driftErr)
            expect(a.markUnsupported).not.toHaveBeenCalled()
        })

    it('row 3: drift, drift, served core: three sends, served 2, B then A marked after the core resolved', async () => {
        const e1 = validationError('bomScan'); const e2 = validationError('notScannable')
        let resolveCore: (v: string) => void = () => {}
        const order: string[] = []
        const send = vi.fn((doc: DocumentNode) => doc === T1 ? Promise.reject(e1)
            : doc === T2 ? Promise.reject(e2)
                : new Promise<string>((resolve) => { resolveCore = resolve }))
        const b = { supported: vi.fn(() => true), markUnsupported: vi.fn(() => { order.push('B') }) }
        const a = { supported: vi.fn(() => true), markUnsupported: vi.fn(() => { order.push('A') }) }
        const pending = run(send, b, a)
        await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(3))
        expect(send.mock.calls.map((c) => c[0])).toEqual([T1, T2, CORE_DOC])
        expect(order).toEqual([])
        resolveCore('C')
        await expect(pending).resolves.toEqual({ data: 'C', served: 2 })
        expect(order).toEqual(['B', 'A'])
        expect(b.markUnsupported).toHaveBeenCalledWith(e1)
        expect(a.markUnsupported).toHaveBeenCalledWith(e2)
    })

    it('row 4: drift, drift, a failing core rejects with the core error and moves no latch', async () => {
        const coreErr = serverError()
        const send = vi.fn((doc: DocumentNode) => Promise.reject(doc === CORE_DOC ? coreErr : validationError('bomScan')))
        const b = latchOf(true); const a = latchOf(true)
        await expect(run(send, b, a)).rejects.toBe(coreErr)
        expect(send).toHaveBeenCalledTimes(3)
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).not.toHaveBeenCalled()
    })

    it('row 5: drift then a non-drift error of tier 2 is rethrown; two sends, no latch moves', async () => {
        const err = authError()
        const send = vi.fn((doc: DocumentNode) => Promise.reject(doc === T1 ? validationError('bomScan') : err))
        const b = latchOf(true); const a = latchOf(true)
        await expect(run(send, b, a)).rejects.toBe(err)
        expect(send).toHaveBeenCalledTimes(2)
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).not.toHaveBeenCalled()
    })

    it.each([['401', authError], ['500', serverError], ['transport', transportError], ['DataFetchingException', dataFetchingError]])(
        'row 6: %s on tier 1 is rethrown unchanged with nothing else sent', async (_n, make) => {
            const err = make()
            const send = vi.fn(async (_doc: DocumentNode) => { throw err })
            const b = latchOf(true); const a = latchOf(true)
            await expect(run(send, b, a)).rejects.toBe(err)
            expect(send).toHaveBeenCalledTimes(1)
            expect(b.markUnsupported).not.toHaveBeenCalled()
            expect(a.markUnsupported).not.toHaveBeenCalled()
        })

    it('row 7: B down: tier 1 skipped, tier 2 served, served 1, no latch moves', async () => {
        const send = vi.fn(async (_doc: DocumentNode) => 'T2')
        const b = latchOf(false); const a = latchOf(true)
        await expect(run(send, b, a)).resolves.toEqual({ data: 'T2', served: 1 })
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(T2)
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).not.toHaveBeenCalled()
    })

    it('row 8: B down, tier 2 drift, core served: A marked after the core, served 2', async () => {
        const e2 = validationError('notScannable')
        const send = vi.fn((doc: DocumentNode) => doc === T2 ? Promise.reject(e2) : Promise.resolve('C'))
        const b = latchOf(false); const a = latchOf(true)
        await expect(run(send, b, a)).resolves.toEqual({ data: 'C', served: 2 })
        expect(send.mock.calls.map((c) => c[0])).toEqual([T2, CORE_DOC])
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).toHaveBeenCalledTimes(1)
        expect(a.markUnsupported).toHaveBeenCalledWith(e2)
    })

    it('row 9: A down with B up: both tiers skipped, ONE send of the core, served 2, no latch moves', async () => {
        const send = vi.fn(async (_doc: DocumentNode) => 'C')
        const b = latchOf(true); const a = latchOf(false)
        await expect(run(send, b, a)).resolves.toEqual({ data: 'C', served: 2 })
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(CORE_DOC)
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).not.toHaveBeenCalled()
    })

    it.each([[true], [false]])('row 10: B %s, A down, a failing core rejects unchanged', async (bUp) => {
        const err = serverError()
        const send = vi.fn(async (_doc: DocumentNode) => { throw err })
        const b = latchOf(bUp); const a = latchOf(false)
        await expect(run(send, b, a)).rejects.toBe(err)
        expect(send).toHaveBeenCalledTimes(1)
        expect(send.mock.calls[0][0]).toBe(CORE_DOC)
        expect(b.markUnsupported).not.toHaveBeenCalled()
        expect(a.markUnsupported).not.toHaveBeenCalled()
    })
})

describe('releaseBomScanLatch (TPF-133)', () => {
    afterEach(() => vi.restoreAllMocks())

    it('reads the store flag and commits the mutation once with one console.error naming bomScan', () => {
        const stub = { state: { releaseBomScanSupported: true }, commit: vi.fn() }
        const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
        const latch = releaseBomScanLatch(stub)
        expect(latch.supported()).toBe(true)
        const err = validationError('bomScan')
        latch.markUnsupported(err)
        expect(stub.commit).toHaveBeenCalledTimes(1)
        expect(stub.commit).toHaveBeenCalledWith('SET_RELEASE_BOM_SCAN_SUPPORTED', false)
        expect(errSpy).toHaveBeenCalledTimes(1)
        expect(String(errSpy.mock.calls[0][0])).toContain('bomScan')
        expect(errSpy.mock.calls[0][1]).toBe(err)
        expect(releaseBomScanLatch({ state: { releaseBomScanSupported: false }, commit: vi.fn() }).supported()).toBe(false)
    })
})

// Generic schema-drift tolerance for GraphQL queries.
//
// The SAME UI ships to a Pro backend and to CE installs whose backend is a
// delayed mirror of Pro. A field the UI selects that exists in Pro but not
// yet in the deployed schema makes the WHOLE document fail validation
// (FieldUndefined) -> the query is rejected and whatever it feeds blanks for
// every user, for the entire mirror-lag window.
//
// This module provides the reusable primitive: given a FULL document and a
// narrower CORE document that selects only fields guaranteed to exist on
// every backend, `loadWithSchemaDriftFallback` tries FULL and, if the server
// rejects it as a schema-validation problem, retries CORE so the surface
// still renders (flagged `degraded`). Transport / auth / server errors are
// NOT swallowed -- they surface unchanged. Any query can adopt this by
// passing its two documents and a data selector; see notificationInboxQuery.ts
// for the inbox's use. A surface with more than two documents, or that should
// stop asking for a rejected one for a while, uses `loadRichestServed`
// instead (the findings modal, the vulnerability details panel).

import type { DocumentNode } from 'graphql'

export type GraphqlErrorKind = 'validation' | 'network' | 'other'

// Gather message strings, graphql-error `classification` extensions, and any
// HTTP status, across the Apollo v4 error shapes:
//   - CombinedGraphQLErrors (200 + errors[])  -> err.errors (+ extensions)
//   - ServerError (non-2xx)                    -> err.statusCode + err.bodyText
//   - legacy nesting                           -> err.networkError.result.errors
//   - transport failure (no response)          -> no status anywhere
// A validation error's body can be JSON (errors[]) OR stripped to an opaque
// page by an edge proxy / WAF -- in which case the only surviving signal is
// the HTTP status.
function errorContainers (err: any): any[] {
    return [err, err.networkError, err.cause, err.response].filter(Boolean)
}

// The GraphQL errors[] entries an Apollo error carries, across the shapes above, with their
// extensions (code, classification, reason ...). Empty for a transport failure or an opaque body.
export function graphqlErrorsOf (err: any): any[] {
    const out: any[] = []
    if (!err) return out
    for (const c of errorContainers(err)) {
        if (Array.isArray(c.errors)) out.push(...c.errors)
        if (Array.isArray(c.graphQLErrors)) out.push(...c.graphQLErrors)
        if (Array.isArray(c.result?.errors)) out.push(...c.result.errors)
        // Some links hand back the raw body as text; try to recover errors[].
        if (typeof c.bodyText === 'string') {
            try {
                const parsed = JSON.parse(c.bodyText)
                if (Array.isArray(parsed?.errors)) out.push(...parsed.errors)
            } catch { /* opaque (e.g. WAF HTML) -- status is the only signal */ }
        }
    }
    return out
}

function collectErrorSignals (err: any): { messages: string[], classifications: string[], statusCode?: number } {
    const messages: string[] = []
    const classifications: string[] = []
    let statusCode: number | undefined
    if (!err) return { messages, classifications, statusCode }
    if (typeof err.message === 'string') messages.push(err.message)

    for (const c of errorContainers(err)) {
        if (typeof c.statusCode === 'number') statusCode ??= c.statusCode
        else if (typeof c.status === 'number') statusCode ??= c.status
    }
    for (const e of graphqlErrorsOf(err)) {
        if (typeof e?.message === 'string') messages.push(e.message)
        const cls = e?.extensions?.classification
        if (typeof cls === 'string') classifications.push(cls)
    }
    return { messages, classifications, statusCode }
}

// The HTTP status the server assigned, if any. Undefined for a transport
// failure that never got a response.
export function httpStatusOf (err: any): number | undefined {
    return collectErrorSignals(err).statusCode
}

// Classify an Apollo error:
//   - 'validation' : the server's verdict is that the document is invalid
//                    against its schema (a selected field/type/arg is absent).
//   - 'network'    : no response reached us (transport failure).
//   - 'other'      : the server responded with an error we can't attribute
//                    from its body (e.g. a WAF-stripped 400).
export function classifyGraphqlError (err: any): GraphqlErrorKind {
    const { messages, classifications, statusCode } = collectErrorSignals(err)
    const validationClassified = classifications.some(c => c === 'ValidationError' || c === 'ExecutableDefinitionsRule')
    // graphql-java / DGS phrasings and graphql-js phrasings both covered.
    const validationPhrased = messages.some(m =>
        /validation error/i.test(m)
        || /cannot query field/i.test(m)
        || /field\s+.+\s+is undefined/i.test(m)
        || /fieldundefined/i.test(m)
        || /unknown (field|type|argument)/i.test(m)
        || /is not defined by type/i.test(m),
    )
    if (validationClassified || validationPhrased) return 'validation'
    if (statusCode === undefined && messages.some(m => /failed to fetch|networkerror|network error|load failed|econn|etimedout|socket|fetch failed|aborted|timed out/i.test(m))) {
        return 'network'
    }
    return 'other'
}

// Is this error plausibly *schema drift* (a selected field the deployed schema
// lacks), such that retrying a narrower selection could succeed? Only two
// cases qualify:
//   - a classifiable validation verdict (200-with-errors, or JSON error body);
//   - an HTTP 400, which per the GraphQL-over-HTTP spec is the status for a
//     validation error -- even when an edge proxy strips the body to an opaque
//     page, so the classifier can't read it.
// Crucially NOT: 401/403 (auth), 5xx (server), or transport failures -- those
// are transient/unrelated and must surface, never be silently degraded.
export function isSchemaDriftError (err: any): boolean {
    if (classifyGraphqlError(err) === 'validation') return true
    return httpStatusOf(err) === 400
}

export interface DriftFallbackClient {
    query (opts: { query: DocumentNode, variables: Record<string, any>, fetchPolicy?: string }): Promise<{ data?: any }>
}

export interface DriftFallbackOptions {
    fullQuery: DocumentNode
    coreQuery: DocumentNode
    variables: Record<string, any>
    // Pull the payload out of the query's data (e.g. d => d.notificationInbox).
    extractPath: (data: any) => any
    // When true, skip the FULL attempt and go straight to CORE. Callers set
    // this once a prior load proved the deployed backend rejects FULL, to
    // avoid paying the reject-then-retry round-trip on every subsequent load.
    skipFull?: boolean
}

export interface DriftFallbackResult {
    data: any
    // true when only the CORE selection was served -- rows render but
    // enrichment fields are absent.
    degraded: boolean
}

export async function loadWithSchemaDriftFallback (
    client: DriftFallbackClient,
    opts: DriftFallbackOptions,
): Promise<DriftFallbackResult> {
    const { fullQuery, coreQuery, variables, extractPath, skipFull } = opts
    let fullError: any
    if (!skipFull) {
        try {
            const res = await client.query({ query: fullQuery, variables, fetchPolicy: 'network-only' })
            return { data: extractPath(res.data), degraded: false }
        } catch (err: any) {
            // Only a schema-drift-shaped failure warrants a narrower retry.
            // Transport / auth / server errors surface unchanged.
            if (!isSchemaDriftError(err)) throw err
            fullError = err
        }
    }
    try {
        const res = await client.query({ query: coreQuery, variables, fetchPolicy: 'network-only' })
        return { data: extractPath(res.data), degraded: true }
    } catch (err) {
        // The narrower query failed too. If we had a FULL error, surface THAT
        // (it reflects the real first failure); otherwise surface this one.
        throw fullError ?? err
    }
}

// How long a document the backend rejected as schema drift is skipped before it is tried again.
export const SCHEMA_DRIFT_RETRY_MS = 10 * 60 * 1000

// When each document was last rejected as schema drift while a narrower one
// was served. Module-level: every surface on the page talks to one backend.
const driftRejectedAt = new WeakMap<DocumentNode, number>()

// Whether the backend rejected `doc` as schema drift within the last SCHEMA_DRIFT_RETRY_MS.
export function isDriftRejected (doc: DocumentNode): boolean {
    return Date.now() - (driftRejectedAt.get(doc) ?? 0) < SCHEMA_DRIFT_RETRY_MS
}

// Remember that the backend rejected `doc` as schema drift (and served a narrower one).
export function markDriftRejected (doc: DocumentNode): void {
    driftRejectedAt.set(doc, Date.now())
}

export interface RichestServedOptions {
    // Richest first; the last selects only fields every backend serves.
    documents: DocumentNode[]
    variables: Record<string, any>
    extractPath: (data: any) => any
}

export interface RichestServedResult {
    data: any
    // Index into documents of the one served; 0 is the richest.
    served: number
}

// The richest of several documents the backend serves, for a surface whose
// optional fields a CE backend may lag. Unlike loadWithSchemaDriftFallback,
// which retries its CORE document on drift only and surfaces the FULL error,
// any failure of a richer document here falls back, and the rejections are
// remembered. Queries go out uncached, as one-off payloads.
//  - A schema-drift rejection steps down one document. Once a narrower one
//    is served, the rejected ones are skipped for SCHEMA_DRIFT_RETRY_MS; if
//    nothing is served, nothing is remembered (a blanket 400 from an edge
//    proxy reads as drift and must not hide the fields after it clears).
//  - An error the server answered without an HTTP error status (a field that
//    failed to resolve) also steps down one document, unremembered: only the
//    fields that document adds may be at fault.
//  - A transport failure or an HTTP error status goes straight to the last
//    document: a struggling backend should not pay for the ones in between.
//  - When the last document fails too, the first error that was not drift
//    surfaces, else the last document's own.
export async function loadRichestServed (client: DriftFallbackClient, opts: RichestServedOptions): Promise<RichestServedResult> {
    const { documents, variables, extractPath } = opts
    const last = documents.length - 1
    const rejected: DocumentNode[] = []
    let firstOtherError: unknown
    let skipToLast = false
    for (let i = 0; i <= last; i++) {
        const query = documents[i]
        if (i < last && (skipToLast || isDriftRejected(query))) continue
        try {
            const res = await client.query({ query, variables, fetchPolicy: 'no-cache' })
            const data = extractPath(res.data)
            rejected.forEach(markDriftRejected)
            return { data, served: i }
        } catch (err) {
            if (i === last) throw firstOtherError ?? err
            if (isSchemaDriftError(err)) {
                rejected.push(query)
            } else {
                firstOtherError ??= err
                const unanswered = httpStatusOf(err) !== undefined || classifyGraphqlError(err) === 'network'
                if (unanswered) skipToLast = true
            }
        }
    }
    throw new Error('loadRichestServed needs at least one document')
}

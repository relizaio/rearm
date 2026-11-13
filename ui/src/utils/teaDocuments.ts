// TEA documents with additive fields (task FDAW-39, design 3.2): the TEA settings editor, its dry-runs
// and the membership dry-run select the publish-at-shipped fields and the collection version count on
// lines tagged #additive-field. A backend that lags (the CE backend until its catch-up) rejects such a
// document whole; these reads then ask the same document without those lines, with the same variables
// and fetch policy, and say which form was served. Only a schema-drift rejection is retried.

import type { DocumentNode } from 'graphql'
import graphqlQueries from './graphqlQueries'
import { isSchemaDriftError, withoutAdditiveFields } from './graphqlDriftFallback'

const coreForms = new WeakMap<object, DocumentNode>()

/** The document without its #additive-field lines, computed on first use and kept per document. */
export function teaCoreDocument (doc: DocumentNode): DocumentNode {
    let core = coreForms.get(doc)
    if (!core) {
        core = withoutAdditiveFields(doc)
        coreForms.set(doc, core)
    }
    return core
}

export interface TeaDocumentClient {
    query (options: any): Promise<{ data?: any }>
}

/**
 * One read of a document that may carry additive fields. `options` is what the call site would pass
 * to `client.query`. The full document is sent first; only when the backend rejects it as schema
 * drift is the core form sent (`additive` false). When that fails too the first error is thrown;
 * any other error is thrown unchanged after the one call.
 */
export async function loadTeaDocument (client: TeaDocumentClient, options: any): Promise<{ data: any, additive: boolean }> {
    let first: any
    try {
        const resp: any = await client.query({ ...options })
        return { data: resp?.data, additive: true }
    } catch (err: any) {
        if (!isSchemaDriftError(err)) throw err
        first = err
    }
    try {
        const resp: any = await client.query({ ...options, query: teaCoreDocument(options.query) })
        return { data: resp?.data, additive: false }
    } catch {
        throw first
    }
}

/**
 * The preview's children of a release (TeaPublishPreview.children), in the answered order, or
 * `children: null` when the backend does not serve the field (schema drift). Any other error is thrown.
 */
export async function loadTeaPublishChildren (client: TeaDocumentClient, release: string): Promise<{ children: any[] | null }> {
    try {
        const resp: any = await client.query({
            query: graphqlQueries.ReleaseTeaPublishChildrenGql,
            variables: { release },
            fetchPolicy: 'no-cache',
        })
        return { children: resp?.data?.releaseTeaPublicationView?.children ?? null }
    } catch (err: any) {
        if (isSchemaDriftError(err)) return { children: null }
        throw err
    }
}

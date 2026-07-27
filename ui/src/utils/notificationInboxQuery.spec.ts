import { describe, it, expect } from 'vitest'
import {
    loadNotificationInboxPage,
    INBOX_QUERY_FULL,
    INBOX_QUERY_CORE,
    INBOX_QUERY_FULL_SCOPED,
    INBOX_CORE_ITEM_FIELDS,
    INBOX_ENRICHMENT_ITEM_FIELDS,
} from './notificationInboxQuery'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import { print, type DocumentNode } from 'graphql'

// The generic retry/classify logic is covered in graphqlDriftFallback.spec.ts.
// These tests cover the inbox WIRING: field partitioning, the two documents,
// and that the wrapper maps degraded + page through correctly.

function validationError (): any {
    const err: any = new Error("Validation error of type FieldUndefined: Field 'channelName' is undefined")
    err.errors = [{ message: "Field 'channelName' is undefined", extensions: { classification: 'ValidationError' } }]
    return err
}

describe('inbox field partitioning', () => {
    it('keeps core and enrichment field sets disjoint', () => {
        const overlap = INBOX_CORE_ITEM_FIELDS.filter(f => INBOX_ENRICHMENT_ITEM_FIELDS.includes(f))
        expect(overlap).toEqual([])
    })
    it('has a non-empty enrichment set (otherwise the split is pointless)', () => {
        expect(INBOX_ENRICHMENT_ITEM_FIELDS.length).toBeGreaterThan(0)
    })
    it('builds distinct FULL and CORE documents', () => {
        expect(INBOX_QUERY_FULL).not.toBe(INBOX_QUERY_CORE)
    })
    it('scoped documents are distinct and carry $inboxScope; arg-less ones do not', () => {
        expect(INBOX_QUERY_FULL_SCOPED).not.toBe(INBOX_QUERY_FULL)
        expect(print(INBOX_QUERY_FULL_SCOPED)).toContain('inboxScope')
        expect(print(INBOX_QUERY_FULL)).not.toContain('inboxScope')
    })
})

describe('loadNotificationInboxPage', () => {
    it('returns the full page and is not degraded when the backend accepts full', async () => {
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                expect(query).toBe(INBOX_QUERY_FULL)
                return { data: { notificationInbox: { items: [{ uuid: 'a', channelName: '#sec' }], totalCount: 1 } } }
            },
        }
        const res = await loadNotificationInboxPage(client, { orgUuid: 'o1' })
        expect(res.degraded).toBe(false)
        expect(res.page.items[0].channelName).toBe('#sec')
    })

    it('falls back to core (degraded) when the full selection is rejected as drift', async () => {
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                if (query === INBOX_QUERY_CORE) {
                    return { data: { notificationInbox: { items: [{ uuid: 'a' }], totalCount: 1 } } }
                }
                throw validationError()
            },
        }
        const res = await loadNotificationInboxPage(client, { orgUuid: 'o1' })
        expect(res.degraded).toBe(true)
        expect(res.page.items).toHaveLength(1)
        expect('channelName' in res.page.items[0]).toBe(false)
    })

    it('uses the arg-less document when no inboxScope is given (drift-safe default)', async () => {
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                expect(query).toBe(INBOX_QUERY_FULL)
                return { data: { notificationInbox: { items: [], totalCount: 0 } } }
            },
        }
        await loadNotificationInboxPage(client, { orgUuid: 'o1' })
    })

    it('uses the scoped document only when inboxScope is provided (ORG_ALL)', async () => {
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                expect(query).toBe(INBOX_QUERY_FULL_SCOPED)
                return { data: { notificationInbox: { items: [], totalCount: 0 } } }
            },
        }
        const res = await loadNotificationInboxPage(client, { orgUuid: 'o1', inboxScope: 'ORG_ALL' })
        expect(res.degraded).toBe(false)
    })

    it('degrades a scoped request to the arg-less PERSONAL query when the backend lacks inboxScope', async () => {
        const seen: DocumentNode[] = []
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                seen.push(query)
                // Both scoped documents fail as schema drift; the arg-less
                // documents then succeed.
                if (query === INBOX_QUERY_FULL || query === INBOX_QUERY_CORE) {
                    return { data: { notificationInbox: { items: [{ uuid: 'a' }], totalCount: 1 } } }
                }
                throw validationError()
            },
        }
        const res = await loadNotificationInboxPage(client, { orgUuid: 'o1', inboxScope: 'ORG_ALL' })
        expect(res.scopeUnsupported).toBe(true)
        expect(res.page.items).toHaveLength(1)
        expect(seen).toContain(INBOX_QUERY_FULL_SCOPED)
        expect(seen.some(q => q === INBOX_QUERY_FULL || q === INBOX_QUERY_CORE)).toBe(true)
    })

    it('does NOT degrade scope on a non-drift error (surfaces it)', async () => {
        const client: DriftFallbackClient = {
            async query () { throw new Error('Failed to fetch') },
        }
        await expect(
            loadNotificationInboxPage(client, { orgUuid: 'o1', inboxScope: 'ORG_ALL' }),
        ).rejects.toThrow(/failed to fetch/i)
    })

    it('skipFull requests only the core document', async () => {
        let calls = 0
        const client: DriftFallbackClient = {
            async query ({ query }: { query: DocumentNode }) {
                calls++
                expect(query).toBe(INBOX_QUERY_CORE)
                return { data: { notificationInbox: { items: [], totalCount: 0 } } }
            },
        }
        const res = await loadNotificationInboxPage(client, { orgUuid: 'o1' }, true)
        expect(res.degraded).toBe(true)
        expect(calls).toBe(1)
    })
})

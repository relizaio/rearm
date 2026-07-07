import { describe, it, expect } from 'vitest'
import {
    loadNotificationInboxPage,
    INBOX_QUERY_FULL,
    INBOX_QUERY_CORE,
    INBOX_CORE_ITEM_FIELDS,
    INBOX_ENRICHMENT_ITEM_FIELDS,
} from './notificationInboxQuery'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { DocumentNode } from 'graphql'

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

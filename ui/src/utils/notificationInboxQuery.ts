// Inbox query selection + its drift-tolerant loader (built on the reusable
// graphqlDriftFallback primitive).
//
// A field the inbox selects that exists in Pro but not yet in the deployed
// (CE) schema would make the WHOLE notificationInbox document fail validation
// and blank the inbox. Split the selection into CORE (fields a row must have
// to render and function) and ENRICHMENT (server-side conveniences the UI can
// live without); loadNotificationInboxPage serves CORE if the backend rejects
// the full selection, so rows still render.

import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import {
    loadWithSchemaDriftFallback,
    type DriftFallbackClient,
} from '@/utils/graphqlDriftFallback'

// Fields the inbox cannot render a useful row without. These MUST exist on
// every backend the shared UI talks to (Pro and the CE mirror) -- the
// schema-drift test asserts exactly that.
export const INBOX_CORE_ITEM_FIELDS: string[] = [
    'uuid', 'org', 'outboxEventUuid', 'subscriptionUuid', 'channelUuid',
    'status', 'origin', 'dedupKey', 'attemptCount', 'nextAttemptAt', 'sentAt',
    'lastError', 'createdDate', 'readAt', 'eventType', 'severity',
    'title', 'description', 'payloadJson',
]

// Server-resolved conveniences. Allowed to be Pro-ahead-of-CE: their absence
// degrades gracefully (e.g. channelName -> a neutral label) instead of
// blanking the inbox. Every field added here MUST be read in the UI only
// behind a presence guard (`'field' in row`) so a degraded row can't throw.
export const INBOX_ENRICHMENT_ITEM_FIELDS: string[] = [
    'channelName',
    // Channel enabled/auto-disable state so the label can distinguish a
    // disabled/misconfigured channel from a deleted one (see channelLabel).
    'channelEnabled',
    'channelDisabledReason',
]

function buildInboxQuery (itemFields: string[]): DocumentNode {
    return gql`
        query notificationInbox(
            $orgUuid: ID!,
            $unreadOnly: Boolean,
            $status: NotificationDeliveryStatusEnum,
            $eventType: NotificationEventTypeEnum,
            $limit: Int,
            $offset: Int
        ) {
            notificationInbox(
                orgUuid: $orgUuid,
                unreadOnly: $unreadOnly,
                status: $status,
                eventType: $eventType,
                limit: $limit,
                offset: $offset
            ) {
                items { ${itemFields.join(' ')} }
                totalCount unreadCount limit offset
            }
        }
    `
}

export const INBOX_QUERY_FULL: DocumentNode = buildInboxQuery(
    [...INBOX_CORE_ITEM_FIELDS, ...INBOX_ENRICHMENT_ITEM_FIELDS],
)
export const INBOX_QUERY_CORE: DocumentNode = buildInboxQuery(INBOX_CORE_ITEM_FIELDS)

export interface InboxPageResult {
    page: any
    // true when the full selection was rejected and we fell back to core --
    // rows are present but enrichment (e.g. channelName) is absent.
    degraded: boolean
}

// Load one inbox page, tolerating schema drift. Pass `skipFull=true` once a
// prior load has already proved the deployed backend rejects the full
// selection, to skip the reject-then-retry round-trip on subsequent loads.
export async function loadNotificationInboxPage (
    client: DriftFallbackClient,
    variables: Record<string, any>,
    skipFull = false,
): Promise<InboxPageResult> {
    const { data, degraded } = await loadWithSchemaDriftFallback(client, {
        fullQuery: INBOX_QUERY_FULL,
        coreQuery: INBOX_QUERY_CORE,
        variables,
        extractPath: (d: any) => d?.notificationInbox,
        skipFull,
    })
    return { page: data, degraded }
}

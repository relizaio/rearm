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
    isSchemaDriftError,
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

// `withScope` adds the Phase-2c `inboxScope` arg (admin "All org activity"
// toggle). It is OMITTED from the default documents on purpose: PERSONAL is the
// backend default, so the arg-less query works against any backend (incl. a CE
// mirror that predates inboxScope). Only an admin opting into ORG_ALL uses the
// scoped variant, so the new arg never breaks the default path.
function buildInboxQuery (itemFields: string[], withScope = false): DocumentNode {
    const scopeVar = withScope ? ', $inboxScope: NotificationInboxScope' : ''
    const scopeArg = withScope ? ', inboxScope: $inboxScope' : ''
    return gql`
        query notificationInbox(
            $orgUuid: ID!,
            $unreadOnly: Boolean,
            $status: NotificationDeliveryStatusEnum,
            $eventType: NotificationEventTypeEnum,
            $limit: Int,
            $offset: Int${scopeVar}
        ) {
            notificationInbox(
                orgUuid: $orgUuid,
                unreadOnly: $unreadOnly,
                status: $status,
                eventType: $eventType,
                limit: $limit,
                offset: $offset${scopeArg}
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
// Scoped variants (carry $inboxScope), used only when a non-default scope is requested.
export const INBOX_QUERY_FULL_SCOPED: DocumentNode = buildInboxQuery(
    [...INBOX_CORE_ITEM_FIELDS, ...INBOX_ENRICHMENT_ITEM_FIELDS], true,
)
export const INBOX_QUERY_CORE_SCOPED: DocumentNode = buildInboxQuery(INBOX_CORE_ITEM_FIELDS, true)

export interface InboxPageResult {
    page: any
    // true when the full selection was rejected and we fell back to core --
    // rows are present but enrichment (e.g. channelName) is absent.
    degraded: boolean
    // true when a scoped (ORG_ALL) request hit a backend WITHOUT the inboxScope
    // arg and was served the arg-less PERSONAL query instead. The caller should
    // reset its scope toggle to PERSONAL and inform the user.
    scopeUnsupported: boolean
}

// Load one inbox page, tolerating schema drift. Pass `skipFull=true` once a
// prior load has already proved the deployed backend rejects the full
// selection, to skip the reject-then-retry round-trip on subsequent loads.
export async function loadNotificationInboxPage (
    client: DriftFallbackClient,
    variables: Record<string, any>,
    skipFull = false,
): Promise<InboxPageResult> {
    const extractPath = (d: any) => d?.notificationInbox
    // Use the scoped documents only when a scope is actually requested (an admin
    // on ORG_ALL). Absent inboxScope -> the arg-less documents, which the spec
    // pins and which stay safe against a backend without the arg.
    const scoped = variables.inboxScope != null
    if (scoped) {
        try {
            const { data, degraded } = await loadWithSchemaDriftFallback(client, {
                fullQuery: INBOX_QUERY_FULL_SCOPED,
                coreQuery: INBOX_QUERY_CORE_SCOPED,
                variables, extractPath, skipFull,
            })
            return { page: data, degraded, scopeUnsupported: false }
        } catch (err: any) {
            // A backend without the inboxScope arg (e.g. a CE mirror predating
            // Phase 2a) rejects BOTH scoped documents as schema drift. Degrade to
            // the arg-less PERSONAL query so the inbox still renders instead of
            // erroring, and flag scopeUnsupported so the caller resets the toggle.
            // Non-drift errors (auth / network / server) surface unchanged.
            if (!isSchemaDriftError(err)) throw err
            const { inboxScope, ...personalVars } = variables
            const { data, degraded } = await loadWithSchemaDriftFallback(client, {
                fullQuery: INBOX_QUERY_FULL,
                coreQuery: INBOX_QUERY_CORE,
                variables: personalVars, extractPath, skipFull,
            })
            return { page: data, degraded, scopeUnsupported: true }
        }
    }
    const { data, degraded } = await loadWithSchemaDriftFallback(client, {
        fullQuery: INBOX_QUERY_FULL,
        coreQuery: INBOX_QUERY_CORE,
        variables, extractPath, skipFull,
    })
    return { page: data, degraded, scopeUnsupported: false }
}

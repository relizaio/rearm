// Shared, stateless pieces for the notification surfaces that were split
// out of the former NotificationsOfOrg monolith (channel groups,
// subscriptions, delivery history, inbox). Option arrays, tag/format/error
// helpers, the row interfaces, and the survived read-only GraphQL queries
// live here so the host components stay in sync instead of re-declaring
// them five times over.

import gql from 'graphql-tag'
import commonFunctions from '@/utils/commonFunctions'

export type NaiveTagType = 'success' | 'warning' | 'error' | 'info' | 'default'

// ---- Row shapes (shared across surfaces) --------------------------------

export interface ChannelRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    type: string
    status: string
    // Hibernate @Version-managed revision captured on Edit-load; sent
    // back as expectedRevision on save so a concurrent admin edit gets
    // rejected with a "Conflict:" error instead of silently winning.
    revision: number
}

export interface ChannelGroupRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    channels: string[]
    revision: number
    createdDate: string | null
    lastUpdatedDate: string | null
}

export interface SubscriptionRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    status: string
    eventTypes: string[]
    filter: string | null         // JSON-stringified server-side
    routes: string | null         // JSON-stringified server-side
    dedupWindowMinutes: number | null
    rateLimit: string | null      // JSON-stringified server-side
    // See ChannelRow.revision — same optimistic-locking gate.
    revision: number
}

export interface DeliveryRow {
    uuid: string
    org: string
    outboxEventUuid: string
    subscriptionUuid: string | null
    // Null for targeted (per-user) approval deliveries — no channel involved.
    channelUuid: string | null
    status: string
    origin: string
    dedupKey: string | null
    attemptCount: number
    nextAttemptAt: string | null
    sentAt: string | null
    lastError: string | null
    createdDate: string
}

export interface InboxRow {
    uuid: string
    org: string
    outboxEventUuid: string
    subscriptionUuid: string | null
    // Null for targeted (per-user) approval deliveries — no channel involved.
    channelUuid: string | null
    // Server-resolved channel display name (added alongside channelUuid so the
    // inbox doesn't need an admin-only channel-list fetch to render the name).
    // Null when the channel has been deleted or for channel-less targeted rows.
    channelName: string | null
    status: string
    origin: string
    dedupKey: string | null
    attemptCount: number
    nextAttemptAt: string | null
    sentAt: string | null
    lastError: string | null
    createdDate: string
    readAt: string | null
    eventType: string | null
    severity: string | null
    title: string | null
    description: string | null
    payloadJson: string | null
}

// ---- Labels + option arrays ---------------------------------------------

export const TYPE_LABELS: Record<string, string> = {
    SLACK: 'Slack',
    WEBHOOK: 'Webhook',
    MS_TEAMS: 'MS Teams',
    SENTINEL: 'Microsoft Sentinel',
    EMAIL: 'Email',
}

// Email omitted — email channels are managed from the Integrations catalog card.
export const typeOptions = [
    { label: 'Slack', value: 'SLACK' },
    { label: 'Microsoft Teams', value: 'MS_TEAMS' },
    { label: 'Generic Webhook', value: 'WEBHOOK' },
    // Ships to an Azure Log Analytics workspace — Sentinel is the usual
    // consumer but any Log Analytics workspace works.
    { label: 'Microsoft Sentinel (Azure Log Analytics)', value: 'SENTINEL' },
]

export const webhookAuthOptions = [
    { label: 'None (URL secrecy + TLS only)', value: 'NONE' },
    { label: 'Bearer token', value: 'BEARER' },
    { label: 'HMAC-SHA256', value: 'HMAC_SHA256' },
]

export const subscriptionStatusOptions = [
    { label: 'Active (deliveries go out)', value: 'ACTIVE' },
    { label: 'Disabled (no dispatch)', value: 'DISABLED' },
    { label: 'Preview (rows land but no dispatch)', value: 'PREVIEW' },
]

export const eventTypeOptions = [
    { label: 'New vuln affects releases', value: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Vulnerability record updated', value: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'VEX state changed', value: 'VEX_STATE_CHANGED' },
    { label: 'Release created', value: 'RELEASE_CREATED' },
    { label: 'Release lifecycle changed', value: 'RELEASE_LIFECYCLE_CHANGED' },
    { label: 'Release BOM diff', value: 'RELEASE_BOM_DIFF' },
    { label: 'Approval requested', value: 'APPROVAL_REQUESTED' },
    { label: 'Approval resolved', value: 'APPROVAL_RESOLVED' },
]

export const severityOptions = [
    { label: 'CRITICAL', value: 'CRITICAL' },
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' },
    { label: 'INFO', value: 'INFO' },
]

export const deliveryStatusOptions = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'SENT', value: 'SENT' },
    { label: 'ACKED', value: 'ACKED' },
    { label: 'FAILED', value: 'FAILED' },
    { label: 'RATE_LIMITED', value: 'RATE_LIMITED' },
    { label: 'EVAL_TIMEOUT', value: 'EVAL_TIMEOUT' },
    { label: 'TEST', value: 'TEST' },
    { label: 'PREVIEW', value: 'PREVIEW' },
]

export const deliveryOriginOptions = [
    { label: 'REAL', value: 'REAL' },
    { label: 'SYNTHETIC (test)', value: 'SYNTHETIC' },
]

// ---- Tag-type helpers ---------------------------------------------------

export function deliveryStatusTagType (status: string): NaiveTagType {
    if (status === 'SENT' || status === 'ACKED') return 'success'
    if (status === 'FAILED' || status === 'EVAL_TIMEOUT' || status === 'RATE_LIMITED') return 'error'
    if (status === 'PREVIEW' || status === 'TEST') return 'info'
    return 'default'
}

export function severityTagType (severity: string): NaiveTagType {
    if (severity === 'CRITICAL' || severity === 'HIGH') return 'error'
    if (severity === 'MEDIUM') return 'warning'
    if (severity === 'LOW') return 'info'
    // INFO and NONE collapse to the neutral default — a triage queue
    // shouldn't fight for the eye on informational events. Separating
    // LOW (info-blue) from INFO (default-grey) preserves the backend
    // enum distinction in the UI.
    return 'default'
}

// ---- Format helpers -----------------------------------------------------

// Route through commonFunctions.dateDisplay so timestamps stay in the
// en-CA locale convention used elsewhere in the UI (ReleaseView etc.).
// The helper doesn't itself guard null, so we do.
export function formatHistoryTimestamp (s: string | null): string {
    if (!s) return '—'
    try { return commonFunctions.dateDisplay(s) } catch { return s }
}

export function truncate (s: string | null, n: number): string {
    if (!s) return ''
    return s.length > n ? `${s.slice(0, n - 1)}…` : s
}

// ---- Error helpers ------------------------------------------------------

export function extractError (e: any): string {
    return commonFunctions.parseGraphQLError(commonFunctions.extractGraphQLErrorMessage(e))
}

/**
 * Detect the backend's optimistic-lock conflict marker. The upsert
 * services throw a RelizaException with the "Conflict:" prefix when the
 * expectedRevision in the input doesn't match the row's current revision.
 */
export function isConflictError (msg: string): boolean {
    return typeof msg === 'string' && msg.startsWith('Conflict:')
}

// ---- Name-map factory ---------------------------------------------------

// Build a uuid -> name lookup from a loaded row list. Replaces the
// per-component channelNameById / subscriptionNameById computeds — the
// history + inbox surfaces resolve channel/subscription uuids to display
// names from their own fetched lists.
export function buildNameMap (rows: Array<{ uuid: string, name: string }>): Record<string, string> {
    const m: Record<string, string> = {}
    for (const r of rows) m[r.uuid] = r.name
    return m
}

// ---- Survived read-only queries -----------------------------------------

export const LIST_CHANNELS_QUERY = gql`
    query notificationChannels($orgUuid: ID!) {
        notificationChannels(orgUuid: $orgUuid) {
            uuid org resourceGroup name type status revision
        }
    }
`

export const LIST_GROUPS_QUERY = gql`
    query notificationChannelGroups($orgUuid: ID!) {
        notificationChannelGroups(orgUuid: $orgUuid) {
            uuid org resourceGroup name channels revision createdDate lastUpdatedDate
        }
    }
`

export const LIST_SUBSCRIPTIONS_QUERY = gql`
    query notificationSubscriptions($orgUuid: ID!) {
        notificationSubscriptions(orgUuid: $orgUuid) {
            uuid org resourceGroup name status eventTypes
            filter routes dedupWindowMinutes rateLimit revision
        }
    }
`

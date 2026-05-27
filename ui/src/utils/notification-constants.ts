/**
 * Authoritative TypeScript counterpart to the Java enums under
 * rearm-core/backend/src/main/java/io/reliza/model/saas/.
 *
 * Values MUST stay in lock-step with their Java twins — drift between
 * UI string literals and backend enum names produces silent
 * serialization mismatches (GraphQL passes the string through, JPA
 * rejects on the way to the DB, or — worse — the UI dropdown writes a
 * value that backend code never compares against).
 *
 * Each constant array is exported in its naive-ui `n-select` shape
 * (`{ label, value }[]`) and paired with a derived TypeScript union
 * type so call sites get both the runtime list and compile-time
 * narrowing from a single source.
 *
 * Java-side correspondence:
 *   NotificationChannelType         → NotificationChannelType
 *   NotificationChannelStatus       → NotificationChannelStatus
 *   NotificationSubscriptionStatus  → NotificationSubscriptionStatus
 *   NotificationOutboxStatus (delivery-row status) → NotificationDeliveryStatus
 *   NotificationDeliveryOrigin      → NotificationDeliveryOrigin
 *   NotificationEventType           → NotificationEventType
 *   NotificationSeverity            → NotificationSeverity
 *   NotificationChannelData.webhookAuthScheme → NotificationWebhookAuthScheme
 */

// ---------- Channel ----------

export const NOTIFICATION_CHANNEL_TYPES = [
    { label: 'Slack', value: 'SLACK' },
    { label: 'Generic webhook (HTTPS)', value: 'WEBHOOK' },
] as const
export type NotificationChannelType =
    typeof NOTIFICATION_CHANNEL_TYPES[number]['value']

export const NOTIFICATION_CHANNEL_STATUSES = [
    { label: 'Enabled', value: 'ENABLED' },
    { label: 'Disabled', value: 'DISABLED' },
] as const
export type NotificationChannelStatus =
    typeof NOTIFICATION_CHANNEL_STATUSES[number]['value']

export const NOTIFICATION_WEBHOOK_AUTH_SCHEMES = [
    { label: 'None (no auth header)', value: 'NONE' },
    { label: 'Bearer token', value: 'BEARER' },
    { label: 'HMAC-SHA256 signed', value: 'HMAC_SHA256' },
] as const
export type NotificationWebhookAuthScheme =
    typeof NOTIFICATION_WEBHOOK_AUTH_SCHEMES[number]['value']

// ---------- Subscription ----------

export const NOTIFICATION_SUBSCRIPTION_STATUSES = [
    { label: 'Active', value: 'ACTIVE' },
    { label: 'Preview (observe-only)', value: 'PREVIEW' },
    { label: 'Disabled', value: 'DISABLED' },
] as const
export type NotificationSubscriptionStatus =
    typeof NOTIFICATION_SUBSCRIPTION_STATUSES[number]['value']

// ---------- Delivery (outbox row state) ----------

export const NOTIFICATION_DELIVERY_STATUSES = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'SENT', value: 'SENT' },
    { label: 'FAILED', value: 'FAILED' },
    { label: 'ACKED', value: 'ACKED' },
] as const
export type NotificationDeliveryStatus =
    typeof NOTIFICATION_DELIVERY_STATUSES[number]['value']

export const NOTIFICATION_DELIVERY_ORIGINS = [
    { label: 'REAL', value: 'REAL' },
    { label: 'SYNTHETIC', value: 'SYNTHETIC' },
] as const
export type NotificationDeliveryOrigin =
    typeof NOTIFICATION_DELIVERY_ORIGINS[number]['value']

// ---------- Event payloads ----------

export const NOTIFICATION_EVENT_TYPES = [
    { label: 'New vuln affects releases', value: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Vulnerability record updated', value: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'VEX state changed', value: 'VEX_STATE_CHANGED' },
] as const
export type NotificationEventType =
    typeof NOTIFICATION_EVENT_TYPES[number]['value']

export const NOTIFICATION_SEVERITIES = [
    { label: 'CRITICAL', value: 'CRITICAL' },
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' },
    { label: 'INFO', value: 'INFO' },
] as const
export type NotificationSeverity =
    typeof NOTIFICATION_SEVERITIES[number]['value']

// ---------- Helpers ----------

/**
 * Maps a delivery status to the naive-ui `n-tag` intent so the
 * deliveries table and any future status-pill UI render the same color
 * for the same status. Wide input type because GraphQL responses are
 * loosely typed at the boundary.
 */
export function deliveryStatusType (
    s: NotificationDeliveryStatus | string | null | undefined,
): 'success' | 'error' | 'info' | 'default' {
    if (s === 'SENT' || s === 'ACKED') return 'success'
    if (s === 'FAILED') return 'error'
    if (s === 'PENDING') return 'info'
    return 'default'
}

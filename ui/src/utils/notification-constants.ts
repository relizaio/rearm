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
 * Java-side correspondence (1-to-1 unless noted):
 *   NotificationChannelType.java        → NotificationChannelType
 *   NotificationChannelStatus.java      → NotificationChannelStatus
 *   NotificationSubscriptionStatus.java → NotificationSubscriptionStatus
 *   NotificationDeliveryStatus.java     → NotificationDeliveryStatus (delivery row)
 *   NotificationOutboxStatus.java       → NotificationOutboxStatus (outbox event, separate)
 *   NotificationDeliveryOrigin.java     → NotificationDeliveryOrigin
 *   NotificationEventType.java          → NotificationEventType
 *   NotificationSeverity.java           → NotificationSeverity
 *   NotificationChannelData.webhookAuthScheme → NotificationWebhookAuthScheme
 *
 * NOTE: NotificationChannelType has five Java values (SLACK, MS_TEAMS,
 * EMAIL, WEBHOOK, SENTINEL). The TS list ships those with shipped
 * channel workers. MS_TEAMS / SENTINEL will be added by the Phase 10/11
 * commits that land their respective dispatchers; until then, a row
 * of those types is impossible because the UI can't create one.
 */

// ---------- Channel ----------

export const NOTIFICATION_CHANNEL_TYPES = [
    { label: 'Slack', value: 'SLACK' },
    { label: 'Email', value: 'EMAIL' },
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

// ---------- Outbox event status (one row per outbox event) ----------

export const NOTIFICATION_OUTBOX_STATUSES = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'FANNED_OUT', value: 'FANNED_OUT' },
    { label: 'FAILED', value: 'FAILED' },
] as const
export type NotificationOutboxStatus =
    typeof NOTIFICATION_OUTBOX_STATUSES[number]['value']

// ---------- Delivery row (one row per channel × event) ----------

export const NOTIFICATION_DELIVERY_STATUSES = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'SENT', value: 'SENT' },
    { label: 'ACKED', value: 'ACKED' },
    { label: 'FAILED', value: 'FAILED' },
    { label: 'RATE_LIMITED', value: 'RATE_LIMITED' },
    { label: 'EVAL_TIMEOUT', value: 'EVAL_TIMEOUT' },
    { label: 'TEST', value: 'TEST' },
    { label: 'PREVIEW', value: 'PREVIEW' },
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

// NONE is the lowest-weight value on the Java side and exists so events
// without inherent severity (e.g. some non-vuln event types) can still
// route through severity-gated rules without special-casing nullability.
export const NOTIFICATION_SEVERITIES = [
    { label: 'CRITICAL', value: 'CRITICAL' },
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' },
    { label: 'INFO', value: 'INFO' },
    { label: 'NONE', value: 'NONE' },
] as const
export type NotificationSeverity =
    typeof NOTIFICATION_SEVERITIES[number]['value']

// ---------- Helpers ----------

/**
 * Maps a delivery status to the naive-ui `n-tag` intent so the
 * deliveries table and any future status-pill UI render the same color
 * for the same status. Wide input type because GraphQL responses are
 * loosely typed at the boundary.
 *
 * RATE_LIMITED is `warning` (not `error`) — the dispatcher actively
 * preserves these rows for history rather than letting them silently
 * disappear, and operators routinely need to distinguish "we hit a
 * Slack rate limit" from "Slack rejected the payload outright".
 * EVAL_TIMEOUT is `error` — the subscription's CEL ate its wall-clock
 * budget, which is an authoring problem worth surfacing red.
 */
export function deliveryStatusType (
    s: NotificationDeliveryStatus | string | null | undefined,
): 'success' | 'error' | 'warning' | 'info' | 'default' {
    if (s === 'SENT' || s === 'ACKED') return 'success'
    if (s === 'FAILED' || s === 'EVAL_TIMEOUT') return 'error'
    if (s === 'RATE_LIMITED') return 'warning'
    if (s === 'PENDING' || s === 'TEST' || s === 'PREVIEW') return 'info'
    return 'default'
}

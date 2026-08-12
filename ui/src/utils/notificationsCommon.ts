// Shared, stateless pieces for the notification surfaces that were split
// out of the former NotificationsOfOrg monolith (channel groups,
// subscriptions, delivery history, inbox). Option arrays, tag/format/error
// helpers, the row interfaces, and the survived read-only GraphQL queries
// live here so the host components stay in sync instead of re-declaring
// them five times over.

import gql from 'graphql-tag'
import commonFunctions from '@/utils/commonFunctions'
import { PRO_ONLY_ROUTE_FIELDS } from '@/utils/editionCapabilities'

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
    // BUG 3: the outbox event's rendered payload for the expandable row. Optional
    // ENRICHMENT field -- absent on a backend that predates it (drift-tolerant).
    payloadJson?: string | null
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
    // Channel enabled/auto-disable state (Pro-ahead enrichment; optional since a
    // degraded/CE-lagging load may omit it). channelEnabled === false = disabled;
    // channelDisabledReason carries the auto-disable reason when present.
    channelEnabled?: boolean | null
    channelDisabledReason?: string | null
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

// PREVIEW has no fan-out implementation, so it behaves exactly like DISABLED:
// NotificationSubscriptionRepository.findActiveByOrgString matches
// status='ACTIVE' only, so a PREVIEW subscription never reaches fan-out, no
// delivery row is ever written, and NotificationDeliveryStatus.PREVIEW has no
// writer at all. Offering it is a silent trap: a user sets PREVIEW, sees
// nothing arrive, and concludes their filter matches nothing -- when fan-out
// never consulted the subscription in the first place. Keep it unselectable
// until fan-out honours it (same treatment as VEX_STATE_CHANGED below). The
// enum values are deliberately kept on both sides.
// Status is a single select, so unlike the multiple-select VEX case this needs
// no unselect-guard computed: a legacy PREVIEW value still renders and can be
// changed away, it just can't be re-selected.
export const subscriptionStatusOptions = [
    { label: 'Active (deliveries go out)', value: 'ACTIVE' },
    { label: 'Disabled (no dispatch)', value: 'DISABLED' },
    { label: 'Preview (not yet available)', value: 'PREVIEW', disabled: true },
]

// VEX_STATE_CHANGED has no event producer in the backend yet, so a
// subscription on it would never fire (a silent trap). Disable it here until
// a producer ships -- prefer VULNERABILITY_RECORD_UPDATED for vuln-state
// changes. See rearm-core SyntheticEventTemplates / notifications.md.
export const eventTypeOptions = [
    { label: 'New vuln affects releases', value: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Vulnerability record updated', value: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'VEX state changed (not yet available)', value: 'VEX_STATE_CHANGED', disabled: true },
    { label: 'Release created', value: 'RELEASE_CREATED' },
    { label: 'Release lifecycle changed', value: 'RELEASE_LIFECYCLE_CHANGED' },
    { label: 'Release BOM diff', value: 'RELEASE_BOM_DIFF' },
    { label: 'Approval requested', value: 'APPROVAL_REQUESTED' },
    { label: 'Approval resolved', value: 'APPROVAL_RESOLVED' },
]

// Mirrors SyntheticEventTemplates.Template in rearm-core (backend/src/main/
// java/io/reliza/service/SyntheticEventTemplates.java). Only the event types
// listed here have a synthetic template to exercise them -- RELEASE_CREATED,
// RELEASE_LIFECYCLE_CHANGED, RELEASE_BOM_DIFF, APPROVAL_REQUESTED, and
// APPROVAL_RESOLVED have none yet, so a subscription scoped to only those
// event types has nothing the "Test" affordance can inject.
export const syntheticEventTemplates: Array<{ label: string, value: string, eventType: string }> = [
    { label: 'Critical vuln, single shipped release', value: 'CRITICAL_VULN_SINGLE_SHIPPED_RELEASE', eventType: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Critical KEV vuln, three releases', value: 'CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD', eventType: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'KEV-listed vuln on a draft release', value: 'KEV_LISTED_DRAFT_RELEASE', eventType: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Severity bump: MEDIUM to CRITICAL', value: 'SEVERITY_BUMP_MEDIUM_TO_CRITICAL', eventType: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'CVE newly added to KEV', value: 'KEV_ADDED', eventType: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'VEX resolved to not_affected', value: 'VEX_RESOLVED_NOT_AFFECTED', eventType: 'VEX_STATE_CHANGED' },
]

// Templates whose eventType is one the subscription actually listens for --
// picking any of these and injecting it can, at most, be matched by this
// subscription (still subject to its filter/CEL and each route's severity gate).
export function templatesForEventTypes (eventTypes: string[]): Array<{ label: string, value: string }> {
    const types = new Set(eventTypes || [])
    return syntheticEventTemplates
        .filter(t => types.has(t.eventType))
        .map(t => ({ label: t.label, value: t.value }))
}

export const severityOptions = [
    { label: 'CRITICAL', value: 'CRITICAL' },
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' },
    { label: 'INFO', value: 'INFO' },
]

// Which of these the backend can actually WRITE, checked rather than assumed
// (an earlier cut of this list exempted ACKED on a guess and was wrong).
// Sweeping every write path across the backend -- setStatus() call sites, the
// JPA field default, native UPDATE statements, migrations -- the produced set
// is PENDING, SENT, FAILED and BATCHED. Nothing else has a writer:
//   ACKED         setAckedAt() has ZERO callers; read state is a LEFT JOIN on
//                 notification_reads, not a status, so nothing ever flips a row
//                 to ACKED no matter how much you mark read.
//   RATE_LIMITED  the per-subscription rate limit is stored but not enforced.
//   EVAL_TIMEOUT  a failed CEL filter is skipped and logged; no row is written.
//   TEST          superseded -- channel tests are recorded on the ORIGIN
//                 dimension (NotificationDeliveryOrigin.SYNTHETIC) instead.
//   PREVIEW       the PREVIEW subscription mode was never implemented (#197).
//
// Filtering by an unwritable status is a silent trap: the operator filters by
// ACKED to see what has been acknowledged, gets zero rows, and reads that as a
// fact about their data rather than about the schema. On a diagnostic surface
// a false negative is worse than a missing control. Left visible-but-disabled
// rather than deleted so the reason is on screen -- same treatment as the
// PREVIEW subscription status and VEX_STATE_CHANGED.
//
// Re-enable each ONE alongside the backend change that starts producing it.
// The spec pins both directions against the backend source, so adding a writer
// without re-enabling the filter -- or adding a filter with no writer -- fails.
// Produced statuses first, then the unavailable ones grouped at the end. #197
// keeps its single disabled entry in situ, which reads fine for one of three;
// with five of nine disabled, grouping keeps the usable options scannable.
export const deliveryStatusOptions: Array<{ label: string, value: string, disabled?: boolean }> = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'SENT', value: 'SENT' },
    { label: 'FAILED', value: 'FAILED' },
    { label: 'BATCHED', value: 'BATCHED' },
    { label: 'ACKED (not yet available)', value: 'ACKED', disabled: true },
    { label: 'RATE_LIMITED (not yet available)', value: 'RATE_LIMITED', disabled: true },
    { label: 'EVAL_TIMEOUT (not yet available)', value: 'EVAL_TIMEOUT', disabled: true },
    { label: 'TEST (not yet available)', value: 'TEST', disabled: true },
    { label: 'PREVIEW (not yet available)', value: 'PREVIEW', disabled: true },
]

export const deliveryOriginOptions = [
    { label: 'REAL', value: 'REAL' },
    { label: 'SYNTHETIC (test)', value: 'SYNTHETIC' },
]

// ---- Tag-type helpers ---------------------------------------------------

export function deliveryStatusTagType (status: string): NaiveTagType {
    if (status === 'SENT' || status === 'ACKED') return 'success'
    if (status === 'FAILED' || status === 'EVAL_TIMEOUT' || status === 'RATE_LIMITED') return 'error'
    // BATCHED is produced (held in an email digest window, not yet sent), so it
    // must not read as success -- info, alongside the not-yet-produced pair.
    if (status === 'BATCHED' || status === 'PREVIEW' || status === 'TEST') return 'info'
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

// Compact relative time for the inbox triage list: "just now", "5m ago",
// "3h ago", "2d ago". Past ~30 days it falls back to the absolute date so
// ancient rows stay legible. Call sites show the absolute timestamp on hover
// (title attr). nowMs is injectable so the pure function stays unit-testable.
export function relativeTime (s: string | null, nowMs: number = Date.now()): string {
    if (!s) return ''
    const t = new Date(s).getTime()
    if (isNaN(t)) return ''
    const sec = Math.floor((nowMs - t) / 1000)
    // Clock skew / future-dated rows read as "just now" rather than negatives.
    if (sec < 60) return 'just now'
    const min = Math.floor(sec / 60)
    if (min < 60) return `${min}m ago`
    const hr = Math.floor(min / 60)
    if (hr < 24) return `${hr}h ago`
    const day = Math.floor(hr / 24)
    if (day < 30) return `${day}d ago`
    return formatHistoryTimestamp(s)
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

// Channel-group + subscription list queries are split CORE vs ENRICHMENT so
// the list surfaces can degrade gracefully instead of blanking when the
// deployed backend (a CE mirror lagging the Pro schema) lacks a Pro-ahead
// field -- see loadWithSchemaDriftFallback / PR #169. CORE = the identity +
// render essentials that always exist; ENRICHMENT = the newer fields.

const GROUP_CORE_FIELDS = 'uuid org resourceGroup name channels revision'
const GROUP_ENRICHMENT_FIELDS = 'createdDate lastUpdatedDate'
function buildGroupsQuery (fields: string) {
    return gql`
        query notificationChannelGroups($orgUuid: ID!) {
            notificationChannelGroups(orgUuid: $orgUuid) { ${fields} }
        }`
}
export const LIST_GROUPS_QUERY = buildGroupsQuery(`${GROUP_CORE_FIELDS} ${GROUP_ENRICHMENT_FIELDS}`)
export const LIST_GROUPS_CORE_QUERY = buildGroupsQuery(GROUP_CORE_FIELDS)

const SUBSCRIPTION_CORE_FIELDS = 'uuid org resourceGroup name status eventTypes revision'
const SUBSCRIPTION_ENRICHMENT_FIELDS = 'filter routes dedupWindowMinutes rateLimit'
function buildSubscriptionsQuery (fields: string) {
    return gql`
        query notificationSubscriptions($orgUuid: ID!) {
            notificationSubscriptions(orgUuid: $orgUuid) { ${fields} }
        }`
}
export const LIST_SUBSCRIPTIONS_QUERY = buildSubscriptionsQuery(`${SUBSCRIPTION_CORE_FIELDS} ${SUBSCRIPTION_ENRICHMENT_FIELDS}`)
export const LIST_SUBSCRIPTIONS_CORE_QUERY = buildSubscriptionsQuery(SUBSCRIPTION_CORE_FIELDS)

/**
 * Build one `NotificationRouteInput` from a modeled route row.
 *
 * Same hazard as buildNotificationFilterInput below, one level down: GraphQL
 * input coercion REJECTS unknown keys outright, so any field the server's
 * NotificationRouteInput does not declare fails the whole mutation rather than
 * being ignored.
 *
 * The offending fields are listed in PRO_ONLY_ROUTE_FIELDS (currently `teams`).
 * Sending `teams: []` from a CE UI makes every subscription save -- including
 * ones that use no teams at all -- fail with
 *   `Field "teams" is not defined by type "NotificationRouteInput"`.
 * Omitting the key when empty keeps CE saving while losing nothing on Pro, where
 * an absent list and an empty list mean the same thing.
 */
export interface NotificationRouteInput {
    whenSeverityAtLeast?: string | null
    channels?: string[]
    channelGroups?: string[]
    perspectives?: string[]
    teams?: string[]
    // Unmodelled passthrough carried verbatim from `_raw` (andEnvIn,
    // andLifecycleIn, rate-limit fields the editor does not surface yet).
    [passthrough: string]: unknown
}

export function buildNotificationRouteInput (route: Record<string, any>): NotificationRouteInput {
    const raw = { ...(route._raw || {}) }
    // Strip the Pro-only fields from the passthrough as well.
    //
    // DEFENSIVE, not currently load-bearing: openEditSubscription models `teams`
    // explicitly alongside `_raw`, so today the re-add below always restores
    // whatever `_raw` carried and this loop changes nothing. It matters the
    // moment a Pro-only field stops being modelled -- `_raw` becomes its only
    // carrier, and without this the field would flow straight through to a CE
    // backend and 400 every save. Kept because the failure it prevents is
    // silent and total, and the cost is one shallow copy.
    for (const f of PRO_ONLY_ROUTE_FIELDS) delete raw[f]
    const out: NotificationRouteInput = {
        ...raw,
        whenSeverityAtLeast: route.whenSeverityAtLeast,
        channels: route.channels,
        channelGroups: route.channelGroups,
        perspectives: route.perspectives,
    }
    // Send a Pro-only field only when it actually carries a value, so a CE
    // backend never sees the key at all.
    for (const f of PRO_ONLY_ROUTE_FIELDS) {
        if (carriesValue(route[f])) out[f] = route[f]
    }
    return out
}

/**
 * Does this route field carry a value worth sending?
 *
 * Pro-only fields are omitted when empty so a CE backend never sees the key at
 * all. "Empty" depends on the shape: `[]` for the list-valued fields (`teams`),
 * `false` for the boolean ones (`notifyComponentOwner`).
 *
 * Worth stating because the list-only version of this check silently broke the
 * boolean: `(true || []).length > 0` is `undefined > 0`, i.e. false, so the
 * field was never sent and owner routing could not be turned on from the UI --
 * a failure that looks like a backend bug from the operator's side.
 */
function carriesValue (v: unknown): boolean {
    if (Array.isArray(v)) return v.length > 0
    if (typeof v === 'boolean') return v
    return v !== null && v !== undefined
}

/**
 * Does this route name at least one delivery target?
 *
 * Mirrors the backend's per-route emptiness gate so the operator gets a clean
 * client-side error instead of a mutation failure. Lives here, not inline in
 * the form, because `isPro` is load-bearing and easy to get wrong: on CE the
 * owner checkbox is hidden and `notifyComponentOwner` is not in the schema, so
 * counting it as a target there would wave through a route that is GUARANTEED
 * to fail the save -- and the save it fails is the whole subscription, not just
 * that route.
 *
 * `teams` is deliberately NOT gated on edition: a CE backend soft-fails the
 * teams query to `[]`, so a CE route cannot carry teams in the first place,
 * and gating it would wrongly reject a Pro route whose team list loaded fine.
 */
export function routeHasTarget (route: Record<string, any>, isPro: boolean): boolean {
    if ((route.channels || []).length > 0) return true
    if ((route.channelGroups || []).length > 0) return true
    if ((route.teams || []).length > 0) return true
    return isPro && route.notifyComponentOwner === true
}

/**
 * Build the payload for `NotificationFilterInput` from the modeled UI fields
 * plus the as-loaded output blob (`_rawFilter`). Only `mode`,
 * `presetConfigJson` and `celExpression` are valid input fields; the output
 * blob carries an unmodelled `presetConfig` OBJECT that must NOT be spread
 * into the input — doing so 400s the mutation ("field presetConfig not
 * defined for NotificationFilterInput") and silently loses every Edit -> Save.
 * Preset state is preserved by mapping `presetConfig` -> `presetConfigJson`.
 */
export function buildNotificationFilterInput (
    rawFilter: Record<string, any> | null | undefined,
    filterMode: string,
    celExpression: string,
): { mode: string, celExpression: string | null, presetConfigJson?: string } {
    const raw = rawFilter || {}
    const out: { mode: string, celExpression: string | null, presetConfigJson?: string } = {
        mode: filterMode,
        celExpression: filterMode === 'ADVANCED' ? celExpression : null,
    }
    if (raw.presetConfigJson != null) {
        out.presetConfigJson = raw.presetConfigJson
    } else if (raw.presetConfig != null) {
        out.presetConfigJson = typeof raw.presetConfig === 'string'
            ? raw.presetConfig
            : JSON.stringify(raw.presetConfig)
    }
    return out
}

// ---------------------------------------------------------------------------
// Subscription-test result classification.
//
// Split out of SubscriptionsOfOrg.vue so it can be unit-tested: the polling
// loop there previously exited early ONLY when a delivery row existed, so a
// test that legitimately produced NO delivery -- an unowned component, a filter
// that excluded it, a severity gate -- could never take the "finished" branch.
// It burned all 40 polls and then reported a timeout, which reads as "still
// working on it" for an event that finished seconds earlier. Worse, the
// accurate no-delivery explanation was written for exactly that case and was
// unreachable.
// ---------------------------------------------------------------------------

/**
 * Outbox statuses meaning fan-out is DONE for this event, so the set of
 * delivery rows it produced is final and will not grow. Mirrors
 * NotificationOutboxStatus: PENDING is the only non-terminal value.
 */
export const TERMINAL_OUTBOX_STATUSES: readonly string[] = ['FANNED_OUT', 'SUPPRESSED', 'FAILED']

export type SubscriptionTestOutcome = 'DELIVERED' | 'NO_DELIVERY' | 'FANOUT_FAILED' | 'IN_FLIGHT'

/**
 * What a poll tick learned.
 *
 * - `DELIVERED`     rows exist and none is still PENDING -- render them.
 * - `NO_DELIVERY`   fan-out finished and produced nothing for this
 *                   subscription. A real, final answer, NOT a timeout.
 * - `FANOUT_FAILED` fan-out itself errored. Kept separate from NO_DELIVERY
 *                   because the no-delivery copy blames the subscription's
 *                   filter or severity gate, and sending an operator to audit
 *                   a perfectly good filter while a backend failure goes
 *                   unmentioned is worse than saying nothing.
 * - `IN_FLIGHT`     keep polling.
 *
 * Ordering is deliberate. Non-empty rows win over the event status: rows that
 * exist but are still PENDING stay IN_FLIGHT even once fan-out is terminal,
 * because the channel worker dispatches AFTER fan-out commits. Only an EMPTY
 * set is final at that point.
 *
 * CALLER CONTRACT: read the event status BEFORE the deliveries. Fan-out writes
 * the delivery rows and flips the event terminal in one transaction, so a
 * status read first that comes back terminal guarantees a deliveries read
 * issued after it sees those rows. Reading deliveries first opens a
 * one-round-trip window where the commit lands between the two queries, and
 * this function would then be handed an empty set with a terminal status and
 * confidently report NO_DELIVERY for a subscription that did deliver.
 */
export function classifySubscriptionTest (
    eventStatus: string | null | undefined,
    items: Array<{ status: string }>,
): SubscriptionTestOutcome {
    if (items.length > 0) {
        return items.every(d => d.status !== 'PENDING') ? 'DELIVERED' : 'IN_FLIGHT'
    }
    if (eventStatus === 'FAILED') return 'FANOUT_FAILED'
    const fanOutFinished = !!eventStatus && TERMINAL_OUTBOX_STATUSES.includes(eventStatus)
    return fanOutFinished ? 'NO_DELIVERY' : 'IN_FLIGHT'
}

/** True when any route on the subscription delivers to the component owner. */
export function isOwnerRouted (routesJson: string | null | undefined): boolean {
    try {
        const routes = routesJson ? JSON.parse(routesJson) : []
        return Array.isArray(routes) && routes.some((r: any) => r?.notifyComponentOwner === true)
    } catch {
        return false  // unparseable routes: fall back to the generic wording
    }
}

/**
 * True when any route targets a TEAM explicitly (as opposed to naming channels
 * or delivering to the component owner).
 *
 * <p>A team target resolves to that team's channels at fan-out, and resolution
 * drops a team that is archived, cross-org, unreadable, or simply has no
 * channels. Every one of those yields zero deliveries with the subscription's
 * filter and severity gate working perfectly -- so a diagnosis that names only
 * the filter and the gate sends the operator to audit two innocent things.
 *
 * <p>Observed live: archiving a targeted team, then testing, produced exactly
 * that misdirection.
 */
export function isTeamRouted (routesJson: string | null | undefined): boolean {
    try {
        const routes = routesJson ? JSON.parse(routesJson) : []
        return Array.isArray(routes)
            && routes.some((r: any) => Array.isArray(r?.teams) && r.teams.some((t: any) => !!t))
    } catch {
        return false  // unparseable routes: fall back to the generic wording
    }
}

/**
 * Caveat shown on a SUCCESSFUL owner-routed test.
 *
 * Injection deliberately stamps the event onto a component that HAS a routable
 * owner, so an owner-routed subscription passes its test almost regardless of
 * how much of the inventory is actually owned. A real event lands on whichever
 * component carries the finding. Without this note a green test reads as proof
 * that production routing works, which it is not.
 */
export function ownerRoutedSuccessCaveat (routesJson: string | null | undefined): string | null {
    if (!isOwnerRouted(routesJson)) return null
    return 'Note: test events are deliberately stamped onto a component that has an owner,'
        + ' so an owner-routed subscription will usually pass this test. A real event lands on'
        + ' whichever component actually carries the finding, which may be unowned -- check the'
        + ' ownership report for coverage rather than relying on this result.'
}

/**
 * How many routes a subscription carries, read from the JSON-stringified blob
 * the list query returns.
 *
 * An unparseable blob counts as zero rather than throwing: callers use this to
 * decide what to show, and a parse failure should not take the row down.
 */
export function routeCount (routesJson: string | null | undefined): number {
    try {
        const routes = routesJson ? JSON.parse(routesJson) : []
        return Array.isArray(routes) ? routes.length : 0
    } catch {
        return 0  // unparseable: treated as "nothing to lose", same as an empty list
    }
}

/**
 * True when this subscription cannot be honestly edited in the one-route editor.
 *
 * The editor offers exactly ONE route; the backend still supports many. These
 * are NOT only API-made -- the UI shipped an "Add route" control from #130
 * until the editor was collapsed -- so the block needs a visible explanation
 * rather than a bare greyed-out button. It is blocked NOT because
 * routes would be lost -- saveSubscription maps every route the form holds, so
 * routes 2..N are written back untouched -- but because they become invisible:
 * the operator sees one route, takes it for the whole subscription, and the
 * save validator can reject with "Route 3 has no channels" naming something
 * that was never on screen.
 *
 * An unreadable routes blob is treated as "nothing hidden", so a parse failure
 * cannot lock an operator out of a subscription.
 */
export function hasUneditableMultiRoute (routesJson: string | null | undefined): boolean {
    return routeCount(routesJson) > 1
}

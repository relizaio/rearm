<template>
    <span class="inbox-nav-trigger">
        <n-badge :value="navBadgeCount" :max="99" :show="navBadgeCount > 0" data-testid="inbox-badge">
            <n-icon class="clickable" title="Notifications" size="20" @click="openInboxDrawer" data-testid="inbox-bell">
                <Bell />
            </n-icon>
        </n-badge>

        <!-- Inbox list drawer — the triage queue. Mounted at nav level so
             it's reachable from anywhere; the body is loaded lazily the
             first time the drawer opens so a user who never opens it pays
             no round-trip cost. -->
        <n-drawer v-model:show="inboxListDrawerOpen" :width="760" placement="right">
            <n-drawer-content title="Notifications" closable>
                <n-tabs v-model:value="drawerTab" type="line" animated>
                <n-tab-pane name="inbox" tab="Inbox" data-testid="inbox-tab">
                <div class="tab-toolbar">
                    <div class="tab-toolbar-info">
                        Your personal triage queue. Org-admins see every delivery; perspective-members see deliveries that touch a release in one of their perspectives.
                    </div>
                    <n-space size="small">
                        <n-button
                            v-if="canWrite && selectedInboxRows.length > 0"
                            size="small"
                            type="primary"
                            @click="bulkMarkRead"
                            :loading="inboxBulkLoading"
                            data-testid="mark-selected-read"
                        >
                            <template #icon><n-icon><Check /></n-icon></template>
                            Mark {{ selectedInboxRows.length }} read
                        </n-button>
                        <n-button
                            v-if="canWrite && inboxUnreadCount > 0"
                            size="small"
                            secondary
                            @click="markAllReadConfirm"
                            :loading="inboxMarkAllLoading"
                            data-testid="mark-all-read"
                        >
                            Mark all read
                        </n-button>
                        <n-button size="small" @click="loadInbox">
                            <template #icon><n-icon><Refresh /></n-icon></template>
                            Refresh
                        </n-button>
                    </n-space>
                </div>

                <n-grid :cols="3" :x-gap="12" class="history-filters">
                    <n-gi>
                        <n-form-item label="Show" :show-feedback="false">
                            <n-radio-group v-model:value="inboxUnreadOnly" @update:value="applyInboxFilters">
                                <n-radio-button :value="true">Unread only</n-radio-button>
                                <n-radio-button :value="false">All visible</n-radio-button>
                            </n-radio-group>
                        </n-form-item>
                    </n-gi>
                    <n-gi>
                        <n-form-item label="Status" :show-feedback="false">
                            <n-select
                                v-model:value="inboxStatusFilter"
                                :options="deliveryStatusOptions"
                                placeholder="Any"
                                clearable
                                @update:value="applyInboxFilters"
                            />
                        </n-form-item>
                    </n-gi>
                    <n-gi>
                        <n-form-item label="Event type" :show-feedback="false">
                            <n-select
                                v-model:value="inboxEventTypeFilter"
                                :options="eventTypeOptions"
                                placeholder="Any"
                                clearable
                                @update:value="applyInboxFilters"
                            />
                        </n-form-item>
                    </n-gi>
                </n-grid>

                <n-alert
                    v-if="inboxDegraded"
                    type="warning"
                    :show-icon="false"
                    class="inbox-degraded-alert"
                    data-testid="inbox-degraded-alert"
                >
                    Some inbox details are unavailable on this server version, so a few columns (such as channel name) may be missing. Your notifications are shown below.
                </n-alert>

                <!-- Card-style rows (replaces the old 7-column data-table that
                     overflowed the drawer): title + severity/status tags on top,
                     description below, then a compact channel-dot-time meta line.
                     Unread rows carry a left accent + selection checkbox for the
                     bulk mark-read action. -->
                <n-spin :show="inboxLoading">
                    <div class="inbox-list" data-testid="inbox-list">
                        <div
                            v-if="inboxCards.length === 0 && !inboxLoading"
                            class="inbox-empty muted-12"
                            data-testid="inbox-empty"
                        >
                            {{ inboxUnreadOnly ? 'No unread notifications.' : 'No notifications to show.' }}
                        </div>
                        <div
                            v-for="c in inboxCards"
                            :key="c.row.uuid"
                            class="inbox-card"
                            :class="{ unread: !c.row.readAt, read: !!c.row.readAt }"
                            data-testid="inbox-row"
                        >
                            <!-- Left rail always signals: severity colour (canonical
                                 vuln palette) for vuln/vex rows, neutral grey for
                                 lifecycle (release/approval) rows. -->
                            <span class="inbox-rail" :style="{ backgroundColor: c.rail }"></span>
                            <div class="inbox-card-select">
                                <n-checkbox
                                    v-if="!c.row.readAt"
                                    :checked="selectedInboxRows.includes(c.row.uuid)"
                                    :disabled="!canWrite"
                                    @update:checked="(v) => toggleInboxSelect(c.row.uuid, v)"
                                />
                            </div>
                            <div class="inbox-card-main">
                                <div class="inbox-card-head">
                                    <span v-if="!c.row.readAt" class="inbox-unread-dot" aria-hidden="true"></span>
                                    <button
                                        type="button"
                                        class="inbox-message-link"
                                        data-testid="inbox-message-link"
                                        @click="openInboxRow(c.row)"
                                    >
                                        <span class="inbox-message-title" data-testid="inbox-message-title">{{ c.title }}</span>
                                    </button>
                                    <!-- Severity tag when the row has one; otherwise a neutral
                                         kind pill (RELEASE/APPROVAL/...) so the head always
                                         carries a category. inbox-cell-severity is present iff
                                         the row has a severity. -->
                                    <n-tag
                                        v-if="c.row.severity"
                                        :type="severityTagType(c.row.severity)"
                                        size="small"
                                        :bordered="false"
                                        data-testid="inbox-cell-severity"
                                    >{{ c.row.severity }}</n-tag>
                                    <n-tag
                                        v-else
                                        size="small"
                                        :bordered="false"
                                        class="inbox-kind-tag"
                                        data-testid="inbox-cell-kind"
                                    >{{ c.kind }}</n-tag>
                                </div>
                                <div v-if="c.row.description" class="inbox-message-desc muted-12">{{ c.row.description }}</div>
                                <div class="inbox-card-sub">
                                    <n-tag
                                        :type="deliveryStatusTagType(c.row.status)"
                                        size="small"
                                        :bordered="false"
                                        data-testid="inbox-cell-status"
                                    >{{ c.row.status }}</n-tag>
                                    <span class="inbox-meta-sep">&middot;</span>
                                    <span
                                        data-testid="inbox-cell-channel"
                                        :class="['inbox-chan', { fail: c.failed, 'muted-12': !c.failed && c.channel.muted }]"
                                        :title="c.channel.title"
                                    >{{ c.channel.text }}<span
                                        v-if="c.channel.disabled"
                                        class="inbox-chan-disabled"
                                        data-testid="inbox-cell-channel-disabled"
                                    > (disabled)</span></span>
                                    <span v-if="c.failed" class="inbox-fail-note">&mdash; message was not delivered</span>
                                </div>
                            </div>
                            <div class="inbox-card-side">
                                <span class="inbox-time muted-12" data-testid="inbox-cell-when" :title="c.timeAbs">{{ c.timeRel }}</span>
                                <n-button
                                    v-if="!c.row.readAt"
                                    class="inbox-mark-read"
                                    size="tiny"
                                    tertiary
                                    type="primary"
                                    :disabled="!canWrite"
                                    @click="markRowRead(c.row)"
                                >Mark read</n-button>
                                <n-button
                                    v-else
                                    size="tiny"
                                    quaternary
                                    :disabled="!canWrite"
                                    @click="markRowUnread(c.row)"
                                >Unread</n-button>
                            </div>
                        </div>
                    </div>
                </n-spin>

                <div v-if="inboxTotalCount > inboxPageSize" class="history-pagination">
                    <n-pagination
                        v-model:page="inboxPage"
                        :page-count="inboxPageCount"
                        :page-size="inboxPageSize"
                        :item-count="inboxTotalCount"
                        @update:page="loadInbox"
                    />
                </div>
                </n-tab-pane>

                <!-- Read-only "what needs my approval" view (Phase 3b).
                     Keys off the caller's assigned approval roles server-side;
                     a user with no approval roles sees an empty list. Acting
                     on an approval still happens on the release page — this
                     tab is the triage surface, the Version link is the hop. -->
                <n-tab-pane name="approvals" :tab="approvalsTabLabel" data-testid="approvals-tab">
                    <div class="tab-toolbar">
                        <div class="tab-toolbar-info">
                            Releases with an approval pending on one of your approval roles. Open the release to approve or disapprove.
                        </div>
                        <n-button size="small" @click="loadApprovals()">
                            <template #icon><n-icon><Refresh /></n-icon></template>
                            Refresh
                        </n-button>
                    </div>
                    <n-alert
                        v-if="approvalsDegraded"
                        type="warning"
                        :show-icon="false"
                        style="margin-bottom: 12px;"
                        data-testid="approvals-degraded-alert"
                    >
                        Some approval details are unavailable on this server version, so a few fields may be missing. Your pending approvals are shown below.
                    </n-alert>
                    <n-data-table
                        :data="approvalsItems"
                        :columns="approvalsColumns"
                        :loading="approvalsLoading"
                        :single-line="false"
                        :bordered="false"
                        :row-key="(row) => row.releaseUuid"
                    >
                        <template #empty>
                            <n-alert
                                v-if="isOrgAdmin && !hasApprovalRoleGrant"
                                type="info"
                                :show-icon="false"
                                data-testid="approvals-empty-admin-hint"
                            >
                                Nothing is waiting on your approval role right now. As an Org Admin you can still approve or disapprove any release directly from its page -- this list only shows releases where you hold an explicit approval role. Ask another admin to grant you one if you want items to appear here automatically.
                            </n-alert>
                            <span v-else data-testid="approvals-empty-generic">No releases are waiting on your approval right now.</span>
                        </template>
                    </n-data-table>
                </n-tab-pane>
                </n-tabs>
            </n-drawer-content>
        </n-drawer>

        <!-- Inbox row drawer — opens on Message-cell click. Renders the
             server-rendered title + description plus a structured view
             over the outbox payload so the operator can deep-link into
             affected releases / vulnerabilities without an extra hop. -->
        <n-drawer v-model:show="inboxDrawerOpen" :width="560" placement="right">
            <n-drawer-content v-if="inboxDrawerRow" :title="inboxDrawerRow.title || 'Notification'" closable>
                <n-space vertical size="large">
                    <div v-if="inboxDrawerRow.description" class="inbox-drawer-desc">
                        {{ inboxDrawerRow.description }}
                    </div>

                    <!-- column=1 in this narrow drawer — column=2 wraps
                         the labels mid-word ("Severit/y", "Chann/el", etc).
                         A property-list reading top-to-bottom is more
                         natural than a grid here. -->
                    <n-descriptions :column="1" size="small" label-placement="left">
                        <n-descriptions-item label="Event">
                            <span v-if="inboxDrawerRow.eventType">
                                {{ inboxDrawerRow.eventType.replace(/_/g, ' ').toLowerCase() }}
                            </span>
                            <span v-else class="muted-12">—</span>
                        </n-descriptions-item>
                        <n-descriptions-item label="Severity">
                            <n-tag v-if="inboxDrawerRow.severity" :type="severityTagType(inboxDrawerRow.severity)" size="small">
                                {{ inboxDrawerRow.severity }}
                            </n-tag>
                            <span v-else class="muted-12">—</span>
                        </n-descriptions-item>
                        <n-descriptions-item label="Status">
                            <n-tag :type="deliveryStatusTagType(inboxDrawerRow.status)" size="small">{{ inboxDrawerRow.status }}</n-tag>
                        </n-descriptions-item>
                        <n-descriptions-item label="Channel">
                            <span :class="{ 'muted-12': drawerChannel.muted }" :title="drawerChannel.title">{{ drawerChannel.text }}<span v-if="drawerChannel.disabled" class="inbox-chan-disabled"> (disabled)</span></span>
                        </n-descriptions-item>
                        <n-descriptions-item label="Delivered">
                            {{ formatHistoryTimestamp(inboxDrawerRow.sentAt || inboxDrawerRow.createdDate) }}
                        </n-descriptions-item>
                        <n-descriptions-item label="Attempts">
                            {{ inboxDrawerRow.attemptCount }}
                        </n-descriptions-item>
                    </n-descriptions>

                    <n-alert v-if="inboxDrawerRow.lastError" type="warning" :show-icon="false" title="Last error">
                        {{ inboxDrawerRow.lastError }}
                    </n-alert>

                    <!-- Actionable approval events (Phase 4b): typed summary
                         with a release deep link so the operator can act
                         without digging the uuid out of the raw payload.
                         Navigation closes both drawers — the target page
                         renders underneath them otherwise. -->
                    <n-descriptions v-if="approvalPayloadView" :column="1" size="small" label-placement="left">
                        <n-descriptions-item label="Release">
                            <router-link
                                :to="`/release/show/${approvalPayloadView.releaseUuid}`"
                                @click="inboxDrawerOpen = false; inboxListDrawerOpen = false"
                            >
                                {{ approvalPayloadView.releaseLabel }}
                            </router-link>
                        </n-descriptions-item>
                        <n-descriptions-item v-if="approvalPayloadView.entryNames.length" label="Approval entries">
                            {{ approvalPayloadView.entryNames.join(', ') }}
                        </n-descriptions-item>
                        <n-descriptions-item :label="approvalPayloadView.actorLabel">
                            {{ approvalPayloadView.actor }}
                        </n-descriptions-item>
                        <n-descriptions-item v-if="approvalPayloadView.resolution" label="Resolution">
                            <n-tag :type="approvalPayloadView.resolution === 'APPROVED' ? 'success' : 'error'" size="small">
                                {{ approvalPayloadView.resolution }}
                            </n-tag>
                        </n-descriptions-item>
                    </n-descriptions>

                    <!-- Structured payload view. Pretty-printed JSON as the
                         generic fallback; approval events additionally get
                         the typed summary above.
                         v-if keys off the COMPUTED parsed payload, not the
                         raw string, so a malformed JSON falls through to
                         the alert below instead of rendering the literal
                         "null" inside the <pre>. -->
                    <n-collapse v-if="inboxDrawerPayload">
                        <n-collapse-item title="Raw payload" name="payload">
                            <pre class="inbox-drawer-payload">{{ JSON.stringify(inboxDrawerPayload, null, 2) }}</pre>
                        </n-collapse-item>
                    </n-collapse>
                    <n-alert v-else-if="inboxDrawerRow.payloadJson" type="warning" :show-icon="false" title="Malformed payload">
                        The outbox event's payload couldn't be parsed as JSON. Raw text is logged server-side.
                    </n-alert>

                    <n-space>
                        <n-button
                            v-if="!inboxDrawerRow.readAt"
                            type="primary"
                            :disabled="!canWrite"
                            @click="markRowReadFromDrawer"
                        >
                            <template #icon><n-icon><Check /></n-icon></template>
                            Mark read
                        </n-button>
                        <n-button
                            v-else
                            secondary
                            :disabled="!canWrite"
                            @click="markRowUnreadFromDrawer"
                        >
                            Mark unread
                        </n-button>
                        <n-button
                            v-if="isOrgAdmin && inboxDrawerRow.channelUuid"
                            tertiary
                            @click="viewDeliveryLog"
                        >
                            View delivery log
                        </n-button>
                        <n-button @click="inboxDrawerOpen = false">Close</n-button>
                    </n-space>
                </n-space>
            </n-drawer-content>
        </n-drawer>
    </span>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted, onUnmounted, watch } from 'vue'
import {
    NDrawer, NDrawerContent, NDataTable, NButton, NIcon, NSpace, NGrid, NGi,
    NFormItem, NSelect, NRadioGroup, NRadioButton, NPagination, NTag,
    NDescriptions, NDescriptionsItem, NAlert, NCollapse, NCollapseItem,
    NBadge, NTabs, NTabPane, NCheckbox, NSpin, useDialog, useMessage
} from 'naive-ui'
import { Bell, Check, Refresh } from '@vicons/tabler'
import { RouterLink, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import constants from '@/utils/constants'
import {
    InboxRow, deliveryStatusOptions, eventTypeOptions,
    deliveryStatusTagType, severityTagType,
    formatHistoryTimestamp, relativeTime, extractError
} from '@/utils/notificationsCommon'
import { loadNotificationInboxPage } from '@/utils/notificationInboxQuery'
import { loadWithSchemaDriftFallback } from '@/utils/graphqlDriftFallback'

const props = defineProps<{
    orguuid: string
}>()

const router = useRouter()
const store = useStore()
const dialog = useDialog()
const message = useMessage()

const orgUuid = computed<string>(() => props.orguuid)
const myuser = computed<any>(() => store.getters.myuser)
// Inbox is all-editions, but mark-read/unread/bulk flows still gate on a
// write-capable permission on the active org so a read-only member can
// triage-view without mutating. Route through the shared isWritable helper
// (same as OrgSettings et al.) rather than hand-rolling the org-permission
// check so the logic stays consistent.
const canWrite = computed<boolean>(() =>
    commonFunctions.isWritable(orgUuid.value, myuser.value, 'ORG')
)
// Mirrors ApprovalNeedsService's own rule server-side: Org Admin lets you
// cast a vote on any release, but this tab (and the APPROVAL_REQUESTED
// notification) is keyed off an explicit approval role, not the ADMIN
// override. An admin with no approval role legitimately sees an empty
// list here even with pending releases elsewhere -- the empty-state hint
// below exists so that doesn't read as broken.
const isOrgAdmin = computed<boolean>(() =>
    commonFunctions.isAdmin(orgUuid.value, myuser.value)
)
const hasApprovalRoleGrant = computed<boolean>(() => {
    const perms = myuser.value?.permissions?.permissions || []
    return perms.some((p: any) => p.org === orgUuid.value && p.approvals && p.approvals.length > 0)
})

const inboxListDrawerOpen = ref<boolean>(false)
const drawerTab = ref<string>('inbox')
let inboxLoadedOnce = false

const inboxItems = ref<InboxRow[]>([])
const inboxLoading = ref<boolean>(false)
// True when the deployed backend rejected the full inbox selection (a field
// it doesn't have yet -- e.g. a CE mirror lagging Pro) and we fell back to
// the core selection. Rows still render; enrichment (channelName) is absent.
const inboxDegraded = ref<boolean>(false)
// Sticky within a session: set once the backend rejects the full selection so
// later loads skip straight to the core query. Reset on org switch to re-probe
// (e.g. if pointed at a different / upgraded backend).
let inboxFullRejected = false
const inboxTotalCount = ref<number>(0)
const inboxUnreadCount = ref<number>(0)
const inboxPage = ref<number>(1)
const inboxPageSize = ref<number>(25)
const inboxPageCount = computed<number>(() =>
    Math.max(1, Math.ceil(inboxTotalCount.value / inboxPageSize.value))
)
const inboxUnreadOnly = ref<boolean>(true)
const inboxStatusFilter = ref<string | null>(null)
const inboxEventTypeFilter = ref<string | null>(null)
const selectedInboxRows = ref<string[]>([])
const inboxBulkLoading = ref<boolean>(false)
const inboxMarkAllLoading = ref<boolean>(false)

interface PendingApprovalEntry {
    approvalEntryUuid: string
    approvalName: string | null
    pendingRoleIds: string[]
}
interface ReleasePendingApproval {
    releaseUuid: string
    version: string | null
    componentUuid: string | null
    componentName: string | null
    componentType: string | null
    lifecycle: string | null
    pendingEntries: PendingApprovalEntry[]
}

const approvalsItems = ref<ReleasePendingApproval[]>([])
const approvalsLoading = ref<boolean>(false)
// True when the backend rejected the full approvals selection and we fell back
// to core fields (componentType/pendingRoleIds absent) -- see PR #169 pattern.
const approvalsDegraded = ref<boolean>(false)
// A ref, not items.length: the badge poll uses a slim releaseUuid-only
// query so it can refresh the count every minute without shipping full
// rows; the full row load syncs it too.
const approvalsCount = ref<number>(0)
const approvalsTabLabel = computed<string>(() =>
    approvalsCount.value > 0 ? `Needs my approval (${approvalsCount.value})` : 'Needs my approval'
)
// Org-level approval roles live on the store's org object; map id →
// displayView so pendingRoleIds render as human-readable role names.
// Bell badge = unread notifications + releases pending my approval, so a
// pending approval is visible from anywhere in the app even when the user
// has zero unread deliveries.
const navBadgeCount = computed<number>(() => inboxUnreadCount.value + approvalsCount.value)
const approvalRoleNameById = computed<Record<string, string>>(() => {
    const org = store.getters.allOrganizations
        .find((o: any) => o.uuid === orgUuid.value)
    const map: Record<string, string> = {}
    for (const r of org?.approvalRoles || []) map[r.id] = r.displayView
    return map
})

const inboxDrawerOpen = ref<boolean>(false)
const inboxDrawerRow = ref<InboxRow | null>(null)
const inboxDrawerPayload = computed<Record<string, unknown> | null>(() => {
    const raw = inboxDrawerRow.value?.payloadJson
    if (!raw) return null
    try {
        return JSON.parse(raw) as Record<string, unknown>
    } catch {
        return null
    }
})

interface ApprovalPayloadView {
    releaseUuid: string
    releaseLabel: string
    entryNames: string[]
    actorLabel: string
    actor: string
    resolution: string | null
}

function formatActor (name?: string, email?: string): string {
    if (name && email) return `${name} <${email}>`
    return name || email || '—'
}

// Normalized view over ApprovalRequestedPayload / ApprovalResolvedPayload;
// null for every other event type (those keep the raw-payload collapse only).
const approvalPayloadView = computed<ApprovalPayloadView | null>(() => {
    const row = inboxDrawerRow.value
    const p: any = inboxDrawerPayload.value
    if (!row || !p?.release?.releaseUuid) return null
    const releaseLabel = [p.release.componentName, p.release.version || p.release.releaseUuid]
        .filter(Boolean).join(' ')
    if (row.eventType === 'APPROVAL_REQUESTED') {
        return {
            releaseUuid: p.release.releaseUuid,
            releaseLabel,
            entryNames: (p.entries || [])
                .map((e: any) => e?.approvalEntryName || e?.approvalEntryUuid)
                .filter(Boolean),
            actorLabel: 'Requested by',
            actor: formatActor(p.requestedByName, p.requestedByEmail),
            resolution: null,
        }
    }
    if (row.eventType === 'APPROVAL_RESOLVED') {
        return {
            releaseUuid: p.release.releaseUuid,
            releaseLabel,
            entryNames: [p.approvalEntryName || p.approvalEntryUuid].filter(Boolean),
            actorLabel: 'Resolved by',
            actor: formatActor(p.resolvedByName, p.resolvedByEmail),
            resolution: p.resolution || null,
        }
    }
    return null
})

// The inbox query selection + its drift-tolerant loader live in
// @/utils/notificationInboxQuery (loadNotificationInboxPage). Kept out of
// this component so the core/enrichment field split has a single source of
// truth that the schema-drift test can assert against.

const INBOX_UNREAD_COUNT_QUERY = gql`
    query notificationUnreadCount($orgUuid: ID!) {
        notificationUnreadCount(orgUuid: $orgUuid)
    }
`

const MARK_READ_MUTATION = gql`
    mutation markNotificationRead($deliveryUuid: ID!) {
        markNotificationRead(deliveryUuid: $deliveryUuid) { uuid readAt }
    }
`

const MARK_UNREAD_MUTATION = gql`
    mutation markNotificationUnread($deliveryUuid: ID!) {
        markNotificationUnread(deliveryUuid: $deliveryUuid)
    }
`

const MARK_ALL_READ_MUTATION = gql`
    mutation markAllNotificationsRead($orgUuid: ID!) {
        markAllNotificationsRead(orgUuid: $orgUuid) { count hasMore }
    }
`

const RELEASES_NEEDING_MY_APPROVAL_QUERY = gql`
    query releasesNeedingMyApproval($orgUuid: ID!) {
        releasesNeedingMyApproval(orgUuid: $orgUuid) {
            releaseUuid version componentUuid componentName componentType lifecycle
            pendingEntries { approvalEntryUuid approvalName pendingRoleIds }
        }
    }
`

// Core selection without the Pro-ahead fields (componentType, pendingRoleIds)
// so the approvals tab degrades to rows instead of blanking when a CE mirror
// lags those fields -- see loadWithSchemaDriftFallback / PR #169.
const RELEASES_NEEDING_MY_APPROVAL_CORE_QUERY = gql`
    query releasesNeedingMyApproval($orgUuid: ID!) {
        releasesNeedingMyApproval(orgUuid: $orgUuid) {
            releaseUuid version componentUuid componentName lifecycle
            pendingEntries { approvalEntryUuid approvalName }
        }
    }
`

// Slim variant for the 60s badge poll — same field, releaseUuid-only
// selection, so polling the count doesn't ship full rows + nested
// entries org-wide every minute.
const APPROVALS_COUNT_QUERY = gql`
    query releasesNeedingMyApprovalCount($orgUuid: ID!) {
        releasesNeedingMyApproval(orgUuid: $orgUuid) { releaseUuid }
    }
`

function openInboxDrawer (): void {
    inboxListDrawerOpen.value = true
    // Land on the tab that has something for the user: when there are no
    // unread deliveries but the badge is lit by pending approvals, opening
    // on an empty Inbox would look broken.
    drawerTab.value = (inboxUnreadCount.value === 0 && approvalsCount.value > 0)
        ? 'approvals' : 'inbox'
    // Lazy-load the body the first time the drawer opens so a user who
    // never opens it pays no round-trip cost. Channel names ride along on
    // each inbox row (channelName), so no separate channel-list fetch.
    if (!inboxLoadedOnce) {
        inboxLoadedOnce = true
        loadInbox()
    }
    // Approvals are part of the nav badge, so the poll keeps the count fresh
    // in the background — but pull full rows on every open so the tab
    // reflects an approval granted seconds ago rather than stale poll data.
    loadApprovals()
}

// Same monotonic in-flight token pattern as loadInbox (see comment there):
// shared between the full load and the slim count poll so an old-org or
// out-of-order response can't overwrite newer state after an org switch.
let approvalsInflightToken = 0

async function loadApprovals (): Promise<void> {
    const myToken = ++approvalsInflightToken
    approvalsLoading.value = true
    try {
        const { data, degraded } = await loadWithSchemaDriftFallback(graphqlClient, {
            fullQuery: RELEASES_NEEDING_MY_APPROVAL_QUERY,
            coreQuery: RELEASES_NEEDING_MY_APPROVAL_CORE_QUERY,
            variables: { orgUuid: orgUuid.value },
            extractPath: (d: any) => d?.releasesNeedingMyApproval,
        })
        if (myToken !== approvalsInflightToken) return
        approvalsItems.value = data || []
        approvalsCount.value = approvalsItems.value.length
        approvalsDegraded.value = degraded
    } catch (e: any) {
        if (myToken !== approvalsInflightToken) return
        approvalsDegraded.value = false
        message.error(`Failed to load pending approvals: ${extractError(e)}`)
    } finally {
        if (myToken === approvalsInflightToken) approvalsLoading.value = false
    }
}

async function loadApprovalsCount (): Promise<void> {
    const myToken = ++approvalsInflightToken
    try {
        const res = await graphqlClient.query({
            query: APPROVALS_COUNT_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        if (myToken !== approvalsInflightToken) return
        approvalsCount.value = (res.data?.releasesNeedingMyApproval || []).length
    } catch {
        // Badge is decorative; failures are fine (matches loadInboxUnreadCount).
    }
}

// Monotonic in-flight token — ApolloClient network-only doesn't dedup or
// cancel concurrent identical queries, so a rapid filter toggle can race
// the older response in last and overwrite the newer state. The token
// guards apply-time so a stale response is dropped silently.
let inboxInflightToken = 0

async function loadInbox (): Promise<void> {
    const myToken = ++inboxInflightToken
    inboxLoading.value = true
    try {
        const offset = (inboxPage.value - 1) * inboxPageSize.value
        const { page, degraded } = await loadNotificationInboxPage(graphqlClient, {
            orgUuid: orgUuid.value,
            unreadOnly: inboxUnreadOnly.value,
            status: inboxStatusFilter.value,
            eventType: inboxEventTypeFilter.value,
            limit: inboxPageSize.value,
            offset,
        }, inboxFullRejected)
        if (myToken !== inboxInflightToken) return
        inboxDegraded.value = degraded
        // Once this backend has rejected the full selection, skip straight to
        // the core selection on subsequent loads so we don't pay the
        // reject-then-retry round-trip on every open/page/filter/refresh.
        if (degraded) inboxFullRejected = true
        inboxItems.value = page?.items || []
        inboxTotalCount.value = page?.totalCount || 0
        // Don't write inboxUnreadCount from page.unreadCount: that value is
        // the filtered count (matches the page's status / eventType /
        // unreadOnly), but the badge needs the org-wide unread total.
        // Otherwise a status=FAILED filter would silently shrink the badge.
        // loadInboxUnreadCount fires alongside (below) so any server-side
        // change is still picked up on every page.
        // Drop checked rows that aren't on the new page.
        const visible = new Set(inboxItems.value.map(r => r.uuid))
        selectedInboxRows.value = selectedInboxRows.value.filter(uuid => visible.has(uuid))
    } catch (e: any) {
        if (myToken !== inboxInflightToken) return
        // Clear the degraded banner on a hard failure so a stale "some details
        // unavailable" hint doesn't linger above a generic load-error toast.
        inboxDegraded.value = false
        message.error(`Failed to load inbox: ${extractError(e)}`)
    } finally {
        if (myToken === inboxInflightToken) inboxLoading.value = false
    }
    // Refresh the unfiltered badge alongside every inbox reload so
    // markAll / bulk-mark paths that loadInbox() at the end also pull a
    // fresh badge — no scattered explicit calls needed.
    loadInboxUnreadCount()
}

function applyInboxFilters (): void {
    inboxPage.value = 1
    loadInbox()
}

// "View delivery log": jump from this inbox row to the Delivery History audit
// surface pre-filtered to the row's channel, so a user going "my alert didn't
// arrive -> why" lands on that channel's full send log. Failures open the log
// filtered to FAILED. Admin-only (the Audit tab is admin-gated); the button is
// hidden otherwise, and hidden for channel-less rows (targeted approvals).
function viewDeliveryLog (): void {
    const row = inboxDrawerRow.value
    if (!row || !row.channelUuid) return
    inboxDrawerOpen.value = false
    inboxListDrawerOpen.value = false
    router.push({
        name: 'OrgSettings',
        params: { orguuid: orgUuid.value },
        query: {
            tab: 'audit',
            auditTab: 'deliveryHistory',
            historyChannel: row.channelUuid,
            ...(row.status === 'FAILED' ? { historyStatus: 'FAILED' } : {}),
        },
    })
}

function openInboxRow (row: InboxRow): void {
    inboxDrawerRow.value = row
    inboxDrawerOpen.value = true
    // Mark-read-on-open is intentionally NOT wired here — keep the explicit
    // mark-read affordance as the user's choice, matching how the rest of
    // ReARM treats explicit user signals as primary.
}

async function markRowRead (row: InboxRow): Promise<void> {
    try {
        const res = await graphqlClient.mutate({
            mutation: MARK_READ_MUTATION,
            variables: { deliveryUuid: row.uuid },
        })
        const readAt = res.data?.markNotificationRead?.readAt || new Date().toISOString()
        // Optimistic update — patch the row in place rather than reload.
        const idx = inboxItems.value.findIndex(r => r.uuid === row.uuid)
        if (idx >= 0) inboxItems.value[idx].readAt = readAt
        // A read row drops its selection checkbox (v-if unread), so prune it
        // from the pending bulk set too -- otherwise its uuid would linger and
        // keep the "Mark N read" count inflated in the all-visible view.
        selectedInboxRows.value = selectedInboxRows.value.filter(u => u !== row.uuid)
        if (inboxUnreadCount.value > 0) inboxUnreadCount.value -= 1
        if (inboxUnreadOnly.value) {
            inboxItems.value = inboxItems.value.filter(r => r.uuid !== row.uuid)
            if (inboxTotalCount.value > 0) inboxTotalCount.value -= 1
        }
        // Invalidate any concurrent loadInbox so its late-landing response
        // can't overwrite this optimistic patch with stale pre-mark data.
        inboxInflightToken++
    } catch (e: any) {
        message.error(`Mark read failed: ${extractError(e)}`)
    }
}

async function markRowUnread (row: InboxRow): Promise<void> {
    try {
        const wasUnmarked = await graphqlClient.mutate({
            mutation: MARK_UNREAD_MUTATION,
            variables: { deliveryUuid: row.uuid },
        })
        const removed = wasUnmarked.data?.markNotificationUnread === true
        const idx = inboxItems.value.findIndex(r => r.uuid === row.uuid)
        if (idx >= 0) inboxItems.value[idx].readAt = null
        // Only bump the unread count when a row actually went read → unread
        // server-side. The button is gated by row.readAt != null so this is
        // rarely false in practice, but a stale-cached row could double-count.
        if (removed) inboxUnreadCount.value += 1
        inboxInflightToken++
    } catch (e: any) {
        message.error(`Mark unread failed: ${extractError(e)}`)
    }
}

async function markRowReadFromDrawer (): Promise<void> {
    if (!inboxDrawerRow.value) return
    await markRowRead(inboxDrawerRow.value)
    // Sync drawer's readAt so the buttons swap without a fresh fetch.
    // Fallback: when Unread-only filter is active, markRowRead has already
    // filtered the row OUT of inboxItems — find() returns undefined. Stamp
    // readAt directly on the captured drawer row so the UI still flips.
    const fresh = inboxItems.value.find(r => r.uuid === inboxDrawerRow.value?.uuid)
    if (fresh) {
        inboxDrawerRow.value = fresh
    } else {
        inboxDrawerRow.value = { ...inboxDrawerRow.value, readAt: new Date().toISOString() }
    }
}

async function markRowUnreadFromDrawer (): Promise<void> {
    if (!inboxDrawerRow.value) return
    await markRowUnread(inboxDrawerRow.value)
    const fresh = inboxItems.value.find(r => r.uuid === inboxDrawerRow.value?.uuid)
    if (fresh) {
        inboxDrawerRow.value = fresh
    } else {
        inboxDrawerRow.value = { ...inboxDrawerRow.value, readAt: null }
    }
}

async function bulkMarkRead (): Promise<void> {
    if (selectedInboxRows.value.length === 0) return
    inboxBulkLoading.value = true
    const uuids = [...selectedInboxRows.value]
    try {
        // Fire one mutation per selected row in parallel. allSettled so a
        // single failed uuid doesn't drop the success count from the
        // surviving rows; the backend's per-uuid visibility guard is the
        // per-row check, the UI surfaces the mixed outcome.
        const results = await Promise.allSettled(uuids.map(uuid =>
            graphqlClient.mutate({
                mutation: MARK_READ_MUTATION,
                variables: { deliveryUuid: uuid },
            })
        ))
        const ok = results.filter(r => r.status === 'fulfilled').length
        const failed = results.length - ok
        if (failed === 0) {
            message.success(`Marked ${ok} read`)
        } else if (ok > 0) {
            message.warning(`Marked ${ok} of ${results.length} read (${failed} failed)`)
        } else {
            message.error(`Mark read failed for all ${results.length} selected`)
        }
        selectedInboxRows.value = []
    } finally {
        inboxBulkLoading.value = false
        // Always reload regardless of pass/fail mix so the table reconciles
        // with server truth — the optimistic-patch approach for the
        // single-row path doesn't fit here because partial failures could
        // leave the table in an arbitrary state.
        await loadInbox()
    }
}

function markAllReadConfirm (): void {
    const n = Math.min(inboxUnreadCount.value, 500)
    const remainder = Math.max(0, inboxUnreadCount.value - 500)
    const tail = remainder > 0
        ? ` Backend caps a single sweep at 500 — re-run to clear the remaining ${remainder}.`
        : ''
    dialog.warning({
        title: 'Mark every visible unread notification as read?',
        content: `This marks up to ${n} unread item(s) as read.${tail}`,
        positiveText: 'Mark all read',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            inboxMarkAllLoading.value = true
            try {
                const res = await graphqlClient.mutate({
                    mutation: MARK_ALL_READ_MUTATION,
                    variables: { orgUuid: orgUuid.value },
                })
                const result = res.data?.markAllNotificationsRead
                const marked = result?.count || 0
                const hasMore = result?.hasMore === true
                if (hasMore) {
                    // Backend hit the per-sweep cap; some unread remain. Use
                    // warning so the operator sees the "re-run" hint rather
                    // than a green "done" toast that hides the tail.
                    message.warning(
                        `Marked ${marked} read — cap reached; re-run to clear the rest.`,
                    )
                } else {
                    message.success(`Marked ${marked} read`)
                }
                await loadInbox()
            } catch (e: any) {
                message.error(`Mark all failed: ${extractError(e)}`)
            } finally {
                inboxMarkAllLoading.value = false
            }
        },
    })
}

async function loadInboxUnreadCount (): Promise<void> {
    try {
        const res = await graphqlClient.query({
            query: INBOX_UNREAD_COUNT_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        inboxUnreadCount.value = res.data?.notificationUnreadCount || 0
    } catch {
        // Badge is decorative; failures are fine.
    }
}

// Single source of truth for the Channel label, shared by the table cell and
// the row-detail drawer so the deleted-vs-degraded distinction can't drift
// between them. `muted` = render dim; `title` = hover text (the uuid) where
// useful. States: no channelUuid => Direct; channelName present => the name;
// channelName selected-but-null => the channel was deleted; channelName ABSENT
// from the row (degraded fallback) => a neutral label, NOT a false "deleted".
function channelLabel (row: InboxRow | null): { text: string, muted: boolean, title?: string, disabled?: boolean } {
    if (!row || !row.channelUuid) return { text: 'Direct', muted: true }
    if (row.channelName) {
        // The channel exists. Surface a disabled/auto-disabled state (Pro-ahead
        // enrichment; absent on a degraded load) so a failed delivery explains
        // itself -- a disabled channel is why nothing lands, distinct from a
        // deleted one. channelDisabledReason (when present) rides the hover.
        const disabled = row.channelEnabled === false
        return {
            text: row.channelName,
            muted: false,
            disabled,
            title: disabled
                ? (row.channelDisabledReason || 'This channel is disabled; deliveries to it are paused.')
                : undefined,
        }
    }
    return { text: ('channelName' in row) ? '(deleted channel)' : 'Channel', muted: true, title: row.channelUuid }
}

const drawerChannel = computed(() => channelLabel(inboxDrawerRow.value))

// Neutral grey rail for rows with no severity (release/approval lifecycle
// events) -- a UI accent, not a severity, so it stays out of the vuln palette.
const RAIL_KIND_COLOR = '#c9ccd4'

// Left-rail colour from the canonical severity palette (constants.Vulnerability-
// Colors, shared with the vuln charts / PDF export) so severity reads the same
// hue here as everywhere else; grey for lifecycle rows so the rail always signals.
function railColor (severity: string | null): string {
    if (!severity) return RAIL_KIND_COLOR
    return (constants.VulnerabilityColors as Record<string, string>)[severity] || RAIL_KIND_COLOR
}

// Neutral kind pill shown in place of a severity tag for events that carry no
// severity, so the head line always names the category.
function kindLabel (eventType: string | null): string {
    if (!eventType) return 'EVENT'
    if (eventType.startsWith('RELEASE')) return 'RELEASE'
    if (eventType.startsWith('APPROVAL')) return 'APPROVAL'
    if (eventType.startsWith('VEX')) return 'VEX'
    if (eventType.includes('VULN')) return 'VULN'
    return 'EVENT'
}

// Card-list view model. Resolve each row's display title (server title, else
// the event-type label, else a placeholder), channel label, rail colour, kind
// pill, and failed-delivery flag ONCE here so the template renders straight
// fields instead of recomputing per binding.
const inboxCards = computed(() => inboxItems.value.map(row => {
    const ts = row.sentAt || row.createdDate
    return {
        row,
        title: row.title || (row.eventType
            ? row.eventType.replace(/_/g, ' ').toLowerCase()
            : '(no content)'),
        channel: channelLabel(row),
        rail: railColor(row.severity),
        kind: row.severity ? null : kindLabel(row.eventType),
        // FAILED = the delivery didn't reach the channel; surfaced inline so a
        // "why didn't my alert arrive" scan lands immediately.
        failed: row.status === 'FAILED',
        // Relative time for the scan; absolute timestamp shown on hover.
        timeRel: relativeTime(ts),
        timeAbs: formatHistoryTimestamp(ts),
    }
}))

// Selection is a plain uuid array (was n-data-table checked-row-keys). Only
// unread cards render a checkbox, mirroring the old selection-column disable.
function toggleInboxSelect (uuid: string, checked: boolean): void {
    if (checked) {
        if (!selectedInboxRows.value.includes(uuid)) selectedInboxRows.value.push(uuid)
    } else {
        selectedInboxRows.value = selectedInboxRows.value.filter(u => u !== uuid)
    }
}

// Deep-link navigation target sits behind the 760px drawer — close it on
// click or the user lands on a page they can't see.
function approvalLink (to: string, label: string) {
    return h(
        RouterLink as any,
        { to, onClick: () => { inboxListDrawerOpen.value = false } },
        () => label,
    )
}

const approvalsColumns = computed(() => [
    {
        title: 'Component', key: 'componentName',
        render: (row: ReleasePendingApproval) => {
            const inner = !row.componentUuid
                ? h('span', { class: 'muted-12' }, row.componentName || '—')
                : approvalLink(
                    `/${row.componentType === 'PRODUCT' ? 'productsOfOrg' : 'componentsOfOrg'}/${orgUuid.value}/${row.componentUuid}`,
                    row.componentName || row.componentUuid)
            return h('span', { 'data-testid': 'approval-pending-row' }, inner)
        },
    },
    {
        title: 'Release', key: 'version',
        render: (row: ReleasePendingApproval) => approvalLink(
            `/release/show/${row.releaseUuid}`, row.version || row.releaseUuid),
    },
    {
        title: 'Lifecycle', key: 'lifecycle',
        render: (row: ReleasePendingApproval) => row.lifecycle
            ? h(NTag, { size: 'small' }, { default: () => row.lifecycle })
            : h('span', { class: 'muted-12' }, '—'),
    },
    {
        title: 'Pending approvals', key: 'pendingEntries',
        render: (row: ReleasePendingApproval) => h(
            'div',
            (row.pendingEntries || []).map(entry => {
                const roles = (entry.pendingRoleIds || [])
                    .map(id => approvalRoleNameById.value[id] || id)
                    .join(', ')
                return h('div', { class: 'approval-entry-line' }, [
                    h('span', { class: 'approval-entry-name' }, entry.approvalName || entry.approvalEntryUuid),
                    roles ? h('span', { class: 'muted-12' }, ` — ${roles}`) : null,
                ])
            }),
        ),
    },
])

// Badge polling — refresh the org-wide unread count on a slow cadence so a
// delivery that lands while the user sits on an unrelated page still bumps
// the badge. 60s is gentle on the backend; the drawer's own loadInbox path
// refreshes on every interaction for the live view.
let pollTimer: ReturnType<typeof setInterval> | null = null

watch(orgUuid, () => {
    // Org switch in the nav — reset the lazy-load latch + counts and pull a
    // fresh badge for the new org.
    inboxLoadedOnce = false
    inboxItems.value = []
    inboxTotalCount.value = 0
    inboxDegraded.value = false
    inboxFullRejected = false
    selectedInboxRows.value = []
    approvalsItems.value = []
    approvalsCount.value = 0
    if (orgUuid.value) {
        loadInboxUnreadCount()
        loadApprovalsCount()
        // The drawer is mounted once at nav level and can stay open across an
        // org switch (no openInboxDrawer() re-fire to trigger the full-row
        // load). Without this, approvalsItems sits at [] with loading=false
        // for the new org, and the empty-state hint below would confidently
        // claim "nothing pending" for data that was simply never fetched.
        if (inboxListDrawerOpen.value && drawerTab.value === 'approvals') {
            loadApprovals()
        }
    }
})

onMounted(() => {
    if (orgUuid.value) {
        loadInboxUnreadCount()
        loadApprovalsCount()
    }
    pollTimer = setInterval(() => {
        if (orgUuid.value) {
            loadInboxUnreadCount()
            loadApprovalsCount()
        }
    }, 60000)
})

onUnmounted(() => {
    if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.inbox-nav-trigger { display: inline-flex; align-items: center; }

.tab-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0 16px;
}
.tab-toolbar-info { font-size: 12.5px; color: var(--n-text-color-3, #888); }
.muted-12 { font-size: 12px; color: var(--n-text-color-3, #888); }
.history-filters { margin-bottom: 12px; }
.history-pagination { margin-top: 12px; display: flex; justify-content: flex-end; }
.inbox-degraded-alert { margin-bottom: 12px; }

.approval-entry-line { line-height: 1.4; }
.approval-entry-name { font-weight: 500; }

/* Inbox message column — Title (bold) + description (muted) stacked, whole
 * cell is the click target that opens the drawer. Rendered as a button
 * (a11y: announces as "button" not "link"); these resets make it visually
 * indistinguishable from a plain block of text. */
.inbox-message-link {
    display: block;
    width: 100%;
    color: inherit;
    text-align: left;
    background: none;
    border: 0;
    padding: 0;
    margin: 0;
    font: inherit;
    text-decoration: none;
    cursor: pointer;
}
.inbox-message-link:focus-visible {
    outline: 2px solid var(--n-color-primary, #2080f0);
    outline-offset: 2px;
    border-radius: 2px;
}
.inbox-message-link:hover .inbox-message-title { color: var(--n-color-primary, #2080f0); }
/* CSS ellipsis (not JS slicing): clips on the rendered glyph boundary so a
 * multi-byte char / emoji at the cut point can't be split mid-surrogate. */
.inbox-message-title {
    font-weight: 500;
    line-height: 1.3;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 100%;
}
.inbox-message-desc {
    margin-top: 3px;
    line-height: 1.35;
    /* Two-line clamp in the card layout -- enough to preview the event without
     * letting a long description dominate the row. */
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

/* --- Card-style inbox rows (replaces the old overflowing data-table) ------ */
.inbox-list { display: flex; flex-direction: column; }
.inbox-empty { padding: 24px 4px; text-align: center; }
.inbox-card {
    display: flex;
    align-items: stretch;
    gap: 10px;
    padding: 12px 4px 12px 0;
    border-bottom: 1px solid var(--n-border-color, #efeff5);
}
.inbox-card:hover { background: var(--inbox-hover-bg, rgba(32, 128, 240, 0.035)); }
/* Unread rows get a faint tint + bolder title so the triage queue reads at a
 * glance; read rows sit flat and slightly muted. The left rail (below) carries
 * the severity/kind colour independent of read state. */
.inbox-card.unread { background: var(--inbox-unread-bg, rgba(32, 128, 240, 0.03)); }
.inbox-card.unread .inbox-message-title { font-weight: 600; }
.inbox-card.read .inbox-message-title {
    font-weight: 400;
    color: var(--n-text-color-2, #666);
}

/* Left rail: 4px severity/kind colour bar. Colour is bound inline from the
 * shared severity palette (constants.VulnerabilityColors) via :style. */
.inbox-rail { flex: 0 0 4px; width: 4px; border-radius: 2px; align-self: stretch; }

.inbox-card-select { flex: 0 0 auto; width: 20px; padding-top: 2px; }
.inbox-card-main { flex: 1 1 auto; min-width: 0; }
.inbox-card-head { display: flex; align-items: center; gap: 8px; }
.inbox-card-head .inbox-message-link { flex: 1 1 auto; min-width: 0; }
.inbox-unread-dot {
    flex: 0 0 8px;
    width: 8px; height: 8px;
    border-radius: 50%;
    background: var(--n-color-primary, #2080f0);
}
.inbox-kind-tag { font-weight: 600; letter-spacing: 0.04em; }

/* Sub-line: status pill, channel, and (on failure) an inline not-delivered
 * note so a missed alert is obvious without opening the row. */
.inbox-card-sub { display: flex; align-items: center; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.inbox-meta-sep { opacity: 0.5; }
.inbox-chan.fail { color: #d03050; font-weight: 600; }
/* Disabled-channel suffix on the channel label: amber, hover shows the reason. */
.inbox-chan-disabled { color: #f0a020; font-weight: 600; }
.inbox-fail-note { color: #d03050; font-size: 12px; }

/* Right column: timestamp over the per-row read/unread action. */
.inbox-card-side {
    flex: 0 0 auto;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    justify-content: space-between;
    gap: 6px;
    padding-top: 1px;
}
.inbox-time { white-space: nowrap; }
.inbox-card.unread .inbox-time { color: var(--n-text-color-2, #606266); font-weight: 600; }

/* Inbox drawer — payload viewer. Monospace + wrap-on-word so a deeply-nested
 * JSON doesn't blow the drawer's horizontal extent. */
.inbox-drawer-desc { font-size: 14px; line-height: 1.45; }
.inbox-drawer-payload {
    font-family: var(--font-mono, monospace);
    font-size: 12px;
    background: var(--code-bg, #f6f7f9);
    padding: 8px 10px;
    border-radius: 4px;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-word;
    margin: 0;
}
</style>

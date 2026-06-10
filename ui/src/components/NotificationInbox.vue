<template>
    <span class="inbox-nav-trigger">
        <n-badge :value="navBadgeCount" :max="99" :show="navBadgeCount > 0">
            <n-icon class="clickable" title="Notifications" size="20" @click="openInboxDrawer">
                <Bell />
            </n-icon>
        </n-badge>

        <!-- Inbox list drawer — the triage queue. Mounted at nav level so
             it's reachable from anywhere; the body is loaded lazily the
             first time the drawer opens so a user who never opens it pays
             no round-trip cost. -->
        <n-drawer v-model:show="inboxListDrawerOpen" :width="760" placement="right">
            <n-drawer-content title="Notifications" closable>
                <n-tabs type="line" animated>
                <n-tab-pane name="inbox" tab="Inbox">
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

                <n-data-table
                    :data="inboxItems"
                    :columns="inboxColumns"
                    :loading="inboxLoading"
                    :single-line="false"
                    :bordered="false"
                    :row-key="(row) => row.uuid"
                    v-model:checked-row-keys="selectedInboxRows"
                />

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
                <n-tab-pane name="approvals" :tab="approvalsTabLabel">
                    <div class="tab-toolbar">
                        <div class="tab-toolbar-info">
                            Releases with an approval pending on one of your approval roles. Open the release to approve or disapprove.
                        </div>
                        <n-button size="small" @click="loadApprovals()">
                            <template #icon><n-icon><Refresh /></n-icon></template>
                            Refresh
                        </n-button>
                    </div>
                    <n-data-table
                        :data="approvalsItems"
                        :columns="approvalsColumns"
                        :loading="approvalsLoading"
                        :single-line="false"
                        :bordered="false"
                        :row-key="(row) => row.releaseUuid"
                    />
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
                            <span>{{ channelNameById[inboxDrawerRow.channelUuid] || '(deleted)' }}</span>
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

                    <!-- Structured payload view. Pretty-printed JSON for now;
                         richer per-event-type rendering (deep-link buttons,
                         affected-release table) lands with BD-3 actionable
                         events in phase 4.
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
    NBadge, NTabs, NTabPane, useDialog, useMessage
} from 'naive-ui'
import { Bell, Check, Refresh } from '@vicons/tabler'
import { RouterLink } from 'vue-router'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import {
    ChannelRow, InboxRow, deliveryStatusOptions, eventTypeOptions,
    LIST_CHANNELS_QUERY, deliveryStatusTagType, severityTagType,
    formatHistoryTimestamp, extractError, buildNameMap
} from '@/utils/notificationsCommon'

const props = defineProps<{
    orguuid: string
}>()

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

const channels = ref<ChannelRow[]>([])
const channelNameById = computed<Record<string, string>>(() => buildNameMap(channels.value))

const inboxListDrawerOpen = ref<boolean>(false)
let inboxLoadedOnce = false

const inboxItems = ref<InboxRow[]>([])
const inboxLoading = ref<boolean>(false)
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
    lifecycle: string | null
    pendingEntries: PendingApprovalEntry[]
}

const approvalsItems = ref<ReleasePendingApproval[]>([])
const approvalsLoading = ref<boolean>(false)
const approvalsCount = computed<number>(() => approvalsItems.value.length)
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

const INBOX_QUERY = gql`
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
            items {
                uuid org outboxEventUuid subscriptionUuid channelUuid
                status origin dedupKey attemptCount nextAttemptAt sentAt
                lastError createdDate readAt eventType severity
                title description payloadJson
            }
            totalCount unreadCount limit offset
        }
    }
`

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
            releaseUuid version componentUuid componentName lifecycle
            pendingEntries { approvalEntryUuid approvalName pendingRoleIds }
        }
    }
`

async function loadChannels (): Promise<void> {
    try {
        const res = await graphqlClient.query({
            query: LIST_CHANNELS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channels.value = res.data?.notificationChannels || []
    } catch (e: any) {
        message.error(`Failed to load channels: ${extractError(e)}`)
    }
}

function openInboxDrawer (): void {
    inboxListDrawerOpen.value = true
    // Lazy-load the body + channel name map the first time the drawer
    // opens so a user who never opens it pays no round-trip cost.
    if (!inboxLoadedOnce) {
        inboxLoadedOnce = true
        loadChannels()
        loadInbox()
    }
    // Approvals are part of the nav badge, so the poll keeps them fresh in
    // the background — but re-pull on every open so the tab reflects an
    // approval granted seconds ago rather than up-to-60s-old poll data.
    loadApprovals()
}

async function loadApprovals (quiet = false): Promise<void> {
    approvalsLoading.value = true
    try {
        const res = await graphqlClient.query({
            query: RELEASES_NEEDING_MY_APPROVAL_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        approvalsItems.value = res.data?.releasesNeedingMyApproval || []
    } catch (e: any) {
        // The badge poll calls this quietly — a transient failure there
        // shouldn't toast; the user-initiated Refresh path should.
        if (!quiet) message.error(`Failed to load pending approvals: ${extractError(e)}`)
    } finally {
        approvalsLoading.value = false
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
        const res = await graphqlClient.query({
            query: INBOX_QUERY,
            variables: {
                orgUuid: orgUuid.value,
                unreadOnly: inboxUnreadOnly.value,
                status: inboxStatusFilter.value,
                eventType: inboxEventTypeFilter.value,
                limit: inboxPageSize.value,
                offset,
            },
            fetchPolicy: 'network-only',
        })
        if (myToken !== inboxInflightToken) return
        const page = res.data?.notificationInbox
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

const inboxColumns = computed(() => [
    {
        type: 'selection' as const,
        // Selection cell only meaningful for unread rows — read rows can't
        // be re-marked-read.
        disabled: (row: InboxRow) => !!row.readAt,
    },
    {
        title: 'When', key: 'when',
        render: (row: InboxRow) => formatHistoryTimestamp(row.sentAt || row.createdDate),
    },
    {
        title: 'Status', key: 'status',
        render: (row: InboxRow) => h(
            NTag,
            { type: deliveryStatusTagType(row.status), size: 'small' },
            { default: () => row.status },
        ),
    },
    {
        title: 'Message', key: 'message',
        render: (row: InboxRow) => {
            // The Message cell is the click target that opens the inbox
            // drawer. Title is bold; description sits below as muted helper
            // text. Fallback to the eventType label when the server-rendered
            // title is null (malformed payload path).
            const titleText = row.title || (row.eventType
                ? row.eventType.replace(/_/g, ' ').toLowerCase()
                : '(no content)')
            const description = row.description
            // <button type="button">, not <a href="#">. The cell is an
            // action (open drawer), not navigation — anchor semantics would
            // mis-announce as "link" to screen readers.
            return h(
                'button',
                {
                    type: 'button',
                    class: 'inbox-message-link',
                    onClick: () => openInboxRow(row),
                },
                {
                    default: () => [
                        h('div', { class: 'inbox-message-title' }, titleText),
                        description
                            ? h('div', { class: 'inbox-message-desc muted-12' }, description)
                            : null,
                    ],
                },
            )
        },
    },
    {
        title: 'Severity', key: 'severity',
        render: (row: InboxRow) => row.severity
            ? h(
                NTag,
                { type: severityTagType(row.severity), size: 'small' },
                { default: () => row.severity },
            )
            : h('span', { class: 'muted-12' }, '—'),
    },
    {
        title: 'Channel', key: 'channelUuid',
        render: (row: InboxRow) => channelNameById.value[row.channelUuid]
            || h('span', { class: 'muted-12', title: row.channelUuid }, '(deleted channel)'),
    },
    {
        title: 'Read', key: 'readAt',
        render: (row: InboxRow) => row.readAt
            ? h(NSpace, { size: 'small', align: 'center' }, {
                default: () => [
                    h('span', { class: 'muted-12' }, formatHistoryTimestamp(row.readAt)),
                    h(NButton, {
                        size: 'tiny', secondary: true,
                        onClick: () => markRowUnread(row),
                        disabled: !canWrite.value,
                        title: 'Mark unread',
                    }, { default: () => 'Unread' }),
                ],
            })
            : h(NButton, {
                size: 'tiny', secondary: true, type: 'primary',
                onClick: () => markRowRead(row),
                disabled: !canWrite.value,
            }, { icon: () => h(NIcon, null, { default: () => h(Check) }), default: () => 'Mark read' }),
    },
])

const approvalsColumns = computed(() => [
    {
        title: 'Component', key: 'componentName',
        render: (row: ReleasePendingApproval) => row.componentUuid
            ? h(
                RouterLink as any,
                { to: `/componentsOfOrg/${orgUuid.value}/${row.componentUuid}` },
                () => row.componentName || row.componentUuid,
            )
            : h('span', { class: 'muted-12' }, row.componentName || '—'),
    },
    {
        title: 'Release', key: 'version',
        render: (row: ReleasePendingApproval) => h(
            RouterLink as any,
            { to: `/release/show/${row.releaseUuid}` },
            () => row.version || row.releaseUuid,
        ),
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
    selectedInboxRows.value = []
    approvalsItems.value = []
    if (orgUuid.value) {
        loadInboxUnreadCount()
        loadApprovals(true)
    }
})

onMounted(() => {
    if (orgUuid.value) {
        loadInboxUnreadCount()
        loadApprovals(true)
    }
    pollTimer = setInterval(() => {
        if (orgUuid.value) {
            loadInboxUnreadCount()
            loadApprovals(true)
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
    margin-top: 2px;
    line-height: 1.3;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 100%;
}

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

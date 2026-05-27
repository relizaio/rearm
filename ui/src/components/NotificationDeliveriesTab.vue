<template>
    <div class="notificationDeliveriesTab">
        <div class="head">
            <div class="title-row">
                <h4>Delivery history</h4>
                <n-tooltip trigger="hover" :width="400" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="16" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    Each row is one (event × channel) dispatch attempt. PENDING
                    rows are waiting for the next channel-worker tick or a
                    backoff window. FAILED rows hit the backend's max-attempts
                    bound or a non-retriable error (most often a misconfigured
                    webhook URL); the <code>lastError</code> column has the
                    operator-facing diagnostic. Click any row for the full record.
                </n-tooltip>
            </div>
            <n-space>
                <n-select v-model:value="statusFilter"
                          :options="statusOptions"
                          placeholder="Status: any" size="small" style="width: 160px;" clearable/>
                <n-select v-model:value="originFilter"
                          :options="originOptions"
                          placeholder="Origin: any" size="small" style="width: 140px;" clearable/>
                <n-button @click="reload" size="small">Refresh</n-button>
            </n-space>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!page.items?.length" class="empty">
                No deliveries match the current filter.
            </div>
            <n-data-table v-else
                          :columns="columns"
                          :data="page.items"
                          :pagination="paginationCfg"
                          remote
                          :loading="loading"
                          :row-props="rowProps"/>
            <div class="total-row">Total: {{ page.totalCount }} deliveries</div>
        </div>

        <n-drawer v-model:show="drawerOpen" :width="560" placement="right">
            <n-drawer-content v-if="drawerRow" :title="`Delivery ${drawerRow.uuid?.substring(0, 8)}…`"
                              closable>
                <div class="drawer-section">
                    <n-tag :type="statusType(drawerRow.status)" size="small">{{ drawerRow.status }}</n-tag>
                    <n-tag size="small" bordered>{{ drawerRow.origin }}</n-tag>
                    <span class="muted">{{ drawerRow.attemptCount }} attempt{{ drawerRow.attemptCount === 1 ? '' : 's' }}</span>
                </div>

                <h5>Identifiers</h5>
                <table class="kv">
                    <tr><th>Delivery UUID</th><td><code>{{ drawerRow.uuid }}</code></td></tr>
                    <tr><th>Event UUID</th><td><code>{{ drawerRow.outboxEventUuid }}</code></td></tr>
                    <tr><th>Subscription</th><td>{{ resolveSubscription(drawerRow.subscriptionUuid) }}</td></tr>
                    <tr><th>Channel</th><td>{{ resolveChannel(drawerRow.channelUuid) }}</td></tr>
                    <tr><th>Dedup key</th><td><code>{{ drawerRow.dedupKey ?? '—' }}</code></td></tr>
                </table>

                <h5>Timeline</h5>
                <table class="kv">
                    <tr><th>Created</th><td>{{ formatDate(drawerRow.createdDate) }}</td></tr>
                    <tr><th>Next attempt</th><td>{{ formatDate(drawerRow.nextAttemptAt) }}</td></tr>
                    <tr><th>Sent</th><td>{{ formatDate(drawerRow.sentAt) }}</td></tr>
                </table>

                <h5>Last error</h5>
                <div v-if="drawerRow.lastError" class="error-block">{{ drawerRow.lastError }}</div>
                <div v-else class="muted">No error recorded.</div>
            </n-drawer-content>
        </n-drawer>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import {
    NButton, NDataTable, NDrawer, NDrawerContent, NIcon, NSelect, NSpace, NSpin, NTag, NTooltip,
    DataTableColumns, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import {
    NOTIFICATION_DELIVERY_STATUSES,
    NOTIFICATION_DELIVERY_ORIGINS,
    deliveryStatusType,
    type NotificationDeliveryStatus,
    type NotificationDeliveryOrigin,
} from '@/utils/notification-constants'

const store = useStore()
const route = useRoute()
const notification = useNotification()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => (route.params.orguuid as string) || myorg.value?.uuid)

const page = ref<any>({ items: [], totalCount: 0, limit: 25, offset: 0 })
const loading = ref<boolean>(true)
const statusFilter = ref<NotificationDeliveryStatus | null>(null)
const originFilter = ref<NotificationDeliveryOrigin | null>(null)
const currentPage = ref(1)
const pageSize = 25

// Detail-drawer state. Click a row → open with the row's full record.
// We resolve channel + subscription UUIDs against the in-memory lookups
// so the drawer shows names without an extra round-trip. `lookupsLoading`
// distinguishes "still fetching" from "permanently unknown" — without
// this guard a click in the load window would otherwise show "(unknown)"
// the same as a 403 / RBAC mask would.
const drawerOpen = ref(false)
const drawerRow = ref<any | null>(null)
const channelLookup = ref<Record<string, any>>({})
const subscriptionLookup = ref<Record<string, any>>({})
const lookupsLoading = ref(true)

const statusOptions = NOTIFICATION_DELIVERY_STATUSES
const originOptions = NOTIFICATION_DELIVERY_ORIGINS

const paginationCfg = computed(() => ({
    page: currentPage.value,
    pageSize,
    itemCount: page.value.totalCount,
    showSizePicker: false,
    onChange: (p: number) => {
        currentPage.value = p
        reload()
    },
}))

watch([statusFilter, originFilter], () => {
    currentPage.value = 1
    reload()
})

onMounted(async () => {
    await Promise.all([reload(), loadLookups()])
})

async function reload () {
    if (!orgUuid.value) return
    loading.value = true
    try {
        page.value = await store.dispatch('fetchNotificationDeliveries', {
            orgUuid: orgUuid.value,
            status: statusFilter.value,
            origin: originFilter.value,
            limit: pageSize,
            offset: (currentPage.value - 1) * pageSize,
        }) || { items: [], totalCount: 0, limit: pageSize, offset: 0 }
    } catch (e: any) {
        notification.error({ content: `Failed to load deliveries: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

async function loadLookups () {
    // Resolve channel + subscription names for the drawer. Errors here
    // (RBAC 403, network) MUST surface — otherwise every row in the
    // drawer renders "(unknown)" with no operator signal that lookups
    // are silently broken. Toast on failure so the operator knows the
    // drawer's resolution is degraded; the list itself still works.
    if (!orgUuid.value) return
    lookupsLoading.value = true
    try {
        const channels = await store.dispatch('fetchNotificationChannelsOfOrg', orgUuid.value) || []
        const map: Record<string, any> = {}
        for (const c of channels) map[c.uuid] = c
        channelLookup.value = map
    } catch (e: any) {
        notification.warning({
            content: `Channel name lookup failed — delivery drawer will show UUIDs only: ${e?.message ?? e}`,
            duration: 6000,
        })
    }
    try {
        const subs = await store.dispatch('fetchNotificationSubscriptionsOfOrg', orgUuid.value) || []
        const map: Record<string, any> = {}
        for (const s of subs) map[s.uuid] = s
        subscriptionLookup.value = map
    } catch (e: any) {
        notification.warning({
            content: `Subscription name lookup failed — delivery drawer will show UUIDs only: ${e?.message ?? e}`,
            duration: 6000,
        })
    } finally {
        lookupsLoading.value = false
    }
}

function formatDate (s: any) {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

const statusType = deliveryStatusType

function resolveChannel (uuid: string | null | undefined): string {
    if (!uuid) return '—'
    const c = channelLookup.value[uuid]
    if (c) return `${c.name} (${c.type})`
    // Distinguish "still loading" from "permanently unknown" so a click
    // in the load window doesn't look like the same outcome as a 403.
    return lookupsLoading.value
        ? `${uuid.substring(0, 8)}… (loading…)`
        : `${uuid.substring(0, 8)}… (unknown)`
}

function resolveSubscription (uuid: string | null | undefined): string {
    if (!uuid) return '—'
    const s = subscriptionLookup.value[uuid]
    if (s) return s.name
    return lookupsLoading.value
        ? `${uuid.substring(0, 8)}… (loading…)`
        : `${uuid.substring(0, 8)}… (unknown)`
}

function rowProps (row: any) {
    return {
        style: 'cursor: pointer;',
        onClick: () => {
            drawerRow.value = row
            drawerOpen.value = true
        },
    }
}

const columns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Status',
        key: 'status',
        width: 100,
        render: (row: any) => h(NTag, { size: 'small', type: statusType(row.status) },
            { default: () => row.status }),
    },
    {
        title: 'Origin',
        key: 'origin',
        width: 110,
        render: (row: any) => h(NTag,
            { size: 'small', bordered: false, type: row.origin === 'SYNTHETIC' ? 'info' : 'default' },
            { default: () => row.origin }),
    },
    {
        title: 'Channel',
        key: 'channelUuid',
        render: (row: any) => h('span', { style: 'font-size: 12px;' }, resolveChannel(row.channelUuid)),
    },
    { title: 'Attempts', key: 'attemptCount', width: 90 },
    {
        title: 'Created',
        key: 'createdDate',
        width: 170,
        render: (row: any) => formatDate(row.createdDate),
    },
    {
        title: 'Sent',
        key: 'sentAt',
        width: 170,
        render: (row: any) => formatDate(row.sentAt),
    },
    {
        title: 'Last error',
        key: 'lastError',
        render: (row: any) => row.lastError
            ? h('span', { style: 'color: #c00; font-size: 12px;' },
                row.lastError.length > 80 ? row.lastError.substring(0, 80) + '…' : row.lastError)
            : '—',
    },
])
</script>

<style scoped>
.notificationDeliveriesTab {
    margin-top: 8px;
}
.head {
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.title-row {
    display: flex;
    align-items: center;
    gap: 8px;
}
.empty {
    color: #888;
    font-size: 14px;
    padding: 16px 0;
}
.info-icon {
    cursor: help;
    color: #888;
}
.total-row {
    margin-top: 8px;
    font-size: 12px;
    color: #888;
}
.drawer-section {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-bottom: 16px;
}
.muted {
    color: #888;
    font-size: 12px;
}
h5 {
    margin: 16px 0 8px 0;
    font-size: 13px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: #555;
}
table.kv {
    width: 100%;
    font-size: 12px;
    border-collapse: collapse;
}
table.kv th {
    text-align: left;
    color: #888;
    font-weight: normal;
    padding: 4px 12px 4px 0;
    width: 130px;
    vertical-align: top;
}
table.kv td {
    padding: 4px 0;
    word-break: break-all;
}
table.kv code {
    font-size: 11px;
}
.error-block {
    background: rgba(204, 0, 0, 0.06);
    border-left: 3px solid #c00;
    padding: 10px 12px;
    font-family: monospace;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-word;
    color: #800;
}
</style>

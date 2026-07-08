<template>
    <div class="notification-history-pane">
        <div class="tab-toolbar">
            <div class="tab-toolbar-info">
                Audit log of every delivery row: when, to whom, status, attempts. Test rows (channel-test button) and PREVIEW rows (subscriptions in preview mode) are included.
            </div>
            <n-button size="small" @click="loadDeliveries">
                <template #icon><n-icon><Refresh /></n-icon></template>
                Refresh
            </n-button>
        </div>

        <n-grid :cols="3" :x-gap="12" class="history-filters">
            <n-gi>
                <n-form-item label="Status" :show-feedback="false">
                    <n-select
                        v-model:value="historyFilters.status"
                        :options="deliveryStatusOptions"
                        placeholder="Any"
                        clearable
                        @update:value="applyHistoryFilters"
                    />
                </n-form-item>
            </n-gi>
            <n-gi>
                <n-form-item label="Origin" :show-feedback="false">
                    <n-select
                        v-model:value="historyFilters.origin"
                        :options="deliveryOriginOptions"
                        placeholder="Any"
                        clearable
                        @update:value="applyHistoryFilters"
                    />
                </n-form-item>
            </n-gi>
            <n-gi>
                <n-form-item label="Channel" :show-feedback="false">
                    <n-select
                        v-model:value="historyFilters.channelUuid"
                        :options="channelFilterOptions"
                        placeholder="Any"
                        clearable
                        @update:value="applyHistoryFilters"
                    />
                </n-form-item>
            </n-gi>
        </n-grid>

        <n-alert
            v-if="historyDegraded"
            type="warning"
            :show-icon="false"
            style="margin-bottom: 12px;"
            data-testid="history-degraded-alert"
        >
            Some delivery details are unavailable on this server version, so a few columns (such as error and retry info) may be missing. Your delivery history is shown below.
        </n-alert>

        <n-data-table
            :data="deliveries"
            :columns="deliveryColumns"
            :loading="deliveriesLoading"
            :single-line="false"
            :bordered="false"
        />

        <div v-if="historyTotalCount > historyPageSize" class="history-pagination">
            <n-pagination
                v-model:page="historyPage"
                :page-count="historyPageCount"
                :page-size="historyPageSize"
                :item-count="historyTotalCount"
                @update:page="loadDeliveries"
            />
        </div>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted } from 'vue'
import {
    NDataTable, NButton, NIcon, NFormItem, NSelect, NGrid, NGi, NTag,
    NPagination, NAlert, useMessage
} from 'naive-ui'
import { Refresh } from '@vicons/tabler'
import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import graphqlClient from '@/utils/graphql'
import { loadWithSchemaDriftFallback } from '@/utils/graphqlDriftFallback'
import {
    ChannelRow, SubscriptionRow, DeliveryRow, TYPE_LABELS,
    deliveryStatusOptions, deliveryOriginOptions,
    LIST_CHANNELS_QUERY, LIST_SUBSCRIPTIONS_CORE_QUERY,
    deliveryStatusTagType, formatHistoryTimestamp, truncate,
    buildNameMap, extractError
} from '@/utils/notificationsCommon'

const props = defineProps<{
    orguuid: string
    // Optional deep-link seeds (e.g. "View delivery log" from the inbox drawer
    // or a channel card). Applied once to the filters on mount; the parent
    // remounts this component when they change (keyed on the deep-link), so a
    // fresh seed always takes effect. Manual filter changes are independent.
    initialChannelUuid?: string | null
    initialStatus?: string | null
}>()

const message = useMessage()
const orgUuid = computed<string>(() => props.orguuid)

const channels = ref<ChannelRow[]>([])
const subscriptions = ref<SubscriptionRow[]>([])
const deliveries = ref<DeliveryRow[]>([])
const deliveriesLoading = ref<boolean>(false)
// True when the backend rejected the full delivery selection and we fell back
// to the core fields (retry/error detail columns may be absent).
const historyDegraded = ref<boolean>(false)
const historyPage = ref<number>(1)
const historyPageSize = ref<number>(25)
const historyTotalCount = ref<number>(0)
const historyPageCount = computed<number>(() =>
    Math.max(1, Math.ceil(historyTotalCount.value / historyPageSize.value))
)
const historyFilters = ref<{ status: string | null, origin: string | null, channelUuid: string | null }>({
    status: null,
    origin: null,
    channelUuid: null,
})

// History channel filter spans ALL channels (incl. disabled) so a user
// investigating why nothing landed on a disabled channel can still see
// past deliveries.
const channelFilterOptions = computed(() =>
    channels.value.map(c => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid }))
)

// Cross-ref helpers — history rows carry uuids; resolve to names from
// the loaded channels + subscriptions lists.
const channelNameById = computed<Record<string, string>>(() => buildNameMap(channels.value))
const subscriptionNameById = computed<Record<string, string>>(() => buildNameMap(subscriptions.value))

// CORE = identity + status + time needed to render a delivery row.
// ENRICHMENT = retry/error detail; if a backend lacks one of these (CE mirror
// lagging Pro) the history table degrades to core rows instead of blanking.
const HISTORY_CORE_ITEM_FIELDS = 'uuid org outboxEventUuid subscriptionUuid channelUuid status origin createdDate'
const HISTORY_ENRICHMENT_ITEM_FIELDS = 'dedupKey attemptCount nextAttemptAt sentAt lastError'
function buildHistoryQuery (itemFields: string): DocumentNode {
    return gql`
        query notificationDeliveriesPage(
            $orgUuid: ID!,
            $channelUuid: ID,
            $status: NotificationDeliveryStatusEnum,
            $origin: NotificationDeliveryOriginEnum,
            $limit: Int,
            $offset: Int
        ) {
            notificationDeliveries(
                orgUuid: $orgUuid,
                channelUuid: $channelUuid,
                status: $status,
                origin: $origin,
                limit: $limit,
                offset: $offset
            ) {
                items { ${itemFields} }
                totalCount limit offset
            }
        }
    `
}
const HISTORY_DELIVERIES_FULL = buildHistoryQuery(`${HISTORY_CORE_ITEM_FIELDS} ${HISTORY_ENRICHMENT_ITEM_FIELDS}`)
const HISTORY_DELIVERIES_CORE = buildHistoryQuery(HISTORY_CORE_ITEM_FIELDS)

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

async function loadSubscriptions (): Promise<void> {
    try {
        // History only needs subscription names (uuid -> name map), so the CORE
        // query is enough and can't drift on the Pro-ahead config fields.
        const res = await graphqlClient.query({
            query: LIST_SUBSCRIPTIONS_CORE_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        subscriptions.value = res.data?.notificationSubscriptions || []
    } catch (e: any) {
        message.error(`Failed to load subscriptions: ${extractError(e)}`)
    }
}

// Monotonic in-flight token. ApolloClient with `fetchPolicy: network-only`
// does NOT dedup or cancel concurrent identical queries, so rapidly
// toggling a filter can race the older response in last and overwrite
// the newer state. The token guards apply-time so a stale response is
// dropped silently.
let historyInflightToken = 0

async function loadDeliveries (): Promise<void> {
    const myToken = ++historyInflightToken
    deliveriesLoading.value = true
    try {
        const offset = (historyPage.value - 1) * historyPageSize.value
        const { data: page, degraded } = await loadWithSchemaDriftFallback(graphqlClient, {
            fullQuery: HISTORY_DELIVERIES_FULL,
            coreQuery: HISTORY_DELIVERIES_CORE,
            variables: {
                orgUuid: orgUuid.value,
                channelUuid: historyFilters.value.channelUuid,
                status: historyFilters.value.status,
                origin: historyFilters.value.origin,
                limit: historyPageSize.value,
                offset,
            },
            extractPath: (d: any) => d?.notificationDeliveries,
        })
        if (myToken !== historyInflightToken) return  // stale response
        historyDegraded.value = degraded
        deliveries.value = page?.items || []
        historyTotalCount.value = page?.totalCount || 0
    } catch (e: any) {
        if (myToken !== historyInflightToken) return  // stale failure
        historyDegraded.value = false
        message.error(`Failed to load history: ${extractError(e)}`)
    } finally {
        if (myToken === historyInflightToken) deliveriesLoading.value = false
    }
}

// Filter change must reset to page 1 — otherwise a user on page 3 of an
// unfiltered list who applies a narrowing filter would request an
// out-of-range offset and land on a phantom empty page.
function applyHistoryFilters (): void {
    historyPage.value = 1
    loadDeliveries()
}

const deliveryColumns = computed(() => [
    {
        title: 'When', key: 'createdDate',
        render: (row: DeliveryRow) => formatHistoryTimestamp(row.sentAt || row.createdDate),
    },
    {
        title: 'Status', key: 'status',
        render: (row: DeliveryRow) => h(
            NTag,
            { type: deliveryStatusTagType(row.status), size: 'small' },
            { default: () => row.status },
        ),
    },
    {
        title: 'Origin', key: 'origin',
        render: (row: DeliveryRow) => h(
            NTag,
            { type: row.origin === 'SYNTHETIC' ? 'info' : 'default', size: 'small' },
            { default: () => row.origin },
        ),
    },
    {
        title: 'Channel', key: 'channelUuid',
        // Null channel = Phase 4a targeted delivery (personal inbox copy,
        // no transmission channel) — not a deleted channel.
        render: (row: DeliveryRow) => (row.channelUuid
            ? (channelNameById.value[row.channelUuid]
                || h('span', { class: 'muted-12', title: row.channelUuid }, '(deleted channel)'))
            : h('span', { class: 'muted-12' }, 'Direct')),
    },
    {
        title: 'Subscription', key: 'subscriptionUuid',
        render: (row: DeliveryRow) => {
            if (!row.subscriptionUuid) {
                // Channel tests have a channel but no subscription; targeted
                // approval deliveries have neither.
                return h('span', { class: 'muted-12' }, row.channelUuid ? '(channel test)' : 'Direct')
            }
            return subscriptionNameById.value[row.subscriptionUuid]
                || h('span', { class: 'muted-12', title: row.subscriptionUuid }, '(deleted subscription)')
        },
    },
    {
        title: 'Attempts', key: 'attemptCount',
        // attemptCount is an ENRICHMENT field -- absent (not just null) on a
        // degraded load, so guard against rendering the literal "undefined".
        render: (row: DeliveryRow) => row.attemptCount != null ? `${row.attemptCount}` : '',
    },
    {
        title: 'Last error', key: 'lastError',
        render: (row: DeliveryRow) => row.lastError
            ? h('span', { title: row.lastError, class: 'muted-12' }, truncate(row.lastError, 60))
            : '',
    },
])

onMounted(async () => {
    // Seed filters from a deep-link BEFORE the first load so the initial
    // query is already scoped (no flash of the full list, no extra fetch).
    if (props.initialChannelUuid) historyFilters.value.channelUuid = props.initialChannelUuid
    if (props.initialStatus) historyFilters.value.status = props.initialStatus
    // Channels + subscriptions feed the name-resolution maps; deliveries
    // is the actual table. Load all three in parallel.
    await Promise.all([loadChannels(), loadSubscriptions(), loadDeliveries()])
})
</script>

<style scoped>
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
</style>
